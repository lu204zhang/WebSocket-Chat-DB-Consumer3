package vito.metrics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vito.metrics.instrumentation.ConsumerMetrics;
import vito.metrics.dto.AnalyticsMetricsResponse;
import vito.persistence.dao.RoomAnalyticsRepository;
import vito.metrics.dto.AnalyticsReport;
import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;

import java.time.LocalDate;
import java.util.List;

@Service
@Slf4j
public class AnalyticsService {

    private final RoomAnalyticsRepository roomAnalyticsRepository;
    private final ConsumerMetrics consumerMetrics;

    @Value("${app.analytics.top-rooms-limit:5}")
    private int topRoomsLimit;

    @Value("${app.analytics.top-users-limit:10}")
    private int topUsersLimit;

    /**
     * @param roomAnalyticsRepository DynamoDB repository for RoomAnalytics aggregates
     * @param consumerMetrics         live consumer processing counters
     */
    public AnalyticsService(RoomAnalyticsRepository roomAnalyticsRepository,
                             ConsumerMetrics consumerMetrics) {
        this.roomAnalyticsRepository = roomAnalyticsRepository;
        this.consumerMetrics = consumerMetrics;
    }

    /**
     * Builds a combined analytics response for today: top rooms, top users,
     * aggregate message counts, and live consumer metrics.
     * @return full analytics snapshot
     */
    public AnalyticsMetricsResponse getFullAnalytics() {
        log.debug("Building full analytics report");
        String today = LocalDate.now().toString();
        List<RoomStats> topRooms = roomAnalyticsRepository.getTopActiveRooms(topRoomsLimit, today);
        List<TopUser> topUsers = roomAnalyticsRepository.getTopActiveUsers(topUsersLimit, today);

        long totalMessages = topRooms.stream().mapToLong(RoomStats::getMessageCount).sum();
        long uniqueUserCount = topRooms.stream()
                .flatMap(r -> r.getUniqueUsers().stream())
                .distinct()
                .count();
        int activeRooms = topRooms.size();
        double avgMessagesPerRoom = activeRooms > 0 ? (double) totalMessages / activeRooms : 0.0;
        double avgMessagesPerUser = uniqueUserCount > 0 ? (double) totalMessages / uniqueUserCount : 0.0;

        AnalyticsReport report = new AnalyticsReport();
        report.setTotalMessages(totalMessages);
        report.setUniqueUsers((int) uniqueUserCount);
        report.setActiveRooms(activeRooms);
        report.setAvgMessagesPerRoom(avgMessagesPerRoom);
        report.setAvgMessagesPerUser(avgMessagesPerUser);
        report.setMessagesPerMinute(consumerMetrics.getMessagesPerMinute());
        report.setTopRooms(topRooms);
        report.setTopUsers(topUsers);
        report.setReportGeneratedTime(System.currentTimeMillis());

        return AnalyticsMetricsResponse.builder()
                .analytics(report)
                .messagesProcessed(consumerMetrics.getMessagesProcessed())
                .messagesFailed(consumerMetrics.getMessagesFailed())
                .messagesSkippedDuplicate(consumerMetrics.getMessagesSkippedDuplicate())
                .build();
    }
}

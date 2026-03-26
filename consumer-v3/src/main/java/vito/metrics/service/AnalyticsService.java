package vito.metrics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vito.metrics.instrumentation.ConsumerMetrics;
import vito.metrics.dto.AnalyticsMetricsResponse;
import vito.metrics.dto.AnalyticsReport;
import vito.metrics.dto.RoomSummary;
import vito.persistence.dao.RoomAnalyticsRepository;
import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
     * All aggregate stats (totalMessages, uniqueUsers, averages) cover ALL rooms.
     * @return full analytics snapshot
     */
    public AnalyticsMetricsResponse getFullAnalytics() {
        log.debug("Building full analytics report");
        String today = LocalDate.now().toString();

        List<RoomStats> allRooms = roomAnalyticsRepository.getTopActiveRooms(Integer.MAX_VALUE, today);
        List<RoomStats> topRooms = allRooms.stream()
                .limit(topRoomsLimit)
                .collect(Collectors.toList());

        long totalMessages = allRooms.stream().mapToLong(RoomStats::getMessageCount).sum();
        long uniqueUserCount = allRooms.stream()
                .flatMap(r -> r.getUniqueUsers().stream())
                .distinct()
                .count();
        double avgMessagesPerRoom = !allRooms.isEmpty() ? (double) totalMessages / allRooms.size() : 0.0;
        double avgMessagesPerUser = uniqueUserCount > 0 ? (double) totalMessages / uniqueUserCount : 0.0;

        Map<String, TopUser> userAggregates = new LinkedHashMap<>();
        for (RoomStats room : allRooms) {
            if (room.getTopUsers() == null) continue;
            for (TopUser u : room.getTopUsers()) {
                userAggregates.merge(u.getUserId(), copyTopUser(u), (existing, incoming) -> {
                    existing.setMessageCount(existing.getMessageCount() + incoming.getMessageCount());
                    return existing;
                });
            }
        }
        List<TopUser> topUsers = userAggregates.values().stream()
                .sorted(Comparator.comparingInt(TopUser::getMessageCount).reversed())
                .limit(topUsersLimit)
                .collect(Collectors.toList());

        List<RoomSummary> topRoomSummaries = topRooms.stream()
                .map(r -> new RoomSummary(
                        r.getRoomId(),
                        r.getMessageCount(),
                        r.getUniqueUsers() != null ? r.getUniqueUsers().size() : 0,
                        r.getAvgMessageLength()))
                .collect(Collectors.toList());

        AnalyticsReport report = new AnalyticsReport();
        report.setTotalMessages(totalMessages);
        report.setUniqueUsers((int) uniqueUserCount);
        report.setAvgMessagesPerRoom(avgMessagesPerRoom);
        report.setAvgMessagesPerUser(avgMessagesPerUser);
        report.setMessagesPerMinute(consumerMetrics.getMessagesPerMinute());
        report.setTopRooms(topRoomSummaries);
        report.setTopUsers(topUsers);
        report.setReportGeneratedTime(System.currentTimeMillis());

        return AnalyticsMetricsResponse.builder()
                .analytics(report)
                .messagesProcessed(consumerMetrics.getMessagesProcessed())
                .messagesFailed(consumerMetrics.getMessagesFailed())
                .messagesSkippedDuplicate(consumerMetrics.getMessagesSkippedDuplicate())
                .build();
    }

    private static TopUser copyTopUser(TopUser u) {
        TopUser copy = new TopUser();
        copy.setUserId(u.getUserId());
        copy.setUsername(u.getUsername());
        copy.setMessageCount(u.getMessageCount());
        return copy;
    }
}

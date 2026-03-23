package vito.metrics.controller;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import vito.metrics.instrumentation.ConsumerMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vito.metrics.dto.AnalyticsMetricsResponse;
import vito.metrics.dto.Query1Response;
import vito.metrics.dto.Query2Response;
import vito.metrics.dto.Query3Response;
import vito.metrics.dto.Query4Response;
import vito.metrics.service.ActiveUsersService;
import vito.metrics.service.AnalyticsService;
import vito.metrics.service.RoomMessagesService;
import vito.metrics.service.UserMessagesService;
import vito.metrics.service.UserRoomsService;
import vito.persistence.writebehind.DeadLetterQueue;
import vito.persistence.writebehind.WriteBehindQueue;

import java.util.Map;

@RestController
public class MetricsController {

    private final ConsumerMetrics consumerMetrics;
    private final RoomMessagesService roomMessagesService;
    private final UserMessagesService userMessagesService;
    private final ActiveUsersService activeUsersService;
    private final UserRoomsService userRoomsService;
    private final AnalyticsService analyticsService;
    private final WriteBehindQueue writeBehindQueue;
    private final DeadLetterQueue deadLetterQueue;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /**
     * @param consumerMetrics        live consumer processing counters
     * @param roomMessagesService    handles Query 1 (room message history)
     * @param userMessagesService    handles Query 2 (user message history)
     * @param activeUsersService     handles Query 3 (active user count)
     * @param userRoomsService       handles Query 4 (rooms per user)
     * @param analyticsService       builds combined analytics reports
     * @param writeBehindQueue       exposes queue depth metrics
     * @param deadLetterQueue        exposes DLQ size and drop metrics
     * @param circuitBreakerRegistry used to read CB state for both DynamoDB paths
     */
    public MetricsController(ConsumerMetrics consumerMetrics,
                              RoomMessagesService roomMessagesService,
                              UserMessagesService userMessagesService,
                              ActiveUsersService activeUsersService,
                              UserRoomsService userRoomsService,
                              AnalyticsService analyticsService,
                              WriteBehindQueue writeBehindQueue,
                              DeadLetterQueue deadLetterQueue,
                              CircuitBreakerRegistry circuitBreakerRegistry) {
        this.consumerMetrics         = consumerMetrics;
        this.roomMessagesService     = roomMessagesService;
        this.userMessagesService     = userMessagesService;
        this.activeUsersService      = activeUsersService;
        this.userRoomsService        = userRoomsService;
        this.analyticsService        = analyticsService;
        this.writeBehindQueue        = writeBehindQueue;
        this.deadLetterQueue         = deadLetterQueue;
        this.circuitBreakerRegistry  = circuitBreakerRegistry;
    }

    /** Consumer processing and write-behind counters. */
    @GetMapping("/app/metrics")
    public Map<String, Long> metrics() {
        return Map.of(
                "messagesProcessed",        consumerMetrics.getMessagesProcessed(),
                "messagesFailed",           consumerMetrics.getMessagesFailed(),
                "messagesSkippedDuplicate", consumerMetrics.getMessagesSkippedDuplicate(),
                "dbWritesSuccess",          consumerMetrics.getDbWritesSuccess(),
                "dbWritesFailed",           consumerMetrics.getDbWritesFailed(),
                "writeBehindDropped",       consumerMetrics.getWriteBehindDropped(),
                "dlqSize",                  (long) deadLetterQueue.size(),
                "dlqTotalDropped",          deadLetterQueue.getTotalDropped(),
                "writeQueueSize",           (long) writeBehindQueue.getMessageQueueSize(),
                "statsQueueSize",           (long) writeBehindQueue.getStatsQueueSize()
        );
    }

    /** Query 1 – messages in a room within a time range. */
    @GetMapping("/app/metrics/room-messages")
    public ResponseEntity<Query1Response> getRoomMessages(
            @RequestParam String roomId,
            @RequestParam long startTime,
            @RequestParam long endTime) {
        validateTimeRange(startTime, endTime);
        validateNotBlank(roomId, "roomId");
        return ResponseEntity.ok(roomMessagesService.getMessages(roomId, startTime, endTime));
    }

    /** Query 2 – a user's message history across rooms. */
    @GetMapping("/app/metrics/user-history")
    public ResponseEntity<Query2Response> getUserHistory(
            @RequestParam String userId,
            @RequestParam(required = false) Long startTime,
            @RequestParam(required = false) Long endTime) {
        validateNotBlank(userId, "userId");
        long from = startTime != null ? startTime : 0L;
        long to   = endTime   != null ? endTime   : System.currentTimeMillis();
        validateTimeRange(from, to);
        return ResponseEntity.ok(userMessagesService.getUserMessages(userId, from, to));
    }

    /** Query 3 – distinct active users within a time window. */
    @GetMapping("/app/metrics/active-users")
    public ResponseEntity<Query3Response> getActiveUsers(
            @RequestParam long startTime,
            @RequestParam long endTime) {
        validateTimeRange(startTime, endTime);
        return ResponseEntity.ok(activeUsersService.countActiveUsers(startTime, endTime));
    }

    /** Query 4 – rooms a user has participated in, ordered by most recent activity. */
    @GetMapping("/app/metrics/user-rooms")
    public ResponseEntity<Query4Response> getUserRooms(
            @RequestParam String userId) {
        validateNotBlank(userId, "userId");
        return ResponseEntity.ok(userRoomsService.getUserRooms(userId));
    }

    /** Combined analytics: room stats aggregated with consumer counters. */
    @GetMapping("/app/metrics/analytics")
    public ResponseEntity<AnalyticsMetricsResponse> getAnalytics() {
        return ResponseEntity.ok(analyticsService.getFullAnalytics());
    }

    /** Circuit breaker states for both DynamoDB write paths. */
    @GetMapping("/app/metrics/circuit-breaker")
    public Map<String, Object> circuitBreakerStatus() {
        CircuitBreaker msgs = circuitBreakerRegistry.circuitBreaker("dynamodb-messages");
        CircuitBreaker stats = circuitBreakerRegistry.circuitBreaker("dynamodb-stats");
        return Map.of(
                "messages", Map.of(
                        "state",             msgs.getState().name(),
                        "failureRate",       msgs.getMetrics().getFailureRate(),
                        "failedCalls",       msgs.getMetrics().getNumberOfFailedCalls(),
                        "notPermittedCalls", msgs.getMetrics().getNumberOfNotPermittedCalls()
                ),
                "stats", Map.of(
                        "state",             stats.getState().name(),
                        "failureRate",       stats.getMetrics().getFailureRate(),
                        "failedCalls",       stats.getMetrics().getNumberOfFailedCalls(),
                        "notPermittedCalls", stats.getMetrics().getNumberOfNotPermittedCalls()
                )
        );
    }

    private static void validateTimeRange(long startTime, long endTime) {
        if (startTime < 0 || endTime < 0) {
            throw new IllegalArgumentException("startTime and endTime must be non-negative");
        }
        if (startTime >= endTime) {
            throw new IllegalArgumentException("startTime must be less than endTime");
        }
    }

    private static void validateNotBlank(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(paramName + " must not be blank");
        }
    }
}

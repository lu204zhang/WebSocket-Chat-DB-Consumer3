package vito.persistence.writebehind;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import vito.config.Constants;
import vito.model.MessageType;
import vito.model.QueueMessage;
import vito.persistence.dao.RoomAnalyticsRepository;
import vito.persistence.dao.UserActivityRepository;
import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
@Slf4j
public class StatisticsAggregator implements InitializingBean, DisposableBean {

    private final WriteBehindQueue writeBehindQueue;
    private final UserActivityRepository userActivityRepository;
    private final RoomAnalyticsRepository roomAnalyticsRepository;
    private final BackoffPolicy backoffPolicy;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${app.writebehind.batch-size:500}")
    private int batchSize;

    @Value("${app.writebehind.flush-interval-ms:500}")
    private long flushIntervalMs;

    @Value("${app.writebehind.stats-threads:2}")
    private int statsThreads;

    private ScheduledExecutorService statsScheduler;
    private ExecutorService statsExecutor;
    private CircuitBreaker circuitBreaker;

    private final AtomicInteger threadCounter = new AtomicInteger(0);

    /**
     * @param writeBehindQueue           source queue drained on each flush cycle
     * @param userActivityRepository     DynamoDB async client for UserActivity
     * @param roomAnalyticsRepository    DynamoDB client for RoomAnalytics
     * @param backoffPolicy              controls retry delay and attempt limit
     * @param circuitBreakerRegistry     provides the dynamodb-stats circuit breaker
     */
    public StatisticsAggregator(WriteBehindQueue writeBehindQueue,
                                UserActivityRepository userActivityRepository,
                                RoomAnalyticsRepository roomAnalyticsRepository,
                                BackoffPolicy backoffPolicy,
                                CircuitBreakerRegistry circuitBreakerRegistry) {
        this.writeBehindQueue        = writeBehindQueue;
        this.userActivityRepository  = userActivityRepository;
        this.roomAnalyticsRepository = roomAnalyticsRepository;
        this.backoffPolicy           = backoffPolicy;
        this.circuitBreakerRegistry  = circuitBreakerRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        statsScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "stats-scheduler");
            t.setDaemon(true);
            return t;
        });
        statsExecutor = Executors.newFixedThreadPool(statsThreads, r -> {
            Thread t = new Thread(r, "stats-worker-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("dynamodb-stats");

        statsScheduler.scheduleAtFixedRate(
                () -> statsExecutor.submit(this::flushStats),
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);

        log.info("StatisticsAggregator started: batchSize={}, flushIntervalMs={}, threads={}",
                batchSize, flushIntervalMs, statsThreads);
    }

    /** Visible for testing / forced final drain on shutdown. */
    void flushStats() {
        List<QueueMessage> batch = new ArrayList<>(batchSize);
        int drained = writeBehindQueue.drainStats(batch, batchSize);
        if (drained == 0) return;

        log.debug("StatisticsAggregator processing {} messages", drained);

        // 1. UserActivity — batch writes (25 items per BatchWriteItem call) with retry
        partition(batch, Constants.DYNAMO_BATCH_LIMIT).forEach(chunk -> writeActivityBatch(chunk, 0));

        // 2. RoomAnalytics — aggregate in-memory, then write one item per room with retry
        updateRoomAnalytics(batch);
    }

    private void writeActivityBatch(List<QueueMessage> chunk, int attempt) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit OPEN (dynamodb-stats) — dropping {} UserActivity items", chunk.size());
            return;
        }

        long startNs = System.nanoTime();
        userActivityRepository.batchSaveActivities(chunk)
                .thenAccept(response -> {
                    List<QueueMessage> unprocessed = extractUnprocessedActivities(response);
                    if (!unprocessed.isEmpty()) {
                        circuitBreaker.onError(System.nanoTime() - startNs, TimeUnit.NANOSECONDS,
                                new RuntimeException("unprocessed activities: " + unprocessed.size()));
                        retryActivityOrLog(unprocessed, attempt);
                    } else {
                        circuitBreaker.onSuccess(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
                    }
                })
                .exceptionally(ex -> {
                    circuitBreaker.onError(System.nanoTime() - startNs, TimeUnit.NANOSECONDS, ex);
                    log.error("batchSaveActivities failed attempt={}", attempt, ex);
                    retryActivityOrLog(chunk, attempt);
                    return null;
                });
    }

    private void retryActivityOrLog(List<QueueMessage> items, int attempt) {
        if (backoffPolicy.shouldRetry(attempt)) {
            long delay = backoffPolicy.delayMs(attempt);
            log.warn("Retrying {} UserActivity items attempt={} delay={}ms",
                    items.size(), attempt + 1, delay);
            statsScheduler.schedule(() -> writeActivityBatch(items, attempt + 1),
                    delay, TimeUnit.MILLISECONDS);
        } else {
            log.error("UserActivity permanently failed for {} items after {} attempts, stats lost",
                    items.size(), backoffPolicy.getMaxAttempts());
        }
    }

    private List<QueueMessage> extractUnprocessedActivities(BatchWriteItemResponse response) {
        Map<String, List<WriteRequest>> unprocessed = response.unprocessedItems();
        if (unprocessed == null || unprocessed.isEmpty()) return List.of();
        return unprocessed.values().stream()
                .flatMap(List::stream)
                .map(wr -> {
                    Map<String, AttributeValue> item = wr.putRequest().item();
                    QueueMessage q = new QueueMessage();
                    q.setUserId(item.get("user_id").s());
                    q.setRoomId(item.get("room_id").s());
                    q.setMessageType(MessageType.valueOf(item.get("type").s()));
                    q.setTimestamp(Instant.ofEpochMilli(Long.parseLong(item.get("timestamp").n())));
                    return q;
                })
                .collect(Collectors.toList());
    }

    private static final int TOP_USERS_LIMIT = 10;

    private void updateRoomAnalytics(List<QueueMessage> batch) {
        String today = LocalDate.now().toString();

        Map<String, RoomStats> aggregated = new LinkedHashMap<>();
        // roomId -> (userId -> TopUser) for per-user message count tracking
        Map<String, Map<String, TopUser>> userCountsByRoom = new HashMap<>();

        for (QueueMessage msg : batch) {
            String roomId = msg.getRoomId();
            RoomStats stats = aggregated.computeIfAbsent(roomId, id -> {
                RoomStats s = new RoomStats();
                s.setRoomId(id);
                s.setDate(today);
                s.setHour(-1);
                s.setUniqueUsers(new HashSet<>());
                s.setTopUsers(new ArrayList<>());
                s.setTimestamp(System.currentTimeMillis());
                return s;
            });

            stats.setMessageCount(stats.getMessageCount() + 1);
            stats.getUniqueUsers().add(msg.getUserId());

            // Accumulate total message length (avgMessageLength computed on read)
            int msgLen = msg.getMessage() != null ? msg.getMessage().length() : 0;
            stats.setTotalMessageLength(stats.getTotalMessageLength() + msgLen);

            // Track per-user message count for TopUser aggregation
            Map<String, TopUser> userCounts = userCountsByRoom.computeIfAbsent(roomId, k -> new LinkedHashMap<>());
            TopUser topUser = userCounts.computeIfAbsent(msg.getUserId(), uid -> {
                TopUser u = new TopUser();
                u.setUserId(uid);
                u.setUsername(msg.getUsername() != null ? msg.getUsername() : uid);
                return u;
            });
            topUser.setMessageCount(topUser.getMessageCount() + 1);
        }

        // Attach sorted top-N users to each room's stats
        userCountsByRoom.forEach((roomId, userCounts) -> {
            RoomStats stats = aggregated.get(roomId);
            if (stats != null) {
                List<TopUser> topUsers = userCounts.values().stream()
                        .sorted(Comparator.comparingInt(TopUser::getMessageCount).reversed())
                        .limit(TOP_USERS_LIMIT)
                        .collect(Collectors.toList());
                stats.setTopUsers(topUsers);
            }
        });

        aggregated.forEach((roomId, stats) -> retryStats(roomId, stats, 0));
    }

    private void retryStats(String roomId, RoomStats stats, int attempt) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit OPEN (dynamodb-stats) — dropping RoomAnalytics for roomId={}", roomId);
            return;
        }

        long startNs = System.nanoTime();
        try {
            roomAnalyticsRepository.updateRoomStats(roomId, stats);
            circuitBreaker.onSuccess(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
            log.debug("Updated RoomAnalytics for roomId={}, msgCount={}",
                    roomId, stats.getMessageCount());
        } catch (Exception ex) {
            circuitBreaker.onError(System.nanoTime() - startNs, TimeUnit.NANOSECONDS, ex);
            if (backoffPolicy.shouldRetry(attempt)) {
                long delay = backoffPolicy.delayMs(attempt);
                log.warn("RoomAnalytics write failed roomId={} attempt={} delay={}ms, retrying",
                        roomId, attempt + 1, delay, ex);
                statsScheduler.schedule(
                        () -> retryStats(roomId, stats, attempt + 1),
                        delay, TimeUnit.MILLISECONDS);
            } else {
                log.error("RoomAnalytics permanently failed roomId={} after {} attempts, stats lost",
                        roomId, backoffPolicy.getMaxAttempts(), ex);
            }
        }
    }

    @Override
    public void destroy() throws Exception {
        log.info("StatisticsAggregator shutting down — performing final flush");
        statsScheduler.shutdown();
        flushStats();
        statsExecutor.shutdown();
        if (!statsExecutor.awaitTermination(Constants.EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("statsExecutor did not terminate within 10s");
        }
    }

    static <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> result = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            result.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return result;
    }
}

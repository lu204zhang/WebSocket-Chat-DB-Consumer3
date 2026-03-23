package vito.persistence.writebehind;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.WriteRequest;
import vito.config.Constants;
import vito.metrics.instrumentation.ConsumerMetrics;
import vito.model.QueueMessage;
import vito.persistence.dao.MessageRepository;
import vito.persistence.util.QueueMessageConverter;
import vito.persistence.model.PersistentMessage;
import vito.persistence.util.DynamoDBMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Component
@Slf4j
public class BatchMessageWriter implements InitializingBean, DisposableBean {

    private static final int DYNAMO_BATCH_LIMIT = Constants.DYNAMO_BATCH_LIMIT;

    private final WriteBehindQueue writeBehindQueue;
    private final MessageRepository messageRepository;
    private final DeadLetterQueue dlq;
    private final ConsumerMetrics consumerMetrics;
    private final BackoffPolicy backoffPolicy;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    @Value("${app.writebehind.batch-size:500}")
    private int batchSize;

    @Value("${app.writebehind.flush-interval-ms:500}")
    private long flushIntervalMs;

    @Value("${app.writebehind.db-writer-threads:4}")
    private int dbWriterThreads;

    private ScheduledExecutorService scheduler;
    private ExecutorService dbWriterExecutor;
    private CircuitBreaker circuitBreaker;

    private final AtomicInteger threadCounter = new AtomicInteger(0);

    /**
     * @param writeBehindQueue       source queue drained on each flush cycle
     * @param messageRepository      DynamoDB async client for ChatMessages
     * @param dlq                    receives permanently-failed items after max retries
     * @param consumerMetrics        records DB write success/failure counts
     * @param backoffPolicy          controls retry delay and attempt limit
     * @param circuitBreakerRegistry provides the dynamodb-messages circuit breaker
     */
    public BatchMessageWriter(WriteBehindQueue writeBehindQueue,
                              MessageRepository messageRepository,
                              DeadLetterQueue dlq,
                              ConsumerMetrics consumerMetrics,
                              BackoffPolicy backoffPolicy,
                              CircuitBreakerRegistry circuitBreakerRegistry) {
        this.writeBehindQueue      = writeBehindQueue;
        this.messageRepository     = messageRepository;
        this.dlq                   = dlq;
        this.consumerMetrics       = consumerMetrics;
        this.backoffPolicy         = backoffPolicy;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    public void afterPropertiesSet() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "writebehind-scheduler");
            t.setDaemon(true);
            return t;
        });
        dbWriterExecutor = Executors.newFixedThreadPool(dbWriterThreads, r -> {
            Thread t = new Thread(r, "db-writer-" + threadCounter.getAndIncrement());
            t.setDaemon(true);
            return t;
        });
        circuitBreaker = circuitBreakerRegistry.circuitBreaker("dynamodb-messages");

        scheduler.scheduleAtFixedRate(
                () -> dbWriterExecutor.submit(this::flush),
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);

        log.info("BatchMessageWriter started: batchSize={}, flushIntervalMs={}, threads={}",
                batchSize, flushIntervalMs, dbWriterThreads);
    }

    void flush() {
        List<QueueMessage> batch = new ArrayList<>(batchSize);
        int drained = writeBehindQueue.drainMessages(batch, batchSize);
        if (drained == 0) return;

        log.debug("Flushing {} messages to DynamoDB", drained);

        List<PersistentMessage> persistentMsgs = batch.stream()
                .map(QueueMessageConverter::toPersistentMessage)
                .collect(Collectors.toList());

        partition(persistentMsgs, DYNAMO_BATCH_LIMIT)
                .forEach(chunk -> writeBatch(chunk, 0));
    }

    private void writeBatch(List<PersistentMessage> chunk, int attempt) {
        if (!circuitBreaker.tryAcquirePermission()) {
            log.warn("Circuit OPEN (dynamodb-messages) — fast-failing {} items to DLQ", chunk.size());
            chunk.forEach(msg -> dlq.offer(DynamoDBMapper.messageToItem(msg)));
            consumerMetrics.recordDbWriteFailed(chunk.size());
            return;
        }

        long startNs = System.nanoTime();
        messageRepository.batchSaveMessages(chunk)
                .thenAccept(response -> {
                    List<PersistentMessage> unprocessed = extractUnprocessed(response);
                    if (!unprocessed.isEmpty()) {
                        circuitBreaker.onError(System.nanoTime() - startNs, TimeUnit.NANOSECONDS,
                                new RuntimeException("unprocessed items: " + unprocessed.size()));
                        retryOrDlq(unprocessed, attempt, "unprocessed");
                    } else {
                        circuitBreaker.onSuccess(System.nanoTime() - startNs, TimeUnit.NANOSECONDS);
                        consumerMetrics.recordDbWriteSuccess(chunk.size());
                        log.debug("Batch of {} written successfully", chunk.size());
                    }
                })
                .exceptionally(ex -> {
                    circuitBreaker.onError(System.nanoTime() - startNs, TimeUnit.NANOSECONDS, ex);
                    log.error("batchWriteItem failed attempt={}", attempt, ex);
                    retryOrDlq(chunk, attempt, "exception");
                    return null;
                });
    }

    private void retryOrDlq(List<PersistentMessage> items, int attempt, String reason) {
        if (backoffPolicy.shouldRetry(attempt)) {
            long delay = backoffPolicy.delayMs(attempt);
            log.warn("Retrying {} items ({}) attempt={} delay={}ms",
                    items.size(), reason, attempt + 1, delay);
            scheduler.schedule(() -> writeBatch(items, attempt + 1), delay, TimeUnit.MILLISECONDS);
        } else {
            log.error("Giving up on {} items after {} attempts ({})",
                    items.size(), backoffPolicy.getMaxAttempts(), reason);
            items.forEach(msg -> dlq.offer(DynamoDBMapper.messageToItem(msg)));
            consumerMetrics.recordDbWriteFailed(items.size());
        }
    }

    private List<PersistentMessage> extractUnprocessed(BatchWriteItemResponse response) {
        Map<String, List<WriteRequest>> unprocessed = response.unprocessedItems();
        if (unprocessed == null || unprocessed.isEmpty()) return List.of();
        return unprocessed.values().stream()
                .flatMap(List::stream)
                .map(wr -> DynamoDBMapper.mapToMessage(wr.putRequest().item()))
                .collect(Collectors.toList());
    }

    @Override
    public void destroy() throws Exception {
        log.info("BatchMessageWriter shutting down — performing final flush");
        scheduler.shutdown();
        flush();
        dbWriterExecutor.shutdown();
        if (!dbWriterExecutor.awaitTermination(Constants.EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            log.warn("dbWriterExecutor did not terminate within 10s");
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

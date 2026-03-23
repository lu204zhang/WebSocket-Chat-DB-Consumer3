package vito.metrics.instrumentation;

import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;
import vito.config.Constants;

@Component
public class ConsumerMetrics {

    private final long startTimeMs = System.currentTimeMillis();

    private final AtomicLong messagesProcessed       = new AtomicLong(0);
    private final AtomicLong messagesFailed          = new AtomicLong(0);
    private final AtomicLong messagesSkippedDuplicate = new AtomicLong(0);

    private final AtomicLong dbWritesSuccess     = new AtomicLong(0);
    private final AtomicLong dbWritesFailed      = new AtomicLong(0);
    private final AtomicLong writeBehindDropped  = new AtomicLong(0);

    public void recordProcessed() {
        messagesProcessed.incrementAndGet();
    }

    public void recordFailed() {
        messagesFailed.incrementAndGet();
    }

    public void recordSkippedDuplicate() {
        messagesSkippedDuplicate.incrementAndGet();
    }

    /** @return total messages processed */
    public long getMessagesProcessed() {
        return messagesProcessed.get();
    }

    /** @return average messages per minute since service start */
    public double getMessagesPerMinute() {
        long elapsedMs = System.currentTimeMillis() - startTimeMs;
        if (elapsedMs < Constants.MILLIS_PER_SECOND) return 0.0;
        return messagesProcessed.get() * (double) Constants.MILLIS_PER_MINUTE / elapsedMs;
    }

    /** @return total messages failed */
    public long getMessagesFailed() {
        return messagesFailed.get();
    }

    /** @return total messages skipped as duplicate */
    public long getMessagesSkippedDuplicate() {
        return messagesSkippedDuplicate.get();
    }

    /** Increments DynamoDB batch-write success counter by {@code count}. */
    public void recordDbWriteSuccess(int count) {
        dbWritesSuccess.addAndGet(count);
    }

    /** Increments DynamoDB batch-write failure counter by {@code count}. */
    public void recordDbWriteFailed(int count) {
        dbWritesFailed.addAndGet(count);
    }

    /**
     * Increments the counter for messages dropped from the write-behind queue
     * because the queue was full at offer time.
     */
    public void recordWriteBehindDropped() {
        writeBehindDropped.incrementAndGet();
    }

    /** @return total DynamoDB items successfully written */
    public long getDbWritesSuccess() {
        return dbWritesSuccess.get();
    }

    /** @return total DynamoDB items permanently failed (sent to DLQ) */
    public long getDbWritesFailed() {
        return dbWritesFailed.get();
    }

    /** @return total messages dropped from the write-behind queue (queue-full events) */
    public long getWriteBehindDropped() {
        return writeBehindDropped.get();
    }
}

package vito.consumer;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class MessageDeduplicator {

    private final long dedupTtlMs;
    private final int dedupMaxSize;
    private final ConcurrentHashMap<String, Long> processedMessageIds = new ConcurrentHashMap<>();

    /**
     * @param dedupTtlMinutes TTL in minutes for cache entries
     * @param dedupMaxSize    max number of messageIds to track
     */
    public MessageDeduplicator(
            @Value("${app.dedup.cache-ttl-minutes:5}") int dedupTtlMinutes,
            @Value("${app.dedup.cache-max-size:50000}") int dedupMaxSize) {
        this.dedupTtlMs = TimeUnit.MINUTES.toMillis(dedupTtlMinutes);
        this.dedupMaxSize = dedupMaxSize;
    }

    /**
     * @param messageId message id
     * @return true if this messageId was already processed
     */
    public boolean isDuplicate(String messageId) {
        return processedMessageIds.containsKey(messageId);
    }

    /**
     * Records a messageId as processed to prevent future duplicates.
     * @param messageId message id to mark
     */
    public void markProcessed(String messageId) {
        processedMessageIds.put(messageId, System.currentTimeMillis());
        evictIfNeeded();
    }

    private void evictIfNeeded() {
        if (processedMessageIds.size() < dedupMaxSize) {
            return;
        }
        long cutoff = System.currentTimeMillis() - dedupTtlMs;
        processedMessageIds.entrySet().removeIf(e -> e.getValue() < cutoff);
    }
}

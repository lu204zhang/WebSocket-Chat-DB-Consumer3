package vito.persistence.writebehind;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class DeadLetterQueue {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterQueue.class);

    private final ConcurrentLinkedQueue<Map<String, AttributeValue>> failedItems =
            new ConcurrentLinkedQueue<>();

    private final AtomicLong totalDropped = new AtomicLong(0);

    @Value("${app.writebehind.dlq-capacity:5000}")
    private int capacity;

    /**
     * Adds a permanently-failed DynamoDB item to the DLQ.
     * If the DLQ is at capacity, the item is only logged and counted in totalDropped.
     * @param item DynamoDB attribute map of the failed write
     */
    public void offer(Map<String, AttributeValue> item) {
        String messageId = getAttr(item, "messageId");
        String roomId    = getAttr(item, "roomId");

        if (failedItems.size() >= capacity) {
            totalDropped.incrementAndGet();
            log.error("DLQ full — permanently dropping messageId={} roomId={}", messageId, roomId);
            return;
        }

        failedItems.offer(item);
        log.error("DLQ: persisted failure messageId={} roomId={}", messageId, roomId);
    }

    /** @return current number of items waiting in the DLQ */
    public int size() {
        return failedItems.size();
    }

    /** @return cumulative count of items dropped because the DLQ itself was full */
    public long getTotalDropped() {
        return totalDropped.get();
    }

    private static String getAttr(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        return (av != null && av.s() != null) ? av.s() : "unknown";
    }
}

package vito.persistence.writebehind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vito.model.QueueMessage;

import java.util.Collection;
import java.util.concurrent.LinkedBlockingQueue;

@Component
public class WriteBehindQueue {

    private final LinkedBlockingQueue<QueueMessage> messageQueue;
    private final LinkedBlockingQueue<QueueMessage> statsQueue;

    /**
     * @param capacity max messages buffered in each of the two queues
     */
    public WriteBehindQueue(
            @Value("${app.writebehind.queue-capacity:10000}") int capacity) {
        this.messageQueue = new LinkedBlockingQueue<>(capacity);
        this.statsQueue   = new LinkedBlockingQueue<>(capacity);
    }

    /**
     * Non-blocking offer to the DB-write queue.
     * @param msg message to enqueue
     * @return true if enqueued, false if the queue is full
     */
    public boolean offerMessage(QueueMessage msg) {
        return messageQueue.offer(msg);
    }

    /**
     * Non-blocking offer to the stats queue.
     * @param msg message to enqueue
     * @return true if enqueued, false if the queue is full
     */
    public boolean offerStats(QueueMessage msg) {
        return statsQueue.offer(msg);
    }

    /**
     * Drains up to {@code maxElements} from the DB-write queue into the given collection.
     * @param buffer      collection to receive drained messages
     * @param maxElements maximum number of messages to drain
     * @return number of messages actually drained
     */
    public int drainMessages(Collection<? super QueueMessage> buffer, int maxElements) {
        return messageQueue.drainTo(buffer, maxElements);
    }

    /**
     * Drains up to {@code maxElements} from the stats queue into the given collection.
     * @param buffer      collection to receive drained messages
     * @param maxElements maximum number of messages to drain
     * @return number of messages actually drained
     */
    public int drainStats(Collection<? super QueueMessage> buffer, int maxElements) {
        return statsQueue.drainTo(buffer, maxElements);
    }

    /** @return current number of messages in the DB-write queue */
    public int getMessageQueueSize() {
        return messageQueue.size();
    }

    /** @return current number of messages in the stats queue */
    public int getStatsQueueSize() {
        return statsQueue.size();
    }
}

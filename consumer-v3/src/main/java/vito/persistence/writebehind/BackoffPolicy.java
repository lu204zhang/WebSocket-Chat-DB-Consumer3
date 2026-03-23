package vito.persistence.writebehind;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class BackoffPolicy {

    @Value("${app.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${app.retry.backoff-base-ms:100}")
    private long baseMs;

    /**
     * @param attempt zero-based attempt number (0 = first try)
     * @return true if another attempt is allowed
     */
    public boolean shouldRetry(int attempt) {
        return attempt < maxAttempts;
    }

    /**
     * Returns exponential delay: {@code baseMs * 2^attempt}.
     * @param attempt zero-based attempt number
     * @return delay in milliseconds
     */
    public long delayMs(int attempt) {
        return baseMs * (1L << attempt);
    }

    /** @return configured maximum number of attempts */
    public int getMaxAttempts() {
        return maxAttempts;
    }
}

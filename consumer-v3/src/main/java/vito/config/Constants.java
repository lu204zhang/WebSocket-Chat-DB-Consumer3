package vito.config;

import java.util.stream.IntStream;

public final class Constants {

    private Constants() {
    }

    public static final String ROOM_ID_ATTR  = "roomId";
    public static final String USER_ID_ATTR  = "userId";
    public static final String USERNAME_ATTR = "username";

    public static final int    MIN_ROOM_ID       = 1;
    public static final int    MAX_ROOM_ID        = 20;
    public static final String ROOM_QUEUE_PREFIX  = "room.";

    public static final String[] QUEUE_NAMES = IntStream.range(MIN_ROOM_ID, MAX_ROOM_ID + 1)
            .mapToObj(i -> ROOM_QUEUE_PREFIX + i)
            .toArray(String[]::new);

    /** AWS hard limit: max items per BatchWriteItem request. */
    public static final int DYNAMO_BATCH_LIMIT = 25;

    /** GSI2 user partition-key prefix: "user#&lt;userId&gt;" */
    public static final String KEY_PREFIX_USER = "user#";

    /** GSI2 user sort-key room prefix: "room#&lt;roomId&gt;#&lt;timestamp&gt;" */
    public static final String KEY_PREFIX_ROOM = "room#";

    /** RoomAnalytics sort-key prefix for daily/hourly message-count metrics. */
    public static final String ANALYTICS_SK_PREFIX = "metric#MESSAGE_COUNT#";

    /** Sentinel value stored in RoomAnalytics SK for daily (all-hours) aggregates. */
    public static final String ANALYTICS_SK_ALL = "ALL";

    /** Zero-padded width for hour in RoomAnalytics SK (e.g. "07"). */
    public static final int ANALYTICS_HOUR_FORMAT_WIDTH = 2;

    /** Zero-padded width for timestamp in GSI2 sort key (13 digits covers ms epoch). */
    public static final int TIMESTAMP_PAD_WIDTH = 13;

    public static final long MILLIS_PER_SECOND = 1_000L;
    public static final long MILLIS_PER_MINUTE = 60_000L;

    /** Max seconds to wait for executor termination during graceful shutdown. */
    public static final int EXECUTOR_SHUTDOWN_TIMEOUT_SECONDS = 10;
}

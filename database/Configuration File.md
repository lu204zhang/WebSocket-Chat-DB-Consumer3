# Configuration Files

All runtime parameters are defined in `consumer-v3/src/main/resources/application.properties`.

## Database Connection Settings

The consumer connects to DynamoDB in the `us-east-1` region using on-demand (`PAY_PER_REQUEST`) billing mode. Three tables are configured: `ChatMessages` for message storage, `UserActivity` for active-user tracking, and `RoomAnalytics` for pre-aggregated analytics. The HTTP connection pool is set to a maximum of 50 concurrent connections to balance throughput and resource usage.

## Thread Pool Configurations

The write-behind pipeline uses two separate thread pools. The database writer pool allocates 4 threads dedicated to flushing message batches to DynamoDB, while the statistics aggregator pool uses 2 threads for analytics updates. On the RabbitMQ consumer side, 80 listener threads are configured with a prefetch count of 10 per thread, ensuring messages are consumed faster than they are written to the database.

## Batch Processing Parameters

Messages are accumulated in an in-memory queue (capacity: 50,000 items) and flushed to DynamoDB every 1,000 ms in batches of up to 500 items. This configuration was selected through empirical testing as the optimal balance between write throughput and latency. Failed writes are routed to an in-memory dead-letter queue (capacity: 5,000 items). Retries use exponential backoff starting at 100 ms, with a maximum of 3 attempts before a message is sent to the DLQ.

## Circuit Breaker Thresholds

Two independent circuit breaker instances are configured via Resilience4j — one for the `ChatMessages` write path and one for the `UserActivity`/`RoomAnalytics` statistics path. Each uses a count-based sliding window of 50 calls. The circuit opens when the failure rate exceeds 70%, then waits 30 seconds before transitioning to half-open state, where 3 probe calls are permitted to test recovery. Automatic transition from open to half-open is enabled to avoid manual intervention during transient outages.

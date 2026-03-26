# Database Design Document

## 1. Database Choice Justification

**Selected: Amazon DynamoDB (on-demand / PAY_PER_REQUEST)**

| Criterion             | MySQL                                             | MongoDB                  | **DynamoDB**                     |
| --------------------- | ------------------------------------------------- | ------------------------ | -------------------------------- |
| Write throughput      | ~200–400 msg/s                                    | ~500–800 msg/s           | **2,000+ msg/s**                 |
| Multi-pattern queries | 4–5 indexes (write amplification on every INSERT) | Sharding complexity      | **2 GSIs, independent capacity** |
| Horizontal scaling    | Vertical only; read replicas don't help writes    | Manual sharding required | **Auto-partitioning**            |
| Schema flexibility    | Rigid schema; ALTER TABLE is expensive            | Flexible documents       | **Schemaless, attribute-level**  |

**Rationale**: The system's primary constraint is high-throughput sequential writes (500K–1M+ messages per test). MySQL's InnoDB engine acquires row-level locks and updates multiple B-tree indexes on every `INSERT`, creating a hard ceiling around 400 msg/s under our access patterns. MongoDB improves on this but requires careful sharding configuration to distribute writes across rooms. DynamoDB's partition-based model avoids index maintenance overhead entirely, and its GSI capacity is independent of the base table — eliminating write contention across query patterns.

---

## 2. Complete Schema Design

### Table 1: `ChatMessages` — Primary messages store

Supports **Query 1** (room time range) and **Query 2** (user message history).

| Attribute                                                  | Type   | Role                                    |
| ---------------------------------------------------------- | ------ | --------------------------------------- |
| `room_id`                                                  | String | **Partition Key** — e.g. `"5"`          |
| `messageId`                                                | String | **Sort Key** — globally unique UUID     |
| `user_id`                                                  | String | GSI1 PK, GSI2 FK                        |
| `timestamp`                                                | Number | Epoch millis; GSI1 SK                   |
| `user_room_pk`                                             | String | GSI2 PK — `"user#{userId}"`             |
| `user_room_sk`                                             | String | GSI2 SK — `"room#{roomId}#{timestamp}"` |
| `message`, `username`, `messageType`, `status`, `serverId` | String | Payload                                 |
| `ttl`                                                      | Number | Unix epoch; auto-delete after 30 days   |

**Global Secondary Indexes**:

| GSI                 | Partition Key  | Sort Key       | Projection | Supports                            |
| ------------------- | -------------- | -------------- | ---------- | ----------------------------------- |
| `UserMessagesIndex` | `user_id`      | `timestamp`    | ALL        | Query 2 — user history across rooms |
| `GSI2-UserRooms`    | `user_room_pk` | `user_room_sk` | KEYS_ONLY  | Query 4 — rooms per user            |


---

### Table 2: `UserActivity` — Active user tracking

Supports **Query 3** (count active users in a time window).

| Attribute                 | Type   | Role                        |
| ------------------------- | ------ | --------------------------- |
| `user_id`                 | String | **Partition Key**           |
| `timestamp`               | Number | **Sort Key** — epoch millis |
| `room_id`, `activityType` | String | Payload                     |
| `ttl`                     | Number | Auto-delete after 30 days   |

Query 3 partitions by `user_id` and filters on `timestamp` range; deduplication by `userId` is applied in the application layer to count unique active users.

---

### Table 3: `RoomAnalytics` — Pre-aggregated analytics

Supports **analytics queries** (messages/sec, Top-N users/rooms, participation patterns).

| Attribute                                         | Type   | Role                                           |
| ------------------------------------------------- | ------ | ---------------------------------------------- |
| `date_room_id`                                    | String | **Partition Key** — `"{date}#{roomId}"`        |
| `metric_key`                                      | String | **Sort Key** — `"metric#MESSAGE_COUNT#{hour}"` |
| `messageCount`, `uniqueUsers`, `avgMessageLength` | Number | Aggregated metrics                             |
| `topUsers`                                        | List   | Pre-computed Top-N user rankings               |

Analytics are aggregated hourly by the `StatisticsAggregator` background thread pool and stored here, avoiding full-table scans on `ChatMessages` for analytics queries.

---

## 3. Indexing Strategy

| Query                      | Access Path                    | Key Expression                                  |
| -------------------------- | ------------------------------ | ----------------------------------------------- |
| Q1: Room messages in range | ChatMessages (base table)      | `room_id = X AND messageId BETWEEN ts1 AND ts2` |
| Q2: User message history   | UserMessagesIndex (GSI1)       | `user_id = X AND timestamp BETWEEN ts1 AND ts2` |
| Q3: Count active users     | UserActivity + app-layer dedup | `user_id = X AND timestamp BETWEEN ts1 AND ts2` |
| Q4: Rooms per user         | GSI2-UserRooms                 | `user_room_pk = user#X`                         |

**Key design decisions**:
- **GSI1 projection = ALL**: eliminates table lookups for Q2; trades ~2× storage for lower read latency.
- **GSI2 projection = KEYS_ONLY**: Q4 only needs room IDs encoded in the sort key, minimizing storage cost.
- **`messageId` as sort key** (not timestamp): guarantees uniqueness under burst writes and enables idempotent conditional writes (`attribute_not_exists(messageId)`).
- GSI updates are asynchronous (~1s lag) and do not block the write path.

---

## 4. Scaling Considerations

**Current partition distribution**: `room_id` distributes writes across 20 rooms, each forming an independent partition. At 500K messages × ~500 bytes each ≈ 250 MB per room — well within DynamoDB's 10 GB per partition limit.

**Growth trajectory**:
- At 5M messages/day in production: ~2.5 GB/room/day. Partitions fill in ~4 days.
- **Resolution**: Introduce date-based table suffixes (`ChatMessages-2026-03`) or sub-shard keys (`room_id + date`) before approaching the 10 GB limit.

**Dead-letter queue and future durability**: Messages that fail all DynamoDB write retries are currently held in an in-memory dead-letter queue (capacity: 5,000 items). While this prevents pipeline stalls, items in the DLQ are lost if the consumer process restarts. A future improvement is to back the DLQ with Amazon SQS, which provides durable, persistent storage for failed writes. This would allow the system to replay failed messages after a DynamoDB outage or a consumer crash without any data loss.

---

## 5. Backup and Recovery

All three tables have Point-in-Time Recovery (PITR) enabled, allowing restore to any second within the past 35 days. Daily snapshots via AWS Backup provide an additional 30-day retention layer, and a weekly S3 export (DataPipeline) serves as a long-term cold archive.

In the event of data corruption, the recovery procedure is: restore the affected table to a pre-incident timestamp via PITR, rename it to replace the live table, then re-process any messages from the gap using the RabbitMQ dead-letter queue.

---

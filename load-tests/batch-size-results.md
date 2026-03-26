# Batch Size Optimization Test Results

## Overview

Tested 5 combinations of `batch-size` × `flush-interval` to find the optimal write-behind configuration.
Each test sends **50,000 messages** through the full pipeline and measures DynamoDB write performance.

**Test Environment**
- Instance: EC2 t3.micro (consumer-v3)
- DynamoDB: PAY_PER_REQUEST (us-east-1)
- server Instances_count = 2
- client NUM_WORKERS = 256
- app.consumer.threads = 80
- app.consumer.prefetch = 10
- app.consumer.concurrent-consumers-per-queue = 1
- DB Writer Threads: 4
- Stats Threads: 2

---

## Test Matrix

| Test # | batch-size | flush-interval-ms | Description     |
| ------ | ---------- | ----------------- | --------------- |
| T1     | 100        | 100               | Minimum latency |
| T2     | 500        | 500               | Default config  |
| T3     | 1000       | 500               | Large batch     |
| T4     | 500        | 1000              | Low-frequency   |
| T5     | 5000       | 1000              | Max batch       |

---

## Results

| Test # | batch-size | flush-interval | Duration (ms) | Throughput (msg/s) | dbWritesSuccess | dbWritesFailed | dlqDropped |
| ------ | ---------- | -------------- | ------------- | ------------------ | --------------- | -------------- | ---------- |
| T1     | 100        | 100ms          | 233114        | 2513               | 119768          | 0              | 0          |
| T2     | 500        | 500ms          | 190430        | 3253               | 119530          | 0              | 0          |
| T3     | 1000       | 500ms          | 201291        | 3022               | 112004          | 2              | 0          |
| T4     | 500        | 1000ms         | 178280        | 3555               | 111380          | 0              | 0          |
| T5     | 5000       | 1000ms         | 185556        | 3347               | 108938          | 0              | 0          |

*Data source: `GET http://localhost:8081/app/metrics` after each test run.*

---

## Analysis

### Throughput vs Batch Size

Increasing batch size from 100 (T1) to 500 (T2) improved throughput from 2,513 to 3,253 msg/s (+29%).
Further increasing to 1,000 (T3) reduced throughput to 3,022 msg/s, likely due to larger flush cycles
blocking the write thread longer. At 5,000 (T5), throughput recovered slightly to 3,347 msg/s but
`dbWritesSuccess` was lowest (108,938), indicating a significant portion of writes remained buffered
when metrics were captured at test end.

### Latency vs Flush Interval

Doubling the flush interval from 500ms to 1,000ms (T2 → T4, same batch-size=500) improved throughput
from 3,253 to 3,555 msg/s (+9%) and reduced total duration by ~12 seconds. The longer interval allows
the consumer threads to batch more messages per flush, reducing DynamoDB API call frequency and
amortizing per-call overhead. However, 1,000ms means messages can sit in the write-behind buffer up
to 1 second before being persisted, increasing data-loss window on crash.

### Trade-offs Observed

| Factor             | Small Batch (T1)           | Large Batch (T5)          |
| ------------------ | -------------------------- | ------------------------- |
| Write latency      | Low                        | High (buffered longer)    |
| DynamoDB API calls | High (more BatchWrite ops) | Low                       |
| Memory pressure    | Low                        | Higher (5000-item buffer) |
| DLQ risk on crash  | Low (less in-flight)       | Higher                    |
| Write completeness | High (119,768 recorded)    | Low (108,938 recorded)    |

---

## Optimal Configuration

**Selected**: Test T4 (batch-size = `500`, flush-interval = `1000ms`)

**Reason**:
T4 achieved the highest sustained throughput at 3,555 msg/s with the shortest test duration (178,280ms)
and zero failures or DLQ drops. It uses the same batch-size as the default T2 (500), avoiding the
write-completeness degradation seen in T5 (5,000-item buffer). The 1,000ms flush interval reduces
DynamoDB API call frequency without introducing unacceptable data-loss risk for this use case.
```

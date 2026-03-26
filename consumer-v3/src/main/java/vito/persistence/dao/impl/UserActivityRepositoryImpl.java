package vito.persistence.dao.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import vito.exception.QueryException;
import vito.model.QueueMessage;
import vito.persistence.dao.UserActivityRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class UserActivityRepositoryImpl implements UserActivityRepository {

    private static final String FILTER_EXPRESSION_TIME = "#ts BETWEEN :ts1 AND :ts2";

    private final DynamoDbAsyncClient asyncClient;

    @Value("${app.dynamodb.table.user-activity}")
    private String userActivityTable;

    /**
     * @param asyncClient DynamoDB async client
     */
    @Autowired
    public UserActivityRepositoryImpl(DynamoDbAsyncClient asyncClient) {
        this.asyncClient = asyncClient;
    }

    @Override
    public CompletableFuture<BatchWriteItemResponse> batchSaveActivities(List<QueueMessage> messages) {
        List<WriteRequest> requests = messages.stream()
                .map(msg -> WriteRequest.builder()
                        .putRequest(PutRequest.builder()
                                .item(Map.of(
                                        "user_id",   AttributeValue.builder().s(msg.getUserId()).build(),
                                        "room_id",   AttributeValue.builder().s(msg.getRoomId()).build(),
                                        "type",      AttributeValue.builder().s(msg.getMessageType().name()).build(),
                                        "timestamp", AttributeValue.builder().n(String.valueOf(
                                                msg.getTimestamp() != null
                                                        ? msg.getTimestamp().toEpochMilli()
                                                        : System.currentTimeMillis())).build()
                                ))
                                .build())
                        .build())
                .collect(Collectors.toList());

        Map<String, List<WriteRequest>> requestItems = new HashMap<>();
        requestItems.put(userActivityTable, requests);

        return asyncClient.batchWriteItem(
                BatchWriteItemRequest.builder().requestItems(requestItems).build());
    }

    @Override
    public Set<String> getActiveUsers(long startTime, long endTime) {
        log.debug("Getting active users for startTime={}, endTime={}", startTime, endTime);
        ScanRequest request = ScanRequest.builder()
                .tableName(userActivityTable)
                .filterExpression(FILTER_EXPRESSION_TIME)
                .expressionAttributeNames(Map.of("#ts", "timestamp"))
                .expressionAttributeValues(Map.of(
                        ":ts1", AttributeValue.builder().n(String.valueOf(startTime)).build(),
                        ":ts2", AttributeValue.builder().n(String.valueOf(endTime)).build()
                ))
                .projectionExpression("user_id")
                .build();

        try {
            Set<String> result = new java.util.HashSet<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest pagedRequest = lastKey == null ? request :
                        request.toBuilder().exclusiveStartKey(lastKey).build();
                ScanResponse response = asyncClient.scan(pagedRequest).get();
                response.items().forEach(item -> result.add(item.get("user_id").s()));
                lastKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (lastKey != null);
            return result;
        } catch (Exception e) {
            throw new QueryException("Failed to get active users: " + e.getMessage());
        }
    }
}
package vito.persistence.dao.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import vito.config.Constants;
import vito.exception.QueryException;
import vito.persistence.dao.MessageRepository;
import vito.persistence.model.PersistentMessage;
import vito.metrics.dto.UserRoomSummary;
import vito.persistence.util.DynamoDBMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class MessageRepositoryImpl implements MessageRepository {

    private static final String KEY_CONDITION_ROOM = "room_id = :roomId";
    private static final String FILTER_TS_RANGE    = "#ts BETWEEN :ts1 AND :ts2";
    private static final String KEY_CONDITION_USER = "user_id = :userId AND #ts BETWEEN :ts1 AND :ts2";

    private final DynamoDbAsyncClient asyncClient;

    @Value("${app.dynamodb.table.messages}")
    private String messagesTable;

    /**
     * @param asyncClient DynamoDB async client
     */
    @Autowired
    public MessageRepositoryImpl(DynamoDbAsyncClient asyncClient) {
        this.asyncClient = asyncClient;
    }

    @Override
    public CompletableFuture<BatchWriteItemResponse> batchSaveMessages(List<PersistentMessage> messages) {
        List<WriteRequest> writeRequests = messages.stream()
                .map(msg -> WriteRequest.builder()
                        .putRequest(PutRequest.builder()
                                .item(DynamoDBMapper.messageToItem(msg))
                                .build())
                        .build())
                .collect(Collectors.toList());

        Map<String, List<WriteRequest>> requestItems = new HashMap<>();
        requestItems.put(messagesTable, writeRequests);

        return asyncClient.batchWriteItem(
                BatchWriteItemRequest.builder()
                        .requestItems(requestItems)
                        .build());
    }

    @Override
    public List<PersistentMessage> getMessagesByRoom(String roomId, long startTime, long endTime) {
        log.debug("Querying messages for room={}, startTime={}, endTime={}", roomId, startTime, endTime);
        QueryRequest queryRequest = QueryRequest.builder()
                .tableName(messagesTable)
                .keyConditionExpression(KEY_CONDITION_ROOM)
                .filterExpression(FILTER_TS_RANGE)
                .expressionAttributeNames(Map.of("#ts", "timestamp"))
                .expressionAttributeValues(Map.of(
                        ":roomId", AttributeValue.builder().s(roomId).build(),
                        ":ts1", AttributeValue.builder().n(String.valueOf(startTime)).build(),
                        ":ts2", AttributeValue.builder().n(String.valueOf(endTime)).build()
                ))
                .build();

        try {
            List<PersistentMessage> allMessages = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest pagedRequest = lastKey == null ? queryRequest :
                        queryRequest.toBuilder().exclusiveStartKey(lastKey).build();
                QueryResponse response = asyncClient.query(pagedRequest).get();
                response.items().forEach(item -> allMessages.add(DynamoDBMapper.mapToMessage(item)));
                lastKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (lastKey != null);
            return allMessages;
        } catch (Exception e) {
            throw new QueryException("Failed to get messages by room: " + e.getMessage());
        }
    }

    @Override
    public List<PersistentMessage> getUserMessages(String userId, long startTime, long endTime) {
        log.debug("Querying user messages for userId={}, startTime={}, endTime={}", userId, startTime, endTime);
        QueryRequest request = QueryRequest.builder()
                .tableName(messagesTable)
                .indexName("UserMessagesIndex")
                .keyConditionExpression(KEY_CONDITION_USER)
                .expressionAttributeNames(Map.of("#ts", "timestamp"))
                .expressionAttributeValues(Map.of(
                        ":userId", AttributeValue.builder().s(userId).build(),
                        ":ts1", AttributeValue.builder().n(String.valueOf(startTime)).build(),
                        ":ts2", AttributeValue.builder().n(String.valueOf(endTime)).build()
                ))
                .scanIndexForward(true)
                .consistentRead(false)
                .build();

        try {
            List<PersistentMessage> allMessages = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest pagedRequest = lastKey == null ? request :
                        request.toBuilder().exclusiveStartKey(lastKey).build();
                QueryResponse response = asyncClient.query(pagedRequest).get();
                response.items().forEach(item -> allMessages.add(DynamoDBMapper.mapToMessage(item)));
                lastKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (lastKey != null);
            return allMessages;
        } catch (Exception e) {
            throw new QueryException("Failed to get user messages: " + e.getMessage());
        }
    }

    @Override
    public List<UserRoomSummary> getRoomsForUser(String userId) {
        log.debug("Getting rooms for userId={}", userId);
        QueryRequest request = QueryRequest.builder()
                .tableName(messagesTable)
                .indexName("GSI2-UserRooms")
                .keyConditionExpression("user_room_pk = :userPk")
                .expressionAttributeValues(Map.of(
                        ":userPk", AttributeValue.builder().s(Constants.KEY_PREFIX_USER + userId).build()
                ))
                .projectionExpression("user_room_sk")
                .build();

        try {
            Map<String, Long> roomToLastActivity = new HashMap<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                QueryRequest pagedRequest = lastKey == null ? request :
                        request.toBuilder().exclusiveStartKey(lastKey).build();
                QueryResponse response = asyncClient.query(pagedRequest).get();
                response.items().forEach(item -> {
                    String sk = item.get("user_room_sk").s();
                    String[] parts = sk.split("#", 3);
                    String roomId = parts[1];
                    long ts = Long.parseLong(parts[2]);
                    roomToLastActivity.merge(roomId, ts, Math::max);
                });
                lastKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (lastKey != null);

            return roomToLastActivity.entrySet().stream()
                    .map(e -> new UserRoomSummary(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingLong(UserRoomSummary::getLastActivity).reversed())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new QueryException("Failed to get rooms for user: " + e.getMessage());
        }
    }

}

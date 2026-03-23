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

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class MessageRepositoryImpl implements MessageRepository {

    private static final String KEY_CONDITION_ROOM = "PK = :roomId AND SK BETWEEN :ts1 AND :ts2";
    private static final String KEY_CONDITION_USER = "user_id = :userId AND ts BETWEEN :ts1 AND :ts2";

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
                .expressionAttributeValues(Map.of(
                        ":roomId", AttributeValue.builder().s(roomId).build(),
                        ":ts1", AttributeValue.builder().n(String.valueOf(startTime)).build(),
                        ":ts2", AttributeValue.builder().n(String.valueOf(endTime)).build()
                ))
                .build();

        try {
            QueryResponse response = asyncClient.query(queryRequest).get();
            return response.items()
                    .stream()
                    .map(DynamoDBMapper::mapToMessage)
                    .collect(Collectors.toList());
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
                .expressionAttributeValues(Map.of(
                        ":userId", AttributeValue.builder().s(userId).build(),
                        ":ts1", AttributeValue.builder().n(String.valueOf(startTime)).build(),
                        ":ts2", AttributeValue.builder().n(String.valueOf(endTime)).build()
                ))
                .scanIndexForward(true)
                .consistentRead(false)
                .build();

        try {
            QueryResponse response = asyncClient.query(request).get();
            return response.items()
                    .stream()
                    .map(DynamoDBMapper::mapToMessage)
                    .collect(Collectors.toList());
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
                .keyConditionExpression("user2_pk = :userPk")
                .expressionAttributeValues(Map.of(
                        ":userPk", AttributeValue.builder().s(Constants.KEY_PREFIX_USER + userId).build()
                ))
                .projectionExpression("user2_sk")
                .build();

        try {
            QueryResponse response = asyncClient.query(request).get();
            return response.items().stream()
                    .map(item -> item.get("user2_sk").s())
                    .collect(Collectors.toMap(
                            sk -> sk.split("#", 3)[1],   // index 1 = roomId
                            sk -> Long.parseLong(sk.split("#", 3)[2]),  // index 2 = timestamp
                            Math::max
                    ))
                    .entrySet().stream()
                    .map(e -> new UserRoomSummary(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingLong(UserRoomSummary::getLastActivity).reversed())
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new QueryException("Failed to get rooms for user: " + e.getMessage());
        }
    }
}

package vito.persistence.dao.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import vito.exception.QueryException;
import vito.persistence.dao.RoomAnalyticsRepository;
import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;
import vito.persistence.util.DynamoDBMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
@Slf4j
public class RoomAnalyticsRepositoryImpl implements RoomAnalyticsRepository {

    private final DynamoDbAsyncClient asyncClient;

    @Value("${app.dynamodb.table.room-analytics}")
    private String roomAnalyticsTable;

    /**
     * @param asyncClient DynamoDB async client
     */
    @Autowired
    public RoomAnalyticsRepositoryImpl(DynamoDbAsyncClient asyncClient) {
        this.asyncClient = asyncClient;
    }

    @Override
    public void updateRoomStats(String roomId, RoomStats stats) {
        log.debug("Updating room stats for roomId={}, date={}", roomId, stats.getDate());

        String pk = stats.getDate() + "#" + stats.getRoomId();
        String sk = (stats.getHour() >= 0)
                ? "metric#MESSAGE_COUNT#" + String.format("%02d", stats.getHour())
                : "metric#MESSAGE_COUNT#ALL";

        StringBuilder updateExpr = new StringBuilder(
                "ADD messageCount :mc, totalMessageLength :tl");
        Map<String, AttributeValue> values = new HashMap<>(Map.of(
                ":mc",  AttributeValue.builder().n(String.valueOf(stats.getMessageCount())).build(),
                ":tl",  AttributeValue.builder().n(String.valueOf(stats.getTotalMessageLength())).build(),
                ":rid", AttributeValue.builder().s(stats.getRoomId()).build(),
                ":dt",  AttributeValue.builder().s(stats.getDate()).build(),
                ":ts",  AttributeValue.builder().n(String.valueOf(stats.getTimestamp())).build()
        ));

        if (stats.getUniqueUsers() != null && !stats.getUniqueUsers().isEmpty()) {
            updateExpr.append(", uniqueUsers :uu");
            values.put(":uu", AttributeValue.builder()
                    .ss(new ArrayList<>(stats.getUniqueUsers())).build());
        }

        updateExpr.append(" SET roomId = :rid, #dt = :dt, #ts = :ts");

        if (stats.getTopUsers() != null && !stats.getTopUsers().isEmpty()) {
            updateExpr.append(", topUsers = :tu");
            List<AttributeValue> topUserItems = stats.getTopUsers().stream()
                    .map(u -> AttributeValue.builder().m(Map.of(
                            "userId",       AttributeValue.builder().s(u.getUserId()).build(),
                            "username",     AttributeValue.builder().s(u.getUsername() != null ? u.getUsername() : u.getUserId()).build(),
                            "messageCount", AttributeValue.builder().n(String.valueOf(u.getMessageCount())).build()
                    )).build())
                    .collect(java.util.stream.Collectors.toList());
            values.put(":tu", AttributeValue.builder().l(topUserItems).build());
        }

        UpdateItemRequest request = UpdateItemRequest.builder()
                .tableName(roomAnalyticsTable)
                .key(Map.of(
                        "date_room_id", AttributeValue.builder().s(pk).build(),
                        "metric_key",   AttributeValue.builder().s(sk).build()
                ))
                .updateExpression(updateExpr.toString())
                .expressionAttributeNames(Map.of("#dt", "date", "#ts", "timestamp"))
                .expressionAttributeValues(values)
                .build();

        try {
            asyncClient.updateItem(request).get();
        } catch (Exception e) {
            throw new QueryException("Failed to update room stats: " + e.getMessage());
        }
    }

    @Override
    public List<RoomStats> getTopActiveRooms(int limit, String date) {
        log.debug("Getting top active rooms for limit={}, date={}", limit, date);
        ScanRequest request = ScanRequest.builder()
                .tableName(roomAnalyticsTable)
                .filterExpression("begins_with(date_room_id, :date) AND metric_key = :sk")
                .expressionAttributeValues(Map.of(
                        ":date", AttributeValue.builder().s(date).build(),
                        ":sk",   AttributeValue.builder().s("metric#MESSAGE_COUNT#ALL").build()
                ))
                .build();

        try {
            List<RoomStats> allStats = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest pagedRequest = lastKey == null ? request :
                        request.toBuilder().exclusiveStartKey(lastKey).build();
                ScanResponse response = asyncClient.scan(pagedRequest).get();
                response.items().forEach(item -> allStats.add(DynamoDBMapper.mapToRoomStats(item)));
                lastKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (lastKey != null);
            return allStats.stream()
                    .sorted(Comparator.comparing(RoomStats::getMessageCount).reversed())
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new QueryException("Failed to get top active rooms: " + e.getMessage());
        }
    }

    @Override
    public List<TopUser> getTopActiveUsers(int limit, String date) {
        log.debug("Getting top active users for limit={}, date={}", limit, date);
        ScanRequest request = ScanRequest.builder()
                .tableName(roomAnalyticsTable)
                .filterExpression("begins_with(date_room_id, :date) AND metric_key = :sk")
                .expressionAttributeValues(Map.of(
                        ":date", AttributeValue.builder().s(date).build(),
                        ":sk",   AttributeValue.builder().s("metric#MESSAGE_COUNT#ALL").build()
                ))
                .build();

        try {
            List<Map<String, AttributeValue>> allItems = new ArrayList<>();
            Map<String, AttributeValue> lastKey = null;
            do {
                ScanRequest pagedRequest = lastKey == null ? request :
                        request.toBuilder().exclusiveStartKey(lastKey).build();
                ScanResponse response = asyncClient.scan(pagedRequest).get();
                allItems.addAll(response.items());
                lastKey = response.lastEvaluatedKey().isEmpty() ? null : response.lastEvaluatedKey();
            } while (lastKey != null);
            return allItems.stream()
                    .flatMap(item -> DynamoDBMapper.mapToRoomStats(item).getTopUsers().stream())
                    .sorted(Comparator.comparing(TopUser::getMessageCount).reversed())
                    .distinct()
                    .limit(limit)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            throw new QueryException("Failed to get top active users: " + e.getMessage());
        }
    }
}
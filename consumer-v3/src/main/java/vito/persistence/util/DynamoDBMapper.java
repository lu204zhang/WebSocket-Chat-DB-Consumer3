package vito.persistence.util;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import vito.config.Constants;
import vito.model.MessageType;
import vito.persistence.model.PersistentMessage;
import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;

import java.util.*;
import java.util.stream.Collectors;

public class DynamoDBMapper {

    /**
     * Converts a {@link PersistentMessage} to a DynamoDB attribute map for ChatMessages.
     * @param msg message to convert
     * @return DynamoDB item map
     */
    public static Map<String, AttributeValue> messageToItem(PersistentMessage msg) {
        Map<String, AttributeValue> item = new HashMap<>();

        item.put("room_id", s(msg.getRoomId()));

        item.put("user_id", s(msg.getUserId()));

        item.put("user_room_pk", s(Constants.KEY_PREFIX_USER + msg.getUserId()));
        item.put("user_room_sk", s(Constants.KEY_PREFIX_ROOM + msg.getRoomId() + "#"
                + String.format("%0" + Constants.TIMESTAMP_PAD_WIDTH + "d", msg.getTimestamp())));

        item.put("messageId", s(msg.getMessageId()));
        item.put("roomId",    s(msg.getRoomId()));
        item.put("userId",    s(msg.getUserId()));
        item.put("username",  s(msg.getUsername()));
        item.put("message",   s(msg.getMessage()));
        item.put("timestamp", n(msg.getTimestamp()));

        if (msg.getMessageType() != null) {
            item.put("messageType", s(msg.getMessageType().name()));
        }
        putIfNotNull(item, "status",   msg.getStatus());
        putIfNotNull(item, "serverId", msg.getServerId());
        putIfNotNull(item, "clientIp", msg.getClientIp());

        return item;
    }

    /**
     * Converts a DynamoDB ChatMessages item to a {@link PersistentMessage}.
     * @param item DynamoDB attribute map
     * @return populated PersistentMessage
     */
    public static PersistentMessage mapToMessage(Map<String, AttributeValue> item) {
        PersistentMessage msg = new PersistentMessage();
        msg.setMessageId(  getString(item, "messageId"));
        msg.setRoomId(     getString(item, "roomId"));
        msg.setUserId(     getString(item, "userId"));
        msg.setUsername(   getString(item, "username"));
        msg.setMessage(    getString(item, "message"));
        msg.setTimestamp(  getLong(item, "timestamp"));
        msg.setStatus(     getString(item, "status"));
        msg.setServerId(   getString(item, "serverId"));
        msg.setClientIp(   getString(item, "clientIp"));

        String type = getString(item, "messageType");
        if (type != null) {
            msg.setMessageType(MessageType.valueOf(type));
        }
        return msg;
    }

    /**
     * Converts a DynamoDB RoomAnalytics item to a {@link RoomStats}.
     * @param item DynamoDB attribute map
     * @return populated RoomStats
     */
    public static RoomStats mapToRoomStats(Map<String, AttributeValue> item) {
        RoomStats stats = new RoomStats();
        stats.setRoomId(       getString(item, "roomId"));
        stats.setDate(         getString(item, "date"));
        stats.setMessageCount( getInt(item, "messageCount"));
        stats.setTimestamp(    getLong(item, "timestamp"));

        long totalLen = getLong(item, "totalMessageLength");
        int count = stats.getMessageCount();
        stats.setTotalMessageLength(totalLen);
        stats.setAvgMessageLength(count > 0 ? (double) totalLen / count : 0.0);

        AttributeValue uniqueUsersAv = item.get("uniqueUsers");
        if (uniqueUsersAv != null && uniqueUsersAv.ss() != null) {
            stats.setUniqueUsers(new HashSet<>(uniqueUsersAv.ss()));
        } else {
            stats.setUniqueUsers(new HashSet<>());
        }

        AttributeValue topUsersAv = item.get("topUsers");
        if (topUsersAv != null && topUsersAv.l() != null) {
            List<TopUser> topUsers = topUsersAv.l().stream()
                    .map(av -> mapToTopUser(av.m()))
                    .collect(Collectors.toList());
            stats.setTopUsers(topUsers);
        } else {
            stats.setTopUsers(new ArrayList<>());
        }

        String sk = getString(item, "metric_key");
        if (sk != null && sk.startsWith(Constants.ANALYTICS_SK_PREFIX)) {
            String hourStr = sk.substring(Constants.ANALYTICS_SK_PREFIX.length());
            stats.setHour(Constants.ANALYTICS_SK_ALL.equals(hourStr) ? -1 : Integer.parseInt(hourStr));
        } else {
            stats.setHour(-1);
        }

        return stats;
    }

    private static TopUser mapToTopUser(Map<String, AttributeValue> map) {
        TopUser user = new TopUser();
        user.setUserId(      getString(map, "userId"));
        user.setUsername(    getString(map, "username"));
        user.setMessageCount(getInt(map, "messageCount"));
        return user;
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value != null ? value : "").build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    private static AttributeValue n(int value) {
        return AttributeValue.builder().n(String.valueOf(value)).build();
    }

    private static void putIfNotNull(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) {
            item.put(key, s(value));
        }
    }

    private static String getString(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        return (av != null && av.s() != null) ? av.s() : null;
    }

    private static long getLong(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        return (av != null && av.n() != null) ? Long.parseLong(av.n()) : 0L;
    }

    private static int getInt(Map<String, AttributeValue> item, String key) {
        AttributeValue av = item.get(key);
        return (av != null && av.n() != null) ? Integer.parseInt(av.n()) : 0;
    }

}

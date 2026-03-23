package vito.metrics.dto;

import lombok.Data;
import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;

import java.util.List;

@Data
public class AnalyticsReport {
    private long totalMessages;
    private int uniqueUsers;
    private int activeRooms;
    private double avgMessagesPerRoom;
    private double avgMessagesPerUser;
    private double messagesPerMinute;
    private List<RoomStats> topRooms;
    private List<TopUser> topUsers;
    private long reportGeneratedTime;
}
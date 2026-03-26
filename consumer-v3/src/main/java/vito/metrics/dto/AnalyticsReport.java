package vito.metrics.dto;

import lombok.Data;
import vito.persistence.model.TopUser;

import java.util.List;

@Data
public class AnalyticsReport {
    private long totalMessages;
    private int uniqueUsers;
    private double avgMessagesPerRoom;
    private double avgMessagesPerUser;
    private double messagesPerMinute;
    private List<RoomSummary> topRooms;
    private List<TopUser> topUsers;
    private long reportGeneratedTime;
}
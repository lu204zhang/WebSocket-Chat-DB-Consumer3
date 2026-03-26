package vito.metrics.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RoomSummary {
    private String roomId;
    private int messageCount;
    private int uniqueUserCount;
    private double avgMessageLength;
}

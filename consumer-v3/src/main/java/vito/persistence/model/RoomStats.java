package vito.persistence.model;

import lombok.Data;

import java.util.List;
import java.util.Set;

@Data
public class RoomStats {
    private String roomId;
    private String date;
    private int hour = -1;  // -1 = daily aggregate; 0-23 = specific hour
    private int messageCount;
    private Set<String> uniqueUsers;
    private List<TopUser> topUsers;
    private double avgMessageLength;   // computed on read: totalMessageLength / messageCount
    private long totalMessageLength;   // accumulated sum of message lengths (written to DB)
    private long timestamp;
}

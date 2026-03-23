package vito.metrics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class Query4Response {
    private String userId;
    private List<RoomInfo> rooms;
    private int count;
}

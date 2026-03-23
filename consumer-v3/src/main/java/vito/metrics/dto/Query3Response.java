package vito.metrics.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class Query3Response {
    private long startTime;
    private long endTime;
    private long activeUserCount;
    private Set<String> activeUserIds;
}

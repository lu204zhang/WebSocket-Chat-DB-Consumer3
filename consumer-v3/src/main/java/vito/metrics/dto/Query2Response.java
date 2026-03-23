package vito.metrics.dto;

import lombok.Builder;
import lombok.Data;
import vito.persistence.model.PersistentMessage;

import java.util.List;

@Data
@Builder
public class Query2Response {
    private String userId;
    private long startTime;
    private long endTime;
    private List<PersistentMessage> messages;
    private int count;
}

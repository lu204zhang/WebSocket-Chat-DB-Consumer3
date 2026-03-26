package vito.metrics.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RoomInfo {
    private String roomId;

    @JsonIgnore
    private long lastActivity;

    @JsonProperty("lastActivity")
    public String getLastActivityFormatted() {
        return Instant.ofEpochMilli(lastActivity).toString(); // e.g. "2026-03-21T10:14:53.738Z"
    }
}

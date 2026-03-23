package vito.metrics.dto;

import lombok.Builder;
import lombok.Data;
import vito.metrics.dto.AnalyticsReport;

@Data
@Builder
public class AnalyticsMetricsResponse {
    private AnalyticsReport analytics;
    private long messagesProcessed;
    private long messagesFailed;
    private long messagesSkippedDuplicate;
}

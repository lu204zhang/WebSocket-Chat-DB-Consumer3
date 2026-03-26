package vito.persistence.dao;

import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import vito.model.QueueMessage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface UserActivityRepository {

    /**
     * Persists a batch of user activity events to the UserActivity table asynchronously.
     * @param messages list of messages whose user/room/type/timestamp are recorded
     * @return future resolving to the DynamoDB batch write response
     */
    CompletableFuture<BatchWriteItemResponse> batchSaveActivities(List<QueueMessage> messages);

    /**
     * Returns the set of user IDs that were active within the given timestamp range.
     * @param startTime epoch-millis start of range (inclusive)
     * @param endTime   epoch-millis end of range (inclusive)
     * @return distinct user IDs active in the window
     * @throws vito.exception.QueryException on DynamoDB failure
     */
    Set<String> getActiveUsers(long startTime, long endTime);
}

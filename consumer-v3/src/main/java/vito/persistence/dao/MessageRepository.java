package vito.persistence.dao;

import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import vito.persistence.model.PersistentMessage;
import vito.metrics.dto.UserRoomSummary;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MessageRepository {

    /**
     * Persists a batch of messages to the ChatMessages table asynchronously.
     * @param messages list of messages to write; must not be empty
     * @return future resolving to the DynamoDB batch write response
     */
    CompletableFuture<BatchWriteItemResponse> batchSaveMessages(List<PersistentMessage> messages);

    /**
     * Queries messages in a room within a timestamp range (inclusive).
     * Results are paginated internally and returned as a complete list.
     * @param roomId    the room identifier
     * @param startTime epoch-millis start of range (inclusive)
     * @param endTime   epoch-millis end of range (inclusive)
     * @return all matching messages, ordered by DynamoDB default key order
     * @throws vito.exception.QueryException on DynamoDB failure
     */
    List<PersistentMessage> getMessagesByRoom(String roomId, long startTime, long endTime);

    /**
     * Queries messages sent by a user within a timestamp range via the UserMessagesIndex GSI.
     * @param userId    the user identifier
     * @param startTime epoch-millis start of range (inclusive)
     * @param endTime   epoch-millis end of range (inclusive)
     * @return all matching messages in ascending timestamp order
     * @throws vito.exception.QueryException on DynamoDB failure
     */
    List<PersistentMessage> getUserMessages(String userId, long startTime, long endTime);

    /**
     * Returns a summary of all rooms the user has posted in, ordered by most recent activity.
     * Uses the GSI2-UserRooms index for efficient lookup.
     * @param userId the user identifier
     * @return list of room summaries with last-activity timestamps, newest first
     * @throws vito.exception.QueryException on DynamoDB failure
     */
    List<UserRoomSummary> getRoomsForUser(String userId);
}

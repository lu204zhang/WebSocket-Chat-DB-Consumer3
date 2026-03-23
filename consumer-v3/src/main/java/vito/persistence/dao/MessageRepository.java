package vito.persistence.dao;

import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import vito.persistence.model.PersistentMessage;
import vito.metrics.dto.UserRoomSummary;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface MessageRepository {

    CompletableFuture<BatchWriteItemResponse> batchSaveMessages(List<PersistentMessage> messages);

    List<PersistentMessage> getMessagesByRoom(String roomId, long startTime, long endTime);

    List<PersistentMessage> getUserMessages(String userId, long startTime, long endTime);

    List<UserRoomSummary> getRoomsForUser(String userId);
}

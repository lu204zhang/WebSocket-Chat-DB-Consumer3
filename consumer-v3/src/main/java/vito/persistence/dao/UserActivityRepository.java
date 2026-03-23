package vito.persistence.dao;

import software.amazon.awssdk.services.dynamodb.model.BatchWriteItemResponse;
import vito.model.QueueMessage;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface UserActivityRepository {

    CompletableFuture<BatchWriteItemResponse> batchSaveActivities(List<QueueMessage> messages);

    Set<String> getActiveUsers(long startTime, long endTime);
}

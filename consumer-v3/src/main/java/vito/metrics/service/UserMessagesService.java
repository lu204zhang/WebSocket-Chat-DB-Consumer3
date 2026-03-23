package vito.metrics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vito.metrics.dto.Query2Response;
import vito.persistence.dao.MessageRepository;
import vito.persistence.model.PersistentMessage;

import java.util.List;

@Service
@Slf4j
public class UserMessagesService {

    private final MessageRepository messageRepository;

    /**
     * @param messageRepository DynamoDB repository for ChatMessages
     */
    public UserMessagesService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Returns all messages sent by a user across all rooms within the given time range.
     * @param userId    user to query
     * @param startTime start of range (epoch ms, inclusive)
     * @param endTime   end of range (epoch ms, inclusive)
     * @return response with message list and count
     */
    public Query2Response getUserMessages(String userId, long startTime, long endTime) {
        log.debug("Getting user messages for userId={}, startTime={}, endTime={}", userId, startTime, endTime);
        List<PersistentMessage> messages = messageRepository.getUserMessages(userId, startTime, endTime);
        return Query2Response.builder()
                .userId(userId)
                .startTime(startTime)
                .endTime(endTime)
                .messages(messages)
                .count(messages.size())
                .build();
    }
}

package vito.metrics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vito.metrics.dto.Query1Response;
import vito.persistence.dao.MessageRepository;
import vito.persistence.model.PersistentMessage;

import java.util.List;

@Service
@Slf4j
public class RoomMessagesService {

    private final MessageRepository messageRepository;

    /**
     * @param messageRepository DynamoDB repository for ChatMessages
     */
    public RoomMessagesService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Returns all messages in a room within the given time range.
     * @param roomId    room to query
     * @param startTime start of range (epoch ms, inclusive)
     * @param endTime   end of range (epoch ms, inclusive)
     * @return response with message list and count
     */
    public Query1Response getMessages(String roomId, long startTime, long endTime) {
        log.debug("Getting room messages for roomId={}, startTime={}, endTime={}", roomId, startTime, endTime);
        List<PersistentMessage> messages = messageRepository.getMessagesByRoom(roomId, startTime, endTime);
        return Query1Response.builder()
                .roomId(roomId)
                .startTime(startTime)
                .endTime(endTime)
                .messages(messages)
                .count(messages.size())
                .build();
    }
}

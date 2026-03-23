package vito.metrics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vito.metrics.dto.Query4Response;
import vito.metrics.dto.RoomInfo;
import vito.persistence.dao.MessageRepository;
import vito.metrics.dto.UserRoomSummary;

import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class UserRoomsService {

    private final MessageRepository messageRepository;

    /**
     * @param messageRepository DynamoDB repository for ChatMessages
     */
    public UserRoomsService(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    /**
     * Returns rooms a user has participated in, ordered by most recent activity.
     * @param userId user to query
     * @return response with room list and count
     */
    public Query4Response getUserRooms(String userId) {
        log.debug("Getting rooms for userId={}", userId);
        List<UserRoomSummary> summaries = messageRepository.getRoomsForUser(userId);
        List<RoomInfo> rooms = summaries.stream()
                .map(s -> RoomInfo.builder()
                        .roomId(s.getRoomId())
                        .lastActivity(s.getLastActivity())
                        .build())
                .collect(Collectors.toList());
        return Query4Response.builder()
                .userId(userId)
                .rooms(rooms)
                .count(rooms.size())
                .build();
    }
}

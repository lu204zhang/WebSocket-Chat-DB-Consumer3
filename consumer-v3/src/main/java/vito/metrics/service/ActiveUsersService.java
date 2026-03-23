package vito.metrics.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vito.metrics.dto.Query3Response;
import vito.persistence.dao.UserActivityRepository;

import java.util.Set;

@Service
@Slf4j
public class ActiveUsersService {

    private final UserActivityRepository userActivityRepository;

    /**
     * @param userActivityRepository DynamoDB repository for UserActivity records
     */
    public ActiveUsersService(UserActivityRepository userActivityRepository) {
        this.userActivityRepository = userActivityRepository;
    }

    /**
     * Returns distinct active users whose activity falls within the given time range.
     * @param startTime start of range (epoch ms, inclusive)
     * @param endTime   end of range (epoch ms, inclusive)
     * @return response with active user count and ids
     */
    public Query3Response countActiveUsers(long startTime, long endTime) {
        log.debug("Counting active users for startTime={}, endTime={}", startTime, endTime);
        Set<String> activeUsers = userActivityRepository.getActiveUsers(startTime, endTime);
        return Query3Response.builder()
                .startTime(startTime)
                .endTime(endTime)
                .activeUserCount(activeUsers.size())
                .activeUserIds(activeUsers)
                .build();
    }

}

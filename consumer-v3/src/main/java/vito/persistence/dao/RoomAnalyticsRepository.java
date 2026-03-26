package vito.persistence.dao;

import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;

import java.util.List;

public interface RoomAnalyticsRepository {

    /**
     * Upserts aggregated statistics for a room on a given date into the RoomAnalytics table.
     * Uses DynamoDB ADD expressions so concurrent writes accumulate rather than overwrite.
     * @param roomId the room identifier
     * @param stats  aggregated stats snapshot to merge into the stored record
     * @throws vito.exception.QueryException on DynamoDB failure
     */
    void updateRoomStats(String roomId, RoomStats stats);

    /**
     * Returns the most active rooms for a given date, ordered by message count descending.
     * @param limit maximum number of rooms to return; use {@link Integer#MAX_VALUE} for all
     * @param date  date string in {@code yyyy-MM-dd} format
     * @return up to {@code limit} rooms sorted by message count descending
     * @throws vito.exception.QueryException on DynamoDB failure
     */
    List<RoomStats> getTopActiveRooms(int limit, String date);

    /**
     * Returns the most active users across all rooms for a given date, ordered by message count descending.
     * @param limit maximum number of users to return
     * @param date  date string in {@code yyyy-MM-dd} format
     * @return up to {@code limit} users sorted by message count descending
     * @throws vito.exception.QueryException on DynamoDB failure
     */
    List<TopUser> getTopActiveUsers(int limit, String date);
}
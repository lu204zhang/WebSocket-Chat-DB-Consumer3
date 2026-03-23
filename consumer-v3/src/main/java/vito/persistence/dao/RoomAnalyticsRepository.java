package vito.persistence.dao;

import vito.persistence.model.RoomStats;
import vito.persistence.model.TopUser;

import java.util.List;

public interface RoomAnalyticsRepository {

    void updateRoomStats(String roomId, RoomStats stats);

    List<RoomStats> getTopActiveRooms(int limit, String date);

    List<TopUser> getTopActiveUsers(int limit, String date);
}
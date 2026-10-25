
package com.chamberjams.backend.room;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomMemberRepository
        extends JpaRepository<RoomMember, String> {

    Optional<RoomMember> findByRoomIdAndTelegramId(
            String roomId, String telegramId);

    Optional<RoomMember> findByIdAndRoomId(
            String id, String roomId);

    List<RoomMember> findByRoomIdOrderByJoinedAtAsc(
            String roomId);

    boolean existsByRoomIdAndTelegramId(
            String roomId, String telegramId);

    long countByRoomId(String roomId);
}

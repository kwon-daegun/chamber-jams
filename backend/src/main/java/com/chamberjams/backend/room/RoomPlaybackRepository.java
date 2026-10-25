package com.chamberjams.backend.room;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface RoomPlaybackRepository
        extends JpaRepository<RoomPlaybackState, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select playback
        from RoomPlaybackState playback
        where playback.roomId = :roomId
        """)
    Optional<RoomPlaybackState> findByRoomIdForUpdate(
            @Param("roomId") String roomId);
}
package com.chamberjams.backend.room;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, String> {

    Optional<Room> findByInviteCodeIgnoreCase(String inviteCode);
}

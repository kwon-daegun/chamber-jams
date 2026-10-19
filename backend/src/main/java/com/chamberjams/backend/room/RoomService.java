package com.chamberjams.backend.room;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {

    private final RoomRepository roomRepository;

    public RoomService(RoomRepository roomRepository) {
        this.roomRepository = roomRepository;
    }

    @Transactional
    public Room createRoom(String name, String hostName) {
        String id = UUID.randomUUID().toString();

        String inviteCode = UUID.randomUUID()
            .toString()
            .replace("-", "")
            .substring(0, 8)
            .toUpperCase(Locale.ROOT);

        Room room = new Room(
            id,
            inviteCode,
            name.trim(),
            hostName.trim(),
            1,
            Instant.now()
        );

        return roomRepository.save(room);
    }

    @Transactional(readOnly = true)
    public Optional<Room> findById(String id) {
        return roomRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Room> findByInviteCode(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            return Optional.empty();
        }

        String normalized = inviteCode.trim().toUpperCase(Locale.ROOT);

        return roomRepository.findByInviteCodeIgnoreCase(normalized);
    }

    @Transactional
    public Optional<Room> joinByInviteCode(String inviteCode) {
        Optional<Room> result = findByInviteCode(inviteCode);

        if (result.isEmpty()) {
            return Optional.empty();
        }

        Room room = result.get();
        room.setListenerCount(room.getListenerCount() + 1);

        return Optional.of(roomRepository.save(room));
    }
}

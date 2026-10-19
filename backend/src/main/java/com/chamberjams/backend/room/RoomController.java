package com.chamberjams.backend.room;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    @PostMapping
    public ResponseEntity<?> createRoom(
            @RequestBody CreateRoomRequest request) {

        if (request.name() == null || request.name().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Room name is required"));
        }

        if (request.hostName() == null || request.hostName().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Host name is required"));
        }

        Room room = roomService.createRoom(
            request.name(),
            request.hostName()
        );

        return ResponseEntity.status(HttpStatus.CREATED).body(room);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Room> getRoom(@PathVariable String id) {
        return roomService.findById(id)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/invite/{inviteCode}")
    public ResponseEntity<Room> getRoomByInviteCode(
            @PathVariable String inviteCode) {

        return roomService.findByInviteCode(inviteCode)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record CreateRoomRequest(String name, String hostName) {}

    @PostMapping("/join")
    public ResponseEntity<?> joinRoom(
            @RequestBody JoinRoomRequest request) {

        if (request.inviteCode() == null
                || request.inviteCode().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Invitation code is required"));
        }

        return roomService.joinByInviteCode(request.inviteCode())
            .<ResponseEntity<?>>map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record JoinRoomRequest(String inviteCode) {}
}

package com.chamberjams.backend.room;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chamberjams.backend.auth.TelegramAuthController.TelegramProfile;
import com.chamberjams.backend.auth.TelegramIdentityService;
import com.chamberjams.backend.room.RoomController.MemberView;

@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;
    private final TelegramIdentityService identityService;
    private final RoomPlaybackService playbackService;

   public RoomController(
        RoomService roomService,
        TelegramIdentityService identityService,
        RoomPlaybackService playbackService) {
    this.roomService = roomService;
    this.identityService = identityService;
    this.playbackService = playbackService;
}
    @PostMapping
    public ResponseEntity<?> createRoom(
            @RequestBody(required = false) CreateRoomRequest request) {

        if (request == null
                || request.name() == null
                || request.name().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Room name is required."));
        }

        final TelegramProfile profile;

        try {
            profile = identityService.validateProfile(request.initData());
        } catch (IllegalArgumentException exception) {
            return unauthorized();
        }

        Room room = roomService.createRoom(request.name(), profile);

        return ResponseEntity.status(HttpStatus.CREATED).body(room);
    }

    @GetMapping("/active")
    public List<PublicRoomCard> getActiveRooms() {
        return roomService.findActiveRooms().stream()
                .map(room -> {
                    boolean playing = playbackService
                            .getPlayback(room.getId())
                            .map(state ->
                                    state.isPlaying()
                                    && state.currentTrack() != null)
                            .orElse(false);

                    return new PublicRoomCard(
                            room.getId(),
                            room.getName(),
                            playing);
                })
                .toList();
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

    @PostMapping("/join")
    public ResponseEntity<?> joinRoom(
            @RequestBody(required = false) JoinRoomRequest request) {

        if (request == null
                || request.inviteCode() == null
                || request.inviteCode().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Invitation code is required."));
        }

        final TelegramProfile profile;

        try {
            profile = identityService.validateProfile(request.initData());
        } catch (IllegalArgumentException exception) {
            return unauthorized();
        }

        try {
            return roomService.joinByInviteCode(
                            request.inviteCode(), profile)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (RoomService.MemberRequiresApprovalException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<?> getMembers(
            @PathVariable String id,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        final TelegramProfile profile;

        try {
            profile = identityService.validateProfile(initData);
        } catch (IllegalArgumentException exception) {
            return unauthorized();
        }

        try {
            List<MemberView> members = roomService
                    .activeMembersForRoom(id, profile)
                    .stream()
                    .map(member -> new MemberView(
                            member.getId(),
                            member.getFirstName(),
                            member.getLastName(),
                            member.getUsername(),
                            member.getPhotoUrl(),
                            member.isHost()))
                    .toList();

            return ResponseEntity.ok(members);
        } catch (RoomService.RoomAccessException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/{id}/heartbeat")
    public ResponseEntity<?> heartbeat(
            @PathVariable String id,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        final TelegramProfile profile;

        try {
            profile = identityService.validateProfile(initData);
        } catch (IllegalArgumentException exception) {
            return unauthorized();
        }

        try {
            int count = roomService.heartbeat(id, profile);
            return ResponseEntity.ok(Map.of("listenerCount", count));
        } catch (RoomService.RoomAccessException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/{id}/leave")
    public ResponseEntity<?> leaveRoom(
            @PathVariable String id,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        final TelegramProfile profile;

        try {
            profile = identityService.validateProfile(initData);
        } catch (IllegalArgumentException exception) {
            return unauthorized();
        }

        try {
            int count = roomService.leaveRoom(id, profile);
            return ResponseEntity.ok(Map.of("listenerCount", count));
        } catch (RoomService.RoomAccessException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/{id}/members/{memberId}/kick")
    public ResponseEntity<?> kickMember(
            @PathVariable String id,
            @PathVariable String memberId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        final TelegramProfile profile;

        try {
            profile = identityService.validateProfile(initData);
        } catch (IllegalArgumentException exception) {
            return unauthorized();
        }

        try {
            int count = roomService.kickMember(id, memberId, profile);
            return ResponseEntity.ok(Map.of("listenerCount", count));
        } catch (RoomService.RoomAccessException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/{id}/members/{memberId}/approve-rejoin")
    public ResponseEntity<?> approveRejoin(
            @PathVariable String id,
            @PathVariable String memberId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        final TelegramProfile profile;

        try {
            profile = identityService.validateProfile(initData);
        } catch (IllegalArgumentException exception) {
            return unauthorized();
        }

        try {
            roomService.approveRejoin(id, memberId, profile);
            return ResponseEntity.ok(Map.of("approved", true));
        } catch (RoomService.RoomAccessException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of(
                        "error",
                        "Open Chamber Jams inside Telegram and try again."));
    }

    public record CreateRoomRequest(String name, String initData) {}

    public record JoinRoomRequest(String inviteCode, String initData) {}

    public record MemberView(
            String id,
            String firstName,
            String lastName,
            String username,
            String photoUrl,
            boolean host
    ) {}

    public record PublicRoomCard(
        String id,
        String name,
        boolean playing
    ) {}
}

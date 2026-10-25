
package com.chamberjams.backend.room;

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
import com.chamberjams.backend.music.AudiusSearchController.MusicTrack;
import com.chamberjams.backend.room.RoomPlaybackService.PlaybackSnapshot;

@RestController
@RequestMapping("/api/rooms/{roomId}/playback")
public class RoomPlaybackController {

    private final RoomPlaybackService playbackService;
    private final TelegramIdentityService identityService;
    private final RoomService roomService;

    public RoomPlaybackController(
            RoomPlaybackService playbackService,
            TelegramIdentityService identityService,
            RoomService roomService) {
        this.playbackService = playbackService;
        this.identityService = identityService;
        this.roomService = roomService;
    }

    private ResponseEntity<?> authorizationError(
            String roomId, String initData) {
        try {
            TelegramProfile profile =
                    identityService.validateProfile(initData);
            roomService.assertCurrentMember(roomId, profile);
            return null;
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of(
                            "error",
                            "Open Chamber Jams inside Telegram and try again."));
        } catch (RoomService.RoomAccessException exception) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<?> getPlayback(
            @PathVariable String roomId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        ResponseEntity<?> denied = authorizationError(roomId, initData);
        if (denied != null) return denied;

        return playbackService.getPlayback(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/queue")
    public ResponseEntity<?> queueTrack(
            @PathVariable String roomId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData,
            @RequestBody(required = false) MusicTrack track) {

        ResponseEntity<?> denied = authorizationError(roomId, initData);
        if (denied != null) return denied;

        try {
            return playbackService.queueTrack(roomId, track)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/skip")
    public ResponseEntity<?> skip(
            @PathVariable String roomId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData,
            @RequestBody(required = false) SkipRequest request) {

        ResponseEntity<?> denied = authorizationError(roomId, initData);
        if (denied != null) return denied;

        Long expectedRevision = request == null
                ? null
                : request.expectedRevision();

        try {
            return playbackService.skip(roomId, expectedRevision)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (RoomPlaybackService.StalePlaybackException exception) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    @PostMapping("/previous")
    public ResponseEntity<?> previous(
            @PathVariable String roomId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        ResponseEntity<?> denied = authorizationError(roomId, initData);
        if (denied != null) return denied;

        return playbackService.previous(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/pause")
    public ResponseEntity<?> pause(
            @PathVariable String roomId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        ResponseEntity<?> denied = authorizationError(roomId, initData);
        if (denied != null) return denied;

        return playbackService.pause(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/resume")
    public ResponseEntity<?> resume(
            @PathVariable String roomId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData) {

        ResponseEntity<?> denied = authorizationError(roomId, initData);
        if (denied != null) return denied;

        return playbackService.resume(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/select")
    public ResponseEntity<?> selectQueuedTrack(
            @PathVariable String roomId,
            @RequestHeader(
                    name = "X-Telegram-Init-Data",
                    required = false) String initData,
            @RequestBody(required = false) SelectTrackRequest request) {

        ResponseEntity<?> denied = authorizationError(roomId, initData);
        if (denied != null) return denied;

        if (request == null
                || request.trackId() == null
                || request.trackId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Track ID is required."));
        }

        try {
            return playbackService.selectQueuedTrack(
                            roomId, request.trackId())
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException exception) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", exception.getMessage()));
        }
    }

    public record SkipRequest(Long expectedRevision) {}

    public record SelectTrackRequest(String trackId) {}
}

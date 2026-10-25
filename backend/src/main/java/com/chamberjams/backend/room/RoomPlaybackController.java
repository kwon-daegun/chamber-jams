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

import com.chamberjams.backend.music.AudiusSearchController.MusicTrack;
import com.chamberjams.backend.room.RoomPlaybackService.PlaybackSnapshot;

@RestController
@RequestMapping("/api/rooms/{roomId}/playback")
public class RoomPlaybackController {

    private final RoomPlaybackService playbackService;

    public RoomPlaybackController(
            RoomPlaybackService playbackService) {
        this.playbackService = playbackService;
    }

    @GetMapping
    public ResponseEntity<?> getPlayback(
            @PathVariable String roomId) {

        return playbackService.getPlayback(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/queue")
    public ResponseEntity<?> queueTrack(
            @PathVariable String roomId,
            @RequestBody MusicTrack track) {

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
            @RequestBody(required = false) SkipRequest request) {

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
            @PathVariable String roomId) {

        return playbackService.previous(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/pause")
    public ResponseEntity<?> pause(
            @PathVariable String roomId) {

        return playbackService.pause(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/resume")
    public ResponseEntity<?> resume(
            @PathVariable String roomId) {

        return playbackService.resume(roomId)
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/select")
    public ResponseEntity<?> selectQueuedTrack(
            @PathVariable String roomId,
            @RequestBody SelectTrackRequest request) {

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
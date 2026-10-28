package com.chamberjams.backend.music;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/music")
public class YouTubeMusicController {

    private final YouTubeMusicService youtubeMusicService;

    public YouTubeMusicController(YouTubeMusicService youtubeMusicService) {
        this.youtubeMusicService = youtubeMusicService;
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(
            @RequestParam("q") String query,
            @RequestParam(value = "limit", defaultValue = "10") int limit) {

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Search query is required."));
        }

        try {
            List<MusicTrack> tracks = youtubeMusicService.searchTracks(query.trim(), limit);
            return ResponseEntity.ok(tracks);
        } catch (Exception exception) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Unable to search YouTube right now."));
        }
    }

    @GetMapping("/stream/{videoId}")
    public ResponseEntity<Resource> streamAudio(@PathVariable String videoId) {
        try {
            File audioFile = youtubeMusicService.getOrDownloadAudio(videoId);
            Resource resource = new FileSystemResource(audioFile);

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("audio/mp4"))
                    .cacheControl(CacheControl.maxAge(24, TimeUnit.HOURS).cachePublic())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .body(resource);
        } catch (IllegalArgumentException badId) {
            return ResponseEntity.badRequest().build();
        } catch (Exception exception) {
            return ResponseEntity.notFound().build();
        }
    }

    public record MusicTrack(
            String id,
            String title,
            String artist,
            int durationSeconds,
            String artworkUrl,
            String streamUrl,
            String sourceUrl) {}
}
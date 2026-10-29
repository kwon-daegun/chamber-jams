package com.chamberjams.backend.music;

import java.io.File;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
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
    public ResponseEntity<?> streamAudio(
            @PathVariable String videoId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader) {
        try {
            File audioFile = youtubeMusicService.getOrDownloadAudio(videoId);
            Resource resource = new FileSystemResource(audioFile);
            MediaType contentType = getAudioContentType(audioFile);

            if (rangeHeader != null && !rangeHeader.isBlank()) {
                List<HttpRange> ranges;
                try {
                    ranges = HttpRange.parseRanges(rangeHeader);
                } catch (IllegalArgumentException exception) {
                    return rangeNotSatisfiable(audioFile.length());
                }

                if (ranges.size() == 1) {
                    HttpRange range = ranges.get(0);
                    long start;
                    long end;
                    try {
                        start = range.getRangeStart(audioFile.length());
                        end = range.getRangeEnd(audioFile.length());
                    } catch (IllegalArgumentException exception) {
                        return rangeNotSatisfiable(audioFile.length());
                    }
                    if (start >= audioFile.length() || end < start) {
                        return rangeNotSatisfiable(audioFile.length());
                    }

                    long contentLength = end - start + 1;
                    Resource rangeResource = new AbstractResource() {
                        @Override
                        public InputStream getInputStream() throws IOException {
                            InputStream inputStream = Files.newInputStream(audioFile.toPath());
                            try {
                                inputStream.skipNBytes(start);
                            } catch (IOException exception) {
                                inputStream.close();
                                throw exception;
                            }
                            return new FilterInputStream(inputStream) {
                                private long remaining = contentLength;

                                @Override
                                public int read() throws IOException {
                                    if (remaining == 0) {
                                        return -1;
                                    }
                                    int value = in.read();
                                    if (value != -1) {
                                        remaining--;
                                    }
                                    return value;
                                }

                                @Override
                                public int read(byte[] buffer, int offset, int length)
                                        throws IOException {
                                    if (length == 0) {
                                        return 0;
                                    }
                                    if (remaining == 0) {
                                        return -1;
                                    }
                                    int bytesRead = in.read(
                                            buffer,
                                            offset,
                                            (int) Math.min(length, remaining));
                                    if (bytesRead > 0) {
                                        remaining -= bytesRead;
                                    }
                                    return bytesRead;
                                }
                            };
                        }

                        @Override
                        public long contentLength() {
                            return contentLength;
                        }

                        @Override
                        public String getDescription() {
                            return "Byte range from " + audioFile;
                        }
                    };
                    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                            .contentType(contentType)
                            .cacheControl(CacheControl.maxAge(24, TimeUnit.HOURS).cachePublic())
                            .contentLength(contentLength)
                            .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                            .header(
                                    HttpHeaders.CONTENT_RANGE,
                                    "bytes " + start + "-" + end + "/" + audioFile.length())
                            .body(rangeResource);
                }
            }

            return ResponseEntity.ok()
                    .contentType(contentType)
                    .cacheControl(CacheControl.maxAge(24, TimeUnit.HOURS).cachePublic())
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .contentLength(audioFile.length())
                    .body(resource);
        } catch (IllegalArgumentException badId) {
            return ResponseEntity.badRequest().build();
        } catch (Exception exception) {
            System.err.println("STREAM ERROR for " + videoId + ": " + exception.getMessage());
            exception.printStackTrace();
            return ResponseEntity.notFound().build();
        }
    }

    private MediaType getAudioContentType(File audioFile) {
        String name = audioFile.getName().toLowerCase();
        if (name.endsWith(".webm")) {
            return MediaType.parseMediaType("audio/webm");
        }
        if (name.endsWith(".opus")) {
            return MediaType.parseMediaType("audio/ogg");
        }
        if (name.endsWith(".mp3")) {
            return MediaType.parseMediaType("audio/mpeg");
        }
        return MediaType.parseMediaType("audio/mp4");
    }

    private ResponseEntity<Void> rangeNotSatisfiable(long fileLength) {
        return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_RANGE, "bytes */" + fileLength)
                .build();
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
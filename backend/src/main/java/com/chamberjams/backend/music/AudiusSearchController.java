package com.chamberjams.backend.music;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/music")
public class AudiusSearchController {

    private static final String API_ROOT =
            "https://api.audius.co/v1";

    private static final HttpClient HTTP_CLIENT =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build();

    private final ObjectMapper objectMapper;

    public AudiusSearchController(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @GetMapping("/search")
    public ResponseEntity<?> searchTracks(
            @RequestParam("q") String query,
            @RequestParam(defaultValue = "10") int limit) {

        if (query == null || query.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Enter a song name."));
        }

        int safeLimit = Math.max(1, Math.min(limit, 20));

        String encodedQuery = URLEncoder.encode(
                query.trim(), StandardCharsets.UTF_8);

        String requestUrl = API_ROOT
                + "/tracks/search?query=" + encodedQuery
                + "&limit=" + safeLimit
                + "&app_name=ChamberJams";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(requestUrl))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        try {
            HttpResponse<String> response = HTTP_CLIENT.send(
                    request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return ResponseEntity
                        .status(HttpStatus.BAD_GATEWAY)
                        .body(Map.of(
                                "error",
                                "Audius could not complete the search."));
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root.path("data");

            List<MusicTrack> tracks = new ArrayList<>();

            for (JsonNode item : data) {
                if (!item.path("is_streamable").asBoolean(false)) {
                    continue;
                }

                String id = item.path("id").asText("");

                if (id.isBlank()) {
                    continue;
                }

                String title = item.path("title")
                        .asText("Untitled");

                String artist = item.path("user")
                        .path("name")
                        .asText("Unknown artist");

                int durationSeconds = item.path("duration")
                        .asInt(0);

                String artworkUrl = item.path("artwork")
                        .path("150x150")
                        .asText(null);

                String permalink = item.path("permalink")
                        .asText("");

                String sourceUrl = permalink.isBlank()
                        ? null
                        : permalink.startsWith("http")
                                ? permalink
                                : "https://audius.co" + permalink;

                String streamUrl = API_ROOT + "/tracks/"
                        + URLEncoder.encode(
                                id, StandardCharsets.UTF_8)
                        + "/stream?app_name=ChamberJams";

                tracks.add(new MusicTrack(
                        id,
                        title,
                        artist,
                        durationSeconds,
                        artworkUrl,
                        streamUrl,
                        sourceUrl
                ));
            }

            return ResponseEntity.ok(tracks);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "The music search was interrupted."));

        } catch (Exception e) {
            return ResponseEntity
                    .status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of(
                            "error",
                            "Unable to contact the music service."));
        }
    }

    public record MusicTrack(
            String id,
            String title,
            String artist,
            int durationSeconds,
            String artworkUrl,
            String streamUrl,
            String sourceUrl
    ) {}
}

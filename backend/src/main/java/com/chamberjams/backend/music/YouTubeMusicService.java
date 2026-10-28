package com.chamberjams.backend.music;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.chamberjams.backend.music.YouTubeMusicController.MusicTrack;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class YouTubeMusicService {

    private final Path cacheDir = Paths.get("audio_cache").toAbsolutePath();
    private final ConcurrentHashMap<String, CompletableFuture<File>> activeDownloads = new ConcurrentHashMap<>();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public YouTubeMusicService() throws IOException {
        Files.createDirectories(cacheDir);
    }

    public List<MusicTrack> searchTracks(String query, int limit) throws IOException, InterruptedException {
        int safeLimit = Math.max(1, Math.min(limit, 15));

        List<MusicTrack> fastResults = searchViaInnerTube(query, safeLimit);
        if (!fastResults.isEmpty()) {
            return fastResults;
        }

        return searchViaYtDlp(query, safeLimit);
    }

    private List<MusicTrack> searchViaInnerTube(String query, int limit) {
        List<MusicTrack> results = new ArrayList<>();
        try {
            Map<String, Object> payload = Map.of(
                "context", Map.of(
                    "client", Map.of(
                        "clientName", "WEB",
                        "clientVersion", "2.20250101.00.00",
                        "hl", "en",
                        "gl", "US"
                    )
                ),
                "query", query
            );

            String requestBody = objectMapper.writeValueAsString(payload);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://www.youtube.com/youtubei/v1/search?prettyPrint=false"))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return results;
            }

            JsonNode root = objectMapper.readTree(response.body());
            List<JsonNode> videoRenderers = root.findValues("videoRenderer");

            for (JsonNode video : videoRenderers) {
                if (results.size() >= limit) break;

                String videoId = video.path("videoId").asText("");
                if (!videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
                    continue;
                }

                String title = "Unknown Title";
                JsonNode titleRuns = video.path("title").path("runs");
                if (titleRuns.isArray() && !titleRuns.isEmpty()) {
                    title = titleRuns.get(0).path("text").asText("Unknown Title");
                }

                String artist = "YouTube Artist";
                JsonNode ownerRuns = video.path("ownerText").path("runs");
                if (ownerRuns.isArray() && !ownerRuns.isEmpty()) {
                    artist = ownerRuns.get(0).path("text").asText("YouTube Artist");
                }

                String lengthText = video.path("lengthText").path("simpleText").asText("");
                if (lengthText.isEmpty()) {
                    if (!video.findValues("BADGE_STYLE_TYPE_LIVE_NOW").isEmpty()) {
                        continue;
                    }
                }
                int durationSeconds = parseColonDuration(lengthText);
                if (durationSeconds <= 0) {
                    durationSeconds = 210; 
                }
                if (durationSeconds > 3600) {
                    continue;
                }

                String artworkUrl = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
                String streamUrl = "/api/music/stream/" + videoId;
                String sourceUrl = "https://www.youtube.com/watch?v=" + videoId;

                results.add(new MusicTrack(
                        videoId,
                        title,
                        artist,
                        durationSeconds,
                        artworkUrl,
                        streamUrl,
                        sourceUrl
                ));
            }
        } catch (Exception e) {
            System.err.println("InnerTube search warning: " + e.getMessage());
        }
        return results;
    }

    private int parseColonDuration(String lengthText) {
        if (lengthText == null || lengthText.isBlank()) return 0;
        String[] parts = lengthText.trim().split(":");
        try {
            if (parts.length == 2) {
                return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
            } else if (parts.length == 3) {
                return Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]);
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }

    private List<MusicTrack> searchViaYtDlp(String query, int limit) throws IOException, InterruptedException {
        List<MusicTrack> results = new ArrayList<>();
        String searchSpec = "ytsearch" + limit + ":" + query;

        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                searchSpec,
                "--flat-playlist",
                "--extractor-args", "youtube:player_client=android,web",
                "--print", "%(id)s\t%(duration)s\t%(channel,uploader)s\t%(title)s",
                "--no-warnings"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 4);
                if (parts.length < 4) continue;

                String videoId = parts[0].trim();
                if (!videoId.matches("^[a-zA-Z0-9_-]{11}$")) continue;

                int durationSeconds = 210;
                try {
                    durationSeconds = (int) Math.round(Double.parseDouble(parts[1].trim()));
                } catch (NumberFormatException ignored) {}
                if (durationSeconds <= 0) durationSeconds = 210;

                String artist = parts[2].trim();
                if (artist.isEmpty() || "NA".equalsIgnoreCase(artist)) artist = "YouTube Artist";

                String title = parts[3].trim();
                if (title.isEmpty() || "NA".equalsIgnoreCase(title)) title = "Unknown Title";

                results.add(new MusicTrack(
                        videoId,
                        title,
                        artist,
                        durationSeconds,
                        "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg",
                        "/api/music/stream/" + videoId,
                        "https://www.youtube.com/watch?v=" + videoId
                ));
            }
        }
        process.waitFor(20, TimeUnit.SECONDS);
        return results;
    }

    public void prewarmAudioAsync(String videoId) {
        if (videoId == null || !videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                getOrDownloadAudio(videoId);
            } catch (Exception e) {
                System.err.println("Prewarm failed for " + videoId + ": " + e.getMessage());
            }
        });
    }

    public File getOrDownloadAudio(String videoId) throws Exception {
        if (videoId == null || !videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
            throw new IllegalArgumentException("Invalid YouTube video ID.");
        }

        File cachedFile = cacheDir.resolve(videoId + ".m4a").toFile();
        if (cachedFile.exists() && cachedFile.length() > 0) {
            return cachedFile;
        }

        CompletableFuture<File> future = activeDownloads.computeIfAbsent(videoId, id ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return downloadWithYtDlp(id);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to extract audio for " + id, e);
                    } finally {
                        activeDownloads.remove(id);
                    }
                })
        );

        return future.get(60, TimeUnit.SECONDS);
    }

    private File downloadWithYtDlp(String videoId) throws IOException, InterruptedException {
        File targetFile = cacheDir.resolve(videoId + ".m4a").toFile();
        String outputTemplate = cacheDir.resolve(videoId + ".%(ext)s").toString();
        String url = "https://www.youtube.com/watch?v=" + videoId;

        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                "-f", "bestaudio[ext=m4a]/140/bestaudio",
                "--extractor-args", "youtube:player_client=android,ios,web",
                "--no-playlist",
                "--geo-bypass",
                "--no-warnings",
                "-o", outputTemplate,
                url
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();
        StringBuilder outputLog = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputLog.append(line).append("\n");
            }
        }

        boolean finished = process.waitFor(55, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0 || !targetFile.exists()) {
            System.err.println("yt-dlp error output for " + videoId + ":\n" + outputLog);
            throw new IOException("yt-dlp extraction failed for videoId: " + videoId);
        }
        return targetFile;
    }

    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void cleanupOldCacheFiles() {
        File[] files = cacheDir.toFile().listFiles((dir, name) -> name.endsWith(".m4a") || name.endsWith(".part"));
        if (files == null) return;

        long cutoff = System.currentTimeMillis() - (30 * 60 * 1000L); // 30 minutes
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }
}
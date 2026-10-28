package com.chamberjams.backend.music;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(12))
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
                        "gl", "IN"
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
        if (cachedFile.exists() && cachedFile.length() > 1024) {
            return cachedFile;
        }

        CompletableFuture<File> future = activeDownloads.computeIfAbsent(videoId, id ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return downloadAudioWithFallbacks(id);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to extract audio for " + id, e);
                    } finally {
                        activeDownloads.remove(id);
                    }
                })
        );

        return future.get(90, TimeUnit.SECONDS);
    }

    private File downloadAudioWithFallbacks(String videoId) throws Exception {
        File targetFile = cacheDir.resolve(videoId + ".m4a").toFile();
        String outputTemplate = cacheDir.resolve(videoId + ".%(ext)s").toString();
        String url = "https://www.youtube.com/watch?v=" + videoId;

        String[] clientProfiles = {
            "youtube:player_client=tv,ios,mweb",
            "youtube:player_client=android_vr,android,web"
        };

        for (String profile : clientProfiles) {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "-f", "140/bestaudio[ext=m4a]/bestaudio",
                    "-x",
                    "--audio-format", "m4a",
                    "--extractor-args", profile,
                    "--no-playlist",
                    "--geo-bypass",
                    "--no-check-certificates",
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

            boolean finished = process.waitFor(50, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            if (targetFile.exists() && targetFile.length() > 1024) {
                System.out.println("yt-dlp success (" + profile + ") for " + videoId);
                return targetFile;
            }
            System.err.println("yt-dlp attempt (" + profile + ") failed for " + videoId + ":\n" + outputLog);
        }

        // Fallback to Piped / Invidious APIs if YouTube blocks datacenter IP
        File fallback = downloadViaPublicApis(videoId, targetFile);
        if (fallback != null) {
            return fallback;
        }

        throw new IOException("All audio extraction methods failed for videoId: " + videoId);
    }

    private File downloadViaPublicApis(String videoId, File targetFile) {
        String[] pipedApis = {
            "https://pipedapi.kavin.rocks/streams/" + videoId,
            "https://pipedapi.tokhmi.xyz/streams/" + videoId,
            "https://api.piped.private.coffee/streams/" + videoId
        };

        for (String apiUrl : pipedApis) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(apiUrl))
                        .header("User-Agent", "Mozilla/5.0")
                        .timeout(Duration.ofSeconds(10))
                        .GET()
                        .build();

                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(res.body());
                    JsonNode audioStreams = root.path("audioStreams");
                    if (audioStreams.isArray() && !audioStreams.isEmpty()) {
                        String streamUrl = audioStreams.get(0).path("url").asText("");
                        if (!streamUrl.isEmpty()) {
                            if (downloadDirectStream(streamUrl, targetFile)) {
                                System.out.println("Downloaded via Piped API (" + apiUrl + ") for " + videoId);
                                return targetFile;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Public API fallback (" + apiUrl + ") error: " + e.getMessage());
            }
        }
        return null;
    }

    private boolean downloadDirectStream(String streamUrl, File targetFile) {
        try {
            HttpRequest audioReq = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(40))
                    .GET()
                    .build();

            HttpResponse<InputStream> audioRes = httpClient.send(audioReq, HttpResponse.BodyHandlers.ofInputStream());
            if (audioRes.statusCode() == 200 || audioRes.statusCode() == 206) {
                try (InputStream in = audioRes.body()) {
                    Files.copy(in, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                return targetFile.exists() && targetFile.length() > 1024;
            }
        } catch (Exception e) {
            System.err.println("Direct stream download error: " + e.getMessage());
        }
        return false;
    }

    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void cleanupOldCacheFiles() {
        File[] files = cacheDir.toFile().listFiles((dir, name) -> name.endsWith(".m4a") || name.endsWith(".part") || name.endsWith(".webm") || name.endsWith(".opus"));
        if (files == null) return;

        long cutoff = System.currentTimeMillis() - (30 * 60 * 1000L);
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }
}
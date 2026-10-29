package com.chamberjams.backend.music;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
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
    private final ConcurrentHashMap<String, String> videoTitleCache = new ConcurrentHashMap<>();
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

                videoTitleCache.put(videoId, title + " " + artist);

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

                videoTitleCache.put(videoId, title + " " + artist);

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

        File cachedFile = findCachedFile(videoId);
        if (cachedFile != null) {
            return ensureBrowserCompatibleAudio(cachedFile, videoId);
        }

        CompletableFuture<File> future = activeDownloads.computeIfAbsent(videoId, id ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return downloadAudioGuaranteed(id);
                    } catch (Exception e) {
                        throw new RuntimeException("Failed to extract audio for " + id, e);
                    } finally {
                        activeDownloads.remove(id);
                    }
                })
        );

        File downloadedFile = future.get(90, TimeUnit.SECONDS);
        return ensureBrowserCompatibleAudio(downloadedFile, videoId);
    }

    private File findCachedFile(String videoId) {
        String[] extensions = {".mp3", ".m4a", ".webm", ".opus", ".mp4"};
        for (String ext : extensions) {
            File file = cacheDir.resolve(videoId + ext).toFile();
            if (file.exists()) {
                if (file.length() > 25 * 1024) {
                    return file;
                } else {
                    file.delete();
                }
            }
        }
        return null;
    }

    private File ensureBrowserCompatibleAudio(File audioFile, String videoId) throws Exception {
        if (audioFile.getName().toLowerCase().endsWith(".mp3")) {
            return audioFile;
        }

        Path normalizedPath = cacheDir.resolve(videoId + ".normalized.mp3");
        Path outputPath = cacheDir.resolve(videoId + ".mp3");
        Files.deleteIfExists(normalizedPath);

        Process process = new ProcessBuilder(
                "ffmpeg",
                "-nostdin",
                "-y",
                "-i", audioFile.getAbsolutePath(),
                "-map", "0:a:0",
                "-vn",
                "-codec:a", "libmp3lame",
                "-q:a", "4",
                "-f", "mp3",
                normalizedPath.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();

        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IOException("Timed out converting audio for " + videoId);
        }
        if (process.exitValue() != 0
                || !Files.exists(normalizedPath)
                || Files.size(normalizedPath) <= 25 * 1024) {
            Files.deleteIfExists(normalizedPath);
            throw new IOException("Unable to convert audio to a browser-compatible format for " + videoId);
        }

        Files.move(normalizedPath, outputPath, StandardCopyOption.REPLACE_EXISTING);
        return outputPath.toFile();
    }

    private File downloadAudioGuaranteed(String videoId) throws Exception {
        String searchTitle = resolveTitleForVideo(videoId);
        if (searchTitle != null && !searchTitle.isBlank()) {
            File saavnFile = downloadViaJioSaavn(videoId, searchTitle);
            if (saavnFile != null) {
                return saavnFile;
            }
        }

        File invidiousFile = downloadViaInvidiousProxy(videoId);
        if (invidiousFile != null) {
            return invidiousFile;
        }

        String outputTemplate = cacheDir.resolve(videoId + ".%(ext)s").toString();
        String url = "https://www.youtube.com/watch?v=" + videoId;
        String[] clientProfiles = {
            "youtube:player_client=android_vr",
            "youtube:player_client=tv,ios"
        };

        for (String profile : clientProfiles) {
            ProcessBuilder pb = new ProcessBuilder(
                    "yt-dlp",
                    "-f", "140/251/bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio",
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

            boolean finished = process.waitFor(25, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
            }

            File downloaded = findCachedFile(videoId);
            if (downloaded != null) {
                System.out.println("yt-dlp YouTube success (" + profile + ") for " + videoId);
                return downloaded;
            }
            System.err.println("yt-dlp (" + profile + ") log for " + videoId + ": " + outputLog);
        }

        if (searchTitle != null && !searchTitle.isBlank()) {
            ProcessBuilder scPb = new ProcessBuilder(
                    "yt-dlp",
                    "scsearch1:" + searchTitle,
                    "-f", "bestaudio[ext=m4a]/bestaudio[ext=mp3]/bestaudio",
                    "--no-playlist",
                    "--no-warnings",
                    "-o", outputTemplate
            );
            scPb.redirectErrorStream(true);
            Process scProc = scPb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(scProc.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) {}
            }
            scProc.waitFor(25, TimeUnit.SECONDS);
            File scDownloaded = findCachedFile(videoId);
            if (scDownloaded != null) {
                System.out.println("SoundCloud fallback success for " + videoId);
                return scDownloaded;
            }
        }

        throw new IOException("All audio extraction methods failed for videoId: " + videoId);
    }

    private String resolveTitleForVideo(String videoId) {
        String cached = videoTitleCache.get(videoId);
        if (cached != null && !cached.isBlank()) {
            return cleanSearchTitle(cached);
        }
        try {
            String oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=" + videoId + "&format=json";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(oembedUrl))
                    .header("User-Agent", "Mozilla/5.0")
                    .timeout(Duration.ofSeconds(6))
                    .GET()
                    .build();
            HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(res.body());
                String title = node.path("title").asText("");
                if (!title.isBlank()) {
                    videoTitleCache.put(videoId, title);
                    return cleanSearchTitle(title);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String cleanSearchTitle(String rawTitle) {
        return rawTitle
                .replaceAll("(?i)\\b(lyrical|video|official|full song|audio|hd|4k|jhankar|with lyrics|lyrics)\\b", " ")
                .replaceAll("[\\(\\)\\[\\]\\|\\-\\+\\:\\\"']", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private File downloadViaJioSaavn(String videoId, String cleanTitle) {
        String[] saavnApis = {
            "https://saavn.dev/api/search/songs?query=",
            "https://jiosaavn-api-privatecvc2.vercel.app/search/songs?query="
        };

        for (String apiBase : saavnApis) {
            try {
                String url = apiBase + URLEncoder.encode(cleanTitle, StandardCharsets.UTF_8);
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "Mozilla/5.0")
                        .timeout(Duration.ofSeconds(8))
                        .GET()
                        .build();

                HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(res.body());
                    List<JsonNode> downloadUrls = root.findValues("downloadUrl");
                    if (!downloadUrls.isEmpty()) {
                        JsonNode firstTrackUrls = downloadUrls.get(0);
                        String streamUrl = "";
                        if (firstTrackUrls.isArray() && !firstTrackUrls.isEmpty()) {
                            JsonNode best = firstTrackUrls.get(firstTrackUrls.size() - 1);
                            streamUrl = best.path("url").asText(best.path("link").asText(""));
                        }
                        if (!streamUrl.isEmpty()) {
                            File target = cacheDir.resolve(videoId + ".m4a").toFile();
                            if (downloadDirectStream(streamUrl, target)) {
                                System.out.println("Downloaded via JioSaavn mirror for " + videoId + " (" + cleanTitle + ")");
                                return target;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Saavn fallback (" + apiBase + ") notice: " + e.getMessage());
            }
        }
        return null;
    }

    private File downloadViaInvidiousProxy(String videoId) {
        String[] instances = {
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de",
            "https://inv.tux.pizza"
        };

        for (String instance : instances) {
            try {
                String proxyStreamUrl = instance + "/latest_version?id=" + videoId + "&itag=140&local=true";
                File target = cacheDir.resolve(videoId + ".m4a").toFile();
                if (downloadDirectStream(proxyStreamUrl, target)) {
                    System.out.println("Downloaded via Invidious proxy (" + instance + ") for " + videoId);
                    return target;
                }
            } catch (Exception e) {
                System.err.println("Invidious proxy (" + instance + ") error: " + e.getMessage());
            }
        }
        return null;
    }

    private boolean downloadDirectStream(String streamUrl, File targetFile) {
        try {
            HttpRequest audioReq = HttpRequest.newBuilder()
                    .uri(URI.create(streamUrl))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .timeout(Duration.ofSeconds(25))
                    .GET()
                    .build();

            HttpResponse<InputStream> audioRes = httpClient.send(audioReq, HttpResponse.BodyHandlers.ofInputStream());
            if (audioRes.statusCode() == 200 || audioRes.statusCode() == 206) {
                String contentType = audioRes.headers().firstValue("content-type").orElse("").toLowerCase();
                if (contentType.contains("text/html") || contentType.contains("application/json")) {
                    return false; // Reject HTML error pages disguised as 200 OK
                }
                try (InputStream in = audioRes.body()) {
                    Files.copy(in, targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                if (targetFile.exists() && targetFile.length() > 25 * 1024) {
                    return true;
                } else {
                    targetFile.delete();
                }
            }
        } catch (Exception e) {
            System.err.println("Direct stream download error: " + e.getMessage());
        }
        return false;
    }

    @Scheduled(fixedRate = 15 * 60 * 1000)
    public void cleanupOldCacheFiles() {
        File[] files = cacheDir.toFile().listFiles();
        if (files == null) return;

        long cutoff = System.currentTimeMillis() - (30 * 60 * 1000L);
        for (File file : files) {
            if (file.isFile() && file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }
}
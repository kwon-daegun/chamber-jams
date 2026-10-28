package com.chamberjams.backend.music;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;

import com.chamberjams.backend.music.YouTubeMusicController.MusicTrack;

@Service
public class YouTubeMusicService {

    private final Path cacheDir = Paths.get("audio_cache").toAbsolutePath();
    private final ConcurrentHashMap<String, CompletableFuture<File>> activeDownloads = new ConcurrentHashMap<>();

    public YouTubeMusicService() throws IOException {
        Files.createDirectories(cacheDir);
    }

    public List<MusicTrack> searchTracks(String query, int limit) throws IOException, InterruptedException {
        int safeLimit = Math.max(1, Math.min(limit, 15));
        List<MusicTrack> results = new ArrayList<>();
        String searchSpec = "ytsearch" + safeLimit + ":" + query;

        ProcessBuilder pb = new ProcessBuilder(
                "yt-dlp",
                searchSpec,
                "--flat-playlist",
                "--print", "%(id)s\t%(duration)s\t%(channel,uploader)s\t%(title)s",
                "--no-warnings",
                "--quiet"
        );

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\t", 4);
                if (parts.length < 4) {
                    continue;
                }

                String videoId = parts[0].trim();
                if (videoId.isEmpty() || !videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
                    continue;
                }

                int durationSeconds = parseDurationSeconds(parts[1].trim());
                // Skip livestreams (0s) or mixes longer than 15 minutes
                if (durationSeconds <= 0 || durationSeconds > 900) {
                    continue;
                }

                String artist = parts[2].trim();
                if (artist.isEmpty() || "NA".equalsIgnoreCase(artist)) {
                    artist = "YouTube Artist";
                }

                String title = parts[3].trim();
                if (title.isEmpty() || "NA".equalsIgnoreCase(title)) {
                    title = "Unknown Title";
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
        }

        process.waitFor(15, TimeUnit.SECONDS);
        return results;
    }

    private int parseDurationSeconds(String raw) {
        try {
            return (int) Math.round(Double.parseDouble(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public void prewarmAudioAsync(String videoId) {
        if (videoId == null || !videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                getOrDownloadAudio(videoId);
            } catch (Exception ignored) {
            }
        });
    }

    public File getOrDownloadAudio(String videoId) throws Exception {
        if (videoId == null || !videoId.matches("^[a-zA-Z0-9_-]{11}$")) {
            throw new IllegalArgumentException("Invalid YouTube video ID.");
        }

        cleanupOldCacheFiles();
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
                "--no-playlist",
                "--geo-bypass",
                "--quiet",
                "--no-warnings",
                "-o", outputTemplate,
                url
        );

        Process process = pb.start();
        boolean finished = process.waitFor(45, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0 || !targetFile.exists()) {
            throw new IOException("yt-dlp extraction failed for videoId: " + videoId);
        }
        return targetFile;
    }

    @Scheduled(fixedRate = 30 * 60 * 1000)
    public void cleanupOldCacheFiles() {
        File[] files = cacheDir.toFile().listFiles((dir, name) -> name.endsWith(".m4a") || name.endsWith(".part"));
        if (files == null) return;

        long cutoff = System.currentTimeMillis() - (2 * 60 * 60 * 1000L); // 2 hours
        for (File file : files) {
            if (file.lastModified() < cutoff) {
                file.delete();
            }
        }
    }
}
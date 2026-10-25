
package com.chamberjams.backend.room;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chamberjams.backend.music.AudiusSearchController.MusicTrack;

import tools.jackson.databind.ObjectMapper;

@Service
public class RoomPlaybackService {

    private final RoomRepository roomRepository;
    private final RoomPlaybackRepository playbackRepository;
    private final ObjectMapper objectMapper;

    public RoomPlaybackService(
            RoomRepository roomRepository,
            RoomPlaybackRepository playbackRepository,
            ObjectMapper objectMapper) {
        this.roomRepository = roomRepository;
        this.playbackRepository = playbackRepository;
        this.objectMapper = objectMapper;
    }

    public record StoredPlayback(
            MusicTrack currentTrack,
            MusicTrack previousTrack,
            List<MusicTrack> queue,
            boolean isPlaying,
            double positionSeconds) {

        public StoredPlayback {
            queue = queue == null ? List.of() : List.copyOf(queue);
            positionSeconds = Math.max(0, positionSeconds);
        }
    }

    public record PlaybackSnapshot(
            MusicTrack currentTrack,
            MusicTrack previousTrack,
            List<MusicTrack> queue,
            boolean isPlaying,
            double positionSeconds,
            long revision,
            Instant updatedAt) {
    }

    public static class StalePlaybackException
            extends RuntimeException {
        public StalePlaybackException(String message) {
            super(message);
        }
    }

    @Transactional(readOnly = true)
    public Optional<PlaybackSnapshot> getPlayback(String roomId) {
        if (!roomRepository.existsById(roomId)) {
            return Optional.empty();
        }

        Optional<RoomPlaybackState> saved =
                playbackRepository.findById(roomId);

        if (saved.isEmpty()) {
            return Optional.of(new PlaybackSnapshot(
                    null, null, List.of(), false, 0, 0, Instant.now()));
        }

        return Optional.of(snapshot(saved.get(), Instant.now()));
    }

    @Transactional
    public Optional<PlaybackSnapshot> queueTrack(
            String roomId, MusicTrack track) {

        if (!roomRepository.existsById(roomId)) {
            return Optional.empty();
        }

        validateTrack(track);

        RoomPlaybackState row = lockOrCreate(roomId);
        Instant now = Instant.now();
        StoredPlayback state = normalized(row, now);

        if (state.currentTrack() == null) {
            StoredPlayback next = new StoredPlayback(
                    track,
                    state.previousTrack(),
                    state.queue(),
                    true,
                    0);

            return Optional.of(saveState(row, next, now));
        }

        boolean alreadyQueued =
                state.currentTrack().id().equals(track.id())
                || state.queue().stream()
                        .anyMatch(item -> item.id().equals(track.id()));

        if (alreadyQueued) {
            return Optional.of(snapshot(row, now));
        }

        List<MusicTrack> nextQueue =
                new ArrayList<>(state.queue());
        nextQueue.add(track);

        StoredPlayback next = new StoredPlayback(
                state.currentTrack(),
                state.previousTrack(),
                nextQueue,
                state.isPlaying(),
                state.positionSeconds());

        return Optional.of(saveState(row, next, now));
    }

    @Transactional
    public Optional<PlaybackSnapshot> skip(
            String roomId, Long expectedRevision) {

        if (!roomRepository.existsById(roomId)) {
            return Optional.empty();
        }

        RoomPlaybackState row = lockOrCreate(roomId);

        if (expectedRevision != null
                && expectedRevision >= 0
                && row.getRevision() != expectedRevision) {
            throw new StalePlaybackException(
                    "Room playback changed. Refresh and try again.");
        }

        Instant now = Instant.now();
        StoredPlayback state = normalized(row, now);

        MusicTrack rememberedTrack =
                state.currentTrack() != null
                        ? state.currentTrack()
                        : state.previousTrack();

        MusicTrack nextTrack = state.queue().isEmpty()
                ? null
                : state.queue().get(0);

        List<MusicTrack> remainingQueue =
                state.queue().isEmpty()
                        ? List.of()
                        : state.queue().subList(
                                1, state.queue().size());

        StoredPlayback next = new StoredPlayback(
                nextTrack,
                rememberedTrack,
                remainingQueue,
                nextTrack != null,
                0);

        return Optional.of(saveState(row, next, now));
    }

    @Transactional
    public Optional<PlaybackSnapshot> previous(String roomId) {
        if (!roomRepository.existsById(roomId)) {
            return Optional.empty();
        }

        RoomPlaybackState row = lockOrCreate(roomId);
        Instant now = Instant.now();
        StoredPlayback state = normalized(row, now);

        if (state.previousTrack() == null) {
            return Optional.of(snapshot(row, now));
        }

        List<MusicTrack> nextQueue =
                new ArrayList<>(state.queue());

        if (state.currentTrack() != null
                && !state.currentTrack().id()
                        .equals(state.previousTrack().id())) {
            nextQueue.add(0, state.currentTrack());
        }

        StoredPlayback next = new StoredPlayback(
                state.previousTrack(),
                null,
                nextQueue,
                true,
                0);

        return Optional.of(saveState(row, next, now));
    }

    @Transactional
    public Optional<PlaybackSnapshot> pause(String roomId) {
        return setPlaying(roomId, false);
    }

    @Transactional
    public Optional<PlaybackSnapshot> resume(String roomId) {
        return setPlaying(roomId, true);
    }

    private Optional<PlaybackSnapshot> setPlaying(
            String roomId, boolean playing) {

        if (!roomRepository.existsById(roomId)) {
            return Optional.empty();
        }

        RoomPlaybackState row = lockOrCreate(roomId);
        Instant now = Instant.now();
        StoredPlayback state = normalized(row, now);

        if (state.currentTrack() == null
                || state.isPlaying() == playing) {
            return Optional.of(snapshot(row, now));
        }

        StoredPlayback next = new StoredPlayback(
                state.currentTrack(),
                state.previousTrack(),
                state.queue(),
                playing,
                state.positionSeconds());

        return Optional.of(saveState(row, next, now));
    }

    @Transactional
    public Optional<PlaybackSnapshot> selectQueuedTrack(
            String roomId, String trackId) {

        if (!roomRepository.existsById(roomId)) {
            return Optional.empty();
        }

        RoomPlaybackState row = lockOrCreate(roomId);
        Instant now = Instant.now();
        StoredPlayback state = normalized(row, now);

        int selectedIndex = -1;

        for (int i = 0; i < state.queue().size(); i++) {
            if (state.queue().get(i).id().equals(trackId)) {
                selectedIndex = i;
                break;
            }
        }

        if (selectedIndex < 0) {
            throw new IllegalArgumentException(
                    "That track is no longer in the queue.");
        }

        MusicTrack selected = state.queue().get(selectedIndex);

        List<MusicTrack> remainingQueue = new ArrayList<>(
                state.queue().subList(
                        selectedIndex + 1, state.queue().size()));

        StoredPlayback next = new StoredPlayback(
                selected,
                state.currentTrack(),
                remainingQueue,
                true,
                0);

        return Optional.of(saveState(row, next, now));
    }

    private RoomPlaybackState lockOrCreate(String roomId) {
        Optional<RoomPlaybackState> existing =
                playbackRepository.findByRoomIdForUpdate(roomId);

        if (existing.isPresent()) {
            return existing.get();
        }

        Instant now = Instant.now();

        RoomPlaybackState created = new RoomPlaybackState(
                roomId,
                encode(new StoredPlayback(
                        null, null, List.of(), false, 0)),
                0,
                now);

        return playbackRepository.saveAndFlush(created);
    }

    private PlaybackSnapshot saveState(
            RoomPlaybackState row,
            StoredPlayback state,
            Instant now) {

        row.setStateJson(encode(state));
        row.setRevision(row.getRevision() + 1);
        row.setUpdatedAt(now);

        playbackRepository.save(row);

        return snapshot(row, now);
    }

    private PlaybackSnapshot snapshot(
            RoomPlaybackState row, Instant now) {

        StoredPlayback state = normalized(row, now);

        return new PlaybackSnapshot(
                state.currentTrack(),
                state.previousTrack(),
                state.queue(),
                state.isPlaying(),
                state.positionSeconds(),
                row.getRevision(),
                row.getUpdatedAt());
    }

    private StoredPlayback normalized(
            RoomPlaybackState row, Instant now) {

        StoredPlayback state = decode(row.getStateJson());
        double position = state.positionSeconds();

        if (state.isPlaying()
                && state.currentTrack() != null
                && row.getUpdatedAt() != null) {

            double elapsed = Math.max(
                    0,
                    Duration.between(row.getUpdatedAt(), now)
                            .toMillis() / 1000.0);

            position += elapsed;
        }

        if (state.currentTrack() != null
                && state.currentTrack().durationSeconds() > 0) {
            position = Math.min(
                    position,
                    state.currentTrack().durationSeconds());
        }

        return new StoredPlayback(
                state.currentTrack(),
                state.previousTrack(),
                state.queue(),
                state.isPlaying(),
                position);
    }

    private void validateTrack(MusicTrack track) {
        if (track == null
                || track.id() == null
                || track.id().isBlank()
                || track.title() == null
                || track.artist() == null
                || track.streamUrl() == null
                || !track.streamUrl().startsWith(
                        "https://api.audius.co/v1/tracks/")) {
            throw new IllegalArgumentException(
                    "A valid Audius track is required.");
        }
    }

    private StoredPlayback decode(String json) {
        try {
            return objectMapper.readValue(
                    json, StoredPlayback.class);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to read room playback state.",
                    exception);
        }
    }

    private String encode(StoredPlayback state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Unable to save room playback state.",
                    exception);
        }
    }
}

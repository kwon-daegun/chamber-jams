package com.chamberjams.backend.room;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "room_playback")
public class RoomPlaybackState {

    @Id
    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    @Column(name = "state_json", nullable = false, columnDefinition = "TEXT")
    private String stateJson;

    @Column(nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoomPlaybackState() {
    }

    public RoomPlaybackState(
            String roomId,
            String stateJson,
            long revision,
            Instant updatedAt) {
        this.roomId = roomId;
        this.stateJson = stateJson;
        this.revision = revision;
        this.updatedAt = updatedAt;
    }

    public String getRoomId() {
        return roomId;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public long getRevision() {
        return revision;
    }

    public void setRevision(long revision) {
        this.revision = revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
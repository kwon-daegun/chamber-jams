
package com.chamberjams.backend.room;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
    name = "room_members",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_room_member_telegram",
        columnNames = {"room_id", "telegram_id"}
    )
)
public class RoomMember {

    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "room_id", nullable = false, length = 36)
    private String roomId;

    @Column(name = "telegram_id", nullable = false, length = 32)
    private String telegramId;

    @Column(name = "first_name", nullable = false, length = 128)
    private String firstName;

    @Column(name = "last_name", length = 128)
    private String lastName;

    @Column(length = 64)
    private String username;

    @Column(name = "photo_url", length = 2048)
    private String photoUrl;

    @Column(name = "is_host", nullable = false)
    private boolean host;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "is_kicked", nullable = false)
    private boolean kicked;

    @Column(name = "rejoin_approved", nullable = false)
    private boolean rejoinApproved;

    protected RoomMember() {
    }

    public RoomMember(
            String id,
            String roomId,
            String telegramId,
            String firstName,
            String lastName,
            String username,
            String photoUrl,
            boolean host,
            Instant joinedAt) {
        this.id = id;
        this.roomId = roomId;
        this.telegramId = telegramId;
        this.firstName = firstName;
        this.lastName = lastName;
        this.username = username;
        this.photoUrl = photoUrl;
        this.host = host;
        this.joinedAt = joinedAt;
        this.lastSeenAt = joinedAt;
        this.kicked = false;
        this.rejoinApproved = false;
    }

    public String getId() { return id; }
    public String getRoomId() { return roomId; }
    public String getTelegramId() { return telegramId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public String getUsername() { return username; }
    public String getPhotoUrl() { return photoUrl; }
    public boolean isHost() { return host; }
    public Instant getJoinedAt() { return joinedAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public boolean isKicked() { return kicked; }
    public boolean isRejoinApproved() { return rejoinApproved; }

    public void updateProfile(
            String firstName,
            String lastName,
            String username,
            String photoUrl) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.username = username;
        this.photoUrl = photoUrl;
    }

    public void markSeen(Instant at) {
        this.lastSeenAt = at;
    }

    public void markLeft() {
        this.lastSeenAt = Instant.EPOCH;
    }

    public boolean isActiveAt(Instant cutoff) {
        return !kicked
                && lastSeenAt != null
                && !lastSeenAt.isBefore(cutoff);
    }

    public void kick() {
        this.kicked = true;
        this.rejoinApproved = false;
        this.lastSeenAt = Instant.EPOCH;
    }

    public void approveRejoin() {
        if (kicked) {
            this.rejoinApproved = true;
        }
    }

    public void restoreAfterApproval(Instant now) {
        if (kicked && rejoinApproved) {
            this.kicked = false;
            this.rejoinApproved = false;
            this.lastSeenAt = now;
        }
    }
}

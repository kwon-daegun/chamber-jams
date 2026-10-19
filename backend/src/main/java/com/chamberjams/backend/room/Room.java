package com.chamberjams.backend.room;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "rooms")
public class Room {

    @Id
    @Column(nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "invite_code", nullable = false, unique = true, length = 8)
    private String inviteCode;

    @Column(nullable = false, length = 60)
    private String name;

    @Column(name = "host_name", nullable = false, length = 40)
    private String hostName;

    @Column(name = "listener_count", nullable = false)
    private int listenerCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Room() {
        // Required by JPA
    }

    public Room(
            String id,
            String inviteCode,
            String name,
            String hostName,
            int listenerCount,
            Instant createdAt) {
        this.id = id;
        this.inviteCode = inviteCode;
        this.name = name;
        this.hostName = hostName;
        this.listenerCount = listenerCount;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public String getName() {
        return name;
    }

    public String getHostName() {
        return hostName;
    }

    public int getListenerCount() {
        return listenerCount;
    }

    public void setListenerCount(int listenerCount) {
        this.listenerCount = listenerCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

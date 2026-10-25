
package com.chamberjams.backend.room;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chamberjams.backend.auth.TelegramAuthController.TelegramProfile;
import com.chamberjams.backend.room.RoomService.RoomAccessException;

@Service
public class RoomService {

    private static final Duration PRESENCE_TIMEOUT =
            Duration.ofSeconds(45);

    private final RoomRepository roomRepository;
    private final RoomMemberRepository memberRepository;

    public RoomService(
            RoomRepository roomRepository,
            RoomMemberRepository memberRepository) {
        this.roomRepository = roomRepository;
        this.memberRepository = memberRepository;
    }

    public static class RoomAccessException extends RuntimeException {
        public RoomAccessException(String message) {
            super(message);
        }
    }

    public static class MemberRequiresApprovalException
            extends RuntimeException {
        public MemberRequiresApprovalException(String message) {
            super(message);
        }
    }

    @Transactional
    public Room createRoom(String name, TelegramProfile profile) {
        Instant now = Instant.now();

        Room room = new Room(
                UUID.randomUUID().toString(),
                generateInviteCode(),
                name.trim(),
                profile.firstName(),
                1,
                now);

        roomRepository.save(room);

        memberRepository.save(new RoomMember(
                UUID.randomUUID().toString(),
                room.getId(),
                profile.telegramId(),
                profile.firstName(),
                profile.lastName(),
                profile.username(),
                profile.photoUrl(),
                true,
                now));

        updateListenerCount(room.getId(), now);

        return roomRepository.findById(room.getId()).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<Room> findById(String id) {
        return roomRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public Optional<Room> findByInviteCode(String inviteCode) {
        if (inviteCode == null || inviteCode.isBlank()) {
            return Optional.empty();
        }

        String normalized =
                inviteCode.trim().toUpperCase(Locale.ROOT);

        return roomRepository.findByInviteCodeIgnoreCase(normalized);
    }

    @Transactional
    public Optional<Room> joinByInviteCode(
            String inviteCode,
            TelegramProfile profile) {

        Optional<Room> found = findByInviteCode(inviteCode);

        if (found.isEmpty()) {
            return Optional.empty();
        }

        Room room = found.get();
        Instant now = Instant.now();

        Optional<RoomMember> existing =
                memberRepository.findByRoomIdAndTelegramId(
                        room.getId(), profile.telegramId());

        if (existing.isPresent()) {
            RoomMember member = existing.get();

            if (member.isKicked() && !member.isRejoinApproved()) {
                throw new MemberRequiresApprovalException(
                        "The room host must approve your return.");
            }

            member.restoreAfterApproval(now);
            member.updateProfile(
                    profile.firstName(),
                    profile.lastName(),
                    profile.username(),
                    profile.photoUrl());
            member.markSeen(now);

            memberRepository.save(member);
        } else {
            memberRepository.save(new RoomMember(
                    UUID.randomUUID().toString(),
                    room.getId(),
                    profile.telegramId(),
                    profile.firstName(),
                    profile.lastName(),
                    profile.username(),
                    profile.photoUrl(),
                    false,
                    now));
        }

        updateListenerCount(room.getId(), now);

        return roomRepository.findById(room.getId());
    }

    @Transactional(readOnly = true)
    public List<RoomMember> activeMembersForRoom(
            String roomId, TelegramProfile profile) {

        requireCurrentMember(roomId, profile);

        Instant cutoff = Instant.now().minus(PRESENCE_TIMEOUT);

        return memberRepository.findByRoomIdOrderByJoinedAtAsc(roomId)
                .stream()
                .filter(member -> member.isActiveAt(cutoff))
                .toList();
    }

    @Transactional
    public int heartbeat(
            String roomId, TelegramProfile profile) {

        RoomMember member = requireCurrentMember(roomId, profile);
        Instant now = Instant.now();

        member.updateProfile(
                profile.firstName(),
                profile.lastName(),
                profile.username(),
                profile.photoUrl());
        member.markSeen(now);

        memberRepository.save(member);
        return updateListenerCount(roomId, now);
    }

    @Transactional
    public int leaveRoom(String roomId, TelegramProfile profile) {
        RoomMember member = requireCurrentMember(roomId, profile);
        member.markLeft();
        memberRepository.save(member);

        return updateListenerCount(roomId, Instant.now());
    }

    @Transactional
    public int kickMember(
            String roomId,
            String targetMemberId,
            TelegramProfile hostProfile) {

        RoomMember host = requireHost(roomId, hostProfile);

        RoomMember target = memberRepository
                .findByIdAndRoomId(targetMemberId, roomId)
                .orElseThrow(() -> new RoomAccessException(
                        "That member is not in this room."));

        if (host.getId().equals(target.getId())) {
            throw new RoomAccessException(
                    "The host cannot kick themselves.");
        }

        target.kick();
        memberRepository.save(target);

        return updateListenerCount(roomId, Instant.now());
    }

    @Transactional
    public void approveRejoin(
            String roomId,
            String targetMemberId,
            TelegramProfile hostProfile) {

        requireHost(roomId, hostProfile);

        RoomMember target = memberRepository
                .findByIdAndRoomId(targetMemberId, roomId)
                .orElseThrow(() -> new RoomAccessException(
                        "That member was not found."));

        target.approveRejoin();
        memberRepository.save(target);
    }
    @Transactional(readOnly = true)
    public List<Room> findActiveRooms() {
        Instant cutoff = Instant.now().minus(PRESENCE_TIMEOUT);

        return roomRepository.findAll().stream()
                .filter(room -> memberRepository
                        .findByRoomIdOrderByJoinedAtAsc(room.getId())
                        .stream()
                        .anyMatch(member -> member.isActiveAt(cutoff)))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean hasActiveMembers(String roomId) {
        Instant cutoff = Instant.now().minus(PRESENCE_TIMEOUT);

        return memberRepository.findByRoomIdOrderByJoinedAtAsc(roomId)
                .stream()
                .anyMatch(member -> member.isActiveAt(cutoff));
    }

    private RoomMember requireCurrentMember(
            String roomId, TelegramProfile profile) {

        if (!roomRepository.existsById(roomId)) {
            throw new RoomAccessException("Room not found.");
        }

        RoomMember member = memberRepository
                .findByRoomIdAndTelegramId(
                        roomId, profile.telegramId())
                .orElseThrow(() -> new RoomAccessException(
                        "Join this room before accessing its members."));

        if (member.isKicked()) {
            throw new RoomAccessException(
                    "You cannot access this room until the host approves your return.");
        }

        return member;
    }

    private RoomMember requireHost(
            String roomId, TelegramProfile profile) {

        RoomMember host = requireCurrentMember(roomId, profile);

        if (!host.isHost()) {
            throw new RoomAccessException(
                    "Only the room host can do this.");
        }

        return host;
    }

    private int updateListenerCount(String roomId, Instant now) {
        Room room = roomRepository.findById(roomId).orElseThrow();

        Instant cutoff = now.minus(PRESENCE_TIMEOUT);

        long activeCount = memberRepository
                .findByRoomIdOrderByJoinedAtAsc(roomId)
                .stream()
                .filter(member -> member.isActiveAt(cutoff))
                .count();

        room.setListenerCount((int) activeCount);
        roomRepository.save(room);

        return (int) activeCount;
    }

    private static String generateInviteCode() {
        return UUID.randomUUID()
                .toString()
                .replace("-", "")
                .substring(0, 8)
                .toUpperCase(Locale.ROOT);
    }
}

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type SyntheticEvent,
} from "react";
import "./AudiusMusicPlayer.css";

interface RoomInfo {
  id: string;
  name: string;
  hostName: string;
  listenerCount: number;
  inviteCode: string;
}

interface MusicTrack {
  id: string;
  title: string;
  artist: string;
  durationSeconds: number;
  artworkUrl: string | null;
  streamUrl: string;
  sourceUrl: string | null;
}

interface PlaybackSnapshot {
  currentTrack: MusicTrack | null;
  previousTrack: MusicTrack | null;
  queue: MusicTrack[];
  isPlaying: boolean;
  positionSeconds: number;
  revision: number;
  updatedAt: string;
}

interface RoomMemberProfile {
  id: string;
  firstName: string;
  lastName: string | null;
  username: string | null;
  photoUrl: string | null;
  host: boolean;
}

interface Props {
  room: RoomInfo;
  onInviteClick: () => void;
  onLeaveRoom: () => void;
}

const API_BASE = (
  import.meta.env.VITE_API_BASE_URL || "http://localhost:8080"
).replace(/\/$/, "");

function formatDuration(seconds: number): string {
  if (!Number.isFinite(seconds) || seconds < 0) return "0:00";

  const minutes = Math.floor(seconds / 60);
  const remaining = Math.floor(seconds % 60);

  return `${minutes}:${String(remaining).padStart(2, "0")}`;
}

export default function AudiusMusicPlayer({
  room,
  onInviteClick,
  onLeaveRoom,
}: Props) {
  const audioRef = useRef<HTMLAudioElement>(null);

  const [query, setQuery] = useState("");
  const [searchResults, setSearchResults] = useState<MusicTrack[]>([]);
  const [queue, setQueue] = useState<MusicTrack[]>([]);
  const [activeTrack, setActiveTrack] = useState<MusicTrack | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [queueNotice, setQueueNotice] = useState("");
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentTime, setCurrentTime] = useState(0);
  const [duration, setDuration] = useState(0);
  const [previousTrack, setPreviousTrack] = useState<MusicTrack | null>(null);

  const [revision, setRevision] = useState(0);
  const [roomMembers, setRoomMembers] = useState<RoomMemberProfile[]>([]);
  const [membersError, setMembersError] = useState("");

  const telegramInitData = window.Telegram?.WebApp?.initData ?? "";

  useEffect(() => {
    let cancelled = false;

    async function refreshMembers() {
      if (!telegramInitData) {
        setRoomMembers([]);
        setMembersError(
          "Open Chamber Jams inside Telegram to see room members.",
        );
        return;
      }

      try {
        const headers = {
          "X-Telegram-Init-Data": telegramInitData,
        };

        const heartbeatResponse = await fetch(
          `${API_BASE}/api/rooms/${encodeURIComponent(room.id)}/heartbeat`,
          {
            method: "POST",
            headers,
          },
        );

        if (!heartbeatResponse.ok) {
          throw new Error(
            heartbeatResponse.status === 403
              ? "You are not an active member of this room."
              : "Unable to refresh room membership.",
          );
        }

        const response = await fetch(
          `${API_BASE}/api/rooms/${encodeURIComponent(room.id)}/members`,
          { headers },
        );

        if (!response.ok) {
          throw new Error(
            response.status === 403
              ? "Join this room to see its members."
              : "Unable to load room members.",
          );
        }

        const members = (await response.json()) as RoomMemberProfile[];

        if (!cancelled) {
          setRoomMembers(members);
          setMembersError("");
        }
      } catch (err) {
        if (!cancelled) {
          setMembersError(
            err instanceof Error ? err.message : "Unable to load room members.",
          );
        }
      }
    }

    void refreshMembers();

    // Refresh presence before the backend's 45-second timeout.
    const timer = window.setInterval(() => {
      void refreshMembers();
    }, 15_000);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [room.id, telegramInitData]);

  const loadedTrackIdRef = useRef<string | null>(null);
  const autoplayBlockedRef = useRef(false);

  const applyPlaybackSnapshot = useCallback((snapshot: PlaybackSnapshot) => {
    const audio = audioRef.current;
    const current = snapshot.currentTrack;

    setActiveTrack(current);
    setPreviousTrack(snapshot.previousTrack ?? null);
    setQueue(snapshot.queue ?? []);
    setRevision(snapshot.revision);
    setIsPlaying(snapshot.isPlaying);

    if (!audio) return;

    if (!current) {
      audio.pause();
      audio.removeAttribute("src");
      audio.load();
      loadedTrackIdRef.current = null;
      setCurrentTime(0);
      setDuration(0);
      return;
    }

    const trackChanged = loadedTrackIdRef.current !== current.id;

    if (trackChanged) {
      audio.pause();
      audio.src = current.streamUrl;
      loadedTrackIdRef.current = current.id;
      setCurrentTime(snapshot.positionSeconds);
      setDuration(current.durationSeconds);

      audio.addEventListener(
        "loadedmetadata",
        () => {
          if (loadedTrackIdRef.current !== current.id) return;

          if (Number.isFinite(audio.duration)) {
            setDuration(audio.duration);
          }

          try {
            audio.currentTime = Math.min(
              snapshot.positionSeconds,
              Number.isFinite(audio.duration)
                ? audio.duration
                : snapshot.positionSeconds,
            );
          } catch {
            // Metadata may not be ready for seeking yet.
          }

          if (snapshot.isPlaying) {
            audio
              .play()
              .then(() => {
                autoplayBlockedRef.current = false;
              })
              .catch(() => {
                autoplayBlockedRef.current = true;
                setIsPlaying(false);
                setError("Tap Play to enable audio in this browser.");
              });
          }
        },
        { once: true },
      );

      audio.load();
      return;
    }

    // Correct drift without restarting the song on every poll.
    if (audio.readyState >= 1) {
      const drift = Math.abs(audio.currentTime - snapshot.positionSeconds);

      if (drift > 2.5 && Number.isFinite(snapshot.positionSeconds)) {
        try {
          audio.currentTime = snapshot.positionSeconds;
        } catch {
          // Wait for the browser to permit seeking.
        }
      }
    }

    if (snapshot.isPlaying && audio.paused) {
      if (!autoplayBlockedRef.current) {
        audio.play().catch(() => {
          autoplayBlockedRef.current = true;
          setIsPlaying(false);
          setError("Tap Play to enable audio in this browser.");
        });
      }
    } else if (!snapshot.isPlaying && !audio.paused) {
      audio.pause();
      autoplayBlockedRef.current = false;
    }
  }, []);

  const refreshPlayback = useCallback(async () => {
    const response = await fetch(
      `${API_BASE}/api/rooms/${encodeURIComponent(room.id)}/playback`,
    );

    if (!response.ok) {
      throw new Error("Unable to retrieve room playback.");
    }

    const snapshot = (await response.json()) as PlaybackSnapshot;
    applyPlaybackSnapshot(snapshot);
  }, [room.id, applyPlaybackSnapshot]);

  useEffect(() => {
    let cancelled = false;

    const sync = async () => {
      try {
        const response = await fetch(
          `${API_BASE}/api/rooms/${encodeURIComponent(room.id)}/playback`,
        );

        if (!response.ok) return;

        const snapshot = (await response.json()) as PlaybackSnapshot;

        if (!cancelled) {
          applyPlaybackSnapshot(snapshot);
        }
      } catch {
        // Keep the player usable and retry on the next polling cycle.
      }
    };

    void sync();

    const timer = window.setInterval(() => {
      void sync();
    }, 1500);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [room.id, applyPlaybackSnapshot]);

  async function updateSharedPlayback(
    path: string,
    body?: unknown,
  ): Promise<boolean> {
    try {
      setError("");

      const options: RequestInit = {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
        },
      };

      if (body !== undefined) {
        options.body = JSON.stringify(body);
      }

      const response = await fetch(
        `${API_BASE}/api/rooms/${encodeURIComponent(room.id)}/playback${path}`,
        options,
      );

      // Another listener may have changed the state first.
      if (response.status === 409) {
        await refreshPlayback();
        return false;
      }

      const result = await response.json();

      if (!response.ok) {
        throw new Error(result.error || "Unable to update room playback.");
      }

      applyPlaybackSnapshot(result as PlaybackSnapshot);
      return true;
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Unable to update room playback.",
      );

      return false;
    }
  }

  async function searchTracks(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();

    if (!query.trim()) {
      setError("Enter a song or artist name.");
      return;
    }

    setLoading(true);
    setError("");
    setQueueNotice("");
    setSearchResults([]);

    try {
      const params = new URLSearchParams({
        q: query.trim(),
        limit: "10",
      });

      const response = await fetch(
        `${API_BASE}/api/music/search?${params.toString()}`,
      );

      const data: unknown = await response.json();

      if (!response.ok) {
        throw new Error("The music service couldn't complete the search.");
      }

      if (!Array.isArray(data)) {
        throw new Error("The music service returned an unexpected response.");
      }

      const results = data as MusicTrack[];
      setSearchResults(results);

      if (results.length === 0) {
        setError("No streamable results found. Try another search.");
      }
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Unable to search for music. Check your connection.",
      );
    } finally {
      setLoading(false);
    }
  }

  async function addToQueue(track: MusicTrack) {
    setError("");
    setQueueNotice("");

    if (
      activeTrack?.id === track.id ||
      queue.some((item) => item.id === track.id)
    ) {
      setQueueNotice("That song is already playing or queued.");
      return;
    }

    const success = await updateSharedPlayback("/queue", track);

    if (success) {
      setQueueNotice(`Added "${track.title}" to Up next.`);
    }
  }

  async function playQueuedTrack(track: MusicTrack) {
    await updateSharedPlayback("/select", {
      trackId: track.id,
    });
  }

  function playNextTrack() {
    void updateSharedPlayback("/skip", {
      expectedRevision: revision,
    });
  }

  async function togglePlayback() {
    if (!activeTrack) return;

    const path = isPlaying ? "/pause" : "/resume";
    const audio = audioRef.current;

    // Respond immediately to the local user's click.
    if (isPlaying) {
      audio?.pause();
    } else if (audio) {
      autoplayBlockedRef.current = false;

      void audio.play().catch(() => {
        setError("Playback could not start. Try pressing Play again.");
      });
    }

    await updateSharedPlayback(path);
  }

  function seekTo(value: number) {
    const audio = audioRef.current;
    if (!audio || !Number.isFinite(value)) return;

    audio.currentTime = value;
    setCurrentTime(value);
  }

  function handleMetadata() {
    const audio = audioRef.current;
    if (audio && Number.isFinite(audio.duration)) {
      setDuration(audio.duration);
    }
  }

  const listenerCount = Math.max(0, room.listenerCount);

  return (
    <section className="music-dashboard">
      <header className="music-dashboard-hero">
        <p className="eyebrow">MADE FOR CHATTING CHAMBER</p>
        <h1>
          Good music.
          <br />
          <span>Better together.</span>
        </h1>
        <p>For the songs you send at 2 AM, and the friends who just get it.</p>
      </header>

      <div className="music-dashboard-grid">
        <article className="lobby-card dashboard-main-card">
          <div className="dashboard-room-heading">
            <div className="dashboard-room-copy">
              <p className="eyebrow">● YOUR LISTENING ROOM</p>
              <h2>{room.name}</h2>
              <p className="muted">
                {queue.length === 0
                  ? "No plans, just one more song."
                  : `${queue.length} song${queue.length === 1 ? "" : "s"} in your queue.`}
              </p>
            </div>

            <button
              className="dashboard-invite-button"
              type="button"
              onClick={onInviteClick}
            >
              ↗ Invite friends
            </button>
          </div>

          <div
            className={`dream-artwork ${
              activeTrack?.artworkUrl ? "dream-artwork-playing" : ""
            }`}
            style={
              activeTrack?.artworkUrl
                ? {
                    backgroundImage: `linear-gradient(
            180deg,
            rgba(20, 18, 22, 0.08),
            rgba(20, 18, 22, 0.48)
          ), url("${activeTrack.artworkUrl}")`,
                  }
                : undefined
            }
            role="img"
            aria-label={
              activeTrack
                ? `Artwork for ${activeTrack.title}`
                : "Abstract purple night sky artwork"
            }
          >
            {!activeTrack?.artworkUrl && (
              <>
                <span className="dream-orbit dream-orbit-one" />
                <span className="dream-orbit dream-orbit-two" />
                <span className="dream-star dream-star-one">✳</span>
                <span className="dream-moon">☾</span>
                <span className="dream-star dream-star-two">✦</span>
              </>
            )}
            <p>A SOUNDTRACK FOR US</p>
          </div>

          <div className="dashboard-track-info">
            <div>
              <p className="eyebrow">ON REPEAT WITH EVERYONE</p>
              <h3>{activeTrack?.title ?? "Your next favourite song"}</h3>
              <p className="muted">
                {activeTrack?.artist ?? "Search for a song to get started."}
              </p>
            </div>

            <button
              className="dashboard-heart-button"
              type="button"
              aria-label="Invite friends to listen"
              onClick={onInviteClick}
            >
              ♡
            </button>
          </div>

          <div className="dashboard-progress">
            <input
              type="range"
              min="0"
              max={Math.max(duration, 1)}
              step="0.1"
              value={Math.min(currentTime, duration || 0)}
              onChange={(event) => seekTo(Number(event.target.value))}
              disabled={!activeTrack || duration <= 0}
              aria-label="Track progress"
            />

            <div className="dashboard-time-labels">
              <span>{formatDuration(currentTime)}</span>
              <span>{formatDuration(duration)}</span>
            </div>
          </div>

          <div className="dashboard-playback-buttons">
            <button
              type="button"
              className="dashboard-icon-button"
              onClick={() => {
                void updateSharedPlayback("/previous");
              }}
              disabled={!previousTrack}
              aria-label="Play previous track"
              title="Previous track"
            >
              ↶
            </button>

            <button
              type="button"
              className="dashboard-main-play"
              onClick={togglePlayback}
              disabled={!activeTrack}
              aria-label={isPlaying ? "Pause" : "Play"}
            >
              {isPlaying ? "Ⅱ" : "▶"}
            </button>

            <button
              type="button"
              className="dashboard-icon-button"
              onClick={playNextTrack}
              disabled={queue.length === 0}
            >
              ↷
            </button>
          </div>

          <audio
            ref={audioRef}
            className="dashboard-audio"
            preload="metadata"
            onLoadedMetadata={handleMetadata}
            onTimeUpdate={() => {
              if (audioRef.current) {
                setCurrentTime(audioRef.current.currentTime);
              }
            }}
            onPlay={() => setIsPlaying(true)}
            onPause={() => setIsPlaying(false)}
            onEnded={playNextTrack}
          />

          <button
            className="dashboard-leave-button"
            type="button"
            onClick={onLeaveRoom}
          >
            ← Back to rooms
          </button>
        </article>

        <aside className="dashboard-sidebar">
          <article className="lobby-card dashboard-people-card">
            <div className="dashboard-panel-heading">
              <div>
                <p className="eyebrow">YOUR FAVOURITE PEOPLE</p>
                <h2>
                  In the room{" "}
                  <span className="dashboard-count">{listenerCount}</span>
                </h2>
              </div>
              <span className="dashboard-note-icon" aria-hidden="true">
                ♫
              </span>
            </div>

            <div className="dashboard-member-list">
              {roomMembers.length > 0 ? (
                roomMembers.map((member) => {
                  const displayName = [member.firstName, member.lastName]
                    .filter(Boolean)
                    .join(" ");

                  return (
                    <div className="dashboard-member-row" key={member.id}>
                      {member.photoUrl ? (
                        <img
                          className="dashboard-member-avatar"
                          src={member.photoUrl}
                          alt=""
                          loading="lazy"
                        />
                      ) : (
                        <div className="dashboard-member-avatar">
                          {member.firstName.charAt(0).toUpperCase() || "?"}
                        </div>
                      )}

                      <div className="dashboard-member-info">
                        <strong>{displayName}</strong>
                        <span>
                          {member.username
                            ? `@${member.username}`
                            : member.host
                              ? "Room host"
                              : "Listening together"}
                        </span>
                      </div>

                      {member.host && (
                        <span className="dashboard-host-tag">HOST</span>
                      )}
                    </div>
                  );
                })
              ) : (
                <p className="dashboard-empty-queue">
                  {membersError || "Loading room members…"}
                </p>
              )}
            </div>

            <p className="dashboard-people-summary">
              {roomMembers.length === 1
                ? "You're here for the music, staying for the people."
                : `${roomMembers.length} members in this room. More friends, more memories.`}
            </p>
          </article>

          <article className="lobby-card dashboard-queue-card">
            <div className="dashboard-panel-heading">
              <div>
                <p className="eyebrow">A LITTLE BIT OF EVERYONE</p>
                <h2>Up next</h2>
              </div>
              <span className="dashboard-queue-count">
                {queue.length} song{queue.length === 1 ? "" : "s"}
              </span>
            </div>

            {queue.length > 0 ? (
              <div className="dashboard-queue-list">
                {queue.map((track, index) => (
                  <button
                    type="button"
                    className="dashboard-queue-item"
                    key={track.id}
                    onClick={() => playQueuedTrack(track)}
                  >
                    {track.artworkUrl ? (
                      <img src={track.artworkUrl} alt="" loading="lazy" />
                    ) : (
                      <span className="dashboard-queue-placeholder">♫</span>
                    )}

                    <span className="dashboard-queue-track">
                      <strong>{track.title}</strong>
                      <small>{track.artist}</small>
                    </span>

                    <span className="dashboard-queue-duration">
                      {formatDuration(track.durationSeconds)}
                    </span>

                    <span className="dashboard-queue-index">{index + 1}</span>
                  </button>
                ))}
              </div>
            ) : (
              <p className="dashboard-empty-queue">
                Ready for the next song ✨ Search below and add it to Up next.
              </p>
            )}

            <form className="dashboard-search-form" onSubmit={searchTracks}>
              <input
                type="search"
                value={query}
                onChange={(event) => {
                  const value = event.target.value;
                  setQuery(value);

                  if (!value.trim()) {
                    setSearchResults([]);
                    setError("");
                    setQueueNotice("");
                  }
                }}
                placeholder={'Try "Sunsetz" or an artist...'}
                aria-label="Search songs or artists"
              />

              <button
                type="submit"
                disabled={loading}
                aria-label="Search music"
              >
                {loading ? "…" : "+"}
              </button>
            </form>

            {error && (
              <p className="dashboard-music-message" role="alert">
                {error}
              </p>
            )}

            {queueNotice && (
              <p className="dashboard-queue-notice" role="status">
                {queueNotice}
              </p>
            )}

            {searchResults.length > 0 && (
              <div className="dashboard-search-results">
                <p className="eyebrow">SEARCH RESULTS</p>

                {searchResults.map((track) => (
                  <div className="dashboard-search-result" key={track.id}>
                    {track.artworkUrl ? (
                      <img src={track.artworkUrl} alt="" loading="lazy" />
                    ) : (
                      <span className="dashboard-queue-placeholder">♫</span>
                    )}

                    <span className="dashboard-queue-track">
                      <strong>{track.title}</strong>
                      <small>{track.artist}</small>
                    </span>

                    <button
                      type="button"
                      className="dashboard-add-button"
                      onClick={() => addToQueue(track)}
                      aria-label={`Add ${track.title} to Up next`}
                    >
                      +
                    </button>
                  </div>
                ))}
              </div>
            )}

            <p className="dashboard-ready-message">
              <span /> Ready for the next song ✨
            </p>
          </article>

          <p className="dashboard-sidebar-footer">NO ADS. JUST VIBES.</p>
        </aside>
      </div>
    </section>
  );
}

import { useEffect, useState, type SyntheticEvent } from "react";
import AudiusMusicPlayer from "./AudiusMusicPlayer";
import "./App.css";

const API_BASE = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

type Room = {
  id: string;
  inviteCode: string;
  name: string;
  hostName: string;
  listenerCount: number;
  createdAt: string;
};

type TelegramProfile = {
  telegramId: string;
  firstName: string;
  lastName: string | null;
  username: string | null;
  photoUrl: string | null;
  languageCode: string | null;
};

type PublicRoom = {
  id: string;
  name: string;
  playing: boolean;
};

function App() {
  const [room, setRoom] = useState<Room | null>(null);
  const [roomName, setRoomName] = useState("the after-hours room");

  const initialTelegramInitData = window.Telegram?.WebApp?.initData ?? "";

  const [telegramProfile, setTelegramProfile] =
    useState<TelegramProfile | null>(null);

  const [telegramAuthStatus, setTelegramAuthStatus] = useState<
    "checking" | "verified" | "preview" | "failed"
  >(initialTelegramInitData ? "checking" : "preview");

  const [telegramAuthMessage, setTelegramAuthMessage] = useState(
    initialTelegramInitData
      ? "Checking Telegram identity..."
      : "Preview mode. Open Chamber Jams inside Telegram to verify your identity.",
  );

  useEffect(() => {
    const webApp = window.Telegram?.WebApp;

    webApp?.ready();
    webApp?.expand();

    if (!webApp?.initData) {
      return;
    }

    let cancelled = false;

    async function authenticateTelegramUser() {
      try {
        const response = await fetch(`${API_BASE}/api/auth/telegram/validate`, {
          method: "POST",
          headers: {
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            initData: webApp!.initData,
          }),
        });

        if (!response.ok) {
          throw new Error(
            response.status === 401
              ? "Telegram authentication failed. Close and reopen the Mini App."
              : `Authentication request failed (${response.status}).`,
          );
        }

        const profile: TelegramProfile = await response.json();

        if (cancelled) return;

        setTelegramProfile(profile);
        setTelegramAuthStatus("verified");
        setTelegramAuthMessage(
          `Telegram identity verified for ${profile.firstName}.`,
        );

        // Convenient defaults for the existing forms.
      } catch (error) {
        if (cancelled) return;

        setTelegramAuthStatus("failed");
        setTelegramAuthMessage(
          error instanceof Error
            ? error.message
            : "Unable to verify your Telegram identity.",
        );
      }
    }

    void authenticateTelegramUser();

    return () => {
      cancelled = true;
    };
  }, []);

  const [inviteCode, setInviteCode] = useState("");

  const [creatingRoom, setCreatingRoom] = useState(false);
  const [joiningRoom, setJoiningRoom] = useState(false);

  const [activeRooms, setActiveRooms] = useState<PublicRoom[]>([]);
  const [roomsLoading, setRoomsLoading] = useState(true);
  const [roomsDirectoryError, setRoomsDirectoryError] = useState("");

  useEffect(() => {
    let cancelled = false;

    async function loadActiveRooms() {
      try {
        const response = await fetch(`${API_BASE}/api/rooms/active`);

        if (!response.ok) {
          throw new Error("Unable to load listening rooms.");
        }

        const rooms = (await response.json()) as PublicRoom[];

        if (!cancelled) {
          setActiveRooms(rooms);
          setRoomsDirectoryError("");
        }
      } catch {
        if (!cancelled) {
          setRoomsDirectoryError(
            "Couldn't load rooms. Check the backend connection.",
          );
        }
      } finally {
        if (!cancelled) {
          setRoomsLoading(false);
        }
      }
    }

    void loadActiveRooms();

    const timer = window.setInterval(() => {
      void loadActiveRooms();
    }, 15000);

    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, []);

  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  async function readError(response: Response) {
    const body = await response.text();

    if (!body) {
      return `Request failed (${response.status}).`;
    }

    try {
      const parsed = JSON.parse(body);
      return parsed.error ?? `Request failed (${response.status}).`;
    } catch {
      return body;
    }
  }

  // This is a temporary flag to allow testing the app in a browser without Telegram.
  const isDevPreview =
    import.meta.env.DEV && import.meta.env.VITE_DEV_PREVIEW === "true";

  async function createRoom(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setNotice("");

    // In dev preview mode, we create a mock room without calling the backend.
    if (isDevPreview) {
      const mockRoom: Room = {
        id: `preview-${Date.now()}`,
        inviteCode: "PREVIEW01",
        name: roomName.trim(),
        hostName: "Preview Host",
        listenerCount: 1,
        createdAt: new Date().toISOString(),
      };

      setRoom(mockRoom);
      setNotice(
        "Preview room created locally. It is not saved to the backend.",
      );
      return;
    }
    // ----- //

    const initData = window.Telegram?.WebApp?.initData ?? "";

    if (!roomName.trim()) {
      setError("Enter a room name.");
      return;
    }

    if (telegramAuthStatus !== "verified" || !initData) {
      setError("Open Chamber Jams inside Telegram to create a room.");
      return;
    }

    setCreatingRoom(true);

    try {
      const response = await fetch(`${API_BASE}/api/rooms`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: roomName.trim(),
          initData,
        }),
      });

      if (!response.ok) throw new Error(await readError(response));

      const createdRoom: Room = await response.json();
      setRoom(createdRoom);
      setNotice("Your room is ready. Invite your friends!");
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Couldn't connect to Chamber Jams. Is the backend running?",
      );
    } finally {
      setCreatingRoom(false);
    }
  }

  async function joinRoom(event: SyntheticEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setNotice("");

    const initData = window.Telegram?.WebApp?.initData ?? "";
    const normalizedInviteCode = extractInviteCode(inviteCode);

    if (!normalizedInviteCode) {
      setError("Enter a valid invitation code or a supported invite link.");
      return;
    }

    if (telegramAuthStatus !== "verified" || !initData) {
      setError("Open Chamber Jams inside Telegram to join a room.");
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await fetch(`${API_BASE}/api/rooms/join`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          inviteCode: normalizedInviteCode,
          initData,
        }),
      });

      if (!response.ok) {
        throw new Error(
          response.status === 404
            ? "Room not found. Check the invitation code or ask your friend to create a new room."
            : await readError(response),
        );
      }

      const joinedRoom: Room = await response.json();
      setRoom(joinedRoom);
      setNotice(`You joined "${joinedRoom.name}". Welcome in!`);
    } catch (err) {
      setError(
        err instanceof Error
          ? err.message
          : "Couldn't connect to Chamber Jams. Is the backend running?",
      );
    } finally {
      setJoiningRoom(false);
    }
  }

  async function joinListedRoom(roomId: string) {
    setError("");
    setNotice("");

    const initData = window.Telegram?.WebApp?.initData ?? "";

    if (telegramAuthStatus !== "verified" || !initData) {
      setError("Open Chamber Jams inside Telegram to join a room.");
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await fetch(
        `${API_BASE}/api/rooms/${encodeURIComponent(roomId)}/join`,
        {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ initData }),
        },
      );

      if (!response.ok) {
        throw new Error(await readError(response));
      }

      const joinedRoom: Room = await response.json();
      setRoom(joinedRoom);
      setNotice(`You joined "${joinedRoom.name}". Welcome in!`);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Couldn't join this room.");
    } finally {
      setJoiningRoom(false);
    }
  }

  async function copyInviteCode() {
    if (!room) return;

    try {
      await navigator.clipboard.writeText(room.inviteCode);
      setNotice("Invitation code copied. Send it to your group chat!");
    } catch {
      setNotice(
        `Share this invitation code with your friends: ${room.inviteCode}`,
      );
    }
  }

  function leaveRoom() {
    setRoom(null);
    setError("");
    setNotice("");
  }

  return (
    <main className="app-shell">
      <header className="topbar">
        <a className="brand" href="#" aria-label="Chamber Jams home">
          <span className="brand-mark">♫</span>
          <span>
            Chamber <span className="brand-accent">Jams</span>
          </span>
        </a>

        <div className="topbar-note">
          <span className="online-dot" />
          made for Chatting Chamber
        </div>
      </header>

      <div
        className={`auth-status auth-status--${telegramAuthStatus}`}
        role="status"
      >
        {telegramAuthStatus === "verified" && telegramProfile ? (
          <span>
            Telegram identity verified for{" "}
            <strong>{telegramProfile.firstName}</strong>.
          </span>
        ) : (
          <span>{telegramAuthMessage}</span>
        )}
      </div>

      {!room ? (
        <>
          <section className="welcome">
            <p className="eyebrow">YOUR GROUP'S LITTLE MUSIC CORNER</p>
            <h1>
              Good music.
              <br />
              <span>Better together.</span>
            </h1>
            <p className="welcome-copy">
              Create a room, invite your people, and decide what plays next.
            </p>
          </section>

          <section className="lobby-grid">
            <article className="lobby-card">
              <div className="lobby-icon">♫</div>
              <p className="eyebrow">START THE VIBE</p>
              <h2>Create a room</h2>
              <p className="muted">
                Give your listening room a name and invite your friends.
              </p>

              <form className="lobby-form" onSubmit={createRoom}>
                <label className="field-label" htmlFor="roomName">
                  Room name
                </label>
                <input
                  id="roomName"
                  className="room-input"
                  value={roomName}
                  onChange={(event) => setRoomName(event.target.value)}
                  maxLength={60}
                  required
                />

                <button
                  className="primary-action"
                  type="submit"
                  disabled={creatingRoom}
                >
                  {creatingRoom
                    ? "Creating room..."
                    : "＋ Create listening room"}
                </button>
              </form>
            </article>

            <article className="lobby-card">
              <div className="lobby-icon lobby-icon-alt">↗</div>
              <p className="eyebrow">YOUR FRIENDS ARE HERE</p>
              <h2>Join a room</h2>
              <p className="muted">
                Got a code from someone in Chatting Chamber? Come on in.
              </p>

              <form className="lobby-form" onSubmit={joinRoom}>
                <label className="field-label" htmlFor="inviteCode">
                  Invitation code
                </label>
                <input
                  id="inviteCode"
                  className="room-input invite-input"
                  value={inviteCode}
                  onChange={(event) =>
                    setInviteCode(event.target.value.toUpperCase())
                  }
                  placeholder="e.g. 23FECB8F"
                  maxLength={8}
                  autoCapitalize="characters"
                  required
                />

                <button
                  className="secondary-action"
                  type="submit"
                  disabled={joiningRoom}
                >
                  {joiningRoom ? "Joining room..." : "Join listening room →"}
                </button>
              </form>
            </article>
          </section>

          <section className="public-rooms-section">
            <div className="public-rooms-heading">
              <div>
                <p className="eyebrow">YOUR GROUP'S LITTLE MUSIC CORNER</p>
                <h2>Listening rooms</h2>
                <p className="muted">
                  Find a room that's active and join the vibe.
                </p>
              </div>
              <span className="public-room-total">
                {activeRooms.filter((room) => room.playing).length} playing
              </span>
            </div>

            {roomsLoading ? (
              <p className="muted">Finding active rooms…</p>
            ) : roomsDirectoryError ? (
              <p className="muted" role="status">
                {roomsDirectoryError}
              </p>
            ) : activeRooms.length === 0 ? (
              <div className="public-room-empty">
                <span aria-hidden="true">♫</span>
                <h3>No active rooms just yet</h3>
                <p className="muted">
                  Create a room or join one using an invitation code or link.
                </p>
              </div>
            ) : (
              <div className="public-room-grid">
                {activeRooms.map((publicRoom) => (
                  <article
                    className="lobby-card public-room-card"
                    key={publicRoom.id}
                  >
                    <div className="lobby-icon">♫</div>

                    <div className="public-room-card-copy">
                      <h3>{publicRoom.name}</h3>
                      <p className="muted">
                        <span
                          className={`online-dot ${
                            publicRoom.playing ? "" : "public-room-dot-idle"
                          }`}
                        />
                        {publicRoom.playing ? "Playing now" : "Room active"}
                      </p>
                    </div>

                    <button
                      type="button"
                      className="primary-action public-room-join"
                      disabled={joiningRoom}
                      onClick={() => void joinListedRoom(publicRoom.id)}
                    >
                      {joiningRoom ? "Joining…" : "Join →"}
                    </button>
                  </article>
                ))}
              </div>
            )}

            <p className="public-rooms-privacy">
              Room members stay private. Join a room to see who's listening.
            </p>
          </section>

          <p className="room-footer">
            No ads in our interface. Just friends, music, and shared moments.
          </p>
        </>
      ) : (
        <AudiusMusicPlayer
          room={room}
          onInviteClick={copyInviteCode}
          onLeaveRoom={leaveRoom}
        />
      )}

      {error && (
        <p className="error-message" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="success-message" role="status">
          {notice}
        </p>
      )}

      <footer className="page-footer">
        <span>Chamber Jams © 2026</span>
        <span>Your people. Your playlist.</span>
      </footer>
    </main>
  );
}

function extractInviteCode(input: string): string | null {
  const value = input.trim();
  let candidate = value;

  try {
    const normalizedUrl = /^t\.me\//i.test(value) ? `https://${value}` : value;

    if (/^https?:\/\//i.test(normalizedUrl)) {
      const url = new URL(normalizedUrl);

      candidate =
        url.searchParams.get("startapp") ??
        url.searchParams.get("start") ??
        url.searchParams.get("inviteCode") ??
        url.searchParams.get("code") ??
        url.pathname.split("/").filter(Boolean).pop() ??
        "";
    }
  } catch {
    return null;
  }

  const match = candidate.toUpperCase().match(/[A-F0-9]{8}/);
  return match ? match[0] : null;
}

export default App;

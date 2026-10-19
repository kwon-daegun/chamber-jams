import { useEffect, useState, type FormEvent } from "react";
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

type Track = {
  id: number;
  title: string;
  artist: string;
  symbol: string;
  cover: string;
};

const demoTracks: Track[] = [
  {
    id: 1,
    title: "Space Song",
    artist: "Beach House",
    symbol: "☾",
    cover: "violet",
  },
  {
    id: 2,
    title: "Show Me How",
    artist: "Men I Trust",
    symbol: "✳",
    cover: "sage",
  },
  {
    id: 3,
    title: "Glue Song",
    artist: "beabadoobee",
    symbol: "♡",
    cover: "peach",
  },
  {
    id: 4,
    title: "Apocalypse",
    artist: "Cigarettes After Sex",
    symbol: "✦",
    cover: "rose",
  },
];

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

  const [createHostName, setCreateHostName] = useState("");
  const [joinHostName, setJoinHostName] = useState("");

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
        setCreateHostName(profile.firstName);
        setJoinHostName(profile.firstName);
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
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [queue, setQueue] = useState<Track[]>(demoTracks);
  const [playing, setPlaying] = useState(false);

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

  async function createRoom(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setNotice("");

    if (!roomName.trim() || !createHostName.trim()) {
      setError("Enter a room name and your display name.");
      return;
    }

    setCreatingRoom(true);

    try {
      const response = await fetch(`${API_BASE}/api/rooms`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          name: roomName.trim(),
          hostName: createHostName.trim(),
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

  async function joinRoom(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setError("");
    setNotice("");

    if (!joinHostName.trim() || !inviteCode.trim()) {
      setError("Enter your display name and an invitation code.");
      return;
    }

    setJoiningRoom(true);

    try {
      const response = await fetch(`${API_BASE}/api/rooms/join`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ inviteCode: inviteCode.trim() }),
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

  function skipDemoSong() {
    setQueue((current) =>
      current.length > 1 ? [...current.slice(1), current[0]] : current,
    );
    setNotice("Demo queue updated. Shared Skip is our next backend feature.");
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

                <label className="field-label" htmlFor="createHostName">
                  Your display name
                </label>
                <input
                  id="createHostName"
                  className="room-input"
                  value={createHostName}
                  onChange={(event) => setCreateHostName(event.target.value)}
                  placeholder="What should friends call you?"
                  maxLength={40}
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
                <label className="field-label" htmlFor="joinHostName">
                  Your display name
                </label>
                <input
                  id="joinHostName"
                  className="room-input"
                  value={joinHostName}
                  onChange={(event) => setJoinHostName(event.target.value)}
                  placeholder="What should friends call you?"
                  maxLength={40}
                  required
                />

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

          <p className="room-footer">
            No ads in our interface. Just friends, music, and shared moments.
          </p>
        </>
      ) : (
        <>
          <section className="welcome room-welcome">
            <p className="eyebrow">YOU'RE IN THE ROOM ♡</p>
            <h1>
              {room.name}
              <br />
              <span>the vibe starts here.</span>
            </h1>
            <p className="welcome-copy">
              Room host: {room.hostName} · {room.listenerCount} listener
              {room.listenerCount === 1 ? "" : "s"}
            </p>
          </section>

          <section className="room-active-grid">
            <article className="lobby-card room-invite-card">
              <p className="eyebrow">BRING YOUR PEOPLE</p>
              <h2>Your invitation code</h2>
              <p className="muted">
                Share this code in Chatting Chamber so your friends can find the
                room.
              </p>
              <button className="room-code" onClick={copyInviteCode}>
                <span>{room.inviteCode}</span>
                <span aria-hidden="true">⧉</span>
              </button>
              <p className="muted room-id">Room ID: {room.id}</p>
              <button className="secondary-action" onClick={leaveRoom}>
                ← Back to rooms
              </button>
            </article>

            <article className="lobby-card">
              <p className="eyebrow">A LITTLE BIT OF EVERYONE</p>
              <h2>Now playing together</h2>
              <p className="muted">
                This is still the demo player. Real music playback and shared
                queue synchronization come next.
              </p>

              <div className={`room-demo-art ${queue[0]?.cover ?? "violet"}`}>
                <span>{queue[0]?.symbol ?? "♫"}</span>
              </div>

              <h3 className="demo-track-title">
                {queue[0]?.title ?? "No song queued"}
              </h3>
              <p className="muted">
                {queue[0]?.artist ?? "Add a song to begin"}
              </p>

              <div className="demo-controls">
                <button
                  className="secondary-action"
                  onClick={() => setPlaying((value) => !value)}
                >
                  {playing ? "Ⅱ Pause demo" : "▶ Play demo"}
                </button>
                <button className="primary-action" onClick={skipDemoSong}>
                  Skip song ↠
                </button>
              </div>

              <div className="demo-queue">
                <p className="eyebrow">UP NEXT</p>
                {queue.slice(1).map((track) => (
                  <div className="demo-queue-item" key={track.id}>
                    <span>{track.symbol}</span>
                    <span>
                      {track.title} · {track.artist}
                    </span>
                  </div>
                ))}
              </div>
            </article>
          </section>

          <button className="text-action" onClick={leaveRoom}>
            Leave this room
          </button>
        </>
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

export default App;

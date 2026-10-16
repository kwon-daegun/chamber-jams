import { useState } from "react";
import "./App.css";

type Track = {
  id: number;
  title: string;
  artist: string;
  duration: string;
  symbol: string;
  cover: string;
};

const musicLibrary: Track[] = [
  {
    id: 1,
    title: "Space Song",
    artist: "Beach House",
    duration: "5:20",
    symbol: "☾",
    cover: "violet",
  },
  {
    id: 2,
    title: "Show Me How",
    artist: "Men I Trust",
    duration: "3:35",
    symbol: "✳",
    cover: "sage",
  },
  {
    id: 3,
    title: "Glue Song",
    artist: "beabadoobee",
    duration: "2:16",
    symbol: "♡",
    cover: "peach",
  },
  {
    id: 4,
    title: "Apocalypse",
    artist: "Cigarettes After Sex",
    duration: "4:50",
    symbol: "✦",
    cover: "rose",
  },
  {
    id: 5,
    title: "Sunsetz",
    artist: "Cigarettes After Sex",
    duration: "3:35",
    symbol: "☼",
    cover: "sunset",
  },
];

function App() {
  const [queue, setQueue] = useState<Track[]>(musicLibrary.slice(0, 4));
  const [playing, setPlaying] = useState(true);
  const [search, setSearch] = useState("");
  const [notice, setNotice] = useState("");

  const currentTrack = queue[0];

  function skipSong() {
    setQueue((current) =>
      current.length > 1 ? [...current.slice(1), current[0]] : current,
    );
    setPlaying(true);
    setNotice("Ready for the next song ✨");
  }

  function addSong(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();

    const query = search.trim().toLowerCase();

    if (!query) {
      setNotice("Type a song title or artist first.");
      return;
    }

    const match = musicLibrary.find((track) =>
      `${track.title} ${track.artist}`.toLowerCase().includes(query),
    );

    if (!match) {
      setNotice("No match in our demo library. Try another search.");
      return;
    }

    if (queue.some((track) => track.id === match.id)) {
      setNotice("That song is already in the room.");
      return;
    }

    setQueue((current) => [...current, match]);
    setSearch("");
    setNotice(`Added "${match.title}" to the queue ♡`);
  }

  async function inviteFriends() {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setNotice("Page link copied! Real room invites come in a later step.");
    } catch {
      setNotice(
        "Room invitations will be available when we connect the backend.",
      );
    }
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
          your people are your playlist
          <div className="profile-avatar">Y</div>
        </div>
      </header>

      <section className="welcome">
        <p className="eyebrow">MADE FOR CHATTING CHAMBER</p>
        <h1>
          Good music.
          <br />
          <span>Better together.</span>
        </h1>
        <p className="welcome-copy">
          For the songs you send at 2 AM, and the friends who just get it.
        </p>
      </section>

      <section className="room-layout">
        <div className="player-panel">
          <div className="room-heading">
            <div>
              <div className="room-kicker">
                <span className="live-dot" />
                YOUR LISTENING ROOM
              </div>
              <h2>the after-hours room</h2>
              <p className="muted">no plans, just one more song</p>
            </div>

            <button className="invite-button" onClick={inviteFriends}>
              ↗ Invite friends
            </button>
          </div>

          {currentTrack ? (
            <>
              <div className={`album-art ${currentTrack.cover}`}>
                <span className="album-spark spark-one">✳</span>
                <span className="album-symbol">{currentTrack.symbol}</span>
                <span className="album-spark spark-two">✦</span>
                <span className="album-caption">A SOUNDTRACK FOR US</span>
              </div>

              <div className="now-playing">
                <div className="track-info">
                  <p className="eyebrow">ON REPEAT WITH EVERYONE</p>
                  <h2>{currentTrack.title}</h2>
                  <p className="muted">{currentTrack.artist}</p>
                </div>

                <button
                  className="like-button"
                  aria-label="Save favourite"
                  onClick={() => setNotice("Saved to favourites (demo) ♡")}
                >
                  ♡
                </button>
              </div>

              <div className="progress-track">
                <span className="progress-fill" />
              </div>

              <div className="time-row">
                <span>1:24</span>
                <span>{currentTrack.duration}</span>
              </div>

              <div className="player-controls">
                <button
                  className="subtle-control"
                  aria-label="Previous song"
                  onClick={() => {
                    setQueue((current) =>
                      current.length > 1
                        ? [current[current.length - 1], ...current.slice(0, -1)]
                        : current,
                    );
                    setPlaying(true);
                    setNotice("Went back to the previous song.");
                  }}
                >
                  ↶
                </button>

                <button
                  className="play-button"
                  aria-label={
                    playing ? "Pause demo player" : "Play demo player"
                  }
                  onClick={() => {
                    setPlaying((value) => !value);
                    setNotice(
                      "Demo control only — audio playback comes later.",
                    );
                  }}
                >
                  {playing ? "Ⅱ" : "▶"}
                </button>

                <button
                  className="subtle-control skip-button"
                  onClick={skipSong}
                >
                  <span>Skip song</span> ↠
                </button>
              </div>

              <p className="player-note">
                {playing ? "♫" : "Ⅱ"} we're all listening together
              </p>
            </>
          ) : (
            <div className="empty-state">
              <h2>Your room is waiting for a song.</h2>
              <p>Search the demo library to start the queue.</p>
            </div>
          )}
        </div>

        <aside className="side-panel">
          <section className="friends-card">
            <div className="section-heading">
              <div>
                <p className="eyebrow">YOUR FAVOURITE PEOPLE</p>
                <h3>
                  In the room <span className="count-pill">4</span>
                </h3>
              </div>
              <span className="tiny-wave">♫</span>
            </div>

            <div className="avatar-stack" aria-label="Demo listeners">
              <div className="avatar avatar-maya">M</div>
              <div className="avatar avatar-ari">A</div>
              <div className="avatar avatar-sam">S</div>
              <div className="avatar avatar-you">Y</div>
            </div>

            <p className="friends-copy">
              <strong>Maya, Ari, Sam</strong> and you are sharing this moment.
            </p>

            <div className="listening-status">
              <span className="online-dot" />
              here for the music, staying for the people
            </div>
          </section>

          <section className="queue-card">
            <div className="section-heading">
              <div>
                <p className="eyebrow">A LITTLE BIT OF EVERYONE</p>
                <h3>Up next</h3>
              </div>
              <span className="queue-count">{queue.length} songs</span>
            </div>

            <div className="queue-list">
              {queue.slice(1, 5).map((track, index) => (
                <div className="queue-item" key={track.id}>
                  <div className={`mini-cover ${track.cover}`}>
                    {track.symbol}
                  </div>

                  <div className="queue-track">
                    <strong>{track.title}</strong>
                    <span>{track.artist}</span>
                  </div>

                  <span className="track-duration">{track.duration}</span>

                  <span className="added-by">
                    {["M", "A", "S", "Y"][index % 4]}
                  </span>
                </div>
              ))}

              {queue.length <= 1 && (
                <p className="muted">The queue is clear. Add a song below.</p>
              )}
            </div>

            <form className="add-track" onSubmit={addSong}>
              <input
                aria-label="Search demo music"
                placeholder="Try “Sunsetz” or an artist..."
                value={search}
                onChange={(event) => setSearch(event.target.value)}
              />
              <button type="submit" aria-label="Add song">
                ＋
              </button>
            </form>

            <p className="notice" role="status">
              {notice || "Everyone gets a say in what plays next."}
            </p>
          </section>

          <div className="room-footer">
            <span>♡ made for the group chat</span>
            <span>NO ADS. JUST VIBES.</span>
          </div>
        </aside>
      </section>

      <footer className="page-footer">
        <span>Chamber Jams © 2026</span>
        <span>your people. your playlist.</span>
      </footer>
    </main>
  );
}

export default App;

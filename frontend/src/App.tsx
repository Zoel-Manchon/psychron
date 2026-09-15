import { useCallback, useEffect, useState } from "react";
import { PhonePanel } from "./PhonePanel";
import { RoomPanel } from "./RoomPanel";
import { Pip } from "./layout";
import { useTheme } from "./theme";
import { Invitation } from "./auth/Invitation";
import { SecondFactor } from "./auth/SecondFactor";
import { SignIn } from "./auth/SignIn";
import {
  ApiError, RANGES, api, auth, hoursFromSlug, openLive, rangeSlug, since,
  type AlertEpisode, type AuthState, type Challenge, type Current, type Device, type Health,
  type Series, type Stats,
} from "./api";

// One sheet, read top to bottom.
//
// The previous arrangement put six numbered plates behind a row of buttons, so
// five sixths of what the station knows was always one click away and therefore
// never seen. A panel watched at a glance has to answer at a glance: the state
// of the room, how it got there, how unusual that is, and whether the record can
// be trusted — all in view at once, separated by rules rather than hidden behind
// navigation.
//
// Two nodes, each a titled part of the sheet with its sections numbered inside it.
// The one control that changes what every number means — the window — sits in a bar
// that stays at the top while the page is read, beside the two nodes' pulses.

/** How much elapsed time the record covers, in the coarsest honest unit. */
function spanOf(first: string, last: string | null): string {
  const days = ((last ? Date.parse(last) : Date.now()) - Date.parse(first)) / 86_400_000;
  if (days < 1) return `${(days * 24).toFixed(1)} h of elapsed time`;
  return `${days.toFixed(1)} d of elapsed time`;
}

const initialHours = () =>
  hoursFromSlug(new URLSearchParams(window.location.search).get("range")) ?? 24;

const ageText = (seconds: number | null | undefined) =>
  seconds == null ? "" : ` · ${Math.max(0, seconds).toFixed(0)} s`;

export default function App() {
  const [theme, toggleTheme] = useTheme();
  const [authState, setAuthState] = useState<AuthState | null>(null);
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [who, setWho] = useState<string | null>(null);

  // Seeded from the URL so a link carries its window, and written back on every
  // change with replaceState — pushState would make the back button walk through
  // range selections instead of leaving the page, which is not what a reader
  // pressing Back is asking for.
  const [hours, setHours] = useState(initialHours);
  // The window's start, as a value rather than computed during render: since()
  // returns a fresh instant on every call, and a fresh `from` each render made
  // load() new each time and the page requested in a loop. Moved on by the range
  // buttons and once a minute, so "the last hour" keeps meaning the last hour.
  const [from, setFrom] = useState(() => since(initialHours()));
  const [current, setCurrent] = useState<Current | null>(null);
  const [series, setSeries] = useState<Series | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);
  const [device, setDevice] = useState<Device | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [alerts, setAlerts] = useState<AlertEpisode[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);

  const invite = new URLSearchParams(window.location.search).get("invite");

  const selectRange = useCallback((h: number) => {
    setHours(h);
    setFrom(since(h));
    const label = RANGES.find((r) => r.hours === h)?.label;
    const params = new URLSearchParams(window.location.search);
    if (label) params.set("range", rangeSlug(label));
    window.history.replaceState({}, "", `${window.location.pathname}?${params}`);
  }, []);

  useEffect(() => {
    auth.state().then((s) => { setAuthState(s); setWho(s.signed_in_as); })
        .catch(() => setAuthState(null));
  }, []);

  const load = useCallback(async () => {
    try {
      const [c, s, st, d] = await Promise.all([
        api.current(), api.readings(from), api.stats(from), api.device(),
      ]);
      setCurrent(c); setSeries(s); setStats(st); setDevice(d); setError(null);
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) { setWho(null); return; }
      setError(e instanceof ApiError ? e.message : String(e));
    }
  }, [from]);

  useEffect(() => { if (who) load(); }, [who, load]);

  useEffect(() => {
    const id = setInterval(() => setFrom(since(hours)), 60_000);
    return () => clearInterval(id);
  }, [hours]);

  useEffect(() => {
    const tick = () => api.health().then(setHealth).catch(() => setHealth(null));
    tick();
    const id = setInterval(tick, 5000);
    return () => clearInterval(id);
  }, []);

  // The evaluator runs every 30 s, so polling faster would only re-read the same
  // answer. Failures leave the last known state rather than blanking it.
  useEffect(() => {
    if (!who) return;
    const tick = () => api.alerts().then((a) => setAlerts(a.open)).catch(() => undefined);
    tick();
    const id = setInterval(tick, 30_000);
    return () => clearInterval(id);
  }, [who]);

  useEffect(() => {
    if (!who) return;
    const ws = openLive((r) => {
      setLive(true);
      setCurrent((prev) => prev && {
        ...prev,
        measured: {
          time: r.time as string,
          temperature_c: r.temperature_c as number,
          humidity_pct: r.humidity_pct as number,
        },
        derived: {
          dew_point_c: r.dew_point_c as number,
          absolute_humidity_g_m3: r.absolute_humidity_g_m3 as number,
          vapour_pressure_deficit_kpa: r.vapour_pressure_deficit_kpa as number,
          heat_index_c: r.heat_index_c as number,
          condensation_margin_c: r.condensation_margin_c as number,
        },
      });
    });
    return () => { ws.close(); setLive(false); };
  }, [who]);

  // The same control on every screen, including the ones before sign-in: a
  // reader who prefers a dark ground should not have to authenticate first to
  // get one. The label names the destination, not the present state: a control
  // that says "dark" while the page is dark reads as a status light.
  const themeButton = (
    <button onClick={toggleTheme}
            title={`Switch to the ${theme === "light" ? "dark" : "light"} theme`}>
      {theme === "light" ? "◐ Dark" : "◑ Light"}
    </button>
  );

  // ── access ──────────────────────────────────────────────────────────────

  const gate = (screen: React.ReactNode) => (
    <>
      <div className="corner">{themeButton}</div>
      {screen}
    </>
  );

  if (invite) {
    return gate(<Invitation token={invite} onEnrolled={() => {
      window.history.replaceState({}, "", window.location.pathname);
      auth.state().then((s) => { setAuthState(s); setWho(s.signed_in_as); });
    }} />);
  }
  if (!authState) {
    return gate(<div className="sheet"><p className="note" style={{ paddingTop: 40 }}>Connecting…</p></div>);
  }
  if (!who && challenge) {
    return gate(<SecondFactor challenge={challenge}
                              onSignedIn={(id) => { setChallenge(null); setWho(id); }}
                              onCancel={() => setChallenge(null)} />);
  }
  if (!who) {
    return gate(<SignIn state={authState} onChallenge={setChallenge}
                        onSignedIn={(id) => setWho(id)} />);
  }

  // ── the panel ───────────────────────────────────────────────────────────

  return (
    <div className="sheet">
      <header className="masthead">
        <div>
          <span className="eyebrow">Environmental station · two nodes</span>
          <h1>Psychron</h1>
          {/* The whole argument of this system is that elapsed time cannot be
              measured twice. If that is the claim, the amount of it captured so
              far belongs at the top, counted, not buried in a panel. */}
          <span className="mono-note">
            {device?.extent.first
              // en-GB rather than the browser's locale: the panel is written in
              // English throughout, and a Spanish month abbreviation inside an
              // English sentence reads as a bug, not as a courtesy.
              ? <>Record since {new Date(device.extent.first).toLocaleDateString("en-GB",
                  { day: "numeric", month: "short", year: "numeric" })}
                {" · "}{device.extent.total.toLocaleString("en-GB")} readings
                {" · "}{spanOf(device.extent.first, device.extent.last)}</>
              : "No record yet"}
          </span>
        </div>
        <div className="masthead-actions">
          <span className="mono-note">{who}</span>
          {themeButton}
          <button onClick={() => auth.logout().then(() => setWho(null))}>Sign out</button>
        </div>
      </header>

      <nav className="toolbar" aria-label="Window and nodes">
        <div className="toolbar-group">
          <span className="eyebrow">Window</span>
          <div className="segments" role="group" aria-label="Time window">
            {RANGES.map((r) => (
              <button key={r.label} aria-pressed={hours === r.hours}
                      onClick={() => selectRange(r.hours)}>{r.label}</button>
            ))}
          </div>
        </div>
        <div className="toolbar-group">
          <a className="pulse" href="#room">
            <Pip on={health?.device_reporting ?? false} /><strong>Room</strong>
            {health === null ? " · API unreachable" : health.device_reporting ? ageText(health.last_reading_age_s) : " · silent"}
          </a>
          <a className="pulse" href="#phone">
            <Pip on={health?.phone_reporting ?? false} /><strong>Phone</strong>
            {health === null ? "" : health.phone_reporting ? ageText(health.last_sample_age_s) : " · silent"}
          </a>
        </div>
      </nav>

      {error && <p className="mono-note accent" style={{ marginTop: 12 }}>{error}</p>}

      {alerts.map((a) => (
        <div className="alert-band" key={`${a.kind}-${a.device_id}`} role="status">
          <strong>{a.message}</strong>
          <span className="mono-note">
            {a.device_id} · since {new Date(a.raised_at).toLocaleString("en-GB",
              { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" })}
            {" · "}also sent to the phone
          </span>
        </div>
      ))}

      <RoomPanel current={current} series={series} stats={stats} device={device} health={health}
                 live={live} hours={hours} from={from} theme={theme} onError={setError} />

      <PhonePanel from={from} hours={hours} theme={theme} />
    </div>
  );
}

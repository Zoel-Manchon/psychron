import { useCallback, useEffect, useMemo, useState } from "react";
import { Chart, findGaps } from "./Chart";
import { Distribution } from "./Distribution";
import { PhonePanel } from "./PhonePanel";
import { StationGlyph } from "./StationGlyph";
import { useTheme } from "./theme";
import { Invitation } from "./auth/Invitation";
import { SecondFactor } from "./auth/SecondFactor";
import { SignIn } from "./auth/SignIn";
import {
  ApiError, RANGES, api, auth, hoursFromSlug, openLive, rangeSlug, since,
  type AuthState, type Challenge, type Current, type Device, type Health,
  type Series, type Stats,
} from "./api";

// One sheet, read top to bottom.
//
// The previous arrangement put six numbered plates behind a row of buttons, so
// five sixths of what the station knows was always one click away and therefore
// never seen. A panel watched at a glance has to answer at a glance: the state
// of the room, how it got there, how unusual that is, and whether the record can
// be trusted — all in view at once, separated by hairlines rather than hidden
// behind navigation.
//
// The psychrometric chart is gone. Its axes covered 5–40 °C and 0–26 g/kg while
// this room lives in 20–29 °C and 41–73 %, so the whole history sat in one
// corner as a smudge, and everything it had to say — dew point, margin to
// saturation — is already stated as a number a few centimetres away.

const fmt = (v: number | null | undefined, d = 2) =>
  v === null || v === undefined ? "—" : v.toFixed(d);

/** How much elapsed time the record covers, in the coarsest honest unit. */
function spanOf(first: string, last: string | null): string {
  const days = ((last ? Date.parse(last) : Date.now()) - Date.parse(first)) / 86_400_000;
  if (days < 1) return `${(days * 24).toFixed(1)} h of elapsed time`;
  return `${days.toFixed(1)} d of elapsed time`;
}

/** Movement against the same instant a day earlier.
 *
 * Absent rather than zero when there is nothing to compare against: an outage a
 * day ago must not be rendered as "no change", which is the one reading of a
 * blank that would be wrong.
 */
function Delta({ now, then, unit, digits }: {
  now: number | null; then: number | null; unit: string; digits: number;
}) {
  if (now === null || then === null) {
    return <span className="mono-note">no reading 24 h ago</span>;
  }
  const d = now - then;
  // A sign, not a colour: up is not good and down is not bad in a room.
  const arrow = Math.abs(d) < 0.05 ? "=" : d > 0 ? "▲" : "▼";
  return (
    <span className="mono-note">
      {arrow} {d > 0 ? "+" : ""}{d.toFixed(digits)} {unit} vs 24 h ago
    </span>
  );
}

const DERIVED: [string, string, string, number][] = [
  ["dew_point_c", "Dew point", "°C", 2],
  ["condensation_margin_c", "Margin to saturation", "K", 2],
  ["absolute_humidity_g_m3", "Absolute humidity", "g/m³", 2],
  ["vapour_pressure_deficit_kpa", "Vapour pressure deficit", "kPa", 3],
  ["heat_index_c", "Heat index", "°C", 2],
];

// Holt-Winters with a daily cycle needs whole days of history before it says
// anything, and saying so is better than drawing a line from three hours of data.
const FORECAST_DAYS_REQUIRED = 14;

export default function App() {
  const [theme, toggleTheme] = useTheme();
  const [authState, setAuthState] = useState<AuthState | null>(null);
  const [challenge, setChallenge] = useState<Challenge | null>(null);
  const [who, setWho] = useState<string | null>(null);

  // Seeded from the URL so a link carries its window, and written back on every
  // change with replaceState — pushState would make the back button walk through
  // range selections instead of leaving the page, which is not what a reader
  // pressing Back is asking for.
  const [hours, setHours] = useState(
    () => hoursFromSlug(new URLSearchParams(window.location.search).get("range")) ?? 24);
  const [current, setCurrent] = useState<Current | null>(null);
  const [series, setSeries] = useState<Series | null>(null);
  const [stats, setStats] = useState<Stats | null>(null);
  const [device, setDevice] = useState<Device | null>(null);
  const [health, setHealth] = useState<Health | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [live, setLive] = useState(false);
  const [refreshes, setRefreshes] = useState(0);

  const invite = new URLSearchParams(window.location.search).get("invite");

  const selectRange = useCallback((h: number) => {
    setHours(h);
    const label = RANGES.find((r) => r.hours === h)?.label;
    const params = new URLSearchParams(window.location.search);
    if (label) params.set("range", rangeSlug(label));
    window.history.replaceState({}, "", `${window.location.pathname}?${params}`);
  }, []);

  // Memoised, and not as an optimisation: since(hours) returns a fresh value on
  // every call, so computing it during render made load() new each time and the
  // page requested in a loop.
  const from = useMemo(() => since(hours), [hours, refreshes]);

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
    const id = setInterval(() => setRefreshes((n) => n + 1), 60_000);
    return () => clearInterval(id);
  }, []);

  useEffect(() => {
    const tick = () => api.health().then(setHealth).catch(() => setHealth(null));
    tick();
    const id = setInterval(tick, 5000);
    return () => clearInterval(id);
  }, []);

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

  const gaps = useMemo(
    () => (series ? findGaps(series.points, series.bucket) : []), [series]);

  const trendPerHour = useMemo(() => {
    const pts = series?.points.filter((p) => p.temp !== null) ?? [];
    if (pts.length < 2) return 0;
    const span = (Date.parse(pts[pts.length - 1].t) - Date.parse(pts[0].t)) / 3_600_000;
    return span > 0 ? ((pts[pts.length - 1].temp as number) - (pts[0].temp as number)) / span : 0;
  }, [series]);

  // The same control on every screen, including the ones before sign-in: a
  // reader who prefers a dark ground should not have to authenticate first to
  // get one.
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
    return gate(<div className="sheet"><p className="mono-note">Connecting…</p></div>);
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

  const linkState = health === null ? "API unreachable"
    : health.device_reporting ? "Node reporting" : "Node silent";

  const daysOfHistory = stats && stats.samples > 0 ? (stats.samples * 3) / 86400 : 0;
  const forecastPct = Math.min(100, (daysOfHistory / FORECAST_DAYS_REQUIRED) * 100);
  const completeness = stats ? stats.completeness * 100 : 0;

  // The selected window, named the way the buttons name it, so the label on the
  // export and the name of the file it produces cannot drift from the range.
  const rangeLabel = RANGES.find((r) => r.hours === hours)?.label ?? `${hours} h`;
  const rangeFile = rangeSlug(rangeLabel);

  return (
    <div className="sheet">
      {/* ── masthead ─────────────────────────────────────────────────────── */}
      <div className="spread">
        <div>
          <span className="label label-plain">Environmental station · node 01 · ESP32 + DHT22</span>
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
        <div className="row" style={{ alignItems: "center", gap: 16 }}>
          <span className="status">
            <span className={`pip${health?.device_reporting ? " pip-on" : ""}`} />
            {linkState}
            {health?.last_reading_age_s != null &&
              ` · ${Math.max(0, health.last_reading_age_s).toFixed(0)} s ago`}
            {live && " · live"}
          </span>
          <span className="mono-note">{who}</span>
          {/* The label names the destination, not the present state: a control
              that says "dark" while the page is dark reads as a status light. */}
          {themeButton}
          <button onClick={() => auth.logout().then(() => setWho(null))}>Sign out</button>
        </div>
      </div>

      {error && <p className="mono-note accent" style={{ marginTop: 10 }}>{error}</p>}

      {/* ── now ──────────────────────────────────────────────────────────── */}
      <div className="band band-first">
        <div className="grid g-hero">
          <div className="stack">
            <span className="label">Temperature · measured</span>
            <div>
              <span className="reading-primary">{fmt(current?.measured.temperature_c, 1)}</span>
              <span className="unit">°C</span>
            </div>
            <span className="mono-note">
              ±0.5 K sensor · {trendPerHour >= 0 ? "+" : ""}{trendPerHour.toFixed(2)} K/h
            </span>
            <Delta now={current?.measured.temperature_c ?? null}
                   then={current?.day_ago?.temperature_c ?? null} unit="K" digits={1} />
          </div>

          <div className="stack">
            <span className="label">Relative humidity · measured</span>
            <div>
              <span className="reading-secondary">{fmt(current?.measured.humidity_pct, 1)}</span>
              <span className="unit">%</span>
            </div>
            <span className="mono-note">±2.0 % sensor</span>
            <Delta now={current?.measured.humidity_pct ?? null}
                   then={current?.day_ago?.humidity_pct ?? null} unit="%" digits={1} />
          </div>

          <div>
            <span className="label">Derived · calculated, not measured</span>
            <table className="derived">
              <tbody>
                {DERIVED.map(([k, label, unit, d]) => (
                  <tr key={k}>
                    <td>{label}</td>
                    <td className="v">
                      {fmt(current?.derived[k], d)} <span className="dim">{unit}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="mono-note" style={{ marginTop: 8 }}>
              These carry sensor error ⊕ equation error.
            </p>
          </div>

          <div>
            <span className="label">Station glyph · WMO model adapted</span>
            <StationGlyph temperature={current?.measured.temperature_c ?? null}
                          dewPoint={current?.derived.dew_point_c ?? null}
                          humidity={current?.measured.humidity_pct ?? null}
                          trendPerHour={trendPerHour} />
          </div>
        </div>
      </div>

      {/* ── history ──────────────────────────────────────────────────────── */}
      <div className="band">
        <div className="spread" style={{ marginBottom: 10 }}>
          <div>
            <span className="label label-plain">History</span>
            {series && (
              <span className="mono-note">
                grouped at {series.bucket} · {series.count} points
                {series.truncated && ` · truncated at ${series.max_points}`}
                {gaps.length === 0
                  ? " · no gaps in this window"
                  : ` · ${gaps.length} gap${gaps.length > 1 ? "s" : ""}, marked on the axis`}
              </span>
            )}
          </div>
          <div className="row" style={{ gap: 2 }}>
            {RANGES.map((r) => (
              <button key={r.label} aria-pressed={hours === r.hours}
                      onClick={() => selectRange(r.hours)}>{r.label}</button>
            ))}
          </div>
        </div>
        {series
          ? <Chart points={series.points} bucket={series.bucket} theme={theme} />
          : <p className="mono-note">Loading the window…</p>}

        {/* Export belongs to the window, so it sits under the window it exports
            and names the range in the button strip and in the filename. Put
            anywhere else on the page it silently exports whatever the range
            buttons happened to be set to, which is a file you cannot identify
            afterwards. */}
        <div className="spread" style={{ marginTop: 14, alignItems: "baseline" }}>
          <span className="mono-note">
            Units, device identity and quality flags travel inside the file.
            What you see is what you take.
          </span>
          <div className="row" style={{ gap: 2, alignItems: "baseline" }}>
            <span className="label label-plain" style={{ marginBottom: 0 }}>
              Export {rangeLabel}
            </span>
            {["csv", "json", "xlsx"].map((f) => (
              <button key={f} onClick={async () => {
                const res = await fetch(api.exportUrl(f, from), { credentials: "same-origin" });
                if (!res.ok) { setError(`Export failed: ${res.status}`); return; }
                const url = URL.createObjectURL(await res.blob());
                const a = document.createElement("a");
                a.href = url; a.download = `psychron-${rangeFile}.${f}`; a.click();
                URL.revokeObjectURL(url);
              }}>{f}</button>
            ))}
          </div>
        </div>
      </div>

      {/* ── phone node ───────────────────────────────────────────────────── */}
      {/* Directly under the room's history, so what both nodes are doing now is
          read together, and the analysis of the room's record follows. */}
      <PhonePanel from={from} hours={hours} theme={theme} />

      {/* ── distribution, integrity, provenance ──────────────────────────── */}
      <div className="band">
        <div className="grid g-3">
          <div>
            <span className="label">Distribution over the window</span>
            {stats && stats.samples > 0 ? (
              <>
                <Distribution label="Temperature" unit="°C"
                              min={stats.temp_min} p05={stats.temp_p05}
                              median={stats.temp_median} p95={stats.temp_p95}
                              max={stats.temp_max} sd={stats.temp_sd}
                              now={current?.measured.temperature_c ?? null} />
                <Distribution label="Relative humidity" unit="%"
                              min={stats.hum_min} p05={stats.hum_p05}
                              median={stats.hum_median} p95={stats.hum_p95}
                              max={stats.hum_max} sd={stats.hum_sd}
                              now={current?.measured.humidity_pct ?? null} />
                <p className="mono-note" style={{ marginTop: 14 }}>
                  Box spans the central 90 %, heavy tick is the median, whiskers
                  reach the extremes. The red mark is now, on the same axis, so
                  "unusual" is a position rather than a judgement.
                </p>
              </>
            ) : <p className="mono-note">No samples in this window.</p>}
          </div>

          <div>
            <span className="label">Record integrity</span>
            <table>
              <tbody>
                <tr>
                  <td>Samples</td>
                  <td className="v">
                    {stats ? stats.samples.toLocaleString("en-GB") : "—"}
                    <span className="dim"> of {stats ? stats.expected_samples.toLocaleString("en-GB") : "—"}</span>
                  </td>
                </tr>
                <tr><td>Flagged</td><td className="v">{stats?.flagged ?? "—"}</td></tr>
                <tr><td>Failed readings</td><td className="v">{stats?.failed ?? "—"}</td></tr>
                <tr><td>Rejected messages · 24 h</td><td className="v">{device?.rejected_24h ?? "—"}</td></tr>
              </tbody>
            </table>

            <div style={{ marginTop: 16 }}>
              <div className="spread" style={{ marginBottom: 4 }}>
                <span className="mono-note">Completeness</span>
                <span className="num" style={{ fontSize: 12 }}>{completeness.toFixed(1)} %</span>
              </div>
              <div className="meter"><span style={{ width: `${completeness}%` }} /></div>
            </div>

            <div style={{ marginTop: 18 }}>
              <div className="spread" style={{ marginBottom: 4 }}>
                <span className="mono-note">History toward a first forecast</span>
                <span className="num" style={{ fontSize: 12 }}>
                  {daysOfHistory.toFixed(2)} <span className="dim">/ {FORECAST_DAYS_REQUIRED} d</span>
                </span>
              </div>
              <div className="meter"><span style={{ width: `${forecastPct}%` }} /></div>
              {/* Saying why there is no forecast is worth more than a line drawn
                  from three hours of data and presented as one. */}
              <p className="mono-note" style={{ marginTop: 6 }}>
                A daily cycle cannot be told from noise until whole days exist.
              </p>
            </div>
          </div>

          <div>
            <span className="label">Provenance</span>
            <dl className="revision">
              <dt>Firmware</dt><dd>{current?.provenance.firmware ?? "—"}</dd>
              <dt>Sensor</dt><dd>DHT22 · GPIO 27</dd>
              <dt>Boot</dt><dd>{current?.provenance.boot_id ?? "—"}</dd>
              <dt>Sequence</dt><dd>{current?.provenance.seq ?? "—"}</dd>
              <dt>Device time</dt><dd>{current?.provenance.device_time
                ? new Date(current.provenance.device_time).toLocaleTimeString("en-GB") : "none"}</dd>
              <dt>Received</dt><dd>{current?.provenance.received_at
                ? new Date(current.provenance.received_at).toLocaleTimeString("en-GB") : "—"}</dd>
              <dt>Quality</dt><dd>{current?.provenance.quality_flags.length
                ? current.provenance.quality_flags.join(" · ") : "clean"}</dd>
            </dl>
          </div>
        </div>
      </div>

      {/* ── health ───────────────────────────────────────────────────────── */}
      <div className="band">
        <div className="grid g-2">
          <div>
            <span className="label">Gaps in the record · last 24 h</span>
            <p className="mono-note" style={{ marginBottom: 10 }}>
              Found from the readings, not reported by the node: a device that
              dies cannot say so.
            </p>
            <table>
              <tbody>
                {device && device.gaps_24h.length === 0 &&
                  <tr><td>None</td><td className="v">—</td></tr>}
                {device?.gaps_24h.slice(0, 8).map((g) => (
                  <tr key={g.gap_start}>
                    <td className="mono-note">{new Date(g.gap_start).toLocaleTimeString("en-GB")}</td>
                    <td className="v">no data {g.seconds.toFixed(0)} s</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div>
            <span className="label">Boots</span>
            <table>
              <tbody>
                {device?.boots.slice(0, 8).map((b) => (
                  <tr key={b.boot_id}>
                    <td className="mono-note">{new Date(b.first_seen).toLocaleString("en-GB")}</td>
                    <td className="v">{b.firmware} · {b.reset_reason}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  );
}

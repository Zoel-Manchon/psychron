import { useMemo } from "react";
import { Distribution } from "./Distribution";
import { Node, Panel, Pip, Section, Tile } from "./layout";
import { agoWords } from "./phone";
import { SeriesChart, type ChartPanel } from "./SeriesChart";
import { StationGlyph } from "./StationGlyph";
import { findGaps } from "./series";
import { RANGES, api, rangeSlug, type Current, type Device, type Health, type Series, type Stats } from "./api";

// The room: what the ESP32 measures now, how it got there, how that compares with
// the rest of the window, and whether the record behind it can be trusted — in that
// order, which is the order the questions get asked in.
//
// The psychrometric chart is gone. Its axes covered 5–40 °C and 0–26 g/kg while
// this room lives in 20–29 °C and 41–73 %, so the whole history sat in one corner
// as a smudge, and everything it had to say — dew point, margin to saturation — is
// already stated as a number a few centimetres away.

const fmt = (v: number | null | undefined, d = 2) =>
  v === null || v === undefined ? "—" : v.toFixed(d);

const DERIVED: [string, string, string, number][] = [
  ["dew_point_c", "Dew point", "°C", 2],
  ["condensation_margin_c", "Margin to saturation", "K", 2],
  ["absolute_humidity_g_m3", "Absolute humidity", "g/m³", 2],
  ["vapour_pressure_deficit_kpa", "Vapour pressure deficit", "kPa", 3],
  ["heat_index_c", "Heat index", "°C", 2],
];

// Two panels on one time axis rather than one plot with a scale on each side:
// temperature and humidity drawn against each other's scales would line up wherever
// the scales happened to, and the eye reads that as a relationship.
const HISTORY: ChartPanel[] = [
  { title: "Temperature", height: 230, traces: [{ key: "temp", label: "temperature", unit: "°C", color: "--accent", digits: 2 }] },
  { title: "Relative humidity", height: 150, traces: [{ key: "hum", label: "humidity", unit: "%", color: "--cyan", digits: 1 }] },
];

// Holt-Winters with a daily cycle needs whole days of history before it says
// anything, and saying so is better than drawing a line from three hours of data.
const FORECAST_DAYS_REQUIRED = 14;

/** Movement against the same instant a day earlier.
 *
 * Absent rather than zero when there is nothing to compare against: an outage a
 * day ago must not be rendered as "no change", which is the one reading of a
 * blank that would be wrong.
 */
function Delta({ now, then, unit, digits }: {
  now: number | null; then: number | null; unit: string; digits: number;
}) {
  if (now === null || then === null) return <span className="mono-note">no reading 24 h ago</span>;
  const d = now - then;
  // A sign, not a colour: up is not good and down is not bad in a room.
  const arrow = Math.abs(d) < 0.05 ? "=" : d > 0 ? "▲" : "▼";
  return <span className="mono-note">{arrow} {d > 0 ? "+" : ""}{d.toFixed(digits)} {unit} vs 24 h ago</span>;
}

const shortDateTime = (iso: string) =>
  new Date(iso).toLocaleString("en-GB", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" });
const clock = (iso: string) => new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });

type Props = {
  current: Current | null;
  series: Series | null;
  stats: Stats | null;
  device: Device | null;
  health: Health | null;
  live: boolean;
  hours: number;
  from: string;
  theme: string;
  onError: (message: string) => void;
};

export function RoomPanel({ current, series, stats, device, health, live, hours, from, theme, onError }: Props) {
  const gaps = useMemo(() => (series ? findGaps(series.points, series.bucket) : []), [series]);

  const trendPerHour = useMemo(() => {
    const pts = series?.points.filter((p) => p.temp !== null) ?? [];
    if (pts.length < 2) return 0;
    const span = (Date.parse(pts[pts.length - 1].t) - Date.parse(pts[0].t)) / 3_600_000;
    return span > 0 ? ((pts[pts.length - 1].temp as number) - (pts[0].temp as number)) / span : 0;
  }, [series]);

  const daysOfHistory = stats && stats.samples > 0 ? (stats.samples * 3) / 86400 : 0;
  const forecastPct = Math.min(100, (daysOfHistory / FORECAST_DAYS_REQUIRED) * 100);
  const completeness = stats ? stats.completeness * 100 : 0;

  // The selected window, named the way the buttons name it, so the label on the
  // export and the name of the file it produces cannot drift from the range.
  const rangeLabel = RANGES.find((r) => r.hours === hours)?.label ?? `${hours} h`;
  const rangeFile = rangeSlug(rangeLabel);

  const exportAs = async (format: string) => {
    const res = await fetch(api.exportUrl(format, from), { credentials: "same-origin" });
    if (!res.ok) { onError(`Export failed: ${res.status}`); return; }
    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement("a");
    a.href = url; a.download = `psychron-${rangeFile}.${format}`; a.click();
    URL.revokeObjectURL(url);
  };

  const reporting = health?.device_reporting ?? false;

  return (
    <Node id="room" eyebrow="Node 01 · ESP32 + DHT22 · contract v1" title="Room"
          status={<>
            <Pip on={reporting} />
            {health === null ? "API unreachable" : reporting ? "Reporting" : "Silent"}
            {health?.last_reading_age_s != null && ` · ${Math.max(0, health.last_reading_age_s).toFixed(0)} s ago`}
            {live && " · live"}
            {current && ` · firmware ${current.provenance.firmware}`}
          </>}>

      <Section index="01" title="Now" aside="DHT22 on GPIO 27 · a reading every 3 s">
        <div className="grid4">
          <Tile label="Temperature · measured" value={fmt(current?.measured.temperature_c, 1)} unit="°C" size="hero">
            <span className="mono-note">±0.5 K sensor · {trendPerHour >= 0 ? "+" : ""}{trendPerHour.toFixed(2)} K/h</span>
            <Delta now={current?.measured.temperature_c ?? null}
                   then={current?.day_ago?.temperature_c ?? null} unit="K" digits={1} />
          </Tile>

          <Tile label="Relative humidity · measured" value={fmt(current?.measured.humidity_pct, 1)} unit="%" size="major">
            <span className="mono-note">±2.0 % sensor</span>
            <Delta now={current?.measured.humidity_pct ?? null}
                   then={current?.day_ago?.humidity_pct ?? null} unit="%" digits={1} />
          </Tile>

          <Panel label="Derived · calculated">
            <table className="derived">
              <tbody>
                {DERIVED.map(([k, label, unit, d]) => (
                  <tr key={k}>
                    <td>{label}</td>
                    <td className="v">{fmt(current?.derived[k], d)} <span className="dim">{unit}</span></td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="note figure-foot">Each carries the sensor's error and the equation's.</p>
          </Panel>

          <Panel label="Station glyph · WMO model">
            <StationGlyph temperature={current?.measured.temperature_c ?? null}
                          dewPoint={current?.derived.dew_point_c ?? null}
                          humidity={current?.measured.humidity_pct ?? null}
                          trendPerHour={trendPerHour} />
          </Panel>
        </div>
      </Section>

      <Section index="02" title="History"
               aside={series && <>
                 grouped at {series.bucket} · {series.count.toLocaleString("en-GB")} points
                 {series.truncated && ` · truncated at ${series.max_points}`}
                 {gaps.length === 0 ? " · no gaps" : ` · ${gaps.length} gap${gaps.length > 1 ? "s" : ""}, marked on the axis`}
               </>}>
        {series
          ? <SeriesChart points={series.points} bucket={series.bucket} panels={HISTORY} theme={theme} />
          : <p className="note">Loading the window…</p>}

        {/* Export belongs to the window, so it sits under the window it exports
            and names the range in the button strip and in the filename. Put
            anywhere else on the page it silently exports whatever the range
            buttons happened to be set to, which is a file you cannot identify
            afterwards. */}
        <div className="spread figure-foot" style={{ alignItems: "center" }}>
          <p className="note">Units, device identity and quality flags travel inside the file: what you see is what you take.</p>
          <div className="row" style={{ gap: 2, alignItems: "center" }}>
            <span className="eyebrow" style={{ marginRight: 8 }}>Export {rangeLabel}</span>
            {["csv", "json", "xlsx"].map((f) => <button key={f} onClick={() => exportAs(f)}>{f}</button>)}
          </div>
        </div>
      </Section>

      <Section index="03" title="The window"
               aside={stats && `${stats.samples.toLocaleString("en-GB")} of ${stats.expected_samples.toLocaleString("en-GB")} expected readings`}>
        <div className="grid4">
          <Panel label="Distribution" span={2}>
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
                <p className="note figure-foot">
                  The box spans the central 90 %, the heavy tick is the median and the whiskers reach
                  the extremes. The red mark is now, on the same axis, so "unusual" is a position
                  rather than a judgement.
                </p>
              </>
            ) : <p className="note">No samples in this window.</p>}
          </Panel>

          <Panel label="Record integrity">
            <table>
              <tbody>
                <tr><td>Flagged</td><td className="v">{stats?.flagged ?? "—"}</td></tr>
                <tr><td>Failed readings</td><td className="v">{stats?.failed ?? "—"}</td></tr>
                <tr><td>Rejected · 24 h</td><td className="v">{device?.rejected_24h ?? "—"}</td></tr>
              </tbody>
            </table>
            <div className="stack" style={{ marginTop: 16, gap: 6 }}>
              <div className="spread" style={{ alignItems: "baseline" }}>
                <span className="note">Completeness</span>
                <span className="num">{completeness.toFixed(1)} %</span>
              </div>
              <div className="meter"><span style={{ width: `${completeness}%` }} /></div>
            </div>
            <div className="stack" style={{ marginTop: 16, gap: 6 }}>
              <div className="spread" style={{ alignItems: "baseline" }}>
                <span className="note">Toward a first forecast</span>
                <span className="num">{daysOfHistory.toFixed(2)} <span className="dim">/ {FORECAST_DAYS_REQUIRED} d</span></span>
              </div>
              <div className="meter"><span style={{ width: `${forecastPct}%` }} /></div>
              {/* Saying why there is no forecast is worth more than a line drawn
                  from three hours of data and presented as one. */}
              <p className="note">A daily cycle cannot be told from noise until whole days exist.</p>
            </div>
          </Panel>

          <Panel label="Provenance · latest reading">
            <dl className="revision">
              <dt>Firmware</dt><dd>{current?.provenance.firmware ?? "—"}</dd>
              <dt>Boot</dt><dd>{current?.provenance.boot_id ?? "—"}</dd>
              <dt>Sequence</dt><dd>{current?.provenance.seq ?? "—"}</dd>
              <dt>Device time</dt><dd>{current?.provenance.device_time
                ? new Date(current.provenance.device_time).toLocaleTimeString("en-GB") : "none"}</dd>
              <dt>Received</dt><dd>{current?.provenance.received_at
                ? new Date(current.provenance.received_at).toLocaleTimeString("en-GB") : "—"}</dd>
              <dt>Quality</dt><dd>{current?.provenance.quality_flags.length
                ? current.provenance.quality_flags.join(" · ") : "clean"}</dd>
            </dl>
          </Panel>
        </div>
      </Section>

      <Section index="04" title="Record health" aside="the last 24 hours">
        <div className="grid4">
          <Panel label="Gaps" span={2}
                 aside={device && (device.gaps_24h.length === 0 ? "none" : device.gaps_24h.length > 8 ? `latest 8 of ${device.gaps_24h.length}` : `${device.gaps_24h.length}`)}>
            <p className="note" style={{ marginBottom: 8 }}>
              Found from the readings, not reported by the node: a device that dies cannot say so.
            </p>
            {device && device.gaps_24h.length > 0 && (
              <div className="table-wrap">
                <table className="data">
                  <thead><tr><th>from</th><th>to</th><th className="r">silent for</th></tr></thead>
                  <tbody>
                    {device.gaps_24h.slice(0, 8).map((g) => (
                      <tr key={g.gap_start}>
                        <td>{clock(g.gap_start)}</td>
                        <td>{clock(g.gap_end)}</td>
                        <td className="r">{agoWords(g.seconds)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Panel>

          <Panel label="Boots" span={2} aside={device && `${device.boots.length} most recent`}>
            <div className="table-wrap">
              <table className="data">
                <thead><tr><th>first seen</th><th>firmware</th><th className="r">reset</th></tr></thead>
                <tbody>
                  {device?.boots.slice(0, 8).map((b) => (
                    <tr key={b.boot_id}>
                      <td>{shortDateTime(b.first_seen)}</td>
                      <td>{b.firmware}</td>
                      <td className="r">{b.reset_reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </Panel>
        </div>
      </Section>
    </Node>
  );
}

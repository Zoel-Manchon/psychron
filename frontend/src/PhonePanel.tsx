import { useEffect, useId, useMemo, useState, type ReactNode } from "react";
import { Compass } from "./Compass";
import { SeriesChart, type Trace } from "./SeriesChart";
import { CoverageBar, Track } from "./Track";
import { findGaps } from "./series";
import {
  agoWords, bandColor, carrierLabel, coverage, describeShake, dominantBand, heightChange, isMoving,
  levelPosition, luxPosition, signalBand, tendencyWords, type SignalBand,
} from "./phone";
import {
  ApiError, api, openPhoneLive,
  type PhoneCurrent, type PhoneEvent, type PhonePoint, type PhoneSample, type PhoneSeries,
} from "./api";

// The second node, on the same sheet as the first.
//
// It shares the window selected above rather than having a range of its own: two
// nodes in one room read against two different stretches of time would invite
// comparisons between things that did not happen together.
//
// Grouped by what the readings are about rather than by sensor, each group with its
// numbers above their history: the air around the phone, the phone's own movement,
// and where it is and how well it is connected. Nine tiles in one grid and six
// charts in another read as an inventory; three named groups read as a record.

const PRESSURE: Trace[] = [
  { key: "pressure_hpa", label: "pressure", unit: "hPa", color: "--accent", digits: 2 },
];
const HEIGHT: Trace[] = [
  { key: "height_m", label: "height change", unit: "m", color: "--accent", digits: 1 },
];
const LIGHT_SOUND: Trace[] = [
  { key: "illuminance_lux", label: "light", unit: "lx", color: "--accent", digits: 0 },
  { key: "sound_rms_dbfs", label: "sound", unit: "dBFS", color: "--cyan", digits: 1, axis: "right" },
];
const NOISE: Trace[] = [
  { key: "noise_laeq_dbfs", label: "LAeq", unit: "dBFS(A)", color: "--accent", digits: 1 },
  { key: "noise_l10_dbfs", label: "L10", unit: "dBFS(A)", color: "--ink-2", digits: 1, dash: [4, 3] },
  { key: "noise_l90_dbfs", label: "L90", unit: "dBFS(A)", color: "--cyan", digits: 1, dash: [4, 3] },
];
const MOTION: Trace[] = [
  { key: "accel_rms", label: "acceleration", unit: "m/s²", color: "--accent", digits: 3 },
  { key: "gyro_rms", label: "rotation", unit: "rad/s", color: "--cyan", digits: 3, axis: "right" },
];
const RADIO: Trace[] = [
  { key: "cell_rsrp_dbm", label: "RSRP", unit: "dBm", color: "--accent", digits: 0 },
  { key: "net_rtt_ms", label: "round trip", unit: "ms", color: "--cyan", digits: 0, axis: "right" },
];

const BANDS: SignalBand[] = ["excellent", "good", "fair", "poor"];

// Everything the server derives from history, blank until the first full fetch.
const DERIVED_NONE: Omit<PhoneCurrent, keyof PhoneSample> = {
  device: "phone-01", last_fix: null, pressure_tendency_3h_hpa: null, altitude_m: null,
  altitude_source: null, sea_level_pressure_hpa: null, outlook: null, spl_offset_db: null,
  noise_laeq_dba: null, noise_lamax_dba: null, noise_l10_dba: null, noise_l90_dba: null,
};

const fmt = (v: number | null | undefined, d: number) =>
  v === null || v === undefined ? "—" : v.toFixed(d);

const secondsSince = (iso: string) => (Date.now() - Date.parse(iso)) / 1000;

const clock = (iso: string) =>
  new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });

/** One subject of the phone's record, under its name, with the instruments behind it set small. */
function Section({ index, title, sources, children }: {
  index: string; title: string; sources: string; children: ReactNode;
}) {
  const id = useId();
  return (
    <section className="phone-section" aria-labelledby={id}>
      <div className="section-head">
        <span className="section-index">{index}</span>
        <h3 id={id}>{title}</h3>
        <span className="mono-note section-sources">{sources}</span>
      </div>
      {children}
    </section>
  );
}

/** A labelled number with its unit, and whatever must be said beside it. */
function Tile({ label, value, unit, children }: {
  label: string; value: string; unit: string; children?: ReactNode;
}) {
  return (
    <div className="stack">
      <span className="label">{label}</span>
      <div>
        <span className="reading-secondary">{value}</span>
        <span className="unit">{unit}</span>
      </div>
      {children}
    </div>
  );
}

/** A chart with its label, and a note under it when the chart needs one. */
function Plot({ label, note, children }: { label: string; note?: string; children: ReactNode }) {
  return (
    <div>
      <span className="label">{label}</span>
      {children}
      {note && <span className="mono-note">{note}</span>}
    </div>
  );
}

type Props = { from: string; hours: number; theme: string };
type ChartRows = Parameters<typeof SeriesChart>[0]["points"];

export function PhonePanel({ from, hours, theme }: Props) {
  const [current, setCurrent] = useState<PhoneCurrent | null>(null);
  const [series, setSeries] = useState<PhoneSeries | null>(null);
  const [events, setEvents] = useState<PhoneEvent[]>([]);
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.phoneCurrent(), api.phoneSeries(from), api.phoneEvents(from)])
      .then(([c, s, e]) => {
        if (cancelled) return;
        setCurrent(c); setSeries(s); setEvents(e.events); setMissing(false);
      })
      .catch((e) => {
        if (cancelled) return;
        // 404 is the honest state before the node has ever reported, not a
        // failure: say so instead of rendering a page of dashes.
        if (e instanceof ApiError && e.status === 404) setMissing(true);
      });
    return () => { cancelled = true; };
  }, [from]);

  // Events do not travel on the live socket, which carries windows. Polled instead,
  // often enough that a knock on the table shows up while someone is watching.
  useEffect(() => {
    const id = setInterval(() => {
      api.phoneEvents(from).then((e) => setEvents(e.events)).catch(() => undefined);
    }, 10_000);
    return () => clearInterval(id);
  }, [from]);

  useEffect(() => {
    const ws = openPhoneLive((sample: PhoneSample) => {
      setMissing(false);
      // The live sample carries the measurements; what the server derives from
      // history — sea level, the outlook, calibration — is kept from the last
      // full fetch rather than blanked every two seconds. The last fix moves on
      // only when a window brings a new one.
      setCurrent((prev) => {
        const base = prev ?? { ...DERIVED_NONE, ...sample };
        const last_fix = sample.lat === null ? base.last_fix : {
          time: sample.time, loc_acc_m: sample.loc_acc_m, alt_msl_m: sample.alt_msl_m,
          alt_acc_m: sample.alt_acc_m, speed_ms: sample.speed_ms,
        };
        return { ...base, ...sample, last_fix };
      });

      // In the raw window every sample is a point, so it is appended as it
      // arrives and the charts move with the phone. In a bucketed window a
      // single sample is not a bucket, and appending it would draw a raw value
      // beside averages; those wait for the periodic refresh instead.
      setSeries((prev) => {
        if (!prev || prev.bucket !== "2s") return prev;
        const last = prev.points[prev.points.length - 1];
        if (last && Date.parse(last.t) >= Date.parse(sample.time)) return prev;
        const { time, window_ms: _w, firmware: _f, quality_flags: _q, ...values } = sample;
        const point: PhonePoint = { t: time, ...values };
        const cutoff = Date.now() - hours * 3600_000;
        const points = [...prev.points, point].filter((p) => Date.parse(p.t) >= cutoff);
        return { ...prev, points, count: points.length };
      });
    });
    return () => ws.close();
  }, [hours]);

  const points = series?.points ?? [];
  const gaps = useMemo(() => (series ? findGaps(series.points, series.bucket) : []), [series]);
  const cover = useMemo(() => coverage(series?.points ?? []), [series]);
  const dominant = dominantBand(cover);

  // Height change needs a reference, and the first pressure in the window is the
  // only one that means "since the start of what you are looking at".
  const heightPoints = useMemo(() => {
    const pts = series?.points ?? [];
    const ref = pts.find((p) => p.pressure_hpa !== null)?.pressure_hpa ?? null;
    return pts.map((p) => ({
      t: p.t,
      height_m: ref === null || p.pressure_hpa === null ? null : heightChange(p.pressure_hpa, ref),
    }));
  }, [series]);

  if (missing) {
    return (
      <div className="band">
        <span className="label label-plain">Phone node · Galaxy S26 · contract v2</span>
        <span className="mono-note">No samples yet. Start the node app on the phone, or run python -m psychron.simulate.</span>
      </div>
    );
  }

  const age = current ? secondsSince(current.time) : null;
  const reporting = age !== null && age < 45;
  const tendency = current?.pressure_tendency_3h_hpa ?? null;
  const calibrated = current?.spl_offset_db != null;
  const noiseValue = calibrated ? current?.noise_laeq_dba : current?.noise_laeq_dbfs;
  const rsrp = current?.cell_rsrp_dbm ?? null;
  const carrier = carrierLabel(current?.cell_rat ?? null, current?.cell_band ?? null);
  const fix = current?.last_fix ?? null;
  const fixAge = fix ? secondsSince(fix.time) : null;
  const lastEvent = events[0] ?? null;
  const largest = events.reduce<PhoneEvent | null>((m, e) => (m === null || e.pga_ms2 > m.pga_ms2 ? e : m), null);
  const bucket = series?.bucket ?? "2s";
  const chart = (traces: Trace[], data: ChartRows = points) =>
    <SeriesChart points={data} bucket={bucket} traces={traces} theme={theme} />;

  return (
    <div className="band">
      <div className="spread">
        <div>
          <span className="label label-plain">Phone node · Galaxy S26 · contract v2</span>
          <span className="mono-note">
            two-second window summaries
            {series && ` · grouped at ${series.bucket} · ${series.count} points`}
            {gaps.length > 0 && ` · ${gaps.length} gap${gaps.length > 1 ? "s" : ""}`}
          </span>
        </div>
        <span className="status">
          <span className={`pip${reporting ? " pip-on" : ""}`} />
          {current ? (reporting ? "Phone reporting" : "Phone silent") : "Phone never seen"}
          {age !== null && ` · ${Math.max(0, age).toFixed(0)} s ago`}
          {current && ` · ${current.firmware}`}
          {current?.net_via && ` · via ${current.net_via === "cell" ? "mobile data" : current.net_via}${current.net_vpn ? " + VPN" : ""}`}
          {/* The one number most likely to be misread as the room's: named for the battery. */}
          {current?.battery_temp_c != null && ` · battery ${current.battery_temp_c.toFixed(1)} °C`}
        </span>
      </div>

      <Section index="01" title="Environment" sources="barometer · ambient light · microphone">
        <div className="phone-tiles">
          <Tile label="Pressure · station" value={fmt(current?.pressure_hpa, 1)} unit="hPa">
            {/* Station pressure, at the phone's altitude. A forecast quotes it
                reduced to sea level, so 956 hPa beside a weather site's 1015 reads
                as a faulty sensor unless the panel says which one this is. */}
            <span className="mono-note">at the phone, not reduced to sea level</span>
            <span className="mono-note">
              {tendency === null
                ? "no reading 3 h ago"
                : `${tendency > 0 ? "▲ +" : tendency < 0 ? "▼ " : "= "}${tendency.toFixed(2)} hPa in 3 h · ${tendencyWords(tendency)}`}
            </span>
          </Tile>

          <Tile label="Outlook · sea level · Zambretti" value={fmt(current?.sea_level_pressure_hpa, 1)} unit="hPa">
            <span className="mono-note">
              {current?.outlook
                ? <><span className="ink">{current.outlook.text}</span> · {current.outlook.trend}</>
                : current?.sea_level_pressure_hpa == null
                  ? "needs an altitude: a GNSS fix within ±20 m, or PSYCHRON_STATION_ELEVATION_M"
                  : "needs three hours of pressure"}
            </span>
            {/* Named for what it is. A pressure rule of thumb from 1915 is right
                often enough to be interesting and no more. */}
            <span className="mono-note">
              {current?.altitude_m != null && `reduced from ${current.altitude_m.toFixed(0)} m (${current.altitude_source}) · `}
              a pocket forecaster from 1915, ~12 h
            </span>
          </Tile>

          <Tile label="Light · ambient sensor" value={fmt(current?.illuminance_lux, 0)} unit="lx">
            <div className="meter meter-scale" title="Logarithmic, 0.1 lx to 100 000 lx">
              <span style={{ width: `${luxPosition(current?.illuminance_lux ?? 0) * 100}%` }} />
            </div>
            <span className="mono-note">log scale · 0.1 – 100 000 lx</span>
          </Tile>

          <Tile label="Noise · A-weighted" value={fmt(noiseValue ?? current?.sound_rms_dbfs, 1)}
                unit={noiseValue != null ? (calibrated ? "dB(A)" : "dBFS(A)") : "dBFS"}>
            <div className="meter meter-scale">
              <span style={{ width: `${levelPosition(current?.noise_laeq_dbfs ?? current?.sound_rms_dbfs ?? -90) * 100}%` }} />
            </div>
            <span className="mono-note">
              {current?.noise_l90_dbfs != null
                ? `L10 ${fmt(calibrated ? current.noise_l10_dba : current.noise_l10_dbfs, 1)} · L90 ${fmt(calibrated ? current.noise_l90_dba : current.noise_l90_dbfs, 1)} · last minute`
                : `peak ${fmt(current?.sound_peak_dbfs, 1)} dBFS`}
            </span>
            {/* Stated on the panel, not only in the contract: the number will be
                read as loudness by anyone who does not know otherwise. */}
            <span className="mono-note">
              {calibrated
                ? `calibrated, offset ${current!.spl_offset_db!.toFixed(1)} dB · no audio leaves the phone`
                : "relative to full scale, not dB SPL · no audio leaves the phone"}
            </span>
          </Tile>
        </div>

        {points.length > 0 && (
          <div className="phone-charts">
            <Plot label="Pressure">{chart(PRESSURE)}</Plot>
            <Plot label="Height change · from the barometer"
                  note="since the start of the window · a storey is ~3 m · over hours this is weather, not height">
              {chart(HEIGHT, heightPoints)}
            </Plot>
            <Plot label="Light and sound">{chart(LIGHT_SOUND)}</Plot>
            <Plot label="Noise · LAeq, L10, L90">{chart(NOISE)}</Plot>
          </div>
        )}
      </Section>

      <Section index="02" title="Motion" sources="accelerometer · gyroscope · magnetometer">
        <div className="phone-tiles">
          <Tile label="Motion · gravity removed" value={fmt(current?.accel_rms, 2)} unit="m/s²">
            <span className="mono-note">
              rotation {fmt(current?.gyro_rms, 3)} rad/s · {current ? (isMoving(current.accel_rms, current.gyro_rms) ? "moving" : "still") : "—"}
            </span>
            <span className="mono-note">peak {fmt(current?.accel_peak, 2)} m/s² in the latest window</span>
          </Tile>

          <div className="stack phone-compass">
            <span className="label">Heading · magnetometer</span>
            <div className="row" style={{ alignItems: "center", gap: 14 }}>
              <Compass heading={current?.heading_deg ?? null} size={96} />
              <div className="stack">
                <span className="num" style={{ fontSize: 22 }}>{fmt(current?.heading_deg, 0)}°</span>
                <span className="mono-note">magnetic north</span>
                <span className="mono-note">{fmt(current?.magnetic_ut, 1)} µT</span>
              </div>
            </div>
          </div>

          <Tile label="Vibration · seismic trigger" value={String(events.length)}
                unit={events.length === 1 ? "event in the window" : "events in the window"}>
            <span className="mono-note">
              {lastEvent
                ? `last ${lastEvent.pga_ms2.toFixed(2)} m/s² · ${describeShake(lastEvent.pga_ms2)} · ${agoWords(secondsSince(lastEvent.time))} ago`
                : "none in this window"}
            </span>
            <span className="mono-note">
              {largest && events.length > 1
                ? `largest ${largest.pga_ms2.toFixed(2)} m/s² at ${clock(largest.time)}`
                : "fires when shaking stands ×4 above the background, phone lying still"}
            </span>
          </Tile>
        </div>

        <div className="phone-pair">
          {points.length > 0 ? <Plot label="Motion">{chart(MOTION)}</Plot> : <div />}
          <div>
            <span className="label">Vibration events</span>
            {events.length === 0
              ? <p className="mono-note">None in this window. A knock on the table the phone lies on is enough to raise one.</p>
              : (
                <table className="events">
                  <thead>
                    <tr><th>start</th><th>peak</th><th>for</th><th>freq</th><th>above bg</th></tr>
                  </thead>
                  <tbody>
                    {events.slice(0, 10).map((e) => (
                      <tr key={e.time}>
                        <td>{clock(e.time)}</td>
                        <td>{e.pga_ms2.toFixed(2)} m/s²</td>
                        <td>{(e.duration_ms / 1000).toFixed(1)} s</td>
                        <td>{e.freq_hz === null ? "—" : `${e.freq_hz.toFixed(0)} Hz`}</td>
                        <td>×{e.sta_lta.toFixed(1)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
          </div>
        </div>
      </Section>

      <Section index="03" title="Position and coverage" sources="GNSS · modem · broker link">
        <div className="phone-tiles">
          <Tile label="Altitude · GNSS" value={fmt(fix?.alt_msl_m, 0)} unit="m">
            <span className="mono-note">
              {fix === null
                ? "no fix in the last day"
                : `above sea level · ±${fmt(fix.alt_acc_m ?? fix.loc_acc_m, 0)} m${fixAge! > 10 ? ` · fix ${agoWords(fixAge!)} old` : ""}`}
            </span>
            <span className="mono-note">
              {fix?.speed_ms != null && `${(fix.speed_ms * 3.6).toFixed(1)} km/h · `}
              {fix !== null && `±${fmt(fix.loc_acc_m, 0)} m across · `}coordinates stay on the track
            </span>
          </Tile>

          <Tile label="Cellular · serving cell" value={fmt(rsrp, 0)} unit="dBm">
            <span className="mono-note">
              {rsrp === null
                ? "no serving cell reported"
                : <><span style={{ color: bandColor(signalBand(rsrp)) }}>{signalBand(rsrp)}</span>{carrier && ` · ${carrier}`}</>}
            </span>
            <span className="mono-note">
              RSRQ {fmt(current?.cell_rsrq_db, 0)} dB · SINR {fmt(current?.cell_sinr_db, 0)} dB
              {current?.net_rtt_ms != null && ` · broker ${current.net_rtt_ms.toFixed(0)} ms`}
            </span>
          </Tile>

          <Tile label="Coverage · over the window"
                value={dominant ? (dominant.share * 100).toFixed(0) : "—"}
                unit={dominant ? `% ${dominant.band}` : "%"}>
            <CoverageBar coverage={cover} />
            {/* The split, when there is one to read: a single band is already the number above. */}
            {BANDS.filter((b) => cover.shares[b] > 0).length > 1 && (
              <span className="mono-note">
                {BANDS.filter((b) => cover.shares[b] > 0)
                  .map((b) => `${b} ${(cover.shares[b] * 100).toFixed(0)} %`).join(" · ")}
              </span>
            )}
            <span className="mono-note">
              {cover.carriers.length
                ? cover.carriers.slice(0, 3).map((c) => `${c.label} ${(c.share * 100).toFixed(0)} %`).join(" · ")
                : "no carrier reported"}
            </span>
            <span className="mono-note">
              {cover.changes} carrier change{cover.changes === 1 ? "" : "s"}
              {cover.missing > 0 && ` · ${cover.missing} point${cover.missing === 1 ? "" : "s"} without a cell`}
            </span>
          </Tile>
        </div>

        <div className="phone-pair">
          <div>
            <span className="label">Track · coloured by signal</span>
            <Track points={points} accuracy={fix?.loc_acc_m ?? null} />
          </div>
          {points.length > 0 ? <Plot label="Signal and latency">{chart(RADIO)}</Plot> : <div />}
        </div>
      </Section>
    </div>
  );
}

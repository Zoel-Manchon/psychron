import { useEffect, useMemo, useState } from "react";
import { Compass } from "./Compass";
import { findGaps } from "./Chart";
import { SeriesChart, type Trace } from "./SeriesChart";
import { Track, signalBand } from "./Track";
import {
  ApiError, api, openPhoneLive,
  type PhoneCurrent, type PhoneEvent, type PhonePoint, type PhoneSample, type PhoneSeries,
} from "./api";

// The second node, on the same sheet as the first.
//
// It shares the window selected above rather than having a range of its own: two
// nodes in one room read against two different stretches of time would invite
// comparisons between things that did not happen together.

const PRESSURE: Trace[] = [
  { key: "pressure_hpa", label: "pressure", unit: "hPa", color: "--accent", digits: 2 },
];
const LIGHT_SOUND: Trace[] = [
  { key: "illuminance_lux", label: "light", unit: "lx", color: "--accent", digits: 0 },
  { key: "sound_rms_dbfs", label: "sound", unit: "dBFS", color: "--cyan", digits: 1, axis: "right" },
];
const MOTION: Trace[] = [
  { key: "accel_rms", label: "acceleration", unit: "m/s²", color: "--accent", digits: 3 },
  { key: "gyro_rms", label: "rotation", unit: "rad/s", color: "--cyan", digits: 3, axis: "right" },
];
const NOISE: Trace[] = [
  { key: "noise_laeq_dbfs", label: "LAeq", unit: "dBFS(A)", color: "--accent", digits: 1 },
  { key: "noise_l10_dbfs", label: "L10", unit: "dBFS(A)", color: "--ink-2", digits: 1, dash: [4, 3] },
  { key: "noise_l90_dbfs", label: "L90", unit: "dBFS(A)", color: "--cyan", digits: 1, dash: [4, 3] },
];
const RADIO: Trace[] = [
  { key: "cell_rsrp_dbm", label: "RSRP", unit: "dBm", color: "--accent", digits: 0 },
  { key: "net_rtt_ms", label: "round trip", unit: "ms", color: "--cyan", digits: 0, axis: "right" },
];
const HEIGHT: Trace[] = [
  { key: "height_m", label: "height change", unit: "m", color: "--accent", digits: 1 },
];

const fmt = (v: number | null | undefined, d: number) =>
  v === null || v === undefined ? "—" : v.toFixed(d);

/** Pressure tendency in the words forecasters use.
 *
 * Bands from the UK Met Office's shipping-forecast convention for change over
 * three hours. Borrowed rather than invented, so "falling quickly" means the same
 * thing here as it does to anyone who has read a synoptic chart.
 */
function tendencyWords(delta: number): string {
  const a = Math.abs(delta);
  if (a < 0.1) return "steady";
  const dir = delta > 0 ? "rising" : "falling";
  if (a <= 1.5) return `${dir} slowly`;
  if (a <= 3.5) return dir;
  if (a <= 6.0) return `${dir} quickly`;
  return `${dir} very rapidly`;
}

/** Position on a logarithmic scale from 0.1 lx to 100 000 lx.
 *
 * Illuminance spans six orders of magnitude between a dark room and noon sun. On
 * a linear meter every indoor reading would sit in the first pixel.
 */
const luxPosition = (lux: number) =>
  Math.min(1, Math.max(0, (Math.log10(Math.max(lux, 0.1)) + 1) / 6));

/** Metres climbed between two pressures, the standard-atmosphere formula the
 * backend uses for sea level. Relative only: over hours the weather moves the
 * pressure by more than any building does, and the chart says so. */
const heightChange = (hpa: number, refHpa: number) =>
  44330.77 * (1 - (hpa / refHpa) ** (1 / 5.25588));

const ago = (iso: string) => {
  const s = Math.max(0, (Date.now() - Date.parse(iso)) / 1000);
  if (s < 90) return `${s.toFixed(0)} s ago`;
  if (s < 5400) return `${(s / 60).toFixed(0)} min ago`;
  return `${(s / 3600).toFixed(1)} h ago`;
};

const clock = (iso: string) =>
  new Date(iso).toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit", second: "2-digit" });

type Props = { from: string; hours: number; theme: string };

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

  useEffect(() => {
    const ws = openPhoneLive((sample: PhoneSample) => {
      setMissing(false);
      // The live sample carries the measurements; what the server derives from
      // history — sea level, the outlook, calibration — is kept from the last
      // full fetch rather than blanked every two seconds.
      setCurrent((prev) => ({
        ...(prev ?? {
          device: "phone-01", pressure_tendency_3h_hpa: null, altitude_m: null,
          altitude_source: null, sea_level_pressure_hpa: null, outlook: null,
          spl_offset_db: null, noise_laeq_dba: null, noise_lamax_dba: null,
          noise_l10_dba: null, noise_l90_dba: null,
        }),
        ...sample,
      }) as PhoneCurrent);

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

  const gaps = useMemo(() => (series ? findGaps(series.points, series.bucket) : []), [series]);

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

  const age = current ? (Date.now() - Date.parse(current.time)) / 1000 : null;
  const reporting = age !== null && age < 45;
  const tendency = current?.pressure_tendency_3h_hpa ?? null;
  const moving = (current?.accel_rms ?? 0) > 0.25 || (current?.gyro_rms ?? 0) > 0.2;
  const calibrated = current?.spl_offset_db != null;
  const noiseValue = calibrated ? current?.noise_laeq_dba : current?.noise_laeq_dbfs;
  const noiseUnit = calibrated ? "dB(A)" : "dBFS(A)";
  const rsrp = current?.cell_rsrp_dbm ?? null;
  const technology = current?.cell_rat === "nr" ? "5G NR" : current?.cell_rat === "lte" ? "LTE" : null;
  const band = current?.cell_band != null
    ? (current.cell_rat === "nr" ? ` n${current.cell_band}` : ` B${current.cell_band}`) : "";
  const hasSeries = series !== null && series.points.length > 0;
  const hasFixes = hasSeries && series!.points.some((p) => p.lat !== null);

  return (
    <div className="band">
      <div className="spread" style={{ marginBottom: 12 }}>
        <div>
          <span className="label label-plain">Phone node · Galaxy S26 · contract v2</span>
          <span className="mono-note">
            {missing
              ? "No samples yet. Start the node app on the phone, or run python -m psychron.simulate."
              : <>two-second window summaries
                  {series && ` · grouped at ${series.bucket} · ${series.count} points`}
                  {gaps.length > 0 && ` · ${gaps.length} gap${gaps.length > 1 ? "s" : ""}`}
                </>}
          </span>
        </div>
        <span className="status">
          <span className={`pip${reporting ? " pip-on" : ""}`} />
          {current ? (reporting ? "Phone reporting" : "Phone silent") : "Phone never seen"}
          {age !== null && ` · ${Math.max(0, age).toFixed(0)} s ago`}
          {current && ` · ${current.firmware}`}
          {current?.net_via && ` · via ${current.net_via === "cell" ? "mobile data" : current.net_via}${current.net_vpn ? " + VPN" : ""}`}
        </span>
      </div>

      {!missing && (
        <>
          <div className="phone-tiles">
            <div className="stack">
              <span className="label">Pressure · barometer · station</span>
              <div>
                <span className="reading-secondary">{fmt(current?.pressure_hpa, 1)}</span>
                <span className="unit">hPa</span>
              </div>
              {/* Station pressure, at the phone's altitude. A forecast quotes it
                  reduced to sea level, so 956 hPa beside a weather site's 1015 reads
                  as a faulty sensor unless the panel says which one this is. */}
              <span className="mono-note">at the phone, not reduced to sea level</span>
              <span className="mono-note">
                {tendency === null
                  ? "no reading 3 h ago"
                  : `${tendency > 0 ? "▲ +" : tendency < 0 ? "▼ " : "= "}${tendency.toFixed(2)} hPa in 3 h · ${tendencyWords(tendency)}`}
              </span>
            </div>

            <div className="stack">
              <span className="label">Outlook · sea level · Zambretti</span>
              <div>
                <span className="reading-secondary">{fmt(current?.sea_level_pressure_hpa, 1)}</span>
                <span className="unit">hPa</span>
              </div>
              <span className="mono-note">
                {current?.outlook
                  ? <><span className="ink">{current.outlook.text}</span> · {current.outlook.trend}</>
                  : current?.sea_level_pressure_hpa == null
                    ? "needs an altitude: a GNSS fix, or PSYCHRON_STATION_ELEVATION_M"
                    : "needs three hours of pressure"}
              </span>
              {/* Named for what it is. A pressure rule of thumb from 1915 is right
                  often enough to be interesting and no more. */}
              <span className="mono-note">
                {current?.altitude_m != null && `reduced from ${current.altitude_m.toFixed(0)} m (${current.altitude_source}) · `}
                a pocket forecaster from 1915, ~12 h
              </span>
            </div>

            <div className="stack">
              <span className="label">Light · ambient sensor</span>
              <div>
                <span className="reading-secondary">{fmt(current?.illuminance_lux, 0)}</span>
                <span className="unit">lx</span>
              </div>
              <div className="meter meter-scale" title="Logarithmic, 0.1 lx to 100 000 lx">
                <span style={{ width: `${luxPosition(current?.illuminance_lux ?? 0) * 100}%` }} />
              </div>
              <span className="mono-note">log scale · 0.1 – 100 000 lx</span>
            </div>

            <div className="stack">
              <span className="label">Noise · A-weighted · microphone</span>
              <div>
                <span className="reading-secondary">{fmt(noiseValue ?? current?.sound_rms_dbfs, 1)}</span>
                <span className="unit">{noiseValue != null ? noiseUnit : "dBFS"}</span>
              </div>
              <div className="meter meter-scale">
                <span style={{ width: `${Math.max(0, Math.min(1, ((current?.noise_laeq_dbfs ?? current?.sound_rms_dbfs ?? -90) + 90) / 90)) * 100}%` }} />
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
            </div>

            <div className="stack">
              <span className="label">Motion · accelerometer + gyroscope</span>
              <div>
                <span className="reading-secondary">{fmt(current?.accel_rms, 2)}</span>
                <span className="unit">m/s²</span>
              </div>
              <span className="mono-note">
                rotation {fmt(current?.gyro_rms, 3)} rad/s · {current ? (moving ? "moving" : "still") : "—"}
              </span>
              <span className="mono-note">
                {events.length > 0
                  ? `${events.length} vibration event${events.length > 1 ? "s" : ""} · last ${fmt(events[0].pga_ms2, 2)} m/s², ${ago(events[0].time)}`
                  : "no vibration events in this window"}
              </span>
            </div>

            <div className="stack">
              <span className="label">Cellular · serving cell</span>
              <div>
                <span className="reading-secondary">{fmt(rsrp, 0)}</span>
                <span className="unit">dBm</span>
              </div>
              <span className="mono-note">
                {rsrp === null
                  ? "no serving cell reported"
                  : <><span style={{ color: signalBand(rsrp).color }}>{signalBand(rsrp).word}</span>
                      {technology && ` · ${technology}${band}`}</>}
              </span>
              <span className="mono-note">
                RSRQ {fmt(current?.cell_rsrq_db, 0)} dB · SINR {fmt(current?.cell_sinr_db, 0)} dB
                {current?.net_rtt_ms != null && ` · broker ${current.net_rtt_ms.toFixed(0)} ms`}
              </span>
            </div>

            <div className="stack">
              <span className="label">Location · GNSS</span>
              <div>
                <span className="reading-secondary">{fmt(current?.alt_msl_m, 0)}</span>
                <span className="unit">m</span>
              </div>
              <span className="mono-note">
                {current?.lat == null
                  ? "no fix in the latest window"
                  : `above sea level · ±${fmt(current.alt_acc_m, 0)} m vertical · ±${fmt(current.loc_acc_m, 0)} m horizontal`}
              </span>
              <span className="mono-note">
                {current?.speed_ms != null ? `${(current.speed_ms * 3.6).toFixed(1)} km/h · ` : ""}
                coordinates not shown; track below
              </span>
            </div>

            <div className="stack phone-compass">
              <span className="label">Heading · magnetometer</span>
              <div className="row" style={{ alignItems: "center", gap: 14 }}>
                <Compass heading={current?.heading_deg ?? null} size={108} />
                <div className="stack">
                  <span className="num" style={{ fontSize: 22 }}>{fmt(current?.heading_deg, 0)}°</span>
                  <span className="mono-note">magnetic north</span>
                  <span className="mono-note">{fmt(current?.magnetic_ut, 1)} µT</span>
                </div>
              </div>
            </div>

            <div className="stack">
              <span className="label">Battery · the phone itself</span>
              <div>
                <span className="reading-secondary">{fmt(current?.battery_temp_c, 1)}</span>
                <span className="unit">°C</span>
              </div>
              {/* The one number here most likely to be misread: it follows the
                  charger and the screen, not the air in the room. */}
              <span className="mono-note">device temperature, not the room</span>
            </div>
          </div>

          {hasSeries && (
            <div className="phone-charts">
              <div>
                <span className="label">Pressure</span>
                <SeriesChart points={series!.points} bucket={series!.bucket}
                             traces={PRESSURE} theme={theme} />
              </div>
              <div>
                <span className="label">Height change · from the barometer</span>
                <SeriesChart points={heightPoints} bucket={series!.bucket}
                             traces={HEIGHT} theme={theme} />
                <span className="mono-note">since the start of the window · a storey is ~3 m · over hours this is weather, not height</span>
              </div>
              <div>
                <span className="label">Light and sound</span>
                <SeriesChart points={series!.points} bucket={series!.bucket}
                             traces={LIGHT_SOUND} theme={theme} />
              </div>
              <div>
                <span className="label">Noise · LAeq, L10, L90</span>
                <SeriesChart points={series!.points} bucket={series!.bucket}
                             traces={NOISE} theme={theme} />
              </div>
              <div>
                <span className="label">Motion</span>
                <SeriesChart points={series!.points} bucket={series!.bucket}
                             traces={MOTION} theme={theme} />
              </div>
              <div>
                <span className="label">Signal and latency</span>
                <SeriesChart points={series!.points} bucket={series!.bucket}
                             traces={RADIO} theme={theme} />
              </div>
            </div>
          )}

          <div className="phone-lower">
            <div>
              <span className="label">Track · coverage</span>
              {hasFixes ? <Track points={series!.points} />
                : <p className="mono-note">No GNSS fixes in this window.</p>}
            </div>
            <div>
              <span className="label">Vibration events</span>
              {events.length === 0
                ? <p className="mono-note">None in this window. The phone reports one when the short-term shaking stands well above the background while it is lying still.</p>
                : (
                  <table className="events">
                    <thead>
                      <tr><th>start</th><th>peak</th><th>for</th><th>freq</th><th>above bg</th></tr>
                    </thead>
                    <tbody>
                      {events.slice(0, 12).map((e) => (
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
        </>
      )}
    </div>
  );
}

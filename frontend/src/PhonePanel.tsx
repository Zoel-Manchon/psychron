import { useEffect, useMemo, useState } from "react";
import { Compass } from "./Compass";
import { findGaps } from "./Chart";
import { SeriesChart, type Trace } from "./SeriesChart";
import {
  ApiError, api, openPhoneLive,
  type PhoneCurrent, type PhonePoint, type PhoneSample, type PhoneSeries,
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

type Props = { from: string; hours: number; theme: string };

export function PhonePanel({ from, hours, theme }: Props) {
  const [current, setCurrent] = useState<PhoneCurrent | null>(null);
  const [series, setSeries] = useState<PhoneSeries | null>(null);
  const [missing, setMissing] = useState(false);

  useEffect(() => {
    let cancelled = false;
    Promise.all([api.phoneCurrent(), api.phoneSeries(from)])
      .then(([c, s]) => { if (!cancelled) { setCurrent(c); setSeries(s); setMissing(false); } })
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
      setCurrent((prev) => ({
        ...(prev ?? { device: "phone-01", pressure_tendency_3h_hpa: null }),
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

  const age = current ? (Date.now() - Date.parse(current.time)) / 1000 : null;
  const reporting = age !== null && age < 30;
  const tendency = current?.pressure_tendency_3h_hpa ?? null;
  const moving = (current?.accel_rms ?? 0) > 0.25 || (current?.gyro_rms ?? 0) > 0.2;

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
              <span className="mono-note">not reduced to sea level</span>
              <span className="mono-note">
                {tendency === null
                  ? "no reading 3 h ago"
                  : `${tendency > 0 ? "▲ +" : tendency < 0 ? "▼ " : "= "}${tendency.toFixed(2)} hPa in 3 h · ${tendencyWords(tendency)}`}
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
              <span className="label">Sound · microphone</span>
              <div>
                <span className="reading-secondary">{fmt(current?.sound_rms_dbfs, 1)}</span>
                <span className="unit">dBFS</span>
              </div>
              <div className="meter meter-scale">
                <span style={{ width: `${Math.max(0, Math.min(1, ((current?.sound_rms_dbfs ?? -90) + 90) / 90)) * 100}%` }} />
              </div>
              {/* Stated on the panel, not only in the contract: the number will be
                  read as loudness by anyone who does not know otherwise. */}
              <span className="mono-note">
                peak {fmt(current?.sound_peak_dbfs, 1)} · relative level, not dB SPL · no audio leaves the phone
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
              <span className="mono-note">gravity removed · RMS over the window</span>
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

          {series && series.points.length > 0 && (
            <div className="phone-charts">
              <div>
                <span className="label">Pressure</span>
                <SeriesChart points={series.points} bucket={series.bucket}
                             traces={PRESSURE} theme={theme} />
              </div>
              <div>
                <span className="label">Light and sound</span>
                <SeriesChart points={series.points} bucket={series.bucket}
                             traces={LIGHT_SOUND} theme={theme} />
              </div>
              <div>
                <span className="label">Motion</span>
                <SeriesChart points={series.points} bucket={series.bucket}
                             traces={MOTION} theme={theme} />
              </div>
            </div>
          )}
        </>
      )}
    </div>
  );
}

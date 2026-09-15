// The phone panel's arithmetic and wording, apart from the components that draw it.
//
// Pure and self-contained — no React, no DOM, no import from the API client — so
// that `npm test` runs it on plain Node, and so every word the panel puts beside a
// number is decided somewhere a test can read it.

/** Pressure tendency in the words forecasters use.
 *
 * Bands from the UK Met Office's shipping-forecast convention for change over
 * three hours. Borrowed rather than invented, so "falling quickly" means the same
 * thing here as it does to anyone who has read a synoptic chart.
 */
export function tendencyWords(delta: number): string {
  const a = Math.abs(delta);
  if (a < 0.1) return "steady";
  const dir = delta > 0 ? "rising" : "falling";
  if (a <= 1.5) return `${dir} slowly`;
  if (a <= 3.5) return dir;
  if (a <= 6.0) return `${dir} quickly`;
  return `${dir} very rapidly`;
}

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

/** Position on a logarithmic scale from 0.1 lx to 100 000 lx.
 *
 * Illuminance spans six orders of magnitude between a dark room and noon sun. On
 * a linear meter every indoor reading would sit in the first pixel.
 */
export const luxPosition = (lux: number) => clamp01((Math.log10(Math.max(lux, 0.1)) + 1) / 6);

/** A level in dBFS on a meter from -90 to 0: the microphone's usable range. */
export const levelPosition = (dbfs: number) => clamp01((dbfs + 90) / 90);

/** Metres climbed between two pressures, the standard-atmosphere formula the
 * backend uses for sea level. Relative only: over hours the weather moves the
 * pressure by more than any building does, and the chart says so. */
export const heightChange = (hpa: number, refHpa: number) =>
  44330.77 * (1 - (hpa / refHpa) ** (1 / 5.25588));

/** The same rule the phone's own screen uses, so the two never disagree. */
export const isMoving = (accelRms: number | null, gyroRms: number | null) =>
  (accelRms ?? 0) > 0.25 || (gyroRms ?? 0) > 0.2;

/** A peak acceleration as a word, the same words the phone's screen uses. */
export function describeShake(pgaMs2: number): string {
  if (pgaMs2 < 0.05) return "faint";
  if (pgaMs2 < 0.2) return "light";
  if (pgaMs2 < 1.0) return "strong";
  return "violent";
}

/** A duration in the coarsest unit that still reads exactly. */
export function agoWords(seconds: number): string {
  const s = Math.max(0, seconds);
  if (s < 90) return `${s.toFixed(0)} s`;
  if (s < 5400) return `${(s / 60).toFixed(0)} min`;
  return `${(s / 3600).toFixed(1)} h`;
}

export type SignalBand = "excellent" | "good" | "fair" | "poor";

/** RSRP in the bands most operators and field tools use for LTE and NR. */
export function signalBand(rsrp: number): SignalBand {
  if (rsrp >= -80) return "excellent";
  if (rsrp >= -90) return "good";
  if (rsrp >= -100) return "fair";
  return "poor";
}

/** The colour a band is drawn in: cyan above fair, ink at fair, the accent below. */
export const bandColor = (band: SignalBand) =>
  band === "poor" ? "var(--accent)" : band === "fair" ? "var(--ink-2)" : "var(--cyan)";

/** "LTE B7", "5G NR n78": the way a field tool names a carrier. */
export function carrierLabel(rat: string | null, band: number | null): string | null {
  if (rat === null) return null;
  const tech = rat === "nr" ? "5G NR" : "LTE";
  return band === null ? tech : `${tech} ${rat === "nr" ? "n" : "B"}${band}`;
}

type Radio = { cell_rat: string | null; cell_band: number | null; cell_rsrp_dbm: number | null };

export type Coverage = {
  /** Points with a serving cell. */
  counted: number;
  /** Points without one: no service, or no permission to see it. */
  missing: number;
  /** Share of counted points in each band, 0–1. */
  shares: Record<SignalBand, number>;
  /** Carriers by share of counted points, largest first. */
  carriers: { label: string; share: number }[];
  /** Times the carrier differed from the one before it: handovers the phone made. */
  changes: number;
};

/** How good the signal was over a window, and on what. */
export function coverage(points: Radio[]): Coverage {
  const shares: Record<SignalBand, number> = { excellent: 0, good: 0, fair: 0, poor: 0 };
  const carriers = new Map<string, number>();
  let counted = 0;
  let changes = 0;
  let previous: string | null = null;
  for (const p of points) {
    if (p.cell_rsrp_dbm === null) continue;
    counted++;
    shares[signalBand(p.cell_rsrp_dbm)]++;
    const label = carrierLabel(p.cell_rat, p.cell_band);
    if (label === null) continue;
    carriers.set(label, (carriers.get(label) ?? 0) + 1);
    if (previous !== null && label !== previous) changes++;
    previous = label;
  }
  if (counted > 0) for (const k of Object.keys(shares) as SignalBand[]) shares[k] /= counted;
  return {
    counted,
    missing: points.length - counted,
    shares,
    carriers: [...carriers.entries()]
      .map(([label, n]) => ({ label, share: counted ? n / counted : 0 }))
      .sort((a, b) => b.share - a.share),
    changes,
  };
}

/** The band the window spent most of its time in: "72 % good" says more than
 * "0 % good or better" about a phone that sat in a poor spot all afternoon. */
export function dominantBand(c: Coverage): { band: SignalBand; share: number } | null {
  if (c.counted === 0) return null;
  const order: SignalBand[] = ["excellent", "good", "fair", "poor"];
  const band = order.reduce((best, b) => (c.shares[b] > c.shares[best] ? b : best), order[0]);
  return { band, share: c.shares[band] };
}

export type Fix = { lat: number | null; lon: number | null; cell_rsrp_dbm: number | null; loc_acc_m?: number | null };

export type Projected = {
  /** Metres east and north of the first fix drawn. */
  xy: { x: number; y: number; rsrp: number | null }[];
  /** Metres travelled, counting only moves larger than the fixes are sure of. */
  distance: number;
  /** Fixes left out for being less sure of themselves than [maxAccuracyM]. */
  dropped: number;
};

/**
 * Fixes as metres from the first, on an equirectangular projection around it: over
 * a few kilometres the error is a fraction of a percent, far inside a fix's accuracy.
 *
 * Two things a raw path gets wrong about a phone lying still. A position from the
 * network provider can be a hundred metres out, and one of them draws an excursion
 * that never happened, so fixes worse than [maxAccuracyM] are left out. And GNSS
 * wanders by its own accuracy from fix to fix, so summing every step turns a phone
 * on a desk into a hundred-metre walk; distance accrues only once the phone is
 * further from where it last moved to than either fix's error circle.
 */
export function project(points: Fix[], maxAccuracyM = 25): Projected {
  const located = points.filter((p): p is Fix & { lat: number; lon: number } => p.lat !== null && p.lon !== null);
  const fixes = located.filter((p) => p.loc_acc_m == null || p.loc_acc_m <= maxAccuracyM);
  const dropped = located.length - fixes.length;
  if (fixes.length === 0) return { xy: [], distance: 0, dropped };
  const lat0 = fixes[0].lat;
  const lon0 = fixes[0].lon;
  const kx = 111_320 * Math.cos((lat0 * Math.PI) / 180);
  const ky = 110_574;
  const xy = fixes.map((f) => ({ x: (f.lon - lon0) * kx, y: (f.lat - lat0) * ky, rsrp: f.cell_rsrp_dbm }));

  let distance = 0;
  let anchor = 0;
  for (let i = 1; i < xy.length; i++) {
    const step = Math.hypot(xy[i].x - xy[anchor].x, xy[i].y - xy[anchor].y);
    const sure = Math.max(fixes[anchor].loc_acc_m ?? 0, fixes[i].loc_acc_m ?? 0);
    if (step > sure) {
      distance += step;
      anchor = i;
    }
  }
  return { xy, distance, dropped };
}

/** A scale bar length that reads as a round number. */
export function niceLength(metres: number): number {
  let best = 1;
  for (let exp = 0; exp < 7; exp++) {
    for (const s of [1, 2, 5]) {
      const v = s * 10 ** exp;
      if (v <= metres) best = v;
    }
  }
  return best;
}

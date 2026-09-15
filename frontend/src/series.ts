// Time series arithmetic shared by every chart on the panel. Pure, so it runs under
// `npm test` on plain Node.

export type Gap = { from: number; to: number };

// Parsed from the label rather than looked up, because the two nodes use
// different ladders: "3s" and "1m" for the ESP32, "2s", "30s" and "2m" for the
// phone. A lookup table that knew only the first set would treat every phone
// bucket as a day and never find a gap in it.
export const secondsPerBucket = (bucket: string): number => {
  const m = /^(\d+)\s*([smhd])$/.exec(bucket.trim());
  if (!m) return 86400;
  return Number(m[1]) * { s: 1, m: 60, h: 3600, d: 86400 }[m[2] as "s" | "m" | "h" | "d"];
};

/** Two and a half buckets, not one: a bucket boundary landing a moment late is
 * jitter, and calling that an outage would fill the strip with noise. */
const isGap = (from: number, to: number, expected: number) => to - from > expected * 2.5;

/** Stretches of the window where no reading exists, in epoch seconds.
 *
 * One function for the chart and the caption beneath it, which must agree about
 * how many there are. Two implementations of "what counts as a gap" would drift,
 * and the one thing this panel promises is that an outage is never smoothed over.
 */
export function findGaps(points: { t: string }[], bucket: string): Gap[] {
  const expected = secondsPerBucket(bucket);
  const gaps: Gap[] = [];
  let prev: number | null = null;
  for (const p of points) {
    const t = Date.parse(p.t) / 1000;
    if (prev !== null && isGap(prev, t, expected)) gaps.push({ from: prev, to: t });
    prev = t;
  }
  return gaps;
}

/** Columns for a canvas chart: times in epoch seconds, then one column per key.
 *
 * Gaps stay gaps. A null is inserted one bucket after the last reading before an
 * outage, and a line chart breaks on null, so a missing stretch is drawn as absent
 * rather than as a straight line between two distant points — an interpolated
 * outage looks exactly like a stable room. Anything that is not a number is null.
 */
export function columns(points: ({ t: string } & Record<string, unknown>)[], bucket: string,
                        keys: string[]): [number[], ...(number | null)[][]] {
  const expected = secondsPerBucket(bucket);
  const xs: number[] = [];
  const cols: (number | null)[][] = keys.map(() => []);
  let prev: number | null = null;
  for (const p of points) {
    const t = Date.parse(p.t) / 1000;
    if (prev !== null && isGap(prev, t, expected)) {
      xs.push(prev + expected);
      cols.forEach((c) => c.push(null));
    }
    xs.push(t);
    keys.forEach((k, i) => {
      const v = p[k];
      cols[i].push(typeof v === "number" ? v : null);
    });
    prev = t;
  }
  return [xs, ...cols];
}

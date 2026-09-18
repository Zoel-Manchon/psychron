// Time series arithmetic shared by every chart on the panel. Pure, so it runs under
// `npm test` on plain Node.

export type Gap = { from: number; to: number };

/** The range that was asked for, which is not the range that came back. */
export type Span = { from: string; to: string };

const edgesOf = (span?: Span) =>
  span ? { from: Date.parse(span.from) / 1000, to: Date.parse(span.to) / 1000 } : null;

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
export function findGaps(points: { t: string }[], bucket: string, span?: Span): Gap[] {
  const expected = secondsPerBucket(bucket);
  const edges = edgesOf(span);
  const gaps: Gap[] = [];
  // Starting from the window's own edge, not from the first reading. A node that
  // was already dead when the window opened leaves no pair of readings for a gap
  // to sit between, so the emptiness in front of the first one is the one outage
  // this would otherwise never find — and the morning after a power cut it is by
  // far the largest. Without it the panel can say "no gaps" over a day that is
  // three per cent complete, which are the two halves of one contradiction.
  let prev: number | null = edges && edges.from;
  for (const p of points) {
    const t = Date.parse(p.t) / 1000;
    if (prev !== null && isGap(prev, t, expected)) gaps.push({ from: prev, to: t });
    prev = t;
  }
  if (edges && prev !== null && isGap(prev, edges.to, expected)) {
    gaps.push({ from: prev, to: edges.to });
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
                        keys: string[], span?: Span): [number[], ...(number | null)[][]] {
  const expected = secondsPerBucket(bucket);
  const edges = edgesOf(span);
  const xs: number[] = [];
  const cols: (number | null)[][] = keys.map(() => []);
  const blank = (t: number) => { xs.push(t); cols.forEach((c) => c.push(null)); };
  // Given the window, the columns span it whether or not there is data at both
  // ends, because a chart that ranges over its data alone draws one live hour as
  // though it were the day that was asked for.
  let prev: number | null = edges && edges.from;
  let empty = true;
  for (const p of points) {
    const t = Date.parse(p.t) / 1000;
    if (prev !== null && isGap(prev, t, expected)) blank(empty ? prev : prev + expected);
    xs.push(t);
    keys.forEach((k, i) => {
      const v = p[k];
      cols[i].push(typeof v === "number" ? v : null);
    });
    prev = t;
    empty = false;
  }
  if (edges) {
    if (empty) blank(edges.from);
    if (empty || isGap(prev as number, edges.to, expected)) blank(edges.to);
  }
  return [xs, ...cols];
}

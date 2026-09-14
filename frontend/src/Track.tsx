// Where the phone went, drawn relative to where it started — and nothing else.
//
// No base map, on purpose, and not only because the CSP forbids fetching tiles.
// The coordinates are precise enough to find a front door, and this panel ends up
// in screenshots and screen recordings. A shape measured in metres from its own
// start says everything the track is for — how far, which way, where the signal
// dropped — without saying where.

type Fix = {
  t: string;
  lat: number | null;
  lon: number | null;
  cell_rsrp_dbm: number | null;
};

type Props = { points: Fix[] };

const W = 640;
const H = 280;
const PAD = 26;

/** RSRP in the bands most operators and field tools use for LTE and NR. */
export function signalBand(rsrp: number): { word: string; color: string } {
  if (rsrp >= -80) return { word: "excellent", color: "var(--cyan)" };
  if (rsrp >= -90) return { word: "good", color: "var(--cyan)" };
  if (rsrp >= -100) return { word: "fair", color: "var(--ink-2)" };
  return { word: "poor", color: "var(--accent)" };
}

/** A scale bar length that reads as a round number. */
function niceLength(metres: number): number {
  const steps = [1, 2, 5];
  let best = 1;
  for (let exp = 0; exp < 7; exp++) {
    for (const s of steps) {
      const v = s * 10 ** exp;
      if (v <= metres) best = v;
    }
  }
  return best;
}

export function Track({ points }: Props) {
  const fixes = points.filter((p): p is Fix & { lat: number; lon: number } =>
    p.lat !== null && p.lon !== null);

  if (fixes.length < 2) {
    return <p className="mono-note">No GNSS fixes in this window. The phone reports location once it is granted and has a fix.</p>;
  }

  // An equirectangular projection around the first fix: over a few kilometres the
  // error is a fraction of a percent, far inside the accuracy of the fixes.
  const lat0 = fixes[0].lat;
  const lon0 = fixes[0].lon;
  const kx = 111_320 * Math.cos((lat0 * Math.PI) / 180);
  const ky = 110_574;
  const xy = fixes.map((f) => ({ x: (f.lon - lon0) * kx, y: (f.lat - lat0) * ky, rsrp: f.cell_rsrp_dbm }));

  const xs = xy.map((p) => p.x);
  const ys = xy.map((p) => p.y);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  // Equal scale on both axes, or a walk north looks like a walk east. At least
  // twenty metres across, so a phone lying still is a dot and not a scribble.
  const span = Math.max(maxX - minX, maxY - minY, 20);
  const scale = Math.min((W - 2 * PAD) / span, (H - 2 * PAD) / span);
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  const px = (x: number) => W / 2 + (x - cx) * scale;
  const py = (y: number) => H / 2 - (y - cy) * scale;

  const path = xy.map((p, i) => `${i ? "L" : "M"}${px(p.x).toFixed(1)} ${py(p.y).toFixed(1)}`).join(" ");
  const bar = niceLength(span / 4);
  const distance = xy.slice(1).reduce((d, p, i) => d + Math.hypot(p.x - xy[i].x, p.y - xy[i].y), 0);
  const last = xy[xy.length - 1];

  return (
    <div className="stack">
      <svg viewBox={`0 0 ${W} ${H}`} className="track" role="img"
           aria-label={`Track of ${fixes.length} fixes over ${distance.toFixed(0)} metres`}>
        <rect x="0.5" y="0.5" width={W - 1} height={H - 1} fill="none" stroke="var(--rule-faint)" />
        <path d={path} fill="none" stroke="var(--rule)" strokeWidth="1.2" />
        {xy.map((p, i) => (
          <circle key={i} cx={px(p.x)} cy={py(p.y)} r="2.4"
                  fill={p.rsrp === null ? "var(--ink-3)" : signalBand(p.rsrp).color} />
        ))}
        <rect x={px(xy[0].x) - 4} y={py(xy[0].y) - 4} width="8" height="8"
              fill="none" stroke="var(--ink)" strokeWidth="1.2" />
        <circle cx={px(last.x)} cy={py(last.y)} r="5" fill="none" stroke="var(--ink)" strokeWidth="1.5" />

        <g transform={`translate(${W - PAD} ${PAD + 4})`}>
          <path d="M0 -12 L4 0 L-4 0 Z" fill="var(--ink-2)" />
          <text y="12" textAnchor="middle" fontSize="9" fontFamily="var(--mono)" fill="var(--ink-2)">N</text>
        </g>
        <g transform={`translate(${PAD} ${H - PAD / 2})`}>
          <line x1="0" x2={bar * scale} y1="0" y2="0" stroke="var(--ink-2)" strokeWidth="1.5" />
          <text x={bar * scale + 6} y="3.5" fontSize="9" fontFamily="var(--mono)" fill="var(--ink-2)">
            {bar >= 1000 ? `${bar / 1000} km` : `${bar} m`}
          </text>
        </g>
      </svg>
      <span className="mono-note">
        □ start · ○ latest · {distance >= 1000 ? `${(distance / 1000).toFixed(2)} km` : `${distance.toFixed(0)} m`} along {fixes.length} fixes
        · dots coloured by signal: <span className="cyan">good</span>, fair, <span className="accent">poor</span>
        · relative to the start, no map, on purpose
      </span>
    </div>
  );
}

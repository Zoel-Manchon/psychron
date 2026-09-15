import { useId } from "react";
import { bandColor, niceLength, project, signalBand, type Coverage, type Fix } from "./phone";

// Where the phone went, drawn relative to where it started — and nothing else.
//
// No base map, on purpose, and not only because the CSP forbids fetching tiles.
// The coordinates are precise enough to find a front door, and this panel ends up
// in screenshots and screen recordings. A shape measured in metres from its own
// start says everything the track is for — how far, which way, where the signal
// dropped — without saying where.

const W = 640;
const H = 280;
const PAD = 26;

export function Track({ points, accuracy }: { points: Fix[]; accuracy: number | null }) {
  // React's ids carry punctuation that a url(#…) reference does not survive.
  const clip = `track-${useId().replace(/[^\w-]/g, "")}`;
  const { xy, distance, dropped } = project(points);

  if (xy.length === 0) {
    return (
      <p className="mono-note">
        {dropped > 0
          ? `No fix in this window was sure to within ±25 m; ${dropped} rougher ones were left out rather than drawn as a journey.`
          : "No fixes in this window. The phone reports its position once location is on and granted."}
      </p>
    );
  }

  const xs = xy.map((p) => p.x);
  const ys = xy.map((p) => p.y);
  const minX = Math.min(...xs), maxX = Math.max(...xs);
  const minY = Math.min(...ys), maxY = Math.max(...ys);
  // Equal scale on both axes, or a walk north looks like a walk east. At least
  // twenty metres across, so a phone lying still is a small cloud and not a scribble.
  const span = Math.max(maxX - minX, maxY - minY, 20);
  const scale = Math.min((W - 2 * PAD) / span, (H - 2 * PAD) / span);
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  const px = (x: number) => W / 2 + (x - cx) * scale;
  const py = (y: number) => H / 2 - (y - cy) * scale;

  const path = xy.map((p, i) => `${i ? "L" : "M"}${px(p.x).toFixed(1)} ${py(p.y).toFixed(1)}`).join(" ");
  const bar = niceLength(span / 4);
  const last = xy[xy.length - 1];
  // No move larger than the fixes' own error: the cloud is the uncertainty, not a walk.
  const stationary = distance === 0;

  return (
    <div className="stack">
      <svg viewBox={`0 0 ${W} ${H}`} className="track" role="img"
           aria-label={`Track of ${xy.length} fixes over ${distance.toFixed(0)} metres`}>
        <defs>
          <clipPath id={clip}><rect x="0" y="0" width={W} height={H} /></clipPath>
        </defs>
        <rect x="0.5" y="0.5" width={W - 1} height={H - 1} fill="none" stroke="var(--rule-faint)" />
        <g clipPath={`url(#${clip})`}>
          {accuracy !== null && (
            <circle cx={px(last.x)} cy={py(last.y)} r={accuracy * scale} fill="var(--fill)"
                    stroke="var(--rule)" strokeDasharray="3 3" />
          )}
          {/* A line through the wander of a phone lying still would draw a walk. */}
          {!stationary && <path d={path} fill="none" stroke="var(--rule)" strokeWidth="1.2" />}
          {xy.map((p, i) => (
            <circle key={i} cx={px(p.x)} cy={py(p.y)} r="2.4"
                    fill={p.rsrp === null ? "var(--ink-3)" : bandColor(signalBand(p.rsrp))} />
          ))}
        </g>
        <rect x={px(xy[0].x) - 4} y={py(xy[0].y) - 4} width="8" height="8"
              fill="none" stroke="var(--ink)" strokeWidth="1.2" />
        <circle cx={px(last.x)} cy={py(last.y)} r="5" fill="none" stroke="var(--ink)" strokeWidth="1.5" />

        <g transform={`translate(${W - PAD} ${PAD + 4})`}>
          <path d="M0 -12 L4 0 L-4 0 Z" fill="var(--ink-2)" />
          <text y="12" textAnchor="middle" fontSize="12" fontFamily="var(--mono)" fill="var(--ink-2)">N</text>
        </g>
        <g transform={`translate(${PAD} ${H - PAD / 2})`}>
          <line x1="0" x2={bar * scale} y1="0" y2="0" stroke="var(--ink-2)" strokeWidth="1.5" />
          <text x={bar * scale + 6} y="3.5" fontSize="12" fontFamily="var(--mono)" fill="var(--ink-2)">
            {bar >= 1000 ? `${bar / 1000} km` : `${bar} m`}
          </text>
        </g>
      </svg>
      <span className="mono-note">
        □ start · ○ latest{accuracy !== null && `, dashed ±${accuracy.toFixed(0)} m`}
        {" · "}{stationary
          ? `stationary: ${xy.length} fixes, none further apart than they are sure of`
          : `${distance >= 1000 ? `${(distance / 1000).toFixed(2)} km` : `${distance.toFixed(0)} m`} travelled over ${xy.length} fixes`}
        {dropped > 0 && ` · ${dropped} rougher than ±25 m left out`}
        {" · "}dots by signal: <span className="cyan">good</span>, fair, <span className="accent">poor</span>
        {" · "}relative to the start, no map, on purpose
      </span>
    </div>
  );
}

const ORDER = ["excellent", "good", "fair", "poor"] as const;

/** Signal over the whole window as one bar, divided the way the track's dots are coloured. */
export function CoverageBar({ coverage }: { coverage: Coverage }) {
  if (coverage.counted === 0) {
    return <span className="mono-note">no serving cell in this window</span>;
  }
  return (
    <div className="coverage" role="img"
         aria-label={ORDER.map((b) => `${b} ${(coverage.shares[b] * 100).toFixed(0)} %`).join(", ")}>
      {ORDER.filter((b) => coverage.shares[b] > 0).map((b) => (
        <span key={b} title={`${b} · ${(coverage.shares[b] * 100).toFixed(0)} %`}
              style={{ flexGrow: coverage.shares[b], background: bandColor(b) }} />
      ))}
    </div>
  );
}

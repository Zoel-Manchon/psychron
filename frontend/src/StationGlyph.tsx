// Station glyph, adapted from the WMO station model.
//
// Meteorologists have encoded several quantities around a single circle for a
// century. The adaptation here: temperature upper left, dew point lower left,
// relative humidity as the proportion of the circle that is filled, trend to
// the right, and the margin to condensation as a stroke whose LENGTH is that
// distance — not a number repeated in another place.
//
// It reads in one fixation and it works in flat ink, which is the point: no
// part of it depends on hue.

type Props = {
  temperature: number | null;
  dewPoint: number | null;
  humidity: number | null;
  trendPerHour: number;
  callouts?: boolean;
};

const W = 500;
const H = 340;

const f = (v: number | null, d = 1) => (v === null ? "—" : v.toFixed(d));

export function StationGlyph({ temperature, dewPoint, humidity, trendPerHour,
                               callouts = true }: Props) {
  const r = 44;
  const cx = W * 0.46;
  const cy = H * 0.56;

  const rh = Math.max(0, Math.min(100, humidity ?? 0));
  // The waterline: a circle filled from the bottom in proportion to humidity.
  const fillTop = cy + r - (2 * r * rh) / 100;
  const half = Math.sqrt(Math.max(0, r * r - (fillTop - cy) ** 2));

  const margin = temperature !== null && dewPoint !== null ? temperature - dewPoint : 0;
  const marginBaseY = cy - r - 7;
  // Length is the quantity. A wide margin draws a long stroke and the oxide
  // barely registers; air close to condensing pulls it down towards the circle.
  const marginTopY = marginBaseY - Math.max(2, Math.min(70, margin * 5.2));

  const rising = trendPerHour > 0.05;
  const falling = trendPerHour < -0.05;
  const trY1 = cy + (falling ? -10 : 10);
  const trY2 = cy + (falling ? 14 : -14);
  const trX = cx + r + 26;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" style={{ display: "block", overflow: "visible" }}
         role="img" aria-label="Station glyph">
      <title>Station glyph</title>

      {/* the circle, and the water it holds */}
      <clipPath id="glyph-fill">
        <rect x={cx - r} y={fillTop} width={2 * r} height={cy + r - fillTop} />
      </clipPath>
      <circle cx={cx} cy={cy} r={r} fill="none" stroke="var(--ink)" strokeWidth="1.5" />
      <circle cx={cx} cy={cy} r={r} fill="var(--cyan)" opacity="0.22" clipPath="url(#glyph-fill)" />
      <line x1={cx - half} y1={fillTop} x2={cx + half} y2={fillTop}
            stroke="var(--cyan)" strokeWidth="1.5" />

      {/* margin to condensation, drawn as a distance */}
      <line x1={cx} y1={marginBaseY} x2={cx} y2={marginTopY}
            stroke="var(--accent)" strokeWidth="2" />
      <line x1={cx - 6} y1={marginTopY} x2={cx + 6} y2={marginTopY}
            stroke="var(--accent)" strokeWidth="1" />

      {/* trend over the last hour */}
      {(rising || falling) && (
        <>
          <line x1={trX} y1={trY1} x2={trX} y2={trY2} stroke="var(--ink)" strokeWidth="2" />
          <polyline points={`${trX - 5},${trY2 + (falling ? -6 : 6)} ${trX},${trY2} ${trX + 5},${trY2 + (falling ? -6 : 6)}`}
                    fill="none" stroke="var(--ink)" strokeWidth="2" />
        </>
      )}
      {!rising && !falling && (
        <line x1={trX - 6} y1={cy} x2={trX + 6} y2={cy} stroke="var(--ink-2)" strokeWidth="2" />
      )}

      {/* the five values, placed where the station model puts them */}
      <g fontFamily="var(--mono)" fill="var(--ink)">
        <text x={cx - r - 16} y={cy - 12} textAnchor="end" fontSize="19">{f(temperature)}</text>
        <text x={cx - r - 16} y={cy + 24} textAnchor="end" fontSize="19">{f(dewPoint)}</text>
        <text x={cx} y={cy + 5} textAnchor="middle" fontSize="15" fill="var(--ink)">
          {humidity === null ? "—" : Math.round(rh)}
        </text>
        <text x={cx + 10} y={marginTopY - 6} fontSize="13" fill="var(--accent)">
          {f(margin)}
        </text>
        <text x={trX + 12} y={cy + 4} fontSize="12" fill="var(--ink-2)">
          {trendPerHour >= 0 ? "+" : ""}{trendPerHour.toFixed(1)}
        </text>
      </g>

      {callouts && (
        <g fontFamily="var(--grot)" fontSize="12" letterSpacing="0.06em" fill="var(--ink-2)">
          {/* Leader lines onto the artwork rather than a legend box off to the
              side. Each label owns its own horizontal band so none can collide
              with another as values change width. */}
          <polyline points={`${cx - r - 118},${cy - 42} ${cx - r - 118},${cy - 18} ${cx - r - 22},${cy - 18}`}
                    fill="none" stroke="var(--rule)" strokeWidth="1" />
          <text x={cx - r - 118} y={cy - 48}>DRY BULB °C</text>

          <polyline points={`${cx - r - 118},${cy + 54} ${cx - r - 118},${cy + 20} ${cx - r - 22},${cy + 20}`}
                    fill="none" stroke="var(--rule)" strokeWidth="1" />
          <text x={cx - r - 118} y={cy + 66}>DEW POINT °C</text>

          <polyline points={`${cx + 78},${marginTopY - 24} ${cx + 44},${marginTopY - 24} ${cx + 12},${marginTopY - 2}`}
                    fill="none" stroke="var(--rule)" strokeWidth="1" />
          <text x={cx + 82} y={marginTopY - 20}>MARGIN TO SATURATION K</text>

          <polyline points={`${cx + r + 96},${cy + 40} ${cx + r + 34},${cy + 40} ${cx + r + 34},${cy + 14}`}
                    fill="none" stroke="var(--rule)" strokeWidth="1" />
          <text x={cx + r + 100} y={cy + 44}>1 h TREND K/h</text>

          {/* Well clear of the two left-hand labels, centred under the figure. */}
          <line x1={cx} y1={cy + r + 6} x2={cx} y2={cy + r + 30}
                stroke="var(--rule)" strokeWidth="1" />
          <text x={cx} y={cy + r + 44} textAnchor="middle">FILL = RELATIVE HUMIDITY %</text>
        </g>
      )}
    </svg>
  );
}

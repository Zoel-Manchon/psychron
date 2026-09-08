// A distribution, not a row of numbers.
//
// Five statistics of the same quantity printed side by side make the reader do
// the arithmetic that turns them into a shape: how wide the spread is, whether
// the median sits off-centre, how far the current value is from the usual. The
// shape is the whole point, so it is drawn — box between the 5th and 95th
// percentiles, whiskers to the extremes, a heavy tick at the median, and the
// live value marked on the same axis so "now" is read as a position.

type Props = {
  label: string;
  unit: string;
  min: number;
  p05: number;
  median: number;
  p95: number;
  max: number;
  sd?: number | null;
  now?: number | null;
  digits?: number;
};

const W = 320;
const H = 34;
const PAD = 10;

export function Distribution({
  label, unit, min, p05, median, p95, max, sd, now, digits = 1,
}: Props) {
  // A flat range would divide by zero, and it is a real case: a sealed room
  // over a short window can genuinely not move.
  const span = max - min || 1;
  const x = (v: number) => PAD + ((v - min) / span) * (W - PAD * 2);
  const mid = 17;
  const f = (v: number | null | undefined) =>
    v === null || v === undefined ? "—" : v.toFixed(digits);

  return (
    <div className="dist">
      <div className="dist-head">
        <span className="dist-label">{label}</span>
        <span className="dist-span num">
          {f(min)}<span className="dim"> – </span>{f(max)} <span className="dim">{unit}</span>
        </span>
      </div>

      <svg viewBox={`0 0 ${W} ${H}`} width="100%" preserveAspectRatio="none"
           style={{ display: "block", height: H }} role="img"
           aria-label={`${label}: median ${f(median)} ${unit}, range ${f(min)} to ${f(max)}`}>
        {/* Whisker line, extremes as end caps. */}
        <line x1={x(min)} y1={mid} x2={x(max)} y2={mid} stroke="var(--rule)" strokeWidth="1" />
        <line x1={x(min)} y1={mid - 5} x2={x(min)} y2={mid + 5} stroke="var(--rule)" strokeWidth="1" />
        <line x1={x(max)} y1={mid - 5} x2={x(max)} y2={mid + 5} stroke="var(--rule)" strokeWidth="1" />

        {/* The central 90 %. Hatched rather than filled: a solid block reads as
            a measurement, and this is a summary of many. */}
        <rect x={x(p05)} y={mid - 7} width={Math.max(1, x(p95) - x(p05))} height={14}
              fill="var(--fill)" stroke="var(--ink-3)" strokeWidth="1" />

        <line x1={x(median)} y1={mid - 9} x2={x(median)} y2={mid + 9}
              stroke="var(--ink)" strokeWidth="2" />

        {now !== null && now !== undefined && now >= min && now <= max && (
          <>
            <line x1={x(now)} y1={mid - 11} x2={x(now)} y2={mid + 11}
                  stroke="var(--accent)" strokeWidth="1.5" />
            <circle cx={x(now)} cy={mid} r="3" fill="var(--accent)" />
          </>
        )}
      </svg>

      <div className="dist-foot num">
        <span><span className="dim">p05</span> {f(p05)}</span>
        <span><span className="dim">med</span> {f(median)}</span>
        <span><span className="dim">p95</span> {f(p95)}</span>
        {sd !== null && sd !== undefined && <span><span className="dim">σ</span> {sd.toFixed(2)}</span>}
        {now !== null && now !== undefined &&
          <span className="accent"><span className="dim">now</span> {f(now)}</span>}
      </div>
    </div>
  );
}

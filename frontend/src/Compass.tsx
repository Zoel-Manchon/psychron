// Heading as a needle, not a number alone.
//
// "212°" has to be translated into a direction before it means anything; a needle
// is already one. The number stays beside it, because a needle cannot be quoted.
// Magnetic north, and labelled as such: a phone does not know true north without
// a declination correction for where it is, and pretending otherwise would put the
// error of an unknown location into every reading.

type Props = { heading: number | null; size?: number };

const CARDINALS: [string, number][] = [["N", 0], ["E", 90], ["S", 180], ["W", 270]];

export function Compass({ heading, size = 132 }: Props) {
  const c = size / 2;
  const r = c - 14;

  return (
    <svg viewBox={`0 0 ${size} ${size}`} width={size} height={size} role="img"
         aria-label={heading === null ? "No heading" : `Heading ${heading.toFixed(0)} degrees`}>
      <circle cx={c} cy={c} r={r} fill="none" stroke="var(--rule)" strokeWidth="1" />
      {Array.from({ length: 36 }, (_, i) => i * 10).map((deg) => {
        const long = deg % 90 === 0;
        const a = (deg * Math.PI) / 180;
        const r0 = r - (long ? 7 : 3);
        return (
          <line key={deg}
                x1={c + r0 * Math.sin(a)} y1={c - r0 * Math.cos(a)}
                x2={c + r * Math.sin(a)} y2={c - r * Math.cos(a)}
                stroke={long ? "var(--ink-2)" : "var(--rule)"} strokeWidth="1" />
        );
      })}
      {CARDINALS.map(([name, deg]) => {
        const a = (deg * Math.PI) / 180;
        const rr = r + 9;
        return (
          <text key={name} x={c + rr * Math.sin(a)} y={c - rr * Math.cos(a) + 3.5}
                textAnchor="middle" fontSize="9" fontFamily="var(--mono)"
                fill={name === "N" ? "var(--accent)" : "var(--ink-3)"}>{name}</text>
        );
      })}

      {heading !== null && (
        <g transform={`rotate(${heading} ${c} ${c})`}>
          <path d={`M ${c} ${c - r + 10} L ${c + 5} ${c} L ${c - 5} ${c} Z`} fill="var(--accent)" />
          <path d={`M ${c} ${c + r - 10} L ${c + 5} ${c} L ${c - 5} ${c} Z`} fill="var(--ink-3)" />
        </g>
      )}
      <circle cx={c} cy={c} r="2.5" fill="var(--ink)" />
    </svg>
  );
}

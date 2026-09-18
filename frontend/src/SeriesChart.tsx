import { useCallback, useEffect, useId, useMemo, useRef, useState } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { columns, findGaps, secondsPerBucket, type Gap, type Span } from "./series";

// Every chart on the panel: the room's temperature and humidity, and each of the
// phone's quantities.
//
// uPlot rather than a React charting library: it draws to canvas and stays
// responsive at tens of thousands of points, which matters once a range covers
// weeks. Wrapped by hand because the React bindings add a dependency to do what
// one effect does.
//
// One axis per plot, always. Two quantities of different units on one plot with a
// scale on each side invite a correlation that is only where the two scales happen
// to line up; they are drawn as panels stacked on one time axis instead, with the
// cursor moving through all of them together. Gaps are broken and marked rather than
// bridged, and every stroke comes from the palette so both themes draw.

export type Trace = {
  key: string;
  label: string;
  unit: string;
  /** CSS custom property, e.g. "--accent". */
  color: string;
  digits: number;
  dash?: number[];
};

/** One plot of the stack: a title, its traces, and how tall it is. */
export type ChartPanel = { title: string; traces: Trace[]; height?: number };

type Row = { t: string } & Record<string, number | string | boolean | null>;

type Props = {
  points: Row[];
  bucket: string;
  panels: ChartPanel[];
  theme?: string;
  /** The window that was asked for. Given it, the plots span it, and the stretch
   *  before the first reading is drawn as the outage it is rather than cropped. */
  span?: Span;
};

/** A colour from the page's palette. Read when a chart is built, which is why every
 * chart rebuilds on a theme change: a canvas keeps the ink it was painted with. */
const palette = (name: string) =>
  getComputedStyle(document.documentElement).getPropertyValue(name).trim();

const FONT = '11px "IBM Plex Mono", ui-monospace, monospace';

/** Tick labels in 24-hour en-GB, one line each: a day's ticks carry the date, an hour's the time. */
const timeTicks = (_u: uPlot, splits: number[], _axis: number, _space: number, incr: number) =>
  splits.map((s) => {
    const d = new Date(s * 1000);
    if (incr >= 86400) return d.toLocaleDateString("en-GB", { day: "numeric", month: "short" });
    const time = d.toLocaleTimeString("en-GB", { hour: "2-digit", minute: "2-digit" });
    return incr >= 6 * 3600 ? `${d.toLocaleDateString("en-GB", { day: "numeric", month: "short" })} ${time}` : time;
  });
/** Value ticks with a decimal point, as many decimals as the step needs. uPlot's own
 * follow the browser's locale, and a Spanish one writes 27,2 on an axis beside a
 * readout that says 27.2. */
const numberTicks = (_u: uPlot, splits: number[], _axis: number, _space: number, incr: number) => {
  const decimals = Math.min(3, (String(Number(incr.toFixed(6))).split(".")[1] ?? "").length);
  return splits.map((v) => v.toFixed(decimals));
};

// The same width on every plot, so the plots of a stack share their left edge and
// a time on one sits directly above the same time on the next.
const AXIS_WIDTH = 52;

/** uPlot draw hook that marks each gap on the plot's own canvas.
 *
 * Drawn there rather than beside the chart, so the marks share its time axis by
 * construction: a separate element underneath would have to reproduce uPlot's
 * axis widths and fall out of alignment on the first layout change nobody
 * thought about.
 */
function drawGaps(gaps: Gap[]) {
  return (u: uPlot) => {
    if (gaps.length === 0) return;
    const ctx = u.ctx;
    const { top, height } = u.bbox;
    const strip = 4 * devicePixelRatio;
    ctx.save();
    for (const g of gaps) {
      const x0 = u.valToPos(g.from, "x", true);
      const x1 = u.valToPos(g.to, "x", true);
      const w = Math.max(1.5 * devicePixelRatio, x1 - x0);
      // A faint band across the plot says where the record is silent; the solid
      // mark along the bottom stays visible when the band is a hairline, which is
      // what a one-minute outage over thirty days is.
      ctx.fillStyle = palette("--rule-faint");
      ctx.fillRect(x0, top, w, height);
      ctx.fillStyle = palette("--ink-3");
      ctx.fillRect(x0, top + height - strip, w, strip);
    }
    ctx.restore();
  };
}

function Plot({ data, gaps, traces, height, xLabels, sync, onCursor, theme }: {
  data: uPlot.AlignedData; gaps: Gap[]; traces: Trace[]; height: number; xLabels: boolean;
  sync: string; onCursor: (idx: number | null) => void; theme?: string;
}) {
  const host = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const el = host.current;
    if (!el) return;
    const grid = { stroke: palette("--rule-faint"), width: 1 };
    const ticks = { stroke: palette("--rule"), width: 1, size: 4 };
    // uPlot paints its own axes with built-in colours, which are dark grey —
    // legible on paper and invisible on the dark ground. Every stroke it draws
    // has to come from the palette or half the chart disappears in one theme.
    const axes: uPlot.Axis[] = [
      { stroke: palette("--ink-2"), font: FONT, grid, ticks, size: xLabels ? 30 : 8,
        values: xLabels ? timeTicks : () => [] },
      { stroke: palette("--ink-2"), font: FONT, grid, ticks, size: AXIS_WIDTH, values: numberTicks },
    ];

    const plot = new uPlot({
      width: el.clientWidth,
      height,
      legend: { show: false },
      padding: [8, 8, 0, 0],
      cursor: {
        drag: { x: true, y: false },
        // Every plot of the stack follows the one under the pointer, and a zoom on
        // one zooms them all, so they never show two different stretches of time.
        sync: { key: sync, scales: ["x", null] },
        points: { size: 7, width: 2, fill: palette("--paper") },
      },
      scales: { x: { time: true } },
      axes,
      series: [
        {},
        ...traces.map((tr) => ({
          stroke: palette(tr.color),
          width: 1.75,
          dash: tr.dash,
          points: { show: false },
        })),
      ],
      hooks: {
        draw: [drawGaps(gaps)],
        setCursor: [(u) => onCursor(u.cursor.idx ?? null)],
      },
    }, data, el);

    const resize = new ResizeObserver(() => plot.setSize({ width: el.clientWidth, height }));
    resize.observe(el);
    return () => {
      resize.disconnect();
      plot.destroy();
    };
  }, [data, gaps, traces, height, xLabels, sync, onCursor, theme]);

  return <div ref={host} />;
}

const fmtValue = (v: number | null | undefined, tr: Trace) =>
  v === null || v === undefined ? "—" : `${v.toFixed(tr.digits)} ${tr.unit}`;

/** The latest value of a column, for the readout while nothing is hovered. */
const lastOf = (col: (number | null)[]) => {
  for (let i = col.length - 1; i >= 0; i--) if (col[i] !== null) return col[i];
  return null;
};

export function SeriesChart({ points, bucket, panels, theme, span }: Props) {
  const sync = useId();
  const [idx, setIdx] = useState<number | null>(null);
  const onCursor = useCallback((i: number | null) => setIdx(i), []);

  const keys = useMemo(() => panels.flatMap((p) => p.traces.map((t) => t.key)), [panels]);
  const table = useMemo(() => columns(points, bucket, keys, span), [points, bucket, keys, span]);
  const gaps = useMemo(() => findGaps(points, bucket, span), [points, bucket, span]);
  const xs = table[0];
  const byKey = useMemo(() => new Map(keys.map((k, i) => [k, table[i + 1]])), [keys, table]);
  const panelData = useMemo(
    () => panels.map((p) => [xs, ...p.traces.map((t) => byKey.get(t.key)!)] as uPlot.AlignedData),
    [panels, xs, byKey]);

  const hovered = idx !== null && idx < xs.length ? idx : null;
  const when = hovered === null ? null : new Date(xs[hovered] * 1000);
  const long = secondsPerBucket(bucket) >= 3600;
  const whenText = when === null ? "latest" : when.toLocaleString("en-GB", long
    ? { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }
    : { hour: "2-digit", minute: "2-digit", second: "2-digit" });

  return (
    <div className="figure">
      {panels.map((p, i) => (
        <div className="figure-stack" key={p.title}>
          <div className="figure-head">
            <span className="label">{p.title}</span>
            <span className="readout">
              {i === 0 && <span className="readout-item">{whenText}</span>}
              {p.traces.map((tr) => {
                const col = byKey.get(tr.key)!;
                const v = hovered === null ? lastOf(col) : col[hovered];
                return (
                  <span className="readout-item" key={tr.key}>
                    {/* A key only where there is more than one line to tell apart;
                        a single trace is already named by the panel's title. */}
                    {p.traces.length > 1 && (
                      <span className={`key${tr.dash ? " key-dash" : ""}`} style={{ borderColor: `var(${tr.color})` }} />
                    )}
                    <b>{fmtValue(v, tr)}</b>
                    {p.traces.length > 1 && tr.label}
                  </span>
                );
              })}
            </span>
          </div>
          <Plot data={panelData[i]} gaps={gaps} traces={p.traces} height={p.height ?? 180}
                xLabels={i === panels.length - 1} sync={sync} onCursor={onCursor} theme={theme} />
        </div>
      ))}
    </div>
  );
}

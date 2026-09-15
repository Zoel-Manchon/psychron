import { useEffect, useRef } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import { columns, findGaps, type Gap } from "./series";

// Every chart on the panel: the room's temperature and humidity, and each of the
// phone's quantities.
//
// uPlot rather than a React charting library: it draws to canvas and stays
// responsive at tens of thousands of points, which matters once a range covers
// weeks. Wrapped by hand because the React bindings add a dependency to do what
// one effect does. Gaps are broken and marked rather than bridged, every stroke is
// taken from the palette so both themes draw, and there is one axis per unit so two
// quantities are never forced onto a scale that flattens one of them.

export type Trace = {
  key: string;
  label: string;
  unit: string;
  /** CSS custom property, e.g. "--accent". */
  color: string;
  digits: number;
  /** Traces with the same axis share a scale; a second axis goes on the right. */
  axis?: "left" | "right";
  dash?: number[];
  /** How a value reads in the legend, when the axis label is not the right suffix. */
  suffix?: string;
};

type Row = { t: string } & Record<string, number | string | boolean | null>;

type Props = {
  points: Row[];
  bucket: string;
  traces: Trace[];
  theme?: string;
  height?: number;
};

/** A colour from the page's palette. Read when a chart is built, which is why every
 * chart rebuilds on a theme change: a canvas keeps the ink it was painted with. */
const palette = (name: string) =>
  getComputedStyle(document.documentElement).getPropertyValue(name).trim();

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
    const strip = 5 * devicePixelRatio;
    ctx.save();
    for (const g of gaps) {
      const x0 = u.valToPos(g.from, "x", true);
      const x1 = u.valToPos(g.to, "x", true);
      const w = Math.max(1.5 * devicePixelRatio, x1 - x0);
      // A faint band across the plot says where the record is silent; the solid
      // mark above the axis stays visible when the band is a hairline, which is
      // what a one-minute outage over thirty days is.
      ctx.fillStyle = palette("--rule-faint");
      ctx.fillRect(x0, top, w, height);
      ctx.fillStyle = palette("--ink-2");
      ctx.fillRect(x0, top + height - strip, w, strip);
    }
    ctx.restore();
  };
}

export function SeriesChart({ points, bucket, traces, theme, height = 200 }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const plot = useRef<uPlot | null>(null);

  useEffect(() => {
    if (!host.current) return;

    const data = columns(points, bucket, traces.map((t) => t.key));
    const font = '11px "IBM Plex Mono", ui-monospace, monospace';
    const leftUnit = traces.find((t) => (t.axis ?? "left") === "left")?.unit;
    const rightUnit = traces.find((t) => t.axis === "right")?.unit;

    // uPlot paints its own axes with built-in colours, which are dark grey —
    // legible on paper and invisible on the dark ground. Every stroke it draws
    // has to come from the palette or half the chart disappears in one theme.
    const axis = (extra: uPlot.Axis): uPlot.Axis => ({
      stroke: palette("--ink-2"), font, labelFont: font,
      ticks: { stroke: palette("--rule"), width: 1 }, ...extra,
    });
    const axes: uPlot.Axis[] = [
      axis({ grid: { stroke: palette("--rule-faint"), width: 1 } }),
      axis({ scale: "left", label: leftUnit, size: 58, grid: { stroke: palette("--rule-faint"), width: 1 } }),
    ];
    if (rightUnit) axes.push(axis({ scale: "right", label: rightUnit, side: 1, size: 58, grid: { show: false } }));

    const opts: uPlot.Options = {
      width: host.current.clientWidth,
      height,
      cursor: { drag: { x: true, y: false } },
      scales: { x: { time: true }, left: {}, ...(rightUnit ? { right: {} } : {}) },
      axes,
      series: [
        { label: "time" },
        ...traces.map((tr) => ({
          label: tr.label,
          scale: tr.axis ?? "left",
          stroke: palette(tr.color),
          width: 1.5,
          dash: tr.dash,
          value: (_u: uPlot, v: number | null) =>
            v == null ? "—" : `${v.toFixed(tr.digits)} ${tr.suffix ?? tr.unit}`,
        })),
      ],
      hooks: { draw: [drawGaps(findGaps(points, bucket))] },
    };

    plot.current?.destroy();
    plot.current = new uPlot(opts, data as uPlot.AlignedData, host.current);

    const onResize = () => plot.current?.setSize({ width: host.current!.clientWidth, height });
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
      plot.current?.destroy();
      plot.current = null;
    };
    // `traces` is a module-level constant at every call site, so its identity is
    // stable; listing it would only matter if a caller built it during render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [points, bucket, theme, height]);

  return <div ref={host} />;
}

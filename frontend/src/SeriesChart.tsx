import { useEffect, useRef } from "react";
import uPlot from "uplot";
import { drawGaps, findGaps, palette, secondsPerBucket } from "./Chart";

// A time series of any quantities, sharing the room chart's conventions: gaps
// broken and marked rather than bridged, every stroke taken from the palette so
// both themes draw, and one axis per unit so two quantities are never forced onto
// a scale that flattens one of them.

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
};

type Row = { t: string } & Record<string, number | string | null>;

type Props = {
  points: Row[];
  bucket: string;
  traces: Trace[];
  theme?: string;
  height?: number;
};

export function SeriesChart({ points, bucket, traces, theme, height = 200 }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const plot = useRef<uPlot | null>(null);

  useEffect(() => {
    if (!host.current) return;

    const expected = secondsPerBucket(bucket);
    const xs: number[] = [];
    const cols: (number | null)[][] = traces.map(() => []);
    let prev: number | null = null;

    for (const p of points) {
      const t = Date.parse(p.t) / 1000;
      if (prev !== null && t - prev > expected * 2.5) {
        xs.push(prev + expected);
        cols.forEach((c) => c.push(null));
      }
      xs.push(t);
      traces.forEach((tr, i) => {
        const v = p[tr.key];
        cols[i].push(typeof v === "number" ? v : null);
      });
      prev = t;
    }

    const font = '11px "IBM Plex Mono", ui-monospace, monospace';
    const leftUnit = traces.find((t) => (t.axis ?? "left") === "left")?.unit;
    const rightUnit = traces.find((t) => t.axis === "right")?.unit;

    const axes: uPlot.Axis[] = [
      { stroke: palette("--ink-2"), font,
        grid: { stroke: palette("--rule-faint"), width: 1 },
        ticks: { stroke: palette("--rule"), width: 1 } },
      { scale: "left", label: leftUnit, stroke: palette("--ink-2"), font, labelFont: font,
        size: 58, grid: { stroke: palette("--rule-faint"), width: 1 },
        ticks: { stroke: palette("--rule"), width: 1 } },
    ];
    if (rightUnit) {
      axes.push({ scale: "right", label: rightUnit, side: 1, stroke: palette("--ink-2"),
                  font, labelFont: font, size: 58, grid: { show: false },
                  ticks: { stroke: palette("--rule"), width: 1 } });
    }

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
            v == null ? "—" : `${v.toFixed(tr.digits)} ${tr.unit}`,
        })),
      ],
      hooks: { draw: [drawGaps(findGaps(points, bucket), palette)] },
    };

    plot.current?.destroy();
    plot.current = new uPlot(opts, [xs, ...cols] as uPlot.AlignedData, host.current);

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

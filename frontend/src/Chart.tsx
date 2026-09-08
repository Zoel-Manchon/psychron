import { useEffect, useRef } from "react";
import uPlot from "uplot";
import "uplot/dist/uPlot.min.css";
import type { Point } from "./api";

// uPlot rather than a React charting library: it draws to canvas and stays
// responsive at tens of thousands of points, which matters once a range covers
// weeks. Wrapped by hand because the React bindings add a dependency to do what
// one effect does.

type Props = { points: Point[]; bucket: string; theme?: string };

export type Gap = { from: number; to: number };

const secondsPerBucket = (bucket: string) =>
  bucket === "3s" ? 3 : bucket === "1m" ? 60 : bucket === "1h" ? 3600 : 86400;

/** Stretches of the window where no reading exists, in epoch seconds.
 *
 * Exported because the chart and the caption beneath it must agree about how
 * many there are. Two implementations of "what counts as a gap" would drift,
 * and the one thing this panel promises is that an outage is never smoothed
 * over — a claim that has to be made by one piece of code, not two.
 */
export function findGaps(points: Point[], bucket: string): Gap[] {
  const expected = secondsPerBucket(bucket);
  const gaps: Gap[] = [];
  let prev: number | null = null;
  for (const p of points) {
    const t = Date.parse(p.t) / 1000;
    // Two and a half buckets, not one: a bucket boundary landing a moment late
    // is jitter, and calling that an outage would fill the strip with noise.
    if (prev !== null && t - prev > expected * 2.5) gaps.push({ from: prev, to: t });
    prev = t;
  }
  return gaps;
}

export function Chart({ points, bucket, theme }: Props) {
  const host = useRef<HTMLDivElement>(null);
  const plot = useRef<uPlot | null>(null);

  useEffect(() => {
    if (!host.current) return;

    // Gaps stay gaps. uPlot breaks the line on null, so a missing reading is
    // drawn as absent rather than as a straight line between two distant
    // points — an interpolated outage looks exactly like a stable room.
    const xs: number[] = [];
    const temp: (number | null)[] = [];
    const hum: (number | null)[] = [];

    const expected = secondsPerBucket(bucket);
    let prev: number | null = null;

    for (const p of points) {
      const t = Date.parse(p.t) / 1000;
      if (prev !== null && t - prev > expected * 2.5) {
        xs.push(prev + expected);
        temp.push(null);
        hum.push(null);
      }
      xs.push(t);
      temp.push(p.temp);
      hum.push(p.hum);
      prev = t;
    }

    const gaps = findGaps(points, bucket);

    // Colours come from the palette rather than being hard-coded, so the chart
    // follows the light and dark grounds like everything else. Read here, at
    // build time, which is why `theme` is a dependency of this effect: the
    // canvas is painted once and would otherwise keep the previous ink.
    const ink = (name: string) =>
      getComputedStyle(document.documentElement).getPropertyValue(name).trim();

    const font = '11px "IBM Plex Mono", ui-monospace, monospace';

    const opts: uPlot.Options = {
      width: host.current.clientWidth,
      height: 320,
      cursor: { drag: { x: true, y: false } },
      scales: { x: { time: true }, temp: {}, hum: {} },
      // uPlot paints its own axes with built-in colours, which are dark grey —
      // legible on paper and invisible on the dark ground. Every stroke it draws
      // has to come from the palette or half the chart disappears in one theme.
      axes: [
        { stroke: ink("--ink-2"), font, grid: { stroke: ink("--rule-faint"), width: 1 },
          ticks: { stroke: ink("--rule"), width: 1 } },
        { scale: "temp", label: "°C", stroke: ink("--ink-2"), font, labelFont: font,
          grid: { stroke: ink("--rule-faint"), width: 1 },
          ticks: { stroke: ink("--rule"), width: 1 } },
        { scale: "hum", label: "% RH", side: 1, stroke: ink("--ink-2"), font, labelFont: font,
          grid: { show: false }, ticks: { stroke: ink("--rule"), width: 1 } },
      ],
      series: [
        { label: "time" },
        { label: "temperature", scale: "temp", stroke: ink("--accent"), width: 1.5,
          value: (_u, v) => (v == null ? "—" : v.toFixed(2) + " °C") },
        { label: "humidity", scale: "hum", stroke: ink("--cyan"), width: 1.5,
          value: (_u, v) => (v == null ? "—" : v.toFixed(1) + " %") },
      ],
      hooks: {
        // Drawn on the plot's own canvas rather than beside it, so the marks
        // share the chart's time axis by construction. A separate element
        // underneath would have to reproduce uPlot's axis widths and would fall
        // out of alignment on the first layout change nobody thought about.
        draw: [(u) => {
          if (gaps.length === 0) return;
          const ctx = u.ctx;
          const { top, height } = u.bbox;
          const strip = 5 * devicePixelRatio;
          ctx.save();
          for (const g of gaps) {
            const x0 = u.valToPos(g.from, "x", true);
            const x1 = u.valToPos(g.to, "x", true);
            const w = Math.max(1.5 * devicePixelRatio, x1 - x0);
            // A faint band across the plot says where the record is silent; the
            // solid mark above the axis stays visible when the band is a
            // hairline, which is what a one-minute outage over thirty days is.
            ctx.fillStyle = ink("--rule-faint");
            ctx.fillRect(x0, top, w, height);
            ctx.fillStyle = ink("--ink-2");
            ctx.fillRect(x0, top + height - strip, w, strip);
          }
          ctx.restore();
        }],
      },
    };

    plot.current?.destroy();
    plot.current = new uPlot(opts, [xs, temp, hum] as uPlot.AlignedData, host.current);

    const onResize = () => plot.current?.setSize({
      width: host.current!.clientWidth, height: 320,
    });
    window.addEventListener("resize", onResize);
    return () => {
      window.removeEventListener("resize", onResize);
      plot.current?.destroy();
      plot.current = null;
    };
  }, [points, bucket, theme]);

  return <div ref={host} />;
}

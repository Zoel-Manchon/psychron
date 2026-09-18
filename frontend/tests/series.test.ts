import { test } from "node:test";
import assert from "node:assert/strict";
import { columns, findGaps, secondsPerBucket } from "../src/series.ts";

const at = (seconds: number) => new Date(Date.UTC(2026, 8, 15, 6, 0, 0) + seconds * 1000).toISOString();
const t0 = Date.UTC(2026, 8, 15, 6, 0, 0) / 1000;

test("bucket labels from both nodes' ladders", () => {
  assert.equal(secondsPerBucket("2s"), 2);
  assert.equal(secondsPerBucket("3s"), 3);
  assert.equal(secondsPerBucket("30s"), 30);
  assert.equal(secondsPerBucket("2m"), 120);
  assert.equal(secondsPerBucket("1h"), 3600);
  assert.equal(secondsPerBucket("nonsense"), 86400);
});

test("a late bucket is jitter, a missing stretch is a gap", () => {
  const points = [0, 2, 4.9, 7, 20, 22].map((s) => ({ t: at(s) }));
  assert.deepEqual(findGaps(points, "2s"), [{ from: t0 + 7, to: t0 + 20 }]);
  assert.deepEqual(findGaps([], "2s"), []);
});

test("columns break the line across a gap instead of bridging it", () => {
  const points = [
    { t: at(0), a: 1, b: "x" },
    { t: at(2), a: 2, b: 5 },
    { t: at(20), a: null, b: 6 },
  ];
  const [xs, a, b] = columns(points, "2s", ["a", "b"]);
  assert.deepEqual(xs, [t0, t0 + 2, t0 + 4, t0 + 20]);
  assert.deepEqual(a, [1, 2, null, null]);
  assert.deepEqual(b, [null, 5, null, 6]);
});

test("the window's own edges are gaps too", () => {
  // The morning after a power cut: the day was asked for, one hour came back.
  const span = { from: at(-3600), to: at(60) };
  const points = [0, 2, 4, 6].map((s) => ({ t: at(s) }));
  assert.deepEqual(findGaps(points, "2s", span), [
    { from: t0 - 3600, to: t0 },
    { from: t0 + 6, to: t0 + 60 },
  ]);
  // Without the window there is no pair of readings to sit between, which is how
  // a panel comes to say "no gaps" over a day that is three per cent complete.
  assert.deepEqual(findGaps(points, "2s"), []);
});

test("a window with nothing in it is one gap, not none", () => {
  const span = { from: at(0), to: at(600) };
  assert.deepEqual(findGaps([], "2s", span), [{ from: t0, to: t0 + 600 }]);
  const [xs, a] = columns([], "2s", ["a"], span);
  assert.deepEqual(xs, [t0, t0 + 600]);
  assert.deepEqual(a, [null, null]);
});

test("columns span the window they were asked for", () => {
  const span = { from: at(-60), to: at(60) };
  const points = [{ t: at(0), a: 1 }, { t: at(2), a: 2 }];
  const [xs, a] = columns(points, "2s", ["a"], span);
  assert.deepEqual(xs, [t0 - 60, t0, t0 + 2, t0 + 60]);
  assert.deepEqual(a, [null, 1, 2, null]);
});

test("a window filled end to end grows no edges", () => {
  const span = { from: at(0), to: at(6) };
  const points = [0, 2, 4, 6].map((s) => ({ t: at(s), a: 1 }));
  assert.deepEqual(findGaps(points, "2s", span), []);
  assert.deepEqual(columns(points, "2s", ["a"], span)[0], [t0, t0 + 2, t0 + 4, t0 + 6]);
});

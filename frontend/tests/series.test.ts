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

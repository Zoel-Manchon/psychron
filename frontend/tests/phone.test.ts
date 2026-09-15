import { test } from "node:test";
import assert from "node:assert/strict";
import {
  agoWords, carrierLabel, coverage, describeShake, dominantBand, heightChange, isMoving, levelPosition, luxPosition,
  niceLength, project, signalBand, tendencyWords,
} from "../src/phone.ts";

const near = (actual: number, expected: number, tolerance: number) =>
  assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} is not within ${tolerance} of ${expected}`);

test("tendency uses the shipping forecast's bands", () => {
  assert.equal(tendencyWords(0.05), "steady");
  assert.equal(tendencyWords(-1.2), "falling slowly");
  assert.equal(tendencyWords(2.0), "rising");
  assert.equal(tendencyWords(-4.0), "falling quickly");
  assert.equal(tendencyWords(7.0), "rising very rapidly");
});

test("meters are logarithmic for light and clamp at both ends", () => {
  assert.equal(luxPosition(0), 0);
  near(luxPosition(100), 0.5, 1e-9);
  assert.equal(luxPosition(1e6), 1);
  assert.equal(levelPosition(-120), 0);
  near(levelPosition(-45), 0.5, 1e-9);
});

test("a storey of height is about a third of a hectopascal", () => {
  near(heightChange(957.9, 958.25), 3.1, 0.1);
  assert.equal(heightChange(958, 958), 0);
});

test("moving and shaking mean what the phone's screen means by them", () => {
  assert.equal(isMoving(0.06, 0.002), false);
  assert.equal(isMoving(0.3, null), true);
  assert.equal(isMoving(null, 0.25), true);
  assert.deepEqual([0.03, 0.14, 0.31, 1.2].map(describeShake), ["faint", "light", "strong", "violent"]);
});

test("durations read in the coarsest exact unit", () => {
  assert.equal(agoWords(-3), "0 s");
  assert.equal(agoWords(89), "89 s");
  assert.equal(agoWords(150), "3 min");
  assert.equal(agoWords(5400), "1.5 h");
});

test("signal bands and carrier names", () => {
  assert.equal(signalBand(-75), "excellent");
  assert.equal(signalBand(-90), "good");
  assert.equal(signalBand(-100), "fair");
  assert.equal(signalBand(-111), "poor");
  assert.equal(carrierLabel("lte", 7), "LTE B7");
  assert.equal(carrierLabel("nr", 78), "5G NR n78");
  assert.equal(carrierLabel("lte", null), "LTE");
  assert.equal(carrierLabel(null, 3), null);
});

test("coverage counts shares, carriers and handovers", () => {
  const p = (rsrp: number | null, band: number | null, rat: string | null = "lte") =>
    ({ cell_rsrp_dbm: rsrp, cell_band: band, cell_rat: rat });
  const c = coverage([p(-85, 1), p(-95, 1), p(-112, 7), p(-113, 7), p(null, null, null), p(-88, 1)]);
  assert.equal(c.counted, 5);
  assert.equal(c.missing, 1);
  near(c.shares.good, 0.4, 1e-9);
  near(c.shares.fair, 0.2, 1e-9);
  near(c.shares.poor, 0.4, 1e-9);
  assert.deepEqual(c.carriers.map((x) => x.label), ["LTE B1", "LTE B7"]);
  near(c.carriers[0].share, 0.6, 1e-9);
  // B1 → B7 and back to B1; the window without a cell does not count as one.
  assert.equal(c.changes, 2);
  // Good and poor tie at 40 %; the better band is named.
  assert.deepEqual(dominantBand(c), { band: "good", share: 0.4 });
});

test("coverage of a window with no cell at all is empty, not a division by zero", () => {
  const c = coverage([{ cell_rsrp_dbm: null, cell_band: null, cell_rat: null }]);
  assert.equal(c.counted, 0);
  assert.deepEqual(c.shares, { excellent: 0, good: 0, fair: 0, poor: 0 });
  assert.deepEqual(c.carriers, []);
  assert.equal(dominantBand(c), null);
});

test("a track is measured in metres from its first fix", () => {
  // A thousandth of a degree north is 110.6 m anywhere; east, it shrinks with latitude.
  const t = project([
    { lat: 40.0, lon: -3.0, cell_rsrp_dbm: -90, loc_acc_m: 4 },
    { lat: null, lon: null, cell_rsrp_dbm: -91 },
    { lat: 40.001, lon: -3.0, cell_rsrp_dbm: -92, loc_acc_m: 4 },
    { lat: 40.001, lon: -2.999, cell_rsrp_dbm: null, loc_acc_m: 4 },
  ]);
  assert.equal(t.xy.length, 3);
  near(t.xy[1].y, 110.574, 1e-6);
  near(t.xy[2].x, 111.32 * Math.cos((40 * Math.PI) / 180), 1e-6);
  near(t.distance, 110.574 + 85.28, 0.05);
  assert.equal(t.dropped, 0);
  assert.deepEqual(project([]), { xy: [], distance: 0, dropped: 0 });
});

test("a phone lying still has travelled nowhere, however its fixes wander", () => {
  // GNSS jitter of a few metres around one spot, and one network guess 100 m out.
  const jitter = [0, 3e-5, -2e-5, 1e-5, -3e-5, 2e-5].map((d, i) => ({
    lat: 40 + d, lon: -3 + (i % 2 ? d : -d), cell_rsrp_dbm: -111, loc_acc_m: 5,
  }));
  const t = project([...jitter.slice(0, 3), { lat: 40.001, lon: -3, cell_rsrp_dbm: -111, loc_acc_m: 100 }, ...jitter.slice(3)]);
  assert.equal(t.dropped, 1);
  assert.equal(t.xy.length, 6);
  assert.equal(t.distance, 0);
});

test("scale bars are round numbers", () => {
  assert.equal(niceLength(0.5), 1);
  assert.equal(niceLength(7), 5);
  assert.equal(niceLength(240), 200);
  assert.equal(niceLength(1_600), 1000);
});

/* Checks the panorama-shape and photo-metadata maths that Orbit Tour's editor
 * runs on every uploaded photo. These are pure functions with no DOM in them,
 * so they are lifted straight out of tour/index.html rather than duplicated —
 * a copy here would drift from the real thing and start passing while the app
 * broke.
 *
 *   node tests/tour_pano_test.mjs
 */

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const html = readFileSync(join(root, 'tour', 'index.html'), 'utf8');

/* The block is fenced by marker comments in the source. If either marker is
   removed this throws instead of quietly testing nothing. */
const OPEN = '/* --- pure helpers begin';
const CLOSE = '/* --- pure helpers end';
const from = html.indexOf(OPEN);
const to = html.indexOf(CLOSE);
if (from < 0 || to < 0 || to < from) {
  throw new Error('tour/index.html no longer fences its pure-helper block — see the markers named in this test');
}
const source = html.slice(from, to);
for (const name of ['panoDataFor', 'gpanoCoverage', 'readGps', 'bearing', 'metresBetween', 'guessNavigableYaws',
  'snapToWay', 'fmtIn', 'defectMeasure', 'defectNeedsMeasure', 'nextDefectCode',
  'csvCell', 'registerCsv', 'planPositions']) {
  if (!source.includes(`function ${name}`)) throw new Error(`extracted block is missing ${name}`);
}

const helpers = await import(
  'data:text/javascript,' + encodeURIComponent(
    `${source}\nexport { clamp, isPartial, vFovOf, panoDataFor, gpanoCoverage, readGps, bearing,` +
    ` metresBetween, wrap180, guessNavigableYaws, snapToWay,` +
    ` fmtIn, defectMeasure, defectNeedsMeasure, nextDefectCode, DEFECT_TYPES, DEFECT_MEASURE,` +
    ` DEPTH_STEPS, WIDTH_STEPS, csvCell, registerCsv, planPositions };`
  )
);
const {
  isPartial, vFovOf, panoDataFor, gpanoCoverage, readGps, bearing, metresBetween, wrap180,
  guessNavigableYaws, snapToWay,
  fmtIn, defectMeasure, defectNeedsMeasure, nextDefectCode, DEFECT_TYPES, DEFECT_MEASURE,
  DEPTH_STEPS, WIDTH_STEPS, csvCell, registerCsv, planPositions,
} = helpers;

let failures = 0;
function check(ok, label) {
  console.log(`${ok ? 'ok  ' : 'FAIL'}  ${label}`);
  if (!ok) failures++;
}
const near = (a, b, tol = 1e-6) => Number.isFinite(a) && Math.abs(a - b) <= tol;

/* ---------- what counts as partial ---------- */

check(!isPartial({}), 'a scene with no pano block is a full sphere');
check(!isPartial({ pano: { hFov: 360 } }), 'hFov 360 is a full sphere, not a crop');
check(isPartial({ pano: { hFov: 180 } }), 'hFov 180 is partial');
check(near(vFovOf(180, 4000, 2000), 90), 'vertical fov follows from the aspect ratio');

/* ---------- panoData geometry ---------- */

/* A full sphere still hands PSV an object, carrying a zeroed pose: a photo
   whose XMP claims a compass heading would otherwise have its whole sphere
   silently rotated, leaving two different meanings of yaw in one tour. */
const full = panoDataFor({})({ width: 4000, height: 2000 });
check(full.croppedWidth === undefined && full.fullWidth === undefined,
  'a full sphere overrides no geometry, so XMP and the image still decide it');
check(full.poseHeading === 0 && full.posePitch === 0 && full.poseRoll === 0,
  'a full sphere is handed a zeroed pose so PSV cannot pre-rotate it');

const half = panoDataFor({ pano: { hFov: 180 } })({ width: 4000, height: 2000 });
check(half.fullWidth === 8000 && half.fullHeight === 4000,
  'a 180 degree photo implies a sphere twice its width');
check(half.croppedWidth === 4000 && half.croppedHeight === 2000, 'the crop keeps the real pixel size');
check(half.croppedX === 2000, 'the crop sits centred horizontally');
check(half.croppedY === 1000, 'with no tilt the horizon lands mid-image');
check(half.poseHeading === 0 && half.posePitch === 0 && half.poseRoll === 0,
  'a partial photo is handed a zeroed pose too');

const tilted = panoDataFor({ pano: { hFov: 180, pitch: 30 } })({ width: 4000, height: 2000 });
check(tilted.croppedY === 333, 'tilting the photo up moves the crop up the sphere');
check(tilted.croppedY < half.croppedY, 'a positive tilt always sits higher than none');

/* PSV logs a warning and silently repairs an out-of-range crop, so keep it in range here */
const extreme = panoDataFor({ pano: { hFov: 60, pitch: 80 } })({ width: 3000, height: 1000 });
check(extreme.croppedY >= 0, 'an extreme tilt is clamped rather than pushed off the sphere');
check(extreme.croppedY + extreme.croppedHeight <= extreme.fullHeight,
  'the crop never runs past the bottom of the sphere');

const wide = panoDataFor({ pano: { hFov: 350 } })({ width: 4000, height: 1000 });
check(wide.croppedX >= 0 && wide.croppedX + wide.croppedWidth <= wide.fullWidth,
  'a nearly-complete sweep still fits inside its sphere');

/* ---------- GPano XMP ---------- */

const xmp = (extra = '') => `<x:xmpmeta><rdf:RDF><rdf:Description
  GPano:FullPanoWidthPixels="8000"
  GPano:FullPanoHeightPixels="4000"
  GPano:CroppedAreaImageWidthPixels="4000"
  GPano:CroppedAreaImageHeightPixels="1000"
  ${extra}/></rdf:RDF></x:xmpmeta>`;

const centred = gpanoCoverage(xmp('GPano:CroppedAreaLeftPixels="2000" GPano:CroppedAreaTopPixels="1500"'));
check(near(centred.hFov, 180), 'XMP states the width outright, no guessing');
check(near(centred.pitch, 0), 'a crop centred on the equator reads as no tilt');

const high = gpanoCoverage(xmp('GPano:CroppedAreaLeftPixels="2000" GPano:CroppedAreaTopPixels="1000"'));
check(near(high.pitch, 22.5), 'a crop above the equator reads as a positive tilt');

check(gpanoCoverage('<x:xmpmeta>nothing useful</x:xmpmeta>') === null,
  'XMP without the GPano numbers is ignored');

check(centred.heading === undefined, 'no PoseHeadingDegrees means no heading is invented');
const facing = gpanoCoverage(xmp('GPano:CroppedAreaTopPixels="1500" GPano:PoseHeadingDegrees="217.5"'));
check(near(facing.heading, 217.5), 'a photo sphere states its compass heading in XMP, and it is read');
const wrapped = gpanoCoverage(xmp('GPano:CroppedAreaTopPixels="1500" GPano:PoseHeadingDegrees="-45"'));
check(near(wrapped.heading, 315), 'a negative heading folds into 0-360 like a compass');

/* element form, which Google Camera writes instead of attributes */
const asElements = gpanoCoverage(
  '<GPano:FullPanoWidthPixels>8000</GPano:FullPanoWidthPixels>' +
  '<GPano:FullPanoHeightPixels>4000</GPano:FullPanoHeightPixels>' +
  '<GPano:CroppedAreaImageWidthPixels>2000</GPano:CroppedAreaImageWidthPixels>' +
  '<GPano:CroppedAreaImageHeightPixels>1000</GPano:CroppedAreaImageHeightPixels>');
check(asElements !== null && near(asElements.hFov, 90), 'GPano written as elements reads the same as attributes');

/* ---------- EXIF GPS ----------
   Hand-built so the byte offsets are checked against something known. A
   mistake anywhere in the IFD walk lands somewhere random and returns
   nonsense, which is exactly the failure this test exists to catch. */

function buildJpegWithGps({ latRef = 'N', lonRef = 'W', heading = 123.4 } = {}) {
  const TIFF = 148;
  const buf = new ArrayBuffer(2 + 4 + 6 + TIFF);
  const v = new DataView(buf);
  let o = 0;
  v.setUint16(o, 0xFFD8); o += 2;                 // SOI
  v.setUint16(o, 0xFFE1); o += 2;                 // APP1
  v.setUint16(o, 2 + 6 + TIFF); o += 2;           // segment length, includes itself
  for (const ch of 'Exif\0\0') { v.setUint8(o++, ch.charCodeAt(0)); }
  const T = o;                                    // TIFF header base

  v.setUint16(T, 0x4949);                         // "II" — little endian from here on
  v.setUint16(T + 2, 42, true);
  v.setUint32(T + 4, 8, true);                    // IFD0 starts 8 bytes in

  v.setUint16(T + 8, 1, true);                    // IFD0: one entry
  v.setUint16(T + 10, 0x8825, true);              //   GPS IFD pointer
  v.setUint16(T + 12, 4, true);                   //   LONG
  v.setUint32(T + 14, 1, true);
  v.setUint32(T + 18, 26, true);                  //   -> GPS IFD at TIFF+26
  v.setUint32(T + 22, 0, true);                   // no IFD1

  const G = T + 26;
  v.setUint16(G, 5, true);                        // five GPS entries
  const entry = (i, tag, type, count, value) => {
    const e = G + 2 + i * 12;
    v.setUint16(e, tag, true);
    v.setUint16(e + 2, type, true);
    v.setUint32(e + 4, count, true);
    if (type === 2) { // ASCII short enough to live inline
      v.setUint8(e + 8, value.charCodeAt(0));
      v.setUint8(e + 9, 0);
    } else {
      v.setUint32(e + 8, value, true);            // offset from the TIFF base
    }
  };
  const rational = (at, num, den) => { v.setUint32(T + at, num, true); v.setUint32(T + at + 4, den, true); };

  entry(0, 1, 2, 2, latRef);
  entry(1, 2, 5, 3, 92);
  entry(2, 3, 2, 2, lonRef);
  entry(3, 4, 5, 3, 116);
  entry(4, 17, 5, 1, 140);
  v.setUint32(G + 2 + 5 * 12, 0, true);           // end of the GPS IFD

  rational(92, 40, 1); rational(100, 26, 1); rational(108, 30, 1);   // 40 deg 26' 30"
  rational(116, 79, 1); rational(124, 59, 1); rational(132, 0, 1);   // 79 deg 59' 00"
  rational(140, Math.round(heading * 10), 10);
  return buf;
}

const gps = readGps(buildJpegWithGps());
check(gps !== null, 'GPS is found inside a JPEG APP1 segment');
check(near(gps.lat, 40.4416667, 1e-6), `latitude reads back as degrees (got ${gps?.lat})`);
check(near(gps.lon, -79.9833333, 1e-6), `a west longitude comes back negative (got ${gps?.lon})`);
check(near(gps.heading, 123.4, 1e-6), `the compass heading survives the rational (got ${gps?.heading})`);

/* The same TIFF block, rehoused in the other two containers a browser can
   decode. A JPEG-only parser returns null for both and the GPS silently
   vanishes, which looks identical to a photo that never had a fix. */

function tiffOf(jpeg) {
  /* the builder above puts the TIFF at a fixed offset: SOI + marker + len + "Exif\0\0" */
  return jpeg.slice(2 + 2 + 2 + 6);
}

function wrapPng(tiff) {
  const out = new ArrayBuffer(8 + 12 + tiff.byteLength + 12);
  const v = new DataView(out);
  v.setUint32(0, 0x89504E47); v.setUint32(4, 0x0D0A1A0A);      // PNG signature
  v.setUint32(8, tiff.byteLength);                              // eXIf chunk length
  for (const [i, ch] of [...'eXIf'].entries()) v.setUint8(12 + i, ch.charCodeAt(0));
  new Uint8Array(out, 16).set(new Uint8Array(tiff));
  return out;
}

function wrapWebp(tiff, withPrefix) {
  const payload = tiff.byteLength + (withPrefix ? 6 : 0);
  const pad = payload % 2;
  const out = new ArrayBuffer(12 + 8 + payload + pad);
  const v = new DataView(out);
  const put = (at, s) => [...s].forEach((c, i) => v.setUint8(at + i, c.charCodeAt(0)));
  put(0, 'RIFF'); v.setUint32(4, out.byteLength - 8, true); put(8, 'WEBP');
  put(12, 'EXIF'); v.setUint32(16, payload, true);
  let at = 20;
  if (withPrefix) { put(at, 'Exif'); v.setUint8(at + 4, 0); v.setUint8(at + 5, 0); at += 6; }
  new Uint8Array(out, at).set(new Uint8Array(tiff));
  return out;
}

const tiff = tiffOf(buildJpegWithGps());
const fromPng = readGps(wrapPng(tiff));
check(fromPng !== null && near(fromPng.lat, 40.4416667, 1e-6), 'GPS is found in a PNG eXIf chunk');
const fromWebp = readGps(wrapWebp(tiff, false));
check(fromWebp !== null && near(fromWebp.lon, -79.9833333, 1e-6), 'GPS is found in a WebP EXIF chunk');
const fromWebpPrefixed = readGps(wrapWebp(tiff, true));
check(fromWebpPrefixed !== null && near(fromWebpPrefixed.heading, 123.4, 1e-6),
  'a WebP that kept the JPEG-style Exif\\0\\0 prefix reads the same');

const south = readGps(buildJpegWithGps({ latRef: 'S', lonRef: 'E' }));
check(south.lat < 0 && south.lon > 0, 'the hemisphere refs flip the signs the other way');

check(readGps(new ArrayBuffer(64)) === null, 'a buffer that is not a JPEG yields nothing');
const bare = new ArrayBuffer(4);
new DataView(bare).setUint16(0, 0xFFD8);
check(readGps(bare) === null, 'a JPEG with no EXIF yields nothing rather than throwing');

/* a truncated read must not walk off the end — this is what the head-slice does in practice */
const whole = buildJpegWithGps();
let survived = true;
for (let cut = 8; cut < whole.byteLength; cut += 7) {
  try { readGps(whole.slice(0, cut)); } catch (err) { survived = false; console.log(`   threw at ${cut}: ${err.message}`); break; }
}
check(survived, 'every truncation of the file parses or gives up, never throws');

/* ---------- bearings ---------- */

check(near(bearing({ lat: 0, lon: 0 }, { lat: 1, lon: 0 }), 0, 1e-9), 'due north is 0 degrees');
check(near(bearing({ lat: 0, lon: 0 }, { lat: 0, lon: 1 }), 90, 1e-9), 'due east is 90 degrees');
check(near(bearing({ lat: 0, lon: 0 }, { lat: -1, lon: 0 }), 180, 1e-9), 'due south is 180 degrees');
check(near(bearing({ lat: 0, lon: 0 }, { lat: 0, lon: -1 }), 270, 1e-9), 'due west is 270 degrees');

/* the arrow back must face the arrow out — this is the sign error that would
   silently mirror an entire tour */
const a = { lat: 40.4416, lon: -79.9833 };
const b = { lat: 40.4417, lon: -79.9833 };
check(near(Math.abs(wrap180(bearing(a, b) - bearing(b, a))), 180, 0.01),
  'the return bearing is the reverse of the outgoing one');

check(near(metresBetween({ lat: 0, lon: 0 }, { lat: 0.001, lon: 0 }), 111.19, 0.1),
  'a thousandth of a degree of latitude is about 111 metres');
check(metresBetween(a, a) === 0, 'a scene is zero metres from itself');

check(wrap180(190) === -170, 'wrap180 folds past the half turn');
check(wrap180(-190) === 170, 'wrap180 folds the other way too');
check(wrap180(0) === 0, 'wrap180 leaves a straight-ahead angle alone');

/* ---------- guessing where you can walk ----------
   Synthetic panoramas: a flat wall with one deliberate anomaly in it. The
   claim under test is that the anomaly is found whichever direction it departs
   in, because scoring darkness alone would only ever work indoors. */

function pano(paint, W = 256, H = 128) {
  const data = new Uint8ClampedArray(W * H * 4);
  for (let y = 0; y < H; y++) {
    for (let x = 0; x < W; x++) {
      const v = paint(x, y, W, H);
      const i = (y * W + x) * 4;
      data[i] = data[i + 1] = data[i + 2] = v;
      data[i + 3] = 255;
    }
  }
  return { width: W, height: H, data };
}

const yawOfColumn = (x, W = 256) => wrap180((x + 0.5) / W * 360 - 180);
const nearestTo = (yaws, target) =>
  yaws.length ? yaws.reduce((a, b) => (Math.abs(wrap180(b - target)) < Math.abs(wrap180(a - target)) ? b : a)) : null;

/* an unlit doorway in a bright wall */
const darkGap = guessNavigableYaws(pano((x, y) => (x >= 180 && x < 196 ? 20 : 150)), { count: 3 });
check(darkGap.length > 0, 'a dark opening in a bright wall is found at all');
check(darkGap.length && Math.abs(wrap180(nearestTo(darkGap, yawOfColumn(188)))) < 200
  && Math.abs(wrap180(nearestTo(darkGap, yawOfColumn(188)) - yawOfColumn(188))) <= 12,
  `the dark opening is located within 12 degrees (got ${darkGap}, wanted near ${yawOfColumn(188).toFixed(1)})`);

/* the same wall, but the doorway opens onto daylight — darkness alone would
   miss this. Confined to eye level, because a real doorway does not reach the
   zenith, and anything that does is glare rather than a way through. */
const brightGap = guessNavigableYaws(
  pano((x, y, W, H) => (x >= 60 && x < 76 && y > H * 0.35 ? 240 : 90)), { count: 3 });
check(brightGap.length > 0, 'a bright opening in a dark wall is found too, not just a dark one');
check(brightGap.length && Math.abs(wrap180(nearestTo(brightGap, yawOfColumn(68)) - yawOfColumn(68))) <= 12,
  `the bright opening is located within 12 degrees (got ${brightGap}, wanted near ${yawOfColumn(68).toFixed(1)})`);

/* a featureless scene must admit it has nothing to say rather than inventing */
check(guessNavigableYaws(pano(() => 128)).length === 0,
  'a flat featureless panorama returns no suggestions at all');

/* the sun is bright and is not a doorway: a hot blob high in the sky is excluded */
const sunOnly = guessNavigableYaws(pano((x, y, W, H) => (y < H * 0.22 && x >= 120 && x < 136 ? 255 : 120)));
check(sunOnly.length === 0, 'a blazing sun in the sky is not offered as somewhere to walk');

/* wrap-around: an opening straddling the seam is one suggestion, not two */
const seam = guessNavigableYaws(pano((x, y, W) => ((x >= W - 8 || x < 8) ? 20 : 150)), { count: 3 });
check(seam.length === 1, `an opening across the 0/360 seam counts once, not twice (got ${seam.length})`);

/* suggestions never crowd each other past what the viewer would dim anyway */
const many = guessNavigableYaws(pano((x) => ([30, 45, 120, 200].some(c => x >= c && x < c + 10) ? 20 : 150)), { count: 4 });
const tooClose = many.some((a, i) => many.some((b, j) => i !== j && Math.abs(wrap180(a - b)) < 45));
check(!tooClose, `suggestions stay at least 45 degrees apart (got ${many})`);

/* snapping keeps the direction we already believed in when nothing is near */
check(snapToWay(0, []) === 0, 'with no suggestions the original angle is kept');
check(snapToWay(0, [140, -150]) === 0, 'a suggestion beyond the tolerance is ignored');
check(snapToWay(0, [25, 140]) === 25, 'a suggestion within tolerance wins');
check(snapToWay(0, [40, -12, 80]) === -12, 'the closest suggestion wins, not the first');
check(snapToWay(170, [-175]) === -175, 'snapping works across the seam');

/* ---------- the defect register ----------
 * These numbers are a contract with the sibling field-sketch tool
 * (android/app/src/main/assets/bridge-sketch.html). If the two ever phrase a
 * measurement differently, one inspection produces two disagreeing registers,
 * so the phrasing is asserted, not just the arithmetic. */

check(DEFECT_TYPES.length === 9, 'nine defect types, matching the field sketch');
check(DEFECT_TYPES[0] === 'Spall' && DEFECT_TYPES.includes('Exposed reinforcement'),
  'the type names are the sketch tool\'s, not paraphrased');
check(DEPTH_STEPS.map(s => s[0]).join() === '0.25,0.5,1,2,3', 'depth increments are 1/4, 1/2, 1, 2, over 2');
check(WIDTH_STEPS.map(s => s[0]).join() === '0.01,0.0625,0.125,0.25,0.375',
  'width increments are hairline, 1/16, 1/8, 1/4, over 1/4');

check(fmtIn(0.01) === 'hairline', 'the hairline sentinel is a word, not a hundredth of an inch');
check(fmtIn(0.375) === 'over 1/4 in', 'the open-ended width bucket reads as over 1/4 in');
check(fmtIn(3) === 'over 2 in', 'the open-ended depth bucket reads as over 2 in');
check(fmtIn(0.0625) === '1/16 in', 'a sixteenth renders as a fraction');
check(fmtIn(0.125) === '1/8 in', 'an eighth reduces');
check(fmtIn(0.5) === '1/2 in', 'a half reduces');
check(fmtIn(1) === '1 in', 'a whole inch drops the fraction');
check(fmtIn(2) === '2 in', 'two whole inches drop the fraction');
check(fmtIn(0) === '' && fmtIn(undefined) === '', 'no measurement renders as nothing, not NaN');

check(defectMeasure({ defect: 'Spall', depthIn: 0.5 }) === '1/2 in deep', 'a spall reads as a depth');
check(defectMeasure({ defect: 'Spall', depthIn: 2, rebar: true }) === '2 in deep, reinforcement exposed',
  'exposed reinforcement is appended the way the sketch tool phrases it');
check(defectMeasure({ defect: 'Crack', widthIn: 0.01 }) === 'hairline wide', 'a crack reads as a width');
check(defectMeasure({ defect: 'Efflorescence' }) === '', 'a type with no measurement measures nothing');
/* the field that no longer applies must not leak into the register */
check(defectMeasure({ defect: 'Crack', depthIn: 2, widthIn: 0.125 }) === '1/8 in wide',
  'a stale depth on a crack is ignored, not printed');

check(defectNeedsMeasure({ defect: 'Spall' }), 'a spall with no depth is flagged');
check(!defectNeedsMeasure({ defect: 'Spall', depthIn: 0.25 }), 'a spall with a depth is not flagged');
check(!defectNeedsMeasure({ defect: 'Patch' }), 'a type that takes no measurement is never flagged');

check(nextDefectCode([]) === 'D1', 'the first defect in an empty tour is D1');
check(nextDefectCode(undefined) === 'D1', 'a tour with no scenes still yields D1');
check(nextDefectCode([{ hotspots: [{ type: 'link' }] }]) === 'D1', 'links do not consume defect numbers');
check(nextDefectCode([{ hotspots: [{ code: 'D1' }] }, { hotspots: [{ code: 'D2' }] }]) === 'D3',
  'codes run across the whole tour, not per scene');
/* deleting D2 must not hand D3's number to the next defect and collide */
check(nextDefectCode([{ hotspots: [{ code: 'D1' }, { code: 'D3' }] }]) === 'D4',
  'a gap left by a deleted defect is not reused');

/* ---------- the exported register ---------- */

check(csvCell('plain') === 'plain', 'an ordinary cell is not quoted');
check(csvCell('a,b') === '"a,b"', 'a comma forces quoting');
check(csvCell('say "hi"') === '"say ""hi"""', 'an embedded quote is doubled');
check(csvCell('line\r\nbreak') === '"line\r\nbreak"', 'a newline stays inside one quoted cell');
/* Excel executes a leading =, + or @ however the cell is quoted */
check(csvCell('=SUM(A1:A9)') === "'=SUM(A1:A9)", 'a leading = is defused with an apostrophe');
check(csvCell('+1') === "'+1", 'a leading + is defused');
check(csvCell('@x') === "'@x", 'a leading @ is defused');
/* Excel parses a leading minus as a formula exactly as it does '='. The
   Measure column is generated and can never start with one, but Note, Scene,
   Element and Station are free text and can. */
check(csvCell("-2+3+cmd|'/C calc'!A0") === "'-2+3+cmd|'/C calc'!A0",
  'a leading minus is defused too — the free-text columns can carry one');
check(csvCell(undefined) === '' && csvCell(null) === '', 'a missing value is an empty cell, not "undefined"');

const sampleTour = {
  scenes: [
    { name: 'Bay 2', stop: 'Underside', element: 'Soffit', station: '3+00', hotspots: [
      { type: 'defect', code: 'D10', defect: 'Crack', widthIn: 0.01, note: 'Transverse' },
      { type: 'link', target: 'x' },
    ] },
    { name: 'Pier 1', stop: 'Pier faces', element: 'Pier, column', station: '2+50', hotspots: [
      { type: 'defect', code: 'D9', defect: 'Spall', depthIn: 2, rebar: true, note: 'At the joint, "wet"' },
      { type: 'info', title: 'note' },
    ] },
  ],
};
const csvLines = registerCsv(sampleTour).split('\r\n');
check(csvLines[0] === 'Code,Type,Measure,Scene,Photo stop,Element,Station,Note',
  'the header matches the field sketch register columns');
check(csvLines.length === 3, 'only defects become rows — links and info hotspots do not');
check(csvLines[1].startsWith('D9,'), 'D9 sorts before D10 numerically, not as text');
check(csvLines[1] === 'D9,Spall,"2 in deep, reinforcement exposed",Pier 1,Pier faces,"Pier, column",2+50,"At the joint, ""wet"""',
  'a full defect row round-trips measure, element and a quoted note');
check(csvLines[2] === 'D10,Crack,hairline wide,Bay 2,Underside,Soffit,3+00,Transverse',
  'the second row carries its own scene metadata');
check(registerCsv({ scenes: [] }).split('\r\n').length === 1, 'a tour with no defects exports just the header');
check(registerCsv(undefined).split('\r\n').length === 1, 'a missing tour does not throw');
/* ---------- plan view geometry ---------- */

const geoScene = (id, lat, lon) => ({ id, geo: { lat, lon } });
check(planPositions([]).length === 0, 'no scenes plots nothing');
check(planPositions(undefined).length === 0, 'a missing list does not throw');
check(planPositions([geoScene('a', 40, -75)]).length === 0, 'one located photo is not a plan');
check(planPositions([geoScene('a', 40, -75), { id: 'b' }]).length === 0,
  'a photo with no fix cannot make a plan on its own');

/* a north-south pair: north must sit ABOVE south on screen */
const ns = planPositions([geoScene('s', 40.0000, -75), geoScene('n', 40.0010, -75)]);
check(ns.length === 2, 'two located photos plot');
check(ns.find(p => p.id === 'n').v < ns.find(p => p.id === 's').v, 'north plots above south');
check(near(ns.find(p => p.id === 'n').u, 0.5, 0.02) && near(ns.find(p => p.id === 's').u, 0.5, 0.02),
  'a due north-south pair shares one column');

/* an east-west pair: east must sit RIGHT of west */
const ew = planPositions([geoScene('w', 40, -75.0010), geoScene('e', 40, -75.0000)]);
check(ew.find(p => p.id === 'e').u > ew.find(p => p.id === 'w').u, 'east plots right of west');

/* every photo standing on one fix must not divide by zero */
const same = planPositions([geoScene('a', 40, -75), geoScene('b', 40, -75), geoScene('c', 40, -75)]);
check(same.length === 3 && same.every(p => Number.isFinite(p.u) && Number.isFinite(p.v)),
  'identical fixes stay finite instead of collapsing to NaN');
check(same.every(p => near(p.u, 0.5, 1e-6) && near(p.v, 0.5, 1e-6)), 'identical fixes stack at the centre');

/* the site keeps its own proportions: a long thin walk is not stretched square */
const strip = planPositions([geoScene('a', 40, -75), geoScene('b', 40, -74.999), geoScene('c', 40.00002, -75)]);
const us = strip.map(p => p.u), vs = strip.map(p => p.v);
check((Math.max(...us) - Math.min(...us)) > (Math.max(...vs) - Math.min(...vs)) * 5,
  'a long east-west walk stays long rather than being stretched to fill the box');
check(strip.every(p => p.u >= 0 && p.u <= 1 && p.v >= 0 && p.v <= 1), 'every dot lands inside the box');
check(strip[0].span >= 1, 'the span is reported in metres and never below the one-metre floor');

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);

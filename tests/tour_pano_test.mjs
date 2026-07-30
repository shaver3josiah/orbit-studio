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
for (const name of ['panoDataFor', 'gpanoCoverage', 'readGps', 'bearing', 'metresBetween',
  'panoProfile', 'guessNavigableYaws', 'sunPosition', 'solarHeading',
  'correlateYaw', 'yawBetween', 'packProfile', 'unpackProfile', 'principalAxis', 'triangulate', 'separateMarks',
  'snapToWay', 'fmtIn', 'defectMeasure', 'defectNeedsMeasure', 'nextDefectCode',
  'csvCell', 'registerCsv', 'planPositions']) {
  if (!source.includes(`function ${name}`)) throw new Error(`extracted block is missing ${name}`);
}

const helpers = await import(
  'data:text/javascript,' + encodeURIComponent(
    `${source}\nexport { clamp, isPartial, vFovOf, panoDataFor, gpanoCoverage, readGps, bearing,` +
    ` metresBetween, wrap180, panoProfile, guessNavigableYaws, snapToWay,` +
    ` sunPosition, solarHeading, correlateYaw, yawBetween, packProfile, unpackProfile,` +
    ` principalAxis, triangulate, separateMarks,` +
    ` fmtIn, defectMeasure, defectNeedsMeasure, nextDefectCode, DEFECT_TYPES, DEFECT_MEASURE,` +
    ` DEPTH_STEPS, WIDTH_STEPS, csvCell, registerCsv, planPositions };`
  )
);
const {
  isPartial, vFovOf, panoDataFor, gpanoCoverage, readGps, bearing, metresBetween, wrap180,
  panoProfile, guessNavigableYaws, snapToWay,
  sunPosition, solarHeading, correlateYaw, yawBetween, packProfile, unpackProfile,
  principalAxis, triangulate, separateMarks,
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

/* Offsets are COMPUTED from the entry list rather than hand-counted, so adding
   a tag cannot silently overlap the data of the one before it. An earlier
   version used literal offsets and every new tag meant re-deriving them. */
function buildJpegWithGps({ latRef = 'N', lonRef = 'W', heading = 123.4,
  headingRef = null, utcDate = null, utcHms = null } = {}) {
  /* [tag, type, count, payload]; ASCII of 4 bytes or fewer rides inline */
  const entries = [
    [1, 2, 2, latRef], [2, 5, 3, [[40, 1], [26, 1], [30, 1]]],
    [3, 2, 2, lonRef], [4, 5, 3, [[79, 1], [59, 1], [0, 1]]],
    [17, 5, 1, [[Math.round(heading * 10), 10]]],
  ];
  if (headingRef) entries.push([16, 2, 2, headingRef]);
  if (utcHms) entries.push([7, 5, 3, utcHms.map(n => [Math.round(n), 1])]);
  if (utcDate) entries.push([29, 2, utcDate.length + 1, utcDate]);

  const n = entries.length;
  const DATA = 26 + 2 + n * 12 + 4;               // GPS IFD start + header + entries + terminator
  let need = 0;
  for (const [, type, count, payload] of entries) {
    if (type === 5) need += count * 8;
    else if (typeof payload === 'string' && count > 4) need += count;
  }
  const TIFF = DATA + need;
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
  v.setUint16(G, n, true);
  let at = DATA;
  entries.forEach(([tag, type, count, payload], i) => {
    const e = G + 2 + i * 12;
    v.setUint16(e, tag, true);
    v.setUint16(e + 2, type, true);
    v.setUint32(e + 4, count, true);
    if (type === 2 && count <= 4) {               // short ASCII lives inline
      for (let k = 0; k < payload.length; k++) v.setUint8(e + 8 + k, payload.charCodeAt(k));
      v.setUint8(e + 8 + payload.length, 0);
    } else if (type === 2) {                      // long ASCII is out of line
      v.setUint32(e + 8, at, true);
      for (let k = 0; k < payload.length; k++) v.setUint8(T + at + k, payload.charCodeAt(k));
      v.setUint8(T + at + payload.length, 0);
      at += count;
    } else {                                      // rationals never fit inline
      v.setUint32(e + 8, at, true);
      payload.forEach(([num, den], k) => {
        v.setUint32(T + at + k * 8, num, true);
        v.setUint32(T + at + k * 8 + 4, den, true);
      });
      at += count * 8;
    }
  });
  v.setUint32(G + 2 + n * 12, 0, true);           // end of the GPS IFD
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

/* ---- the UTC instant, which is what makes a sun angle possible ----
   DateTimeOriginal is a local wall clock with no zone, so it cannot be turned
   into a sun position without guessing an offset — and an hour of guess is
   fifteen degrees of azimuth. GPSDateStamp and GPSTimeStamp are UTC by
   definition, which is why the solar compass reads these and not that. */
check(gps.utc === undefined, 'a photo with no GPS timestamp claims no instant');
const stamped = readGps(buildJpegWithGps({ utcDate: '2024:07:04', utcHms: [15, 30, 45] }));
check(stamped.utc === Date.parse('2024-07-04T15:30:45Z'),
  `GPSDateStamp and GPSTimeStamp read back as one UTC instant (got ${stamped?.utc})`);
check(readGps(buildJpegWithGps({ utcHms: [15, 30, 45] })).utc === undefined,
  'a time with no date is not enough to place the sun, so nothing is claimed');
check(readGps(buildJpegWithGps({ utcDate: '2024:07:04' })).utc === undefined,
  'and a date with no time is not either');

/* 'T' true / 'M' magnetic. On a steel structure that difference is the whole
   point of reading the sun at all, so it must survive the parse. */
check(gps.headingRef === 'T', 'a heading with no ref stated is taken as true north');
check(readGps(buildJpegWithGps({ headingRef: 'M' })).headingRef === 'M',
  'a magnetic heading is recorded as magnetic, not silently trusted as true');

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

/* the two now compose: one read of the pixels, then the reading of the bands */
const waysOf = (image, count = 3, opts = {}) => guessNavigableYaws(panoProfile(image, opts), count);

/* an unlit doorway in a bright wall */
const darkGap = waysOf(pano((x, y) => (x >= 180 && x < 196 ? 20 : 150)), 3);
check(darkGap.length > 0, 'a dark opening in a bright wall is found at all');
check(darkGap.length && Math.abs(wrap180(nearestTo(darkGap, yawOfColumn(188)))) < 200
  && Math.abs(wrap180(nearestTo(darkGap, yawOfColumn(188)) - yawOfColumn(188))) <= 12,
  `the dark opening is located within 12 degrees (got ${darkGap}, wanted near ${yawOfColumn(188).toFixed(1)})`);

/* the same wall, but the doorway opens onto daylight — darkness alone would
   miss this. Confined to eye level, because a real doorway does not reach the
   zenith, and anything that does is glare rather than a way through. */
const brightGap = waysOf(pano((x, y, W, H) => (x >= 60 && x < 76 && y > H * 0.35 ? 240 : 90)), 3);
check(brightGap.length > 0, 'a bright opening in a dark wall is found too, not just a dark one');
check(brightGap.length && Math.abs(wrap180(nearestTo(brightGap, yawOfColumn(68)) - yawOfColumn(68))) <= 12,
  `the bright opening is located within 12 degrees (got ${brightGap}, wanted near ${yawOfColumn(68).toFixed(1)})`);

/* a featureless scene must admit it has nothing to say rather than inventing */
check(waysOf(pano(() => 128)).length === 0,
  'a flat featureless panorama returns no suggestions at all');
check(panoProfile({ width: 0, height: 0, data: new Uint8ClampedArray(0) }) === null,
  'an empty image profiles as null rather than throwing');
check(guessNavigableYaws(null).length === 0, 'no profile yields no suggestions');

/* the sun is bright and is not a doorway: a hot blob high in the sky is excluded */
const sunOnly = waysOf(pano((x, y, W, H) => (y < H * 0.22 && x >= 120 && x < 136 ? 255 : 120)));
check(sunOnly.length === 0, 'a blazing sun in the sky is not offered as somewhere to walk');

/* wrap-around: an opening straddling the seam is one suggestion, not two */
const seam = waysOf(pano((x, y, W) => ((x >= W - 8 || x < 8) ? 20 : 150)), 3);
check(seam.length === 1, `an opening across the 0/360 seam counts once, not twice (got ${seam.length})`);

/* suggestions never crowd each other past what the viewer would dim anyway */
const many = waysOf(pano((x) => ([30, 45, 120, 200].some(c => x >= c && x < c + 10) ? 20 : 150)), 4);
const tooClose = many.some((a, i) => many.some((b, j) => i !== j && Math.abs(wrap180(a - b)) < 45));
check(!tooClose, `suggestions stay at least 45 degrees apart (got ${many})`);

/* ---------- finding the sun in the frame ----------
 * The solar compass is only as good as this: an error in the sun's column is a
 * degree-for-degree error in the heading it produces. */

const sunPano = (cols, rows) => pano((x, y) =>
  (x >= cols[0] && x < cols[1] && y >= rows[0] && y < rows[1]) ? 255 : 120);

/* a 12x8 blob well inside the sky band, centred on column 125.5, row 23.5 */
const sunFound = panoProfile(sunPano([120, 132], [20, 28])).sun;
check(sunFound !== null, 'a blazing blob in the sky is identified as the sun');
check(sunFound && near(sunFound.yaw, -2.81, 2),
  `the sun's bearing within the frame is read off its column (got ${sunFound?.yaw})`);
check(sunFound && near(sunFound.alt, 56.25, 2),
  `and its height above the horizon off its row (got ${sunFound?.alt})`);

/* THE 180-DEGREE BUG. A sun straddling the seam has half its pixels near
   column 0 and half near 255. Averaging those column NUMBERS puts it at 127 —
   the far side of the sphere — and every heading derived from it is 180
   degrees wrong while looking perfectly reasonable. The circular mean is the
   only thing standing between this feature and a confidently mirrored tour. */
const seamSun = panoProfile(pano((x, y, Wp) =>
  ((x >= Wp - 6 || x < 6) && y >= 20 && y < 28) ? 255 : 120)).sun;
check(seamSun !== null, 'a sun across the 0/360 seam is still found');
check(seamSun && Math.abs(seamSun.yaw) > 170,
  `and it is placed at the seam, not at the far side of the sphere (got ${seamSun?.yaw})`);

/* an overcast sky is uniformly bright and contains no sun; treating its
   brightest pixel as one would blank the panorama on every dull day */
check(panoProfile(pano((x, y, Wp, Hp) => (y < Hp * 0.4 ? 200 : 90))).sun === null,
  'flat overcast contains no sun, however bright it is overall');

/* ---------- somebody standing in the shot ---------- */

function rgbPano(paint, Wp = 256, Hp = 128) {
  const data = new Uint8ClampedArray(Wp * Hp * 4);
  for (let y = 0; y < Hp; y++) {
    for (let x = 0; x < Wp; x++) {
      const [r, g, b] = paint(x, y, Wp, Hp);
      const i = (y * Wp + x) * 4;
      data[i] = r; data[i + 1] = g; data[i + 2] = b; data[i + 3] = 255;
    }
  }
  return { width: Wp, height: Hp, data };
}
const CONCRETE = [122, 124, 126];
const VEST = [210, 255, 40];

const inspector = panoProfile(rgbPano((x, y, Wp, Hp) =>
  (x >= 74 && x < 82 && y > Hp * 0.55 && y < Hp * 0.78) ? VEST : CONCRETE)).person;
check(inspector !== null, 'a hi-vis vest in an otherwise grey scene is found');
/* The contract is "the view lands ON the person", not on one nominated pixel
   of them — a vest eight columns wide subtends eleven degrees, and which
   column inside it wins is not something worth pinning down. */
const vestFrom = wrap180(74.5 / 256 * 360 - 180);
const vestTo = wrap180(81.5 / 256 * 360 - 180);
check(inspector && inspector.yaw >= vestFrom - 1 && inspector.yaw <= vestTo + 1,
  `and the view is aimed somewhere on them, ${vestFrom.toFixed(1)} to ${vestTo.toFixed(1)} (got ${inspector?.yaw})`);

check(panoProfile(rgbPano(() => CONCRETE)).person === null,
  'a scene with nobody in it suggests no direction at all');
/* the localisation test: rust on a girder, or a whole field of dry grass, is
   the same colour everywhere. Only a vest is orange in ONE place. */
check(panoProfile(rgbPano(() => [190, 150, 60])).person === null,
  'a uniformly rust-coloured scene is not mistaken for a person standing somewhere');

/* ---------- the big thing standing on the deck ----------
 * Not a truck classifier and not claimed to be: what it finds is a wide mass
 * below the horizon that is brighter or more saturated than the deck itself.
 * The tests that matter are the refusals, because a wrong bearing here drags a
 * triangulated position across the site. */

const DECK = [118, 116, 114];   /* weathered concrete */
const PLANT = [236, 238, 240];  /* a white fleet body */
/* rows below the horizon in a 256x128 equirect are y > 64 */
const onDeck = (from, to, colour) => rgbPano((x, y, Wp, Hp) =>
  (x >= from && x < to && y > Hp * 0.55 && y < Hp * 0.78) ? colour : DECK);

const truck = panoProfile(onDeck(100, 140, PLANT)).vehicle;
check(truck !== null, 'a pale vehicle body on a concrete deck is found');
check(truck && truck.yaw >= wrap180(100 / 256 * 360 - 180) - 3
   && truck.yaw <= wrap180(140 / 256 * 360 - 180) + 3,
  `and the bearing points at it (got ${truck?.yaw})`);
check(truck && truck.width > 40 && truck.width < 70,
  `and its width is reported in degrees, near the 56 it spans (got ${truck?.width})`);

check(panoProfile(rgbPano(() => DECK)).vehicle === null,
  'an empty deck holds no vehicle');
/* THE FALSE POSITIVE THIS IS BUILT AROUND. A vehicle casts a shadow of similar
   width; only counting departures that are BRIGHTER or more saturated keeps
   the bearing on the truck instead of splitting it toward the shadow. */
check(panoProfile(onDeck(100, 140, [58, 57, 56])).vehicle === null,
  'a dark shadow on the deck is not reported as a vehicle');
/* width gates: a cone is too narrow to be plant, and a wholesale exposure
   shift across the deck is not an object at all */
check(panoProfile(onDeck(120, 126, PLANT)).vehicle === null,
  'a traffic cone is too narrow to be plant');
check(panoProfile(rgbPano((x, y, Wp, Hp) => (y > Hp * 0.55 ? PLANT : DECK))).vehicle === null,
  'a deck that is bright end to end is an exposure shift, not a vehicle');

/* the seam again: plant parked across 0/360 must read as ONE object */
const seamTruck = panoProfile(rgbPano((x, y, Wp, Hp) =>
  ((x >= Wp - 20 || x < 20) && y > Hp * 0.55 && y < Hp * 0.78) ? PLANT : DECK)).vehicle;
check(seamTruck !== null, 'plant parked across the seam is still found');
check(seamTruck && Math.abs(seamTruck.yaw) > 168,
  `and reads as one object at the seam, not two at the edges (got ${seamTruck?.yaw})`);

/* ---------- keeping hotspots clickable ----------
 * Only pixels move here. The stored yaw and pitch — what the register, the CSV
 * and the photo log read — are never touched, so the guarantee worth asserting
 * is that a mark still covers the point it marks. */

const box = (x, y, w = 40) => ({ x, y, w, h: w });
const applied = marks => separateMarks(marks).map((o, i) => ({
  x: marks[i].x + o.dx, y: marks[i].y + o.dy, w: marks[i].w, h: marks[i].h, o,
}));
const clashes = boxes => {
  let n = 0;
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      if (Math.abs(boxes[i].x - boxes[j].x) < (boxes[i].w + boxes[j].w) / 2
       && Math.abs(boxes[i].y - boxes[j].y) < (boxes[i].h + boxes[j].h) / 2) n++;
    }
  }
  return n;
};

check(separateMarks([]).length === 0, 'nothing to separate does not throw');
const alone = separateMarks([box(10, 10)]);
check(alone[0].dx === 0 && alone[0].dy === 0, 'a lone mark is never moved');

/* already clear: must be left exactly alone, or every pan would jitter */
const clear = separateMarks([box(0, 0), box(300, 0), box(0, 300)]);
check(clear.every(o => o.dx === 0 && o.dy === 0), 'marks that already clear each other are not touched');

/* THE CASE FROM THE REAL TOUR: two auto-links on one bearing, pixel-identical.
   With no direction to push along, a naive relaxation leaves them stacked. */
const stacked = applied([box(200, 200), box(200, 200)]);
check(clashes(stacked) === 0, 'two marks at the very same spot are pulled apart');
check(stacked.every(m => Math.hypot(m.o.dx, m.o.dy) > 0), 'both of them move, not just one');

/* Three on one spot cannot ALL clear each other: they would need centres 44px
   apart while each stays within 20px of the shared point, and 3 points on a
   20px circle are at most ~35px apart. The cap wins that argument on purpose.
   What must still hold is that every mark ends up somewhere of its own with
   exposed area to press, rather than three pins hiding under one. */
const trio = applied([box(200, 200), box(200, 200), box(200, 200)]);
const spread = (bs) => {
  let worst = Infinity;
  for (let i = 0; i < bs.length; i++) {
    for (let j = i + 1; j < bs.length; j++) worst = Math.min(worst, Math.hypot(bs[i].x - bs[j].x, bs[i].y - bs[j].y));
  }
  return worst;
};
/* The contract that decides whether this feature works at all: a mark's OWN
   CENTRE must not end up underneath another mark. Centre clear means there is
   always somewhere to press that belongs to this hotspot and no other — which
   is the whole point, and is achievable under the cap where "no overlap at
   all" is not. */
const centresClear = bs => bs.every((m, i) => bs.every((o, j) =>
  i === j || Math.abs(m.x - o.x) >= o.w / 2 || Math.abs(m.y - o.y) >= o.h / 2));
check(centresClear(trio), `a stack of three fans out so each keeps its own centre (closest pair ${spread(trio).toFixed(1)}px)`);
check(new Set(trio.map(m => `${m.x.toFixed(1)},${m.y.toFixed(1)}`)).size === 3,
  'and all three end up somewhere of their own');
check(centresClear(applied([box(50, 50), box(50, 50), box(50, 50), box(50, 50)])),
  'and so does a stack of four, which is the most the cap can fit');

/* partial overlap resolves along the shorter axis */
const nudgedPair = applied([box(100, 100), box(120, 100)]);
check(clashes(nudgedPair) === 0, 'a partly overlapping pair is separated');
check(Math.abs(nudgedPair[0].y - 100) < 0.01 && Math.abs(nudgedPair[1].y - 100) < 0.01,
  'and separated sideways, the way they were already offset, not vertically');

/* THE HONESTY GUARANTEE. However crowded it gets, a mark may never travel far
   enough to stop covering the point it marks — a defect pin off its defect is
   worse than one you have to zoom in to separate. */
const crowd = [];
for (let i = 0; i < 12; i++) crowd.push(box(200, 200));
const crowded = applied(crowd);
check(crowded.every(m => Math.hypot(m.o.dx, m.o.dy) <= 40 * 0.5 + 1e-9),
  'no mark is ever moved further than its own radius, however crowded');
check(crowded.every(m => Number.isFinite(m.x) && Number.isFinite(m.y)),
  'and a heavy pile-up stays finite rather than flying apart');

/* Different sizes: each mark is capped by ITS OWN radius, so a 24px pin can
   only give 12px and a 96px one 48px — 60px between them where clearing would
   need 64px. They end up touching rather than stacked, and, importantly, the
   small one is not dragged halfway across the photo to satisfy the big one. */
const mixedSize = applied([box(100, 100, 24), box(100, 100, 96)]);
check(Math.hypot(mixedSize[0].o.dx, mixedSize[0].o.dy) <= 12 + 1e-9,
  'a small mark is capped by its own radius, not its neighbour’s');
check(Math.hypot(mixedSize[1].o.dx, mixedSize[1].o.dy) <= 48 + 1e-9,
  'and the large one by its own');
check(spread(mixedSize) > 24, `and they still part enough to press (${spread(mixedSize).toFixed(1)}px)`);

/* ---------- crossing the sightings ---------- */

/* two photos 20 m apart, both seeing something at a bearing that crosses */
const cross = triangulate([
  { x: 0, y: 0, bearing: 45 },
  { x: 20, y: 0, bearing: 315 },
]);
check(cross && near(cross.x, 10, 1e-6) && near(cross.y, 10, 1e-6),
  `two bearings cross where they actually meet (got ${cross && `${cross.x.toFixed(2)},${cross.y.toFixed(2)}`})`);

/* bearings are clockwise from north, same as everything else here */
const due = triangulate([
  { x: 0, y: 0, bearing: 90 },    /* due east  */
  { x: 30, y: -30, bearing: 0 },  /* due north */
]);
check(due && near(due.x, 30, 1e-6) && near(due.y, 0, 1e-6),
  `due east from one and due north from another meet at the corner (got ${due && `${due.x.toFixed(2)},${due.y.toFixed(2)}`})`);

/* THE ONE THAT MATTERS. Nearly parallel bearings cross at a point enormously
   sensitive to a fraction of a degree — the classic way a confident-looking
   triangulation lands miles away. It must refuse, not answer. */
check(triangulate([{ x: 0, y: 0, bearing: 90 }, { x: 0, y: 1, bearing: 90.4 }]) === null,
  'two near-parallel bearings refuse to name a crossing');
check(triangulate([{ x: 0, y: 0, bearing: 90 }, { x: 0, y: 40, bearing: 270 }]) === null,
  'and so do two bearings pointing straight at each other along one line');
check(triangulate([{ x: 0, y: 0, bearing: 45 }]) === null, 'one sighting is not a crossing');
check(triangulate([]) === null && triangulate(undefined) === null, 'no sightings does not throw');

/* more sightings should sharpen, not break: a third that agrees keeps it put */
const three = triangulate([
  { x: 0, y: 0, bearing: 45 }, { x: 20, y: 0, bearing: 315 }, { x: 10, y: -10, bearing: 0 },
]);
check(three && near(three.x, 10, 0.5) && near(three.y, 10, 0.5),
  `a third agreeing sighting keeps the answer put (got ${three && `${three.x.toFixed(2)},${three.y.toFixed(2)}`})`);

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
/* ---------- the sun as a compass ----------
 * Reference azimuths and altitudes are from an independent IAU SOFA/ERFA chain
 * (erfa.epv00 + ab + pnm06a + gst06a), itself checked against Meeus Example
 * 25.b to 0.05 arcsec. The tolerance here is 0.05 deg, twice the largest
 * deviation measured over 200,000 random samples — tight enough that a sign
 * error or a dropped term fails it, loose enough never to fail on rounding. */

const SUN_CASES = [
  ['2000-06-21T18:00:00Z', 40, -105, 137.14360, 68.9149, 'Boulder, northern summer solstice'],
  ['2000-12-21T18:00:00Z', 40, -105, 165.20636, 25.1181, 'Boulder, northern winter solstice'],
  ['2025-03-20T09:01:00Z', 51.478, 0, 126.49673, 25.3363, 'Greenwich, March equinox'],
  ['2050-07-10T02:00:00Z', -33.8688, 151.2093, 0.16458, 33.9174, 'Sydney, southern winter noon'],
  ['2075-09-05T15:00:00Z', 0, -78.5, 78.06159, 56.2619, 'Quito, on the equator'],
  ['2100-12-21T11:00:00Z', 59.914, 10.752, 176.55853, 6.6024, 'Oslo, sun only 6.6 deg up'],
  ['2099-08-08T16:45:00Z', -60, -60, 350.27939, 13.6993, 'latitude -60, southern winter'],
];
for (const [iso, lat, lon, az, alt, label] of SUN_CASES) {
  const got = sunPosition(Date.parse(iso), lat, lon);
  check(near(got.azimuth, az, 0.05) && near(got.altitude, alt, 0.05),
    `${label}: sun at ${az.toFixed(2)} deg / ${alt.toFixed(2)} up ` +
    `(got ${got.azimuth.toFixed(2)} / ${got.altitude.toFixed(2)})`);
}

/* The convention checks. Each of these fails loudly on a whole class of bug
   that the accuracy cases above would also catch, but far less legibly. */
const noonN = sunPosition(Date.parse('2025-06-21T12:00:00Z'), 40, 0).azimuth;
check(noonN > 170 && noonN < 190, `northern local noon puts the sun due SOUTH (got ${noonN.toFixed(1)})`);
const noonS = sunPosition(Date.parse('2025-06-21T12:00:00Z'), -33, 0).azimuth;
check(noonS < 15 || noonS > 345, `southern local noon puts the sun due NORTH (got ${noonS.toFixed(1)})`);
const morning = sunPosition(Date.parse('2025-03-20T08:00:00Z'), 40, 0).azimuth;
const evening = sunPosition(Date.parse('2025-03-20T16:00:00Z'), 40, 0).azimuth;
check(morning > 80 && morning < 140, `the morning sun is in the EAST (got ${morning.toFixed(1)})`);
check(evening > 220 && evening < 280, `the evening sun is in the WEST (got ${evening.toFixed(1)})`);
/* longitude east-positive: 30 degrees east sees noon two hours earlier */
check(sunPosition(Date.parse('2025-03-20T12:00:00Z'), 0, 15).azimuth > 180
   && sunPosition(Date.parse('2025-03-20T12:00:00Z'), 0, -15).azimuth < 180,
  'longitude enters east-positive: the sun is already past noon to the east');
/* the leap day is the platform's job, but a calendar bug here would jump a degree */
const leapA = sunPosition(Date.parse('2024-02-28T12:00:00Z'), 45, 0).azimuth;
const leapB = sunPosition(Date.parse('2024-03-01T12:00:00Z'), 45, 0).azimuth;
check(Math.abs(leapA - leapB) < 1, 'the sun does not jump across a leap day');

/* ---- turning that into the photo's heading ---- */

const AT = Date.parse('2000-06-21T18:00:00Z'); /* Boulder: sun at 137.14, 68.91 up */
const boulder = { lat: 40, lon: -105, utc: AT };
/* the sun sits dead centre of the frame, so the frame faces the sun */
check(near(solarHeading({ yaw: 0, alt: 68.91 }, boulder), 137.14, 0.05),
  'a sun in the middle of the frame means the frame faces the sun');
/* the sun 90 deg to the RIGHT of centre means the centre is 90 deg anticlockwise of it */
check(near(solarHeading({ yaw: 90, alt: 68.91 }, boulder), 47.14, 0.05),
  'a sun to the right of centre puts the centre anticlockwise of the sun');
check(near(solarHeading({ yaw: -90, alt: 68.91 }, boulder), 227.14, 0.05),
  'and a sun to the left puts it clockwise, wrapping into 0-360');

/* the guard that makes this trustworthy: the formula says how high the sun is,
   so a bright thing at the wrong height is not the sun and gets no answer */
check(solarHeading({ yaw: 0, alt: 10 }, boulder) === null,
  'a bright blob 59 degrees below where the sun actually is claims nothing');
check(solarHeading({ yaw: 0, alt: 68.91 }, { ...boulder, utc: undefined }) === null,
  'no UTC instant means no solar heading — a local wall clock will not do');
check(solarHeading(null, boulder) === null, 'no sun found means no heading');
check(solarHeading({ yaw: 0, alt: 0 }, { lat: 40, lon: -105, utc: Date.parse('2000-06-22T04:00:00Z') }) === null,
  'a sun below the horizon is refused, however bright the blob');

/* ---------- matching two photos by their landmarks ---------- */

const W = 256;
/* A profile shaped like something a camera actually produces: big features on
   top of the fine texture every real surface has. The texture is not decoration
   — the matching REFUSES a profile too smooth to correlate, and a bare sum of
   gaussians on a flat baseline crosses its own mean only four times, which no
   photograph of concrete and steel ever does. An earlier version of this test
   used the bare version and read the refusal as a bug in the matcher. */
const bumpy = (shift = 0, gain = 1, lift = 0) => {
  const out = new Float64Array(W).fill(140);
  /* three unlike features: a pier, a parked truck, a gap in the parapet */
  for (const [c, w, h] of [[30, 9, -70], [96, 20, 45], [180, 5, -100]]) {
    for (let k = -3 * w; k <= 3 * w; k++) out[(c + k + W) % W] += h * Math.exp(-(k * k) / (w * w));
  }
  /* deterministic pseudo-texture, so the test result never depends on a seed */
  for (let i = 0; i < W; i++) {
    out[i] += 9 * Math.sin(i * 1.7) + 6 * Math.sin(i * 0.41 + 2) + 5 * Math.sin(i * 3.3 + 1);
  }
  const rolled = new Float64Array(W);
  for (let i = 0; i < W; i++) rolled[(i + shift) % W] = out[i] * gain + lift;
  return rolled;
};

const clean = correlateYaw(bumpy(), bumpy(37));
check(clean.shiftColumns === 37, `a known 37-column turn is recovered exactly (got ${clean.shiftColumns})`);
/* 0.35 is the bar yawBetween applies before it will use an answer. Asserting
   the shipping threshold rather than a rounder number keeps this test honest:
   surface texture legitimately costs confidence, and a match that clears the
   gate is the whole claim being made. */
check(clean.confidence >= 0.35, `and clears the confidence gate (got ${clean.confidence})`);
check(correlateYaw(bumpy(), bumpy(37, 1.5, 20)).shiftColumns === 37,
  'a photo shot brighter still matches: the profiles are normalised first');

/* A row of identical piers correlates perfectly at every pier spacing and
   picks an arbitrary one — measured 180 degrees wrong. Bridges are full of
   repeating structure, so this refusal is not hypothetical. */
const piers = new Float64Array(W), piersTurned = new Float64Array(W);
for (let i = 0; i < W; i++) piers[i] = 140 + 60 * Math.cos(2 * Math.PI * i / 32);
for (let i = 0; i < W; i++) piersTurned[(i + 37) % W] = piers[i];
check(correlateYaw(piers, piersTurned).confidence < 0.1,
  'evenly spaced piers refuse to name a turn rather than naming the wrong one');

check(correlateYaw(new Float64Array(W).fill(7), bumpy()).confidence === 0, 'a flat profile matches nothing');
check(correlateYaw(null, null).confidence === 0, 'a missing profile does not throw');
check(correlateYaw(bumpy(), new Float64Array(8)).confidence === 0, 'mismatched lengths refuse');

/* THE ONE THAT MATTERS. Two photos with a single broad feature each — a deck
   soffit, a flat overcast horizon — share nothing, but their correlation is one
   smooth lobe with no rival to beat. Read naively that scores FULL confidence
   at a 180-degree error. Measured at 1.0000 before the input precondition was
   added, which is why the guard is on the profile and not on the threshold. */
const oneLobe = at => {
  const out = new Float64Array(W).fill(120);
  for (let k = -60; k <= 60; k++) out[(at + k + W) % W] += 60 * Math.exp(-(k * k) / (30 * 30));
  return out;
};
const smoothPair = correlateYaw(oneLobe(20), oneLobe(200));
check(smoothPair.confidence === 0,
  `two unrelated near-flat photos claim NOTHING (got confidence ${smoothPair.confidence})`);

/* the two bands together, which is the only way this is allowed to be used */
const sceneOf = (wallShift, floorShift, hFov) => ({
  prof: packProfile({ W, wall: bumpy(wallShift), floor: bumpy(floorShift) }),
  ...(hFov ? { pano: { hFov } } : {}),
});
const agreed = yawBetween(sceneOf(0, 0), sceneOf(37, 37));
check(agreed && near(agreed.degrees, wrap180(37 * 360 / W), 0.01),
  `two agreeing bands give the turn in degrees (got ${agreed && agreed.degrees})`);
check(yawBetween(sceneOf(0, 0), sceneOf(37, 120)) === null,
  'bands that disagree about the turn refuse rather than picking one');
check(yawBetween(sceneOf(0, 0), sceneOf(37, 37, 180)) === null,
  'photos covering different amounts of sphere are not comparable');
check(yawBetween({}, sceneOf(0, 0)) === null, 'a scene with no stored profile refuses');

/* ---- the profile survives the round trip it is stored through ---- */
const packed = packProfile({ W, wall: bumpy(), floor: bumpy(11) });
check(typeof packed === 'string' && packed.length > 100, 'a profile packs to a string');
const back = unpackProfile(packed);
check(back && back.W === W, 'and unpacks to the same width');
check(back && correlateYaw(back.wall, bumpy(37)).shiftColumns === 37,
  'a byte per column loses nothing the matching can see');
check(unpackProfile(null) === null && unpackProfile('') === null && unpackProfile('!!!') === null,
  'a missing or corrupt profile unpacks to null rather than throwing');
check(packProfile(null) === null, 'nothing to pack packs to nothing');

/* ---------- plan view geometry ---------- */

const geoScene = (id, lat, lon) => ({ id, geo: { lat, lon } });
check(planPositions([]) === null, 'no scenes plots nothing');
check(planPositions(undefined) === null, 'a missing list does not throw');
check(planPositions([geoScene('a', 40, -75)]) === null, 'one photo is not a plan');

/* a north-south pair: north must sit ABOVE south on screen */
const ns = planPositions([geoScene('s', 40.0000, -75), geoScene('n', 40.0010, -75)]);
check(ns.pts.length === 2, 'two located photos plot');
check(ns.pts.find(p => p.id === 'n').v < ns.pts.find(p => p.id === 's').v, 'north plots above south');
check(near(ns.pts.find(p => p.id === 'n').u, 0.5, 0.02) && near(ns.pts.find(p => p.id === 's').u, 0.5, 0.02),
  'a due north-south pair shares one column');
check(ns.scaled && ns.pts.every(p => p.kind === 'gps'), 'a fully located tour is to scale and all GPS');

/* an east-west pair: east must sit RIGHT of west */
const ew = planPositions([geoScene('w', 40, -75.0010), geoScene('e', 40, -75.0000)]);
check(ew.pts.find(p => p.id === 'e').u > ew.pts.find(p => p.id === 'w').u, 'east plots right of west');

/* every photo standing on one fix must not divide by zero */
const same = planPositions([geoScene('a', 40, -75), geoScene('b', 40, -75), geoScene('c', 40, -75)]);
check(same.pts.length === 3 && same.pts.every(p => Number.isFinite(p.u) && Number.isFinite(p.v)),
  'identical fixes stay finite instead of collapsing to NaN');
check(same.pts.every(p => near(p.u, 0.5, 1e-6) && near(p.v, 0.5, 1e-6)), 'identical fixes stack at the centre');

/* the site keeps its own proportions: a long thin walk is not stretched square */
const strip = planPositions([geoScene('a', 40, -75), geoScene('b', 40, -74.999), geoScene('c', 40.00002, -75)]);
const us = strip.pts.map(p => p.u), vs = strip.pts.map(p => p.v);
check((Math.max(...us) - Math.min(...us)) > (Math.max(...vs) - Math.min(...vs)) * 5,
  'a long east-west walk stays long rather than being stretched to fill the box');
check(strip.pts.every(p => p.u >= 0 && p.u <= 1 && p.v >= 0 && p.v <= 1), 'every dot lands inside the box');
check(strip.span >= 1, 'the span is reported in metres and never below the one-metre floor');

/* ---- the guessed layout, which is new: a photo with no fix is placed rather
   than dropped, but it is placed as a GUESS and says so ---- */
const mixed = planPositions([geoScene('a', 40, -75), geoScene('b', 40, -74.999), { id: 'c' }]);
check(mixed.pts.length === 3, 'a photo with no fix is still placed on the plan');
check(mixed.pts.find(p => p.id === 'c').kind === 'guess', 'and it is labelled a guess, not a position');
check(mixed.pts.filter(p => p.kind === 'gps').length === 2, 'the located ones stay labelled gps');

const noneLocated = planPositions([{ id: 'a' }, { id: 'b' }, { id: 'c' }]);
check(noneLocated.pts.length === 3, 'a tour with no GPS at all still gets a plan to correct');
check(!noneLocated.scaled, 'but it is explicitly NOT to scale, because nothing here knows the size');
check(noneLocated.pts.every(p => p.kind === 'guess'), 'and every dot on it is a guess');

/* a hand-placed position outranks GPS, because a human said so */
const dragged = planPositions([
  { id: 'a', geo: { lat: 40, lon: -75 }, plan: { x: 100, y: 0 } },
  geoScene('b', 40, -75.001),
]);
check(dragged.pts.find(p => p.id === 'a').kind === 'manual', 'a dragged photo is marked manual');
check(dragged.pts.find(p => p.id === 'a').u > dragged.pts.find(p => p.id === 'b').u,
  'and it plots where it was dragged to, not where its GPS says');

/* Metres in, metres out. The drag handler stores what toMetres returns and the
   next render puts the dot back through toBox, so a dot dropped somewhere must
   redraw in the same place — otherwise every correction slides on save. */
const roundTrip = planPositions([geoScene('a', 40, -75), geoScene('b', 40, -75.001)]);
const anchor = roundTrip.pts.find(p => p.id === 'a');
const asMetres = roundTrip.toMetres(anchor.u, anchor.v);
const redrawn = planPositions([
  { id: 'a', geo: { lat: 40, lon: -75 }, plan: asMetres },
  geoScene('b', 40, -75.001),
]).pts.find(p => p.id === 'a');
check(near(redrawn.u, anchor.u, 1e-6) && near(redrawn.v, anchor.v, 1e-6),
  `a dot dropped where it already was redraws in the same place (${anchor.u.toFixed(4)} -> ${redrawn.u.toFixed(4)})`);

/* the drawn structure follows the long axis of whatever is pinned down */
const axis = principalAxis([{ x: 0, y: 0 }, { x: 10, y: 0 }, { x: 20, y: 0 }]);
check(near(Math.abs(axis.dx), 1, 1e-6) && near(axis.dy, 0, 1e-6), 'an east-west row of photos gives an east-west axis');
const axisNS = principalAxis([{ x: 0, y: 0 }, { x: 0, y: 10 }, { x: 0, y: 20 }]);
check(near(axisNS.dx, 0, 1e-6) && near(Math.abs(axisNS.dy), 1, 1e-6), 'a north-south row gives a north-south axis');
check(Number.isFinite(principalAxis([{ x: 3, y: 3 }]).dx), 'one point does not produce NaN for an axis');
const deckSpan = Math.hypot(strip.deck.to.u - strip.deck.from.u, strip.deck.to.v - strip.deck.from.v);
check(deckSpan > 0.1 && strip.deck.from.u >= -0.01 && strip.deck.to.u <= 1.01,
  'the drawn structure spans the plate and stays inside it');

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);

# Orbit Tour

A self-hosted replacement for Kuula.co Pro. Your 360° photos become an
explorable virtual tour that lives on your machine, embeds in any website,
and exports as a folder you can host anywhere.

Nothing is uploaded to a third party, there is no monthly plan, no upload
cap, and no vendor watermark to pay to remove.

## Start it

Needs only **Python 3** — no libraries to install, no `setup.ps1`.

Double-click **`tour.bat`**. It starts the server and opens the app. If Python
isn't installed it tells you where to get it. That's the whole setup.

Or from a terminal:

```bash
python server.py
```

Then open <http://localhost:7360/tour>.

On first run a **"Sample Apartment"** tour is copied in so you have something
to walk through before you own any 360 photos. Delete it whenever — it's a
normal tour, and deleting it sticks: it won't come back on the next start.
Deleting anything — a tour, a scene, a hotspot — is two clicks on purpose:
the button turns into **Confirm delete?** (a scene or hotspot just says
**Confirm?**) and only fires if you click it again within a few seconds.

## Make a tour

1. **New tour** — name it, and the editor opens.
2. **Drop photos** into the left panel. Each becomes a scene — nothing is
   turned away for its shape any more:
   - A true 2:1 equirectangular photo (the Insta360 export) is still the
     ideal case, and becomes a full 360×180 sphere.
   - A photo carrying GPano metadata (Google Camera's "Photo Sphere", some
     phone panoramas) has its exact covered slice read straight out of the
     metadata — no guessing.
   - A phone sweep panorama (a wide strip, no metadata) has its width
     guessed from its aspect ratio and mapped onto the matching patch of
     sphere. The guess can be corrected afterwards — see the next step —
     and whatever it doesn't cover is filled with the sky and ground
     colours taken from the photo itself, rather than left black.
   - An ordinary snapshot becomes a small window in the sphere.

   A JPEG, PNG, or WebP photo uploads exactly as it came off the camera —
   none of those are re-encoded. HEIC/HEIF photos, and anything else only
   some browsers can decode, are converted to JPEG in the browser before
   upload, at quality 0.95 and capped to whatever this machine's graphics can
   hold as a texture (beyond that the viewer would scale it down anyway), so
   an exported tour renders everywhere it's shared. Some browsers — Chrome on
   Windows, for one — cannot decode HEIC at all; the editor's error message
   explains how to get JPEGs off an iPhone instead. Scene thumbnails in the
   list are sharp even on a high-density screen.

   A photo that fails to upload no longer pops its own toast that erases the
   one before it — failures collect into a single persistent line under the
   drop zone, grouped by reason rather than by file, so a folder of HEICs
   that all fail the same way on Windows reads as one sentence with a count
   instead of a flurry of vanishing messages. That line says what kind of
   file is wanted and whether the server is reachable, instead of the
   server's raw internal file-type list or a bare "Failed to fetch".
3. **Correct the shape, if it needs it.** Whichever scene you're editing,
   the sidebar's **This scene** section shows two sliders: **Width**, how
   many degrees across the photo covers (30–360, the sphere reshapes live
   as you drag), and **Tilt**, the elevation of the photo's centre, for a
   shot aimed up or down. A note underneath states the resulting coverage,
   for example "Covers 200° across and 40° up and down. The rest takes its
   sky and ground from the photo." There's no separate vertical
   field-of-view control:
   an equirectangular projection shares one degrees-per-pixel scale across
   both axes, so the vertical coverage follows from the width and the
   photo's own aspect ratio. The same section also states the photo's actual
   pixel dimensions and whether it uploaded untouched or got converted, and
   warns you if it's wider than this machine's graphics can hold.
4. **Connect the scenes.** Click **+ Link hotspot**: the hotspot follows your
   pointer around the panorama — already showing the shape, size, and
   opacity it will actually have — until you click the spot where the way
   there is. The hotspot panel then opens asking which scene it leads to,
   starting on *Choose a scene…* rather than guessing; until you pick one,
   the marker's tooltip reads "Not connected yet — click to choose a scene."
   The same cursor-following placement is used for *Info* and *URL*
   hotspots (next step) and for **Move**.

   For laying out a whole floor at speed, **Build links** is the quicker
   alternative: it arms a target scene first, so you click doorway after
   doorway without opening a panel each time. Click **Build links** to enter
   build mode. A blue bar appears under the toolbar reading *Walk to*, with a
   row of scene chips — thumbnail and name, ticked if this scene already
   links there. Click a chip to arm it, then click the spot in the panorama
   where the way there is. Build mode stays on afterwards, so you can keep placing
   links — walking the tour scene to scene, laying arrows as you go —
   without opening a menu each time, and it survives switching scenes.
   **Add the way back too**, ticked by default, also creates the reverse
   link in the target scene, so you never get a one-way dead end. Escape,
   or **Done linking**, exits build mode. Clicking into the part of a
   partial panorama the photo doesn't cover, or onto an existing hotspot
   while a link is armed, both used to do nothing — the first now says the
   photo doesn't reach that far, the second now places the link there anyway.

   Every photo dropped in is also read for the directions in it that look
   walkable — a doorway, a hallway, an opening — and any link the automatic
   linker places is nudged onto the nearest of those guesses instead of
   aiming at a raw bearing or the middle of the photo.

   Rather than placing every link by hand, **Connect scenes for me** in the
   left panel takes a guess, and the sidebar prints which of two tiers it
   used. With two or more photos carrying an EXIF GPS fix, it links each
   scene to its two nearest neighbours within 60 metres; if those photos
   also recorded a compass heading (GPSImgDirection), each arrow is aimed
   from it, otherwise the sidebar says the directions are guessed. Phone GPS
   is only good to a few metres, so it's worth checking each arrow anyway.
   With no GPS at all, it chains the scenes in the order they were added —
   the order the walk was shot in — and any photo with no location is
   chained to its list neighbours so it never drops out of the tour. Every
   link is made both ways, existing links are never duplicated, and the
   whole run is one Ctrl+Z. It also now runs on its own right after a drop
   of photos that don't yet connect to anything, so a tour never sits
   unlinked just because nobody clicked the button — it steps aside once the
   arriving scenes already have links, so it won't fight you mid-edit.

   A small red dot next to a scene in the list means nothing links to it
   yet (and it isn't the start scene) — it would be unreachable in the
   finished tour. A line under the list also states how many scenes that
   is, in case the dot itself is easy to miss.
5. **Add detail.** *+ Info hotspot* and *+ URL hotspot* place the same way as
   a link — click the button, click the spot in the panorama — and then open
   a panel: a title, text, an image, and an audio clip for Info; an external
   page (opened in a new tab) for URL. Click any hotspot — a link arrow
   included — to reopen this same panel and choose its look: a row of shapes
   (Circle, Square, Diamond, Ring, Dot, and, for a link only, Arrow on the
   floor — the viewer's own arrow, which is the one shape that lies flat on
   the ground; Square is the default for links, Circle for the rest), a Size
   slider, and an Opacity slider. Hotspots stand upright facing you rather
   than lying on the path, and a link's arrow points up — forward, the way
   you'd walk. Leave all three untouched and a hotspot looks exactly as it
   always did. The next hotspot you place — by hand or
   via Connect scenes for me — inherits whatever the last one was styled as,
   so a look gets chosen once rather than forty times.

   To reposition a hotspot, press it and drag — the panorama holds still and
   the hotspot follows your pointer, touch included. The grab area reaches
   18px beyond the hotspot itself, so even the smallest dot has a target
   worth touching, and hovering one glows to show it. One drag is one Undo.
   A press and release with no movement in between opens the panel instead
   of doing nothing. **Move**, in the same panel, is kept as the way to do
   it without a pointer.
6. **Aim each scene.** Drag to the view a visitor should land on, then
   *Set start view from here*.
7. **Try it, then share.** **Start tour**, in the toolbar, saves and runs the
   real viewer in a frame right over the editor, so you can click through
   your own links and come back — **Back to editing**, or Escape, closes it.
   **Preview tour**, in the Share panel, opens the same tour in a separate
   tab instead, the right one to send someone else to look at.

Everything saves as you work — the status under the tour name reads
*Saving…* then *Saved*. A failed save retries on its own and says so.

## Share it

| What | Where |
|---|---|
| Link | Share panel → **Link** → Copy. Anyone on your network can open it. |
| Website embed | Share panel → **Embed** → Copy the `<iframe>`. Already carries the permissions gyroscope, VR, and fullscreen need. |
| Standalone site | Share panel → **Download standalone site**. |

The standalone export is the one Kuula never gives you: a zip holding the
viewer, the rendering libraries, and your photos. Unzip it onto GitHub Pages,
Netlify, S3, or any web server and the tour runs with no Orbit Studio, no
Python, and no network calls home. Opening `index.html` straight from disk
will not work — browsers block ES modules on `file://` — so serve the folder,
even locally:

```bash
python -m http.server 8000
```

## Viewer features

Autorotate with adjustable speed, little-planet intro that unfolds into the
scene, scene gallery, gyroscope look-around on phones, stereo VR mode,
fullscreen, background audio with a mute control, and your logo in the corner
with an optional link. Reduced-motion settings are respected: the intro and
autorotate stay off, manual controls all still work. The little-planet intro
is also skipped automatically when the start scene is a partial panorama —
there is no nadir to unfold from.

## Where things live

```
tour/index.html      the whole app — home, editor, viewer
tour/vendor/         Photo Sphere Viewer 5.14.3 + three.js 0.184.0, pinned
tours/<tour-id>/     tour.json + files/  (gitignored: this is your content)
```

Version pins and upgrade rules are in `tour/vendor/VERSIONS.md`. The
libraries are lockstepped — bump them together or not at all.

## API

| Method | Path | Does |
|---|---|---|
| GET | `/api/tours` | list |
| POST | `/api/tours` | create `{name}` |
| GET | `/api/tours/<id>` | fetch one |
| POST | `/api/tours/<id>` | save the whole doc |
| DELETE | `/api/tours/<id>` | delete tour and its files |
| POST | `/api/tours/<id>/duplicate` | copy tour + media |
| POST | `/api/tours/<id>/files` | upload media (multipart `file`) |
| GET | `/api/tours/<id>/files/<name>` | serve media |
| GET | `/api/tours/<id>/export.zip` | standalone static site |

Uploads are capped at 64 MB and limited to jpg/png/webp/mp3/m4a/ogg on the
wire — HEIC and any other non-web-safe image is converted to JPEG in the
browser before it uploads, so this list is unchanged even though the editor
now accepts photos it wouldn't have before. Files
stop being served once nothing in the tour references them, but only after a
day of going unreferenced — long enough that a just-uploaded photo is never
collected before the editor saves a reference to it, and that undoing a scene
deletion always finds its photo still there. Deleting a tour removes its
files immediately.

## Checks

```bash
python tests/tour_smoke_test.py
```

30 checks across the tour API: CRUD, upload validation, the prune grace
window, path-traversal and malformed-id rejection, oversize rejection,
duplicate, and the export bundle's contents.

```bash
node tests/tour_pano_test.mjs
```

58 checks on the coverage geometry, GPano XMP reading, EXIF GPS parsing (from
JPEG APP1, and now PNG eXIf and WebP EXIF chunks too), bearing maths, and the
walkable-direction guess that suggests where hotspots go.

`python tests/seed_demo_tour.py` builds a demo tour with three generated
panoramas and a hotspot ring, useful for looking at the viewer without a
camera handy.

`python tests/seed_mixed_tour.py` builds one photo of each shape — a full
equirect, a phone sweep, a GPano photo sphere, and a plain snapshot — each
carrying EXIF GPS, so the editor can be exercised without owning a phone or a
360 camera. `--files-only` writes just the JPEGs.

## Security posture

The server binds `127.0.0.1` unless started with `--lan`. The API answers
cross-origin requests only from localhost, so a random website you visit
cannot reach in and delete your tours. There is no login: anything that can
reach the port can edit. On a shared or public network, put it behind a
reverse proxy with auth rather than exposing the port directly.

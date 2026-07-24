# Orbit Tour

A self-hosted replacement for Kuula.co Pro. Your 360° photos become an
explorable virtual tour that lives on your machine, embeds in any website,
and exports as a folder you can host anywhere.

Nothing is uploaded to a third party, there is no monthly plan, no upload
cap, and no vendor watermark to pay to remove.

## Start it

```bash
python server.py
```

Then open <http://localhost:7360/tour>.

`start.bat` does the same thing and opens the studio; the tour app is one
click away at `/tour`.

## Make a tour

1. **New tour** — name it, and the editor opens.
2. **Drop 360° photos** into the left panel. Each becomes a scene. Photos must
   be equirectangular (width exactly 2× height) — the same export the Insta360
   app produces. Anything else is rejected with the reason, before it uploads.
3. **Connect the scenes.** Click *+ Link hotspot*, then click the spot in the
   panorama where the doorway is. Pick which scene it leads to. Repeat until
   the tour walks the way the building does.
4. **Add detail.** *+ Info hotspot* opens a panel with a title, text, an image,
   and an audio clip. *+ URL hotspot* opens an external page in a new tab.
5. **Aim each scene.** Drag to the view a visitor should land on, then
   *Set start view from here*.
6. **Preview**, then share.

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
autorotate stay off, manual controls all still work.

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

Uploads are capped at 64 MB and limited to jpg/png/webp/mp3/m4a/ogg. Files
stop being served once nothing in the tour references them, with a five
minute grace window so a fresh upload is never collected before the editor
has saved a reference to it.

## Checks

```bash
python tests/tour_smoke_test.py
```

27 checks across the tour API: CRUD, upload validation, the prune grace
window, path-traversal rejection, oversize rejection, duplicate, and the
export bundle's contents.

`python tests/seed_demo_tour.py` builds a demo tour with three generated
panoramas and a hotspot ring, useful for looking at the viewer without a
camera handy.

## Security posture

The server binds `127.0.0.1` unless started with `--lan`. The API answers
cross-origin requests only from localhost, so a random website you visit
cannot reach in and delete your tours. There is no login: anything that can
reach the port can edit. On a shared or public network, put it behind a
reverse proxy with auth rather than exposing the port directly.

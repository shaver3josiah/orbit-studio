# Orbit Studio

Walk through your world. Your Insta360 X4 Air footage becomes an explorable 3D scene you can fly a camera through and export as video.

Two things live here, sharing one local server:

- **Orbit Studio** — video to Gaussian splat, flown through in the browser. The "Quickstart" below and everything under it covers this.
- **Orbit Tour** — 360° photos to a linked virtual tour with hotspots, embeds, and a standalone export. A self-hosted replacement for Kuula.co Pro. See "Run Orbit Tour" just below, or [docs/TOUR.md](docs/TOUR.md).

## Run Orbit Tour (the 360 virtual-tour app)

This is the simplest thing here — it needs **only Python 3**, nothing else. No `setup.ps1`, no libraries to install, no camera to get started.

1. Install Python 3 from <https://www.python.org/downloads/> if you do not already have it. On the first install screen, tick **"Add python.exe to PATH"**.
2. Double-click **`tour.bat`**. It starts the server and opens the app in your browser.
3. A **"Sample Apartment"** tour is already there to walk through. When you have your own photos, click **New tour** and drag them in — a 2:1 equirectangular photo (straight from the Insta360 app, the format Kuula insists on) is still the ideal case, but a phone panorama or an ordinary photo works too, just covering less of the sphere.

That is the whole setup. If `tour.bat` says Python was not found, finish step 1 and double-click it again. Full guide: [docs/TOUR.md](docs/TOUR.md).

Prefer the terminal? From this folder: `python server.py`, then open <http://localhost:7360/tour>.

### If a script "crashes instantly" after downloading

Downloading the ZIP is fine — use it. Windows does stamp the files "came from the internet" and will refuse a stamped `.ps1`, but `setup.bat`, `tour.bat`, `start.bat` and every `.py` are unaffected, and `setup.bat` clears the stamp before it runs anything else. Only reach for these if a `.ps1` really does refuse:

- **For the tours you need none of it** — run `tour.bat` or `python server.py`.
- **Clone instead of downloading:** `git clone https://github.com/shaver3josiah/orbit-studio.git`. Cloned files carry no stamp.

### On a locked-down work machine

`setup.bat` handles the usual one itself: a security policy that lets `ffmpeg.exe` exist but refuses to run it from `Downloads` (`WinError 5`). Setup proves ffmpeg can actually execute and relocates it to a permitted folder if not, so the project can stay wherever you unzipped it. `python preflight.py` reports what works; `python fix_ffmpeg.py` re-runs just the ffmpeg part.

## Quickstart (Orbit Studio, the splat pipeline)

1. Double-click **`setup.bat`**. One step, and it finishes the job: installs the two libraries, fetches ffmpeg, confirms Windows will actually let ffmpeg run (relocating it if a policy blocks the folder you downloaded into), and prints whether this machine can run the rest.
2. Export an equirectangular MP4, or a set of 2:1 360 photos, from the Insta360 app or the free Insta360 Studio.
3. Double-click `start.bat`, then drop the files in. Orbit Studio preps frames and builds a cloud bundle.
4. Click Open Notebook, run all cells on the free GPU, then drop the returned `artifact.splat` back in and fly.

Run `python preflight.py` any time to re-check the machine on its own.

That is the whole workflow. Everything below explains why it works and what to do when something goes sideways.

## Architecture

Orbit Studio splits the work across two places because they are good at different things. Your laptop handles capture prep, the studio interface, and the final viewer. A free Colab GPU handles the two steps that actually need a GPU: recovering camera poses and training the splat.

```
YOUR LAPTOP                                GOOGLE COLAB (free T4 GPU)

Insta360 X4 Air footage
       |
       v
Orbit Studio capture prep   --- bundle.zip --->   COLMAP poses (pycolmap)
       ^                                                  |
       |                                                  v
Import Result zone   <--- artifact.splat ---       gsplat training
       |
       v
Spark viewer: fly-through, export video
```

Two files cross that boundary, and nothing else needs to. `bundle.zip` carries your extracted frames and a manifest out to the notebook. `artifact.splat` carries the trained scene back.

## Why this design

Your laptop's Intel integrated graphics can display a finished splat beautifully. Spark renders thousands of them at once without strain. But displaying a splat and training one are different jobs. Training means tens of thousands of optimization steps against real memory bandwidth, and that needs a discrete GPU your laptop does not have. Rather than asking you to buy one, Orbit Studio sends that single heavy step to a free cloud GPU and keeps everything else, capture, review, and flying through the result, on the machine you already own.

The pipeline itself leans on the strongest open tools available for each stage instead of reinventing them: COLMAP for poses, gsplat for training, Spark for rendering. Orbit Studio's own code is the thin, opinionated layer that connects them into one workflow.

## Credits

| Tool | License | Role |
|---|---|---|
| three.js | MIT | 3D rendering engine under the viewer |
| Spark (sparkjs.dev) | MIT | Gaussian splat rendering in the browser |
| COLMAP / pycolmap | BSD | Camera pose recovery |
| gsplat | Apache-2.0 | Splat training on the cloud GPU |
| Brush | Apache-2.0 or MIT, confirm in the repo | Optional alternate trainer and viewer |
| ffmpeg | LGPL or GPL depending on build | Video frame extraction |
| antimatter15 splat format | MIT | The `.splat` format Orbit Studio imports |
| Photo Sphere Viewer 5.14.3 | MIT | 360° panorama rendering and tour plugins under Orbit Tour |

Experimental lanes, off by default:

| Tool | License | Role |
|---|---|---|
| AnySplat | MIT | Feed-forward splat generation, no COLMAP or training step |
| SPAG4d | MIT (core) | Single equirectangular photo to splat, runs outside the notebook |
| VGGT | Meta research license, noncommercial | Checkpoint only, research use |

HunyuanWorld's tools are deliberately left out. Their license carries territorial restrictions that do not fit a tool meant to run on anyone's laptop, anywhere.

## Get it on GitHub

This project lives at <https://github.com/shaver3josiah/orbit-studio> (account: shaver3josiah@gmail.com). Clone it with:

```
git clone https://github.com/shaver3josiah/orbit-studio.git
```

This badge opens the splat-training notebook straight into Colab:

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/shaver3josiah/orbit-studio/blob/main/notebooks/OrbitStudio_Colab.ipynb)

## Troubleshooting

**not_equirectangular**: Orbit Studio checks that your source video is a true 360 frame, twice as wide as it is tall, before it builds a bundle. Seeing this error almost always means you exported a stabilized or reframed clip instead of the raw equirectangular one. Go back to the Insta360 app or Studio and export again with reframing and stabilization turned off.

**COLMAP registers too few images**: if the notebook warns that fewer than 60 percent of your images registered, the reconstruction did not find enough shared detail between frames. That traces back to the walk itself almost every time: a gap in the loop, a fast turn, motion blur, or a long blank stretch of wall with nothing to key on. Slow down and check `docs/CAPTURE_GUIDE.md` before re-shooting the weak section.

**Colab disconnects**: free Colab sessions can drop if the tab sits idle or a run goes long. Keep the tab open and active for the training step. A disconnect mid-training is not a disaster, your bundle and frames are still safe on your laptop; just reconnect and start again from the install cell.

**The viewer needs localhost, not file://**: browsers block much of what Spark needs when a page is opened straight from disk. Always reach the viewer through `start.bat`, which serves it at `http://localhost:7360`. Double-clicking `studio/index.html` directly will not work.

# Orbit Studio

Walk through your world. Your Insta360 X4 Air footage becomes an explorable 3D scene you can fly a camera through and export as video.

Two things live here, sharing one local server:

- **Orbit Studio** — video to Gaussian splat, flown through in the browser. Everything below covers this.
- **Orbit Tour** — 360° photos to a linked virtual tour with hotspots, embeds, and a standalone export. A self-hosted replacement for Kuula.co Pro. Run `start.bat`, then open <http://localhost:7360/tour>. See [docs/TOUR.md](docs/TOUR.md).

## Quickstart

1. Right-click `setup.ps1` and choose Run with PowerShell. This one-time step checks Python, installs two small libraries, and fetches ffmpeg if you do not have it.
2. Export an equirectangular MP4 from the Insta360 app or the free Insta360 Studio.
3. Double-click `start.bat`, then drop the file in. Orbit Studio preps frames and builds a cloud bundle.
4. Click Open Notebook, run all cells on the free GPU, then drop the returned `artifact.splat` back in and fly.

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

The notebook badge below only works once this project lives in a real GitHub repository. From the project folder:

```
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/YOUR_GITHUB_USERNAME/orbit-studio.git
git push -u origin main
```

Replace `YOUR_GITHUB_USERNAME` with your GitHub username, then this badge opens the notebook straight into Colab:

[![Open In Colab](https://colab.research.google.com/assets/colab-badge.svg)](https://colab.research.google.com/github/YOUR_GITHUB_USERNAME/orbit-studio/blob/main/notebooks/OrbitStudio_Colab.ipynb)

The account email on file for this project is shaver3josiah@gmail.com. Use the GitHub account tied to that address when you create the repository, and the badge link above will resolve correctly once `YOUR_GITHUB_USERNAME` is replaced.

## Troubleshooting

**not_equirectangular**: Orbit Studio checks that your source video is a true 360 frame, twice as wide as it is tall, before it builds a bundle. Seeing this error almost always means you exported a stabilized or reframed clip instead of the raw equirectangular one. Go back to the Insta360 app or Studio and export again with reframing and stabilization turned off.

**COLMAP registers too few images**: if the notebook warns that fewer than 60 percent of your images registered, the reconstruction did not find enough shared detail between frames. That traces back to the walk itself almost every time: a gap in the loop, a fast turn, motion blur, or a long blank stretch of wall with nothing to key on. Slow down and check `docs/CAPTURE_GUIDE.md` before re-shooting the weak section.

**Colab disconnects**: free Colab sessions can drop if the tab sits idle or a run goes long. Keep the tab open and active for the training step. A disconnect mid-training is not a disaster, your bundle and frames are still safe on your laptop; just reconnect and start again from the install cell.

**The viewer needs localhost, not file://**: browsers block much of what Spark needs when a page is opened straight from disk. Always reach the viewer through `start.bat`, which serves it at `http://localhost:7360`. Double-clicking `studio/index.html` directly will not work.

# Roadmap

Orbit Studio moves in loops, not phases. A capture tool gets better by being used on real footage, not by being planned further ahead than the next shoot. The three loops below run continuously and feed each other. There is no finish line here, just a note on what turns each loop this week and what is queued for later.

## Capture Loop

Shoot a scene, process it, and open the result in the Player. Look at what came back with an honest eye: where does the splat go soft, where do you see yourself, where did a wall never quite resolve. Take what you learn straight to `docs/CAPTURE_GUIDE.md`, adjust technique, and shoot again. This loop is the heartbeat of the project. Everything else exists to make it worth repeating.

## Quality Loop

This loop tunes the pipeline rather than any single capture. Sweep the bundle settings, frame stride, crop, resolution, and watch the registered-percent number the notebook prints after pose recovery. A capture that registers cleanly at 95 percent tells you the settings are right. One that stalls at 40 percent is a pipeline problem worth chasing before you blame the footage. Changes that come out of this loop land as defaults in the pipeline and as updated tips in the capture guide.

## Product Loop (planned)

These are staged, not promised on a date. Each one waits for the loop above it to produce a reason to build it.

- **v1.1**: Native `.spz` export from the notebook, using splat-transform, once a Node runtime is confirmed available in the Colab image.
- **v1.2**: Keyframe easing curves and orbit presets in the viewer, so a fly-through can be shaped once and replayed the same way every time.
- **v1.3**: Direct `.insv` support, skipping the equirectangular export step entirely, if an open and properly calibrated stitcher lands. Watching Moshpit360 and LichtFeld Studio for this.
- **v1.4**: AnySplat promoted from experimental to the default turbo lane, once its VRAM footprint is verified safe on a free T4 across a range of capture sizes.
- **v2.0**: A WebXR walkthrough mode, so a finished scene can be worn, not just watched.

## Parallel agent notes

Three pieces of Orbit Studio can be rebuilt independently without breaking each other, because they only talk through two contracts: the `bundle.zip` format going out to the notebook, and the `artifact.splat` format coming back. The studio UI (`studio/index.html`) can change its entire look and interaction model as long as it still produces a valid bundle and can still import a valid splat. The pipeline and local server (`server.py` and the pipeline package) can change how frames get extracted and packaged as long as the bundle contract holds. The notebook can swap COLMAP for a different pose solver, or gsplat for a different trainer, as long as it still accepts the bundle and still exports a conforming splat. None of the three needs to know how the others are implemented, only what they hand off.

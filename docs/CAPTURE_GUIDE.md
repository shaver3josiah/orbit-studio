# The Field Manual

This is the difference between a splat that holds together and one that dissolves into fog. None of it is complicated, but all of it matters, and skipping a step tends to show up an hour later as a blurry wall or a hole where a room should be.

These rules are tuned for reconstruction, not just a nice-looking video. A shot that looks great on the Insta360 app's preview screen can still starve COLMAP of the sharp, stable detail it needs to match points between frames.

## Before you leave

Set these once before you walk out the door.

- Resolution: the highest your camera offers, 8K at 30fps.
- Color profile: Standard, not Log. Log footage is flat and low-contrast by design, which is exactly what a feature matcher struggles with.
- Sharpness: Low. Counterintuitive, but the in-camera sharpening halo around edges reads as false detail to a reconstruction pipeline.
- Adaptive Tone: Off. It shifts exposure and contrast frame to frame in ways that look fine to your eye and unstable to a solver.
- Exposure: locked indoors, around 1/500 shutter with ISO on auto. A fast shutter kills the motion blur that a slow walk-and-turn otherwise introduces. Outdoors, auto exposure on both shutter and ISO is fine, since daylight is usually even enough not to drift.
- Lenses: clean both of them right before you start. A smudge on a 360 camera does not stay in a corner of the frame, it smears across a huge share of the stitched panorama.

## How to walk

Move slower than feels natural. Start with a perimeter loop around the space, walking the outer edge at a steady pace, then fill the interior with a lattice of overlapping passes. Close the loop by ending near where you started. That closure gives the reconstruction a way to check its own work against itself.

Stay about a meter from the surfaces that matter: the walls, the furniture, the object you actually want detail on. Never go closer than 0.3 meters. Too close and the stitch geometry distorts. Too far and you lose resolution on the thing you care about.

The camera's two lenses meet at a seam, and that seam is where stitching artifacts are worst. Keep people and important detail off it. If you pause, do not pause with a subject sitting on the seam line.

Hold the stick fully extended overhead. It puts distance between the lens and your body, which does two things at once: it makes you easier to mask out later, and it lifts the camera high enough to see over furniture instead of just around it. Wear plain, dark clothing, because in a 360 capture you are always in the shot, right below the camera, and the less visual noise you add there the less cleanup the reconstruction needs to do.

For anything you consider a hero area, a mantelpiece, a doorway, a good piece of furniture, shoot it twice at two different heights. One low pass and one higher pass gives the solver two vantage points on the same detail instead of one.

Budget one to three minutes per room. Longer than that is usually redundant coverage, not extra quality.

## Export

From the Insta360 app: export the equirectangular MP4 directly from the clip, and make sure any reframing or stabilization option is switched off first. Those options crop or flatten the sphere, and Orbit Studio needs the full, uncropped equirectangular frame to work with.

From Insta360 Studio on desktop: open the clip, go to Stitching, then Stitching Optimization, and turn on Optical Flow Stitching before you export. It costs a little processing time and is worth every second of it.

## What good footage looks like

Pause the playback at a handful of random points. Every still should look sharp, not soft or smeared. Lighting should feel even across the frame, not a blown-out window on one side and a dark corner on the other. There should be no visible motion blur or ghosting on anything that was standing still. If a paused frame looks bad, the reconstruction built from it will look worse.

## Sources

This guidance is synthesized from vendor documentation and community capture practice, then tuned toward what actually helps a Gaussian splat reconstruction rather than what makes the nicest-looking video. Where those two goals disagreed, splat quality won.

- [Niantic Spatial: 360 Camera Scanning Guide](https://nianticspatial.com/docs/scaniverse/360camera/)
- [Insta360: Avoid Stitching Issues in 360 Footage, X4 manual](https://onlinemanual.insta360.com/x4/en-us/camera/basicuse/stitching)
- [FreeGaussian: Insta360 to Gaussian Splat, a 360 Video Guide](https://www.freegaussian.ai/blog/insta360-to-gaussian-splat)

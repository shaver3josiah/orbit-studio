# Guided capture & error recovery (v0.9.0 architecture)

From five synthesized user interviews, the two findings that drive this release:

1. **The sketch exists to drive a guided walk.** Its path must set the shot target and coverage
   pattern, and capture must walk the user through it shot-by-shot with a live progress bar and
   turn cues — not just draw a pretty route.
2. **Every mistake needs a redo at the right granularity.** A bad *photo*, a bad *room*, a bad
   *sketch*, or a bad *scan* each get their own cheap recovery. Nothing is a dead end.

## The recovery ladder

| Granularity | Recovery |
|---|---|
| Photo | Blur caught live → retake in place. Any shot deletable from Review. |
| Room | "Rescan room" clears its photos and re-walks the path — without redoing the house. |
| Sketch | Undo/redo; easy select+delete of thin doors/windows; the path recomputes. |
| Scan / plan | Delete a scan or a whole plan; start a fresh draw. |

## How "walk the path" works on a phone (honest)

No indoor GPS, so guidance is **coverage-driven, not position-tracked**. The sketch path sets the
shot target and the pattern (perimeter loop → interior lattice → loop closure). The rotation
sensor drives turn cues; a live "N of ~M" progress bar and stage badges walk the user through it;
at the target the room is marked covered. Position-tracked guidance would require ARCore, which the
budget target phones often lack, so the app does not depend on it.

## This release
- Grid: fixed, visible cell size (no longer fit-to-grid, which made cells microscopic); the grid
  is 100x bigger (7200x900 -> 7200x9000 cell space) with viewport-clipped rendering so it stays
  fast. Pinch to zoom, pan across a huge canvas.
- Doors/windows: window symbol constrained to the wall band (no more spill); thin doors/windows
  are selectable/erasable within a ~0.3 m tap tolerance so grabbing them is easy.
- Guided capture: live progress toward the sketch's shot target + a room-covered state.
- Recovery: rescan room, delete scan, delete plan, retake photo.

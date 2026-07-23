# Orbit Capture (Android) — PRODUCT.md

## What it is
Native Android companion app for Orbit Studio. It captures a room as a set of still photos with locked exposure, coaches the user through a reconstruction-grade scan (perimeter loop → interior lattice → loop closure), and exports a `bundle.zip` that the existing Orbit Studio pipeline (COLMAP poses + gsplat training on Colab) accepts unchanged.

## Who uses it
One person scanning a real room with their phone, usually indoors under mixed lighting, phone in one hand, moving slowly. They may be mid-scan for 1–3 minutes per room with the screen as their only guide.

## Register
Product. Design serves the task; the tool should disappear into the scan. Dark-first: the primary surface is a live camera viewfinder used in dim interiors — light chrome would fight the image the user must read.

## Core loop
Home (scans) → New scan → pre-capture checklist (exposure lock gate) → Capture (viewfinder + coaching HUD) → Review (coverage, weak spots, reshoot) → Bundle (manifest + zip export) → Done (hand off to laptop/Colab, splat comes back).

## Contracts it must honor
- `bundle.zip` = `manifest.json` {app:"orbit-studio", project, crops, rig} + `crops/*.jpg`, ZIP_STORED. Additive `rig.lane:"phone"` shape per docs/PHONE_CAPTURE_SPEC.md.
- Coaching numbers: ~70–80% overlap between shots, 0.3 m minimum / ~1 m ideal standoff, <60% registration = warning, ~95% = healthy, 1–3 min per room.
- Reference UI: design/android-app-preview.html (AR Coach) is the validated design; the app ports it, not reinvents it.

## Honest v1 boundaries
- Overlap meter is driven by rotation-delta + shot cadence heuristics, not feature matching.
- Weak spots come from per-shot blur scoring (Laplacian variance), not reconstruction feedback.
- Export is a local zip + share sheet; no direct Colab upload yet.

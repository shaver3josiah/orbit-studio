# Orbit Capture (Android) — DESIGN.md

Ported from design/android-app-preview.html (winning AR Coach candidate). Compose Material3, dark-first.

## Palette (dark theme is primary)
- Canvas / viewfinder chrome: #0B0B0E (never #000000)
- Elevated surface: #1C1C22; hairlines: white at 12% / 20%
- Text: #F2F2F7 primary, white 60% secondary, white 38% tertiary
- Accent (one): #0A84FF — primary actions, selection, live meters only. Never decoration.
- Semantic: success #30D158, warning #FF9F0A, danger #FF453A (+ soft 14% tints)
- Light theme exists for Home/Review/Bundle/Done (mockup's #F2F2F7 ground); Capture screen is always dark.

## Type
- One family: platform sans (Roboto/system). Scale ratio ~1.2, fixed sp sizes.
- All counts, percentages, meters, timers: monospace numerals (FontFamily.Monospace or tabular numerals).

## Shape & depth
- Corner radius scale: 12 / 16 / 24 dp; device-chrome curves only on overlays.
- No neon glows, no gradient text, no side-stripe accents. Depth via hairline borders + soft tinted shadow, not elevation stacking.

## Motion
- 150–250 ms, ease-out (fast-out-slow-in / cubicOut). Motion conveys state only: meter fills, toast entry, screen transitions, shutter feedback.
- Shutter press: scale 0.96 + ring flash. All buttons: pressed scale ~0.98.
- Respect system reduced-motion (disable non-essential animation).

## Component vocabulary (consistent on every screen)
- Primary button: filled accent, full-width where it is THE action.
- Coaching toast: bottom-anchored pill, one at a time, auto-dismiss, never stacks.
- Meters: thin bars/rings; red <60%, amber 60–75%, green >75% (overlap); same thresholds everywhere.
- Stage badges: Perimeter / Interior / Loop closed — same trio on Capture and Review.
- Every interactive component ships default/pressed/focused/disabled states; every screen ships loading/empty/error/permission-denied states.

## Bans (carried from taste + impeccable)
No emojis anywhere. No pure black. No purple/neon. No identical card grids. No decorative motion. No modal-first flows (bottom sheets for the checklist are earned: they gate the camera).

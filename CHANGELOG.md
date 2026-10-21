# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-10-21

### Added

- Several brushes, one per person: each has its own name, history, statistics, settings and growth level. Chips at
  the top switch between them and the greeting uses the name. When the app opens with several brushes it listens for
  a few seconds which of them are awake and syncs those. "Replace with a new brush" moves a person's history, name and
  level to their new brush; "Delete brush" removes it with its history. Data of 0.1 moves over on the first start.
- New look from a full design pass: an app icon with a white brush and bubbles on teal (plus a themed-icon
  silhouette), one set of line icons, the Brush tab with a header showing the name, a picture of the brush and its
  charge, mode and brush-head cards side by side, list rows with chevrons, a search screen with a glowing scan around
  the brush, a naming dialog, and the colour palette of the design spec.

### Fixed

- Text outside cards (the welcome and brush search titles) was black on the dark theme.
- Release notes in the update dialog keep each list item on one line.

## [0.1.0] - 2026-10-21

### Added

- Project skeleton: brush discovery, Today / History / Brush screens, local JSON history,
  CSV export, streak and two-week chart.
- Bluetooth sync for the Oclean X Pro family, confirmed on an X Pro Digital (`OCLEANY3PB`, firmware 1.0.2.5):
  sessions with score, duration, programme and zones; battery; brush-head days, sessions and wear counter;
  clock and time-zone sync.
- Sync log with raw packets on the Brush tab, for device reports.
- Redesign: brand teal theme (light and dark), animated score ring that counts up, morning/evening goal slots,
  a top-down map of both jaws with per-zone time, growing day bars, staggered card entrance, animated tab
  switching, session details in a bottom sheet, brush-head wear gauge with a replacement hint, radar animation
  while scanning, welcome screen with rising bubbles, haptic feedback when new sessions arrive.
- Interactive 3D model of both jaws, rendered with OpenGL ES 3 (no 3D library): procedurally built anatomical
  crowns (shovel incisors, pointed canines, cusped premolars and molars with fissures), scalloped gums with
  papillae and root bulges, smooth per-pixel lighting with glossy enamel, translucent incisal edges, wet gums,
  ambient occlusion and 4x MSAA. Zones tint the enamel mint (brushed) or orange (missed); drag sideways to rotate
  with inertia, tap a tooth to see its zone's seconds, share of the session and weekly average, double-tap to
  reset.
- Studio look for the 3D jaw: porcelain teeth with softbox reflections, soft-touch gums shaped as a rounded
  ridge, a spotlit stage with a glossy floor reflection and a slow turntable. Zone data moved from tinted enamel
  into light: a glowing line under every zone, mint when brushed, amber when missed. Tapping a tooth or a line
  turns the model to present that zone.
- The jaw mesh is baked at build time by the pure-Kotlin `:jawmodel` module into a compact asset (16 bytes per
  vertex, 16-bit indices, about 0.7 MB in the APK) instead of being generated on the phone (about 0.4 s of CPU per
  start). Rendering is on demand: one entrance turn, finite animations, nothing drawn while the model is off
  screen, so an idle model costs no CPU or GPU time.
- Brush settings and programmes for the X Pro Digital (hardware protocol 0003), probed on a real brush: every switch
  the firmware has, including the pressure alert, brush pause and wake-up mode the official app hides; display
  language; brush-head reset and wear limit; the brush's built-in modes, Oclean's programme catalogue and unlimited
  custom programmes with the full range of motor levels. Acks echo the written value on this firmware.
- Programmes are capped at 9 steps and 20-byte packets: tested on the brush, 8 and 9 steps are accepted every time,
  10 steps (a 22-byte second packet) get no answer, and a 40-byte packet worked once and hung the brush once.
- The settings read ignores a continuation fragment that arrives without its header and asks again, after the brush
  dropped the first fragment once right after a programme write.
- Growth level on the brush screen (bronze, silver, gold...): each sync reads the brush's new growth points (`0308`),
  adds them to the total and writes it back as the base (`0226`), as the official app was meant to but never did.
  Tested: base 100 turned "bronze 1" into "gold 2".
- In-app updates from GitHub Releases: a daily check (it can be turned off under Brush → Updates), a dialog with
  the release notes, the APK download checked against the published SHA-256, and the install through Android's
  package installer, without a prompt once FreeBrush has installed itself.
- Releases are automatic: raising the version in `version.properties` on `main` tags it and publishes the APK
  signed with the release key. Builds between releases are named `X.Y.Z-dev+<commit>`.

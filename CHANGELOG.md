# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

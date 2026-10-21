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

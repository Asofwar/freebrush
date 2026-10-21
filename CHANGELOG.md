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
- Interactive 3D model of both jaws (28 teeth and gums, software-rendered, no 3D library): surfaces are coloured
  by brushing time per zone; drag sideways to rotate with inertia, tap a tooth to see its zone's seconds, share
  of the session and weekly average, double-tap to reset.

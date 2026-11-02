# Redraw cost (2.0 plan, spike S2)

Question: what one redraw costs through today's draw path (`OverlayImage.drawImage`: clear the canvas, draw the
objects with Java2D, convert ABGR to RGBA), and whether redraws that land on the same second for many streams finish
within one frame interval (33 ms at 30 fps). The answer decides whether 2.0 needs "render ahead" (plan 3.1).

Harness: `module/src/test/java/com/wowza/wms/plugin/urloverlay/OverlayDrawBench.java`, run as a main class against the
1.15.1 module classes with WSE's own Java (Temurin 21.0.3), headless, on 2026-11-02:

- Windows 11, 24 cores, local WSE; the module's draw pool would be 8 threads.
- AWS Ubuntu server, 8 cores; draw pool 4 threads. WSE was running but its Java used 0.6% CPU at the start, so the
  transcoder was not busy; these are numbers without transcoding load.

Each case is 400 timed draws after 150 warm-up draws, at the four output scales a 1080p source with 720p, 480p and
360p renditions gives. Cases use Java's logical fonts; cost does not depend on which font.

## One draw (median / p95 / max ms)

| Case | Windows, 1080 | Windows, 360 | AWS, 1080 | AWS, 720 | AWS, 360 |
|---|---|---|---|---|---|
| Empty overlay, full 1920 x 1080 canvas | 2.21 / 2.94 / 5.13 | 0.24 / 0.33 / 1.11 | 5.25 / 7.06 / 7.53 | 2.31 / 3.14 / 3.30 | 0.58 / 0.81 / 0.85 |
| Clock `HH:mm:ss`, full canvas | 2.31 / 3.16 / 4.91 | 0.24 / 0.35 / 1.99 | 5.48 / 7.31 / 7.91 | 2.34 / 3.27 / 3.50 | 0.60 / 0.87 / 0.93 |
| Clock, 420 x 100 bounding box | 0.06 / 0.08 / 0.56 | 0.01 / 0.03 / 0.20 | 0.14 / 0.22 / 0.25 | 0.08 / 0.13 / 0.20 | 0.03 / 0.05 / 0.07 |
| Live badge, full canvas | 2.30 / 3.09 / 5.78 | 0.26 / 0.41 / 0.75 | 5.46 / 7.21 / 7.97 | 2.39 / 3.34 / 3.68 | 0.62 / 0.86 / 1.15 |
| Lower third, full canvas | 2.62 / 3.59 / 4.60 | 0.32 / 0.50 / 1.30 | 5.85 / 7.86 / 8.52 | 2.74 / 3.90 / 5.59 | 0.74 / 1.09 / 1.16 |
| 64 layers, full canvas | 4.01 / 5.35 / 6.70 | 0.82 / 1.06 / 2.30 | 8.84 / 12.86 / 13.91 | 4.41 / 6.49 / 11.84 | 1.67 / 2.53 / 4.18 |

The empty overlay costs almost as much as the clock: a full-canvas redraw is almost all clearing and converting the
whole frame, not drawing. Drawing only the layers' bounding box cuts a clock's redraw by 35 to 40 times.

## Every stream redrawing on the same second (ms until the last of the 4 x N draws finishes)

| Streams | Windows, full canvas | Windows, bounding box | AWS, full canvas | AWS, bounding box |
|---|---|---|---|---|
| 1 | median 2.29, max 5.00 | 0.12, 0.19 | 5.69, 8.86 | 0.31, 0.73 |
| 4 | 5.15, 6.40 | 0.22, 0.31 | 15.06, 20.07 | 0.76, 3.11 |
| 12 | 16.33, 19.05 | 0.44, 0.53 | **37.85, 56.20** | 1.47, 2.48 |

## What it means for 2.0

- **Bounding-box drawing is required, not optional.** With it, 12 streams redraw every output within 2.5 ms of the
  second even on the 8-core server; without it, the same burst takes 38 to 56 ms there, one to two frames late.
- **Render ahead is not needed.** With bounding boxes every case lands within a frame on both servers. An overlay that
  must use the full canvas (a full-frame web page or image layer beside a clock) can show its clock change one or two
  frames late at 12 streams on an 8-core server, under 0.1 s, which a seconds clock does not visibly show.
- **Not measured here:** the same burst while WSE transcodes 12 streams. That part of S2 needs the 2.0 draw path, and
  runs on the built module (plan section 7, Performance).

# Redraw cost under transcoding load, with the 4d draw path (2.0 plan, spike S2, second half)

The first half of S2 (`2026-11-02-s2-redraw-cost.md`) measured 1.x's draw path on an idle server and found that a
full-canvas redraw is almost all clearing and converting the frame. It left one measurement for the built module:
the same second-boundary burst while WSE transcodes 12 streams. This is that measurement, with part 4d, which draws
only the part of the canvas the layers can paint.

## How

- AWS server, 8 cores, 15.4 GB, WSE 4.12.1 with the module at part 4d (`.deb` from commit dddf328).
- 12 streams published on the server itself (ffmpeg `testsrc2`, 1280 x 720, 30 fps, libx264 `ultrafast`), each
  transcoded to 720p and 360p by a copy of the CPU starter, with an overlay of a box, an `HH:mm:ss` clock and a web
  page on both encodes. All 12 transcoders started and all 12 overlays applied (WSE's access log).
- `OverlayDrawBench` (module test tree) run with WSE's own Java 21.0.3, once at idle and once while the 12 streams
  transcoded. Raw output: `s2/aws-4d-idle.txt`, `s2/aws-4d-load.txt`.
- Load during the run: WSE's Java at 518% CPU, load average 69.7 rising to 82.8 on 8 cores. The server was far past
  what it can transcode in real time, so these are worst-case numbers, not a sizing.

With 4d, the bench's "full canvas" clock is drawn as a strip the canvas's width and one line high (text spans the
whole width so a changing value never clips), not the whole 1920 x 1080 frame; "bounding box" is a clock on a 420 x 100
canvas.

## Results

Second-boundary burst, 12 streams x 4 output scales = 48 draws on the module's 4-thread pool, time until the last
draw finishes:

| Draw path | Median | p95 | Max |
|---|---|---|---|
| 1.x, whole 1920 x 1080 canvas, idle (first half of S2) | 38 to 56 ms | | |
| 4d, clock across a 1920-wide canvas, idle | 3.5 ms | 7.8 ms | 11.2 ms |
| 4d, clock across a 1920-wide canvas, 12 streams transcoding | 15.9 ms | 25.4 ms | 42.7 ms |
| 4d, clock in a 420 x 100 canvas, idle | 1.0 ms | 1.6 ms | 2.3 ms |
| 4d, clock in a 420 x 100 canvas, 12 streams transcoding | 4.2 ms | 10.8 ms | 12.0 ms |

One draw of a clock across a 1080-line canvas: 0.53 ms median at idle and 0.92 ms under load, against 5.3 ms for
1.x's whole-frame draw on the same server at idle. The 64-layer stress case under load reached 170 ms at its worst
single draw.

No module warning or error was logged during the run.

## What it means for 2.0

- **No draw-ahead scheme is needed**, as the first half concluded: even with the server overloaded, all 48 draws of
  a second boundary finished within 43 ms, and within 12 ms when the overlay's canvas is small. A seconds clock shows
  at most one frame late at 30 fps under this load and on time otherwise.
- **Box drawing carries the load.** Under load, 4d's worst burst (42.7 ms) is better than 1.x's idle burst (38 to
  56 ms).
- **Many layers stay the expensive case.** 64 layers cost 17 ms a draw at 1080 under load (170 ms at worst), which
  is why the plan caps an overlay at 64 layers and redraws at 10 a second.

Not measured: whether the transcoders kept real time during the run. A read of the HLS output pulls segments that
already exist, faster than real time, so it says nothing about it.

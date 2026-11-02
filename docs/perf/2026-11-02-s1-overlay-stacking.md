# Overlay stacking order (2.0 plan, spike S1)

Question: when a template carries several module overlays (transcoder slots 16 to 19) and WSE's own image overlays
(slots 1 to 8), on the input (Decode) and on outputs (Encode), which draws on top?

## From the source

- Java keeps each stage's overlays in a `HashMap` by slot (`TranscoderStreamSourceVideo.java:21-22`,
  `TranscoderStreamDestinationVideo.java:43-44`) and hands each one to native code by slot number
  (`videoDecoderBufferSetOverlayVideoFrame`, `videoEncoderBufferSetOverlayVideoFrame`). The Java map's order does not
  matter.
- Native code keeps them in a `std::map<uint32_t, ...>` (`wms/src/c/transcoder/VideoOverlayFrames.h:196`), which
  iterates in ascending slot order. `getFrames` lists them in that order (`:115-124`) and
  `VideoFrameBuffer::applyOverlay` blends the list front to back (`VideoFrameBuffer.cpp:1820-1960`). So within one stage,
  **a higher slot is drawn later, on top**.
- The decode-stage overlays are applied to the decoded frame (`TranscoderSession.cpp:2780-2795`) before scaling and
  encoding; encode-stage overlays are applied to each output after. So **an Encode overlay is always above every
  Decode overlay**, whatever their slot numbers.
- `applyOverlay` returns without drawing for a frame held in NETINT memory (`VideoFrameBuffer.cpp:1829`), so overlays
  are not applied on NETINT zero-copy frames. Not tested.

## On video, 2026-11-02

A test template with four Decode image overlays (slots 1, 8, 16, 17, overlapping in that order) and two Encode
overlays on the 720p output (slot 2 placed over the Decode slot 17 square, slot 18 over slot 2), on a 1280 x 720 test
pattern published with ffmpeg, frame taken from the 720p HLS output:

| Server | Path | Result |
|---|---|---|
| AWS Ubuntu, WSE 4.12.1 | CPU decode, scale and encode | 1 under 8 under 16 under 17; Encode slot 2 over Decode slot 17; Encode 18 over Encode 2 |
| Local Windows, WSE 4.12.0, RTX 5060 Laptop | NVIDIA through EVA (`NVCUVID_EVA`, `CUDA_EVA`, `NVENC_EVA`) | Identical |

Frames: `spikes/stacking/aws-cpu-720p.png`, `spikes/stacking/local-gpu-720p.png`. Generator: `spikes/stacking/make_s1.py`.
On the laptop the GPU test stream logged "Video behind filter state change: SKIP1FRAME, SKIP2FRAME" with six image
overlays and dynamic overlays on; that is a load observation on this machine, not a stacking result.

## What it means for 2.0

- Template overlay order maps straight onto slots: the overlay listed later in a stage gets the higher slot and draws
  on top. Module overlays (16 to 19) draw over WSE's fixed images (1 to 8) in the same stage.
- An "Image (fixed)" on an output draws over a module overlay on the input; the page's stage rule (Decode overlays
  always under Encode ones) holds.

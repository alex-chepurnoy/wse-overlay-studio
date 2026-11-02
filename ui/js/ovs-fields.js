/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio field registry: the one source for every label, help line, setting key, range and default the page
   shows. Forms render from it, and tests/ui/labels_test.py checks each key against the module's JSON model and the
   template keys.
   scope "overlay": per-stream overlay JSON field; "object": layer (object) JSON field; "template": WSE transcoder
   template, where key is the XML path and rest the field name in WSE's /v2 template REST. */
(function () {
  'use strict';
  var U = window.OverlayStudio = window.OverlayStudio || {};

  U.fields = {
    // layer: page
    url:            { scope: 'object', key: 'url', label: 'Page URL', type: 'text',
                      help: 'Opened by Chrome on the WSE server. http or https only, no user:password. Private and loopback addresses are blocked unless an administrator allows them.' },
    viewportWidth:  { scope: 'object', key: 'viewportWidth', label: 'Browser width', type: 'int', min: 16, max: 7680, unit: 'px',
                      help: 'Window size the page is rendered at, usually what the vendor designed it for. Blank uses the width on the picture.' },
    viewportHeight: { scope: 'object', key: 'viewportHeight', label: 'Browser height', type: 'int', min: 16, max: 4320, unit: 'px',
                      help: 'Blank uses the height on the picture.' },
    pageDelay:      { scope: 'object', key: 'pageDelay', label: 'Wait after load', type: 'int', min: -1, max: 60000, unit: 'ms', def: -1,
                      help: 'Time for scripts and fonts to settle before the first capture. -1 none.' },
    reloadRate:     { scope: 'object', key: 'reloadRate', label: 'Reload page every', type: 'num', min: 0, max: 86400, unit: 's', def: 0,
                      help: '0 never. Minimum 1. A reload restarts the page; most live pages update themselves and need none.' },
    refreshRate:    { scope: 'overlay', key: 'refreshRate', label: 'Capture every', type: 'num', min: 0, max: 60, unit: 's', def: 0.5,
                      help: 'How often every page of this overlay is captured again. Lower is smoother and costs more CPU.' },

    // layer: transparency
    keyColor:       { scope: 'object', key: 'keyColor', label: 'Key color', type: 'color', def: 'rgb(1,2,3)',
                      help: 'Pixels of this color become transparent. Click the captured image to pick one.' },
    keyTolerance:   { scope: 'object', key: 'keyTolerance', label: 'Tolerance', type: 'int', min: 0, max: 255, def: 0,
                      help: 'Largest difference per color channel still counted as the key color. 0 exact match.' },
    paintBackground:{ scope: 'object', key: 'paintBackground', label: 'Paint page background with the key color', type: 'bool', def: true,
                      help: 'Makes a page with no background of its own transparent. Turn off if the page sets its own key color.' },

    // layer: placement
    x:              { scope: 'object', key: 'x', label: 'X', type: 'int', min: 0, unit: 'px', help: 'Left edge on the source frame.' },
    y:              { scope: 'object', key: 'y', label: 'Y', type: 'int', min: 0, unit: 'px', help: 'Top edge on the source frame.' },
    w:              { scope: 'object', key: 'w', label: 'Width', type: 'int', min: 1, unit: 'px', help: 'Size on the source frame; the capture is scaled to it.' },
    h:              { scope: 'object', key: 'h', label: 'Height', type: 'int', min: 1, unit: 'px', help: '' },
    opacity:        { scope: 'object', key: 'opacity', label: 'Opacity', type: 'int', min: 0, max: 100, unit: '%', def: 100, help: '' },
    // layer: crop, the part of the captured page that is drawn, in the page's own pixels
    cropX:          { scope: 'object', key: 'cropX', label: 'Crop left', type: 'int', min: 0, max: 7680, unit: 'px', def: 0,
                      help: 'Left edge of the part of the page to show, from the page\'s left.' },
    cropY:          { scope: 'object', key: 'cropY', label: 'Crop top', type: 'int', min: 0, max: 4320, unit: 'px', def: 0,
                      help: 'Top edge of the part to show, from the page\'s top.' },
    cropW:          { scope: 'object', key: 'cropW', label: 'Crop width', type: 'int', min: 0, max: 7680, unit: 'px', def: 0,
                      help: 'Width of the part to show. 0 shows the whole page.' },
    cropH:          { scope: 'object', key: 'cropH', label: 'Crop height', type: 'int', min: 0, max: 4320, unit: 'px', def: 0,
                      help: 'Height of the part to show. 0 shows the whole page.' },
    evenCoords:     { scope: 'overlay', key: 'even_coords', label: 'Snap to even pixels', type: 'bool', def: false,
                      help: 'Rounds positions and sizes to even numbers, which avoids color fringing on 4:2:0 video.' },

    // template: which overlay it carries and on which outputs (Transcode/Properties)
    templateOverlay:{ scope: 'templateProperty', key: 'overlayStudio1', label: 'Overlay', type: 'text',
                      help: 'The named overlay this template carries. Overlays are made in step 1.' },
    templateOutputs:{ scope: 'templateProperty', key: 'overlayStudioEncodes1', label: 'Outputs with the overlay', type: 'list',
                      help: 'Empty means every output, drawn before scaling.' },

    // overlay: compose
    threadDrawing:  { scope: 'overlay', key: 'threadDrawing', label: 'Compose in background', type: 'bool', def: true,
                      help: 'Off makes the transcoder wait for each composite, which can stall the stream.' },
    maxDrawingTime: { scope: 'overlay', key: 'maxDrawingTime', label: 'Slow compose warning', type: 'int', min: 0, max: 10000, unit: 'ms', def: 100,
                      help: 'A composite slower than this is counted as slow and logged.' },

    // template: performance
    decoder:        { scope: 'template', key: 'Decode/Video/Implementation', rest: 'implementation', label: 'Decoder', type: 'enum',
                      values: ['default', 'NVCUVID', 'NVCUVID_EVA', 'QuickSync', 'NETINT'], def: 'default',
                      help: 'With an overlay, frames are copied off the GPU to draw it. Which values work depends on hardware and license.' },
    scaler:         { scope: 'template', key: 'Scale/Implementation', rest: 'implementationScale', label: 'Scaler', type: 'enum',
                      values: ['default', 'CUDA', 'CUDA_EVA'], def: 'default', help: '' },
    decodeGpu:      { scope: 'template', key: 'Decode/Video/GPUID', rest: 'gpuid', label: 'Decoder GPU', type: 'int', min: -1, max: 15, def: -1,
                      help: '-1 lets WSE choose.' },
    deinterlace:    { scope: 'template', key: 'Decode/Video/Deinterlace', rest: 'deinterlace', label: 'Deinterlace', type: 'bool', def: false, help: '' },
    dynamicOverlays:{ scope: 'template', key: 'Decode/Video/EnableDynamicOverlays', rest: 'enableDynamicOverlays', label: 'Dynamic overlays', type: 'bool', def: true,
                      help: 'Must be on for overlays.' },
    encoder:        { scope: 'template', key: 'Encode/Video/Implementation', rest: 'encodes[].implementation', label: 'Encoder', type: 'enum',
                      values: ['default', 'x264', 'NVENC', 'NVENC_EVA', 'QuickSync', 'NETINT'], def: 'default', help: '' },
    encodeGpu:      { scope: 'template', key: 'Encode/Video/GPUID', rest: 'encodes[].gpuid', label: 'Encoder GPU', type: 'int', min: -1, max: 15, def: -1, help: '-1 lets WSE choose.' },
    profile:        { scope: 'template', key: 'Encode/Video/Profile', rest: 'encodes[].profile', label: 'Profile', type: 'enum',
                      values: ['baseline', 'main', 'high'], def: 'main', help: '' },
    bitrate:        { scope: 'template', key: 'Encode/Video/Bitrate', rest: 'encodes[].videoBitrate', label: 'Bitrate', type: 'int', min: 1, unit: 'bps', help: '' },
    followSource:   { scope: 'template', key: 'Encode/Video/KeyFrameInterval/FollowSource', rest: 'encodes[].followSource', label: 'Keyframes follow the source', type: 'bool', def: false, help: '' },
    keyInterval:    { scope: 'template', key: 'Encode/Video/KeyFrameInterval/Interval', rest: 'encodes[].interval', label: 'Keyframe every', type: 'int', min: 1, unit: 'frames', def: 60, help: '' }
  };
})();

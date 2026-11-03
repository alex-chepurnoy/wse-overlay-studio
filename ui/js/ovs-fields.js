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
    rotation:       { scope: 'object', key: 'rotation', label: 'Turn', type: 'num', min: -360, max: 360, unit: 'degrees', def: 0,
                      help: 'Turns the layer about its center, or a free text about its start. The round handle on the canvas does the same.' },
    lockAspect:     { scope: 'object', key: 'lockAspect', label: 'Keep the picture\'s shape', type: 'bool', def: true,
                      help: 'On fits the picture inside the box; off stretches it to fill the box.' },

    // layer: text (plan 3.2)
    text:           { scope: 'object', key: 'text', label: 'Text', type: 'textarea',
                      help: 'Up to 2,000 characters. Each line break starts a new line. A token such as {time:HH:mm:ss} draws the time; write {{ and }} for braces.' },
    font:           { scope: 'object', key: 'font', label: 'Font', type: 'font', def: 'Fira Sans',
                      help: 'A font bundled with Overlay Studio, Java\'s logical fonts, or one installed on the server.' },
    fontSize:       { scope: 'object', key: 'fontSize', label: 'Size', type: 'int', min: 1, max: 1000, unit: 'px', def: 48,
                      help: 'Height of the font on the source frame.' },
    fontStyle:      { scope: 'object', key: 'fontStyle', label: 'Style', type: 'enum', values: ['plain', 'bold', 'italic', 'bolditalic'],
                      names: ['Regular', 'Bold', 'Italic', 'Bold italic'], def: 'plain', help: '' },
    textColor:      { scope: 'object', key: 'color', label: 'Text color', type: 'color', def: 'white',
                      help: 'rgb(r,g,b), rgb(r,g,b,a) with each from 0 to 255, or a name such as white.' },
    align:          { scope: 'object', key: 'align', label: 'Align', type: 'enum', values: ['left', 'center', 'right'], names: ['Left', 'Center', 'Right'], def: 'left',
                      help: 'Where each line sits in the box. A text with no box grows from its start.' },
    box:            { scope: 'object', key: 'box', label: 'Wrap in a box', type: 'bool', def: false,
                      help: 'On: X, Y, width and height are a box the text wraps in and is cut at. Off: X, Y is where the first line starts, on its baseline.' },
    background:     { scope: 'object', key: 'background', label: 'Background', type: 'color',
                      help: 'Fills the box behind the text, or the text\'s own extent plus the padding. Blank for none.' },
    padding:        { scope: 'object', key: 'padding', label: 'Padding', type: 'num', min: 0, max: 500, unit: 'px', def: 0, help: 'Space between the background\'s edge and the text.' },
    radius:         { scope: 'object', key: 'radius', label: 'Background corners', type: 'num', min: 0, max: 500, unit: 'px', def: 0, help: 'Rounds the background\'s corners.' },
    outlineColor:   { scope: 'object', key: 'outlineColor', label: 'Outline color', type: 'color', help: 'A stroke around each letter, readable over any video. Blank for none.' },
    outlineWidth:   { scope: 'object', key: 'outlineWidth', label: 'Outline width', type: 'num', min: 0, max: 20, unit: 'px', def: 0, help: '' },
    lineHeight:     { scope: 'object', key: 'lineHeight', label: 'Line height', type: 'num', min: 0.5, max: 5, unit: 'x size', def: 1.2, help: 'Distance between lines, as a multiple of the size.' },
    autoFit:        { scope: 'object', key: 'autoFit', label: 'Shrink to fit the box', type: 'bool', def: false,
                      help: 'Makes the text smaller until it fits the box, down to half its size.' },

    // layer: shapes (plan 3.4)
    fillColor:      { scope: 'object', key: 'fillColor', label: 'Fill', type: 'color', help: 'Blank for none.' },
    gradientColor:  { scope: 'object', key: 'gradientColor', label: 'Gradient to', type: 'color', help: 'Blends the fill and the line into this color. Blank for a flat color.' },
    gradientDirection: { scope: 'object', key: 'gradientDirection', label: 'Gradient direction', type: 'enum', values: ['down', 'up', 'right', 'left', 'downright', 'downleft', 'upright', 'upleft'],
                      names: ['Down', 'Up', 'Right', 'Left', 'Down and right', 'Down and left', 'Up and right', 'Up and left'], def: 'down', help: '' },
    lineColor:      { scope: 'object', key: 'color', label: 'Line color', type: 'color', def: 'black', help: 'The outline of a shape, or the line itself.' },
    stroke:         { scope: 'object', key: 'stroke', label: 'Line width', type: 'num', min: 0, max: 1000, unit: 'px', def: 1, help: '' },
    dash:           { scope: 'object', key: 'dash', label: 'Line style', type: 'enum', values: ['solid', 'dashed', 'dotted'], names: ['Solid', 'Dashed', 'Dotted'], def: 'solid', help: '' },
    arcWidth:       { scope: 'object', key: 'arcWidth', label: 'Corner rounding', type: 'num', min: 0, max: 8192, unit: 'px', def: 0,
                      help: 'How round the corners are: the width and height of each corner\'s curve. 0 for square corners.' },
    arrowHead:      { scope: 'object', key: 'arrowHead', label: 'Arrow head', type: 'enum', values: ['none', 'end', 'both'], names: ['None', 'At the end', 'At both ends'], def: 'none', help: '' },
    shadowColor:    { scope: 'object', key: 'shadowColor', label: 'Shadow', type: 'color', help: 'A copy of the layer behind it, offset down and right. Blank for none.' },
    shadowOffset:   { scope: 'object', key: 'shadowOffset', label: 'Shadow offset', type: 'num', min: 0, max: 1000, unit: 'px', def: 2, help: '' },

    // layer: image (plan 3.5)
    imageFile:      { scope: 'object', key: 'imageFile', label: 'Image file', type: 'text',
                      help: 'A picture in the server\'s content folder, drawn by the module. Upload new ones here.' },

    // layer: timing, every kind (plan 3.3a)
    showFrom:       { scope: 'object', key: 'showFrom', label: 'Show from', type: 'when',
                      help: 'Blank shows it from the start. A date and time with its offset, such as 2027-01-01T18:00:00-05:00, or stream+N for N seconds after the stream starts.' },
    hideAt:         { scope: 'object', key: 'hideAt', label: 'Hide at', type: 'when',
                      help: 'Blank keeps it on. The same forms as Show from.' },
    blinkSeconds:   { scope: 'object', key: 'blinkSeconds', label: 'Blink every', type: 'num', min: 0.1, max: 3600, unit: 's',
                      help: 'On for this long, then off for as long, by the clock. Blank for steady.' },
    fadeIn:         { scope: 'object', key: 'fadeIn', label: 'Fade in', type: 'num', min: 0, max: 2, unit: 's', def: 0, help: 'At the start of the show window.' },
    fadeOut:        { scope: 'object', key: 'fadeOut', label: 'Fade out', type: 'num', min: 0, max: 2, unit: 's', def: 0, help: 'At the end of the show window.' },

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

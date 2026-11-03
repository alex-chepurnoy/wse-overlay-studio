/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio image library (plan 4.2): the pictures in the virtual host's content folder, with previews, and the
   picker that chooses one, uploads one or deletes an upload. Shared by the Templates screen (the transcoder's own
   image overlays) and the editor's Image tab; each says what to do with the chosen file and which files it uses. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var I = U.images = {};
  var lib = null;

  /** The library as last read, or null before the first read. */
  I.cached = function () { return lib; };
  /** GET images, read once per page and again after an upload or a delete (force). */
  I.library = function (force) {
    if (lib && !force) return Promise.resolve(lib);
    return U.api.get(U.vhostPath() + '/images').then(function (r) { lib = (r && r.images) || []; return lib; });
  };
  I.entry = function (file) { return (lib || []).filter(function (x) { return x.file === file; })[0] || null; };

  /** The picker: images already on the server, an upload, and removing an upload. opts: selected file, okLabel. */
  I.pickerHtml = function (opts) {
    opts = opts || {};
    if (!lib) return '<div class="ly-picker"><div class="skel"></div></div>';
    var sel = lib.some(function (x) { return x.file === opts.selected; }) ? opts.selected : lib.length ? lib[0].file : null;
    return '<div class="ly-picker">' + (lib.length ? '<div class="ly-grid" role="radiogroup" aria-label="Images on the server">' + lib.map(function (x) {
        return '<label class="ly-pick"><input type="radio" name="ovs-img" value="' + U.esc(x.file) + '"' + (x.file === sel ? ' checked' : '') + '>' + (x.preview ? '<img src="' + U.esc(x.preview) + '" alt="">' : '<span class="ly-noimg">?</span>') + '<span>' + U.esc(x.file) +
          '<small>' + U.esc(x.width) + ' x ' + U.esc(x.height) + '</small></span>' + (x.uploaded ? '<button class="btn sm icon ghost" type="button" data-imgdel="' + U.esc(x.file) + '" aria-label="Delete ' + U.esc(x.file) + ' from the server">' + U.icon('close') + '</button>' : '') + '</label>'; }).join('') + '</div>'
        : '<p class="muted small">No image in the content folder yet. Upload one.</p>') +
      '<div class="fld" style="margin-top:12px"><label class="f" for="ovs-img-up">Upload an image (PNG, JPEG, GIF or BMP, up to 2 MB)</label><input id="ovs-img-up" type="file" accept=".png,.jpg,.jpeg,.gif,.bmp">' +
      '<div class="hint">Uploads go to the server\'s <code>content/overlaystudio/</code> folder.</div></div>' +
      '<div class="hrow end" style="margin-top:8px"><button type="button" class="btn sm" data-pickcancel>Cancel</button><button type="button" class="btn sm primary" data-pickok' + (lib.length ? '' : ' disabled') + '>' + U.esc(opts.okLabel || 'Use image') + '</button></div></div>';
  };

  /**
   * Wires a picker in box. opts.onPick(file, uploaded) gets the chosen or uploaded file; opts.onCancel closes it;
   * opts.inUse(file) says why an upload may not be deleted, or null; opts.onChange() follows a delete.
   */
  I.bind = function (box, opts) {
    if (!box) return;
    var cancel = box.querySelector('[data-pickcancel]'), ok = box.querySelector('[data-pickok]');
    if (cancel) cancel.onclick = function () { opts.onCancel(); };
    if (ok) ok.onclick = function () { var r = box.querySelector('input[name=ovs-img]:checked'); if (r) opts.onPick(r.value, false); };
    box.querySelectorAll('[data-imgdel]').forEach(function (b) {
      b.onclick = function (e) {
        e.preventDefault();
        var f = b.getAttribute('data-imgdel'), why = opts.inUse && opts.inUse(f);
        if (why) { U.toast(why, { level: 'bad' }); return; }
        U.api.post(U.vhostPath() + '/images', { 'delete': f }).then(function () { U.toast('Deleted ' + f + ' from the server.'); return I.library(true).then(function () { if (opts.onChange) opts.onChange(); }); },
          function (er) { U.toast(er.message, { level: 'bad' }); });
      };
    });
    var up = box.querySelector('#ovs-img-up');
    if (up) up.onchange = function () {
      var f = up.files && up.files[0];
      if (!f) return;
      if (f.size > 2 * 1024 * 1024) { U.toast('An image is at most 2 MB.', { level: 'bad' }); return; }
      var rd = new FileReader();
      rd.onload = function () {
        var name = f.name.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^[^A-Za-z0-9]+/, '');
        U.api.post(U.vhostPath() + '/images', { name: name, data: rd.result }).then(function (r) {
          return I.library(true).then(function () { opts.onPick(r.file, true); });
        }, function (er) { U.toast('Not uploaded: ' + er.message, { level: 'bad' }); });
      };
      rd.readAsDataURL(f);
    };
  };
})();

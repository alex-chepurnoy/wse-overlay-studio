/*
 * This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
/* Overlay Studio home, On air: the application's streams as rows, filtered from the list column. Until the module
   measures what each output draws, rows show what the template is configured to do, labeled "configured", and no
   snapshot (the fallback layout of the design). Each part of a row opens where it is changed: the stream, its
   overlay, and the template its outputs come from. */
(function () {
  'use strict';
  var U = window.OverlayStudio;
  var box = null;
  var TITLES = { all: 'On air in ', attention: 'Needs attention in ', healthy: 'Healthy in ', off: 'Not publishing in ' };

  function outsHtml(s) {
    var outs = U.outputsOf(U.templateOfStream(s));
    if (!s.template) return '<span class="small faint">Not transcoded</span>';
    var tpl = String(s.template).replace(/\.xml$/i, '');
    var to = U.templateEntry && U.templateEntry(tpl) ? ' data-go="template:' + U.esc(tpl) + '" title="Open ' + U.esc(tpl) + (s.own ? ', the copy for this stream' : '') + '"' : ' disabled';
    if (!outs.length) return '<button type="button" class="ochips linkbtn"' + to + '><span class="small faint">' + U.esc(s.template) + '</span></button>';
    return '<button type="button" class="ochips linkbtn"' + to + '>' + outs.map(function (o) {
      return '<span class="ochip" title="' + U.esc(U.configuredText(o)) + ', configured"><b>' + U.esc(o.name) + '</b>' + (o.carries ? 'overlay' : o.passthrough ? 'passthrough' : o.audio ? 'audio' : 'no overlay') + '</span>';
    }).join('') + '<span class="small faint">configured</span></button>';
  }
  // one chip per stream, the most important fact; broken beats attention. At most three amber marks on a screen.
  var amber = 0;
  function chip(s) {
    if (s.overlay && !U.overlayEntry(s.overlay)) return '<span class="bdg bad">Overlay missing</span>';
    if (s.restartNeeded) return '<span class="bdg' + (amber++ < 3 ? ' warn' : '') + '">Restart pending</span>';
    if (s.publishing && !s.overlay) return '<span class="bdg">No overlay</span>';
    return '';
  }
  function vwText(s) { var n = U.viewers(s); return s.publishing && n != null ? n + ' viewer' + (n === 1 ? '' : 's') : ''; }
  function row(s) {
    var n = U.viewers(s), ov = s.overlay && U.overlayEntry(s.overlay);
    return '<div class="srow' + (U.view.highlight === s.name ? ' new' : '') + '">' +
      '<span class="dot ' + (s.publishing ? 'live' : '') + '"></span><button type="button" class="nm linkbtn" data-go="stream:' + U.esc(s.name) + '" title="Open ' + U.esc(s.name) + '">' + U.esc(s.name) + '</button>' +
      '<span class="ov">' + (ov ? '<button type="button" class="linkbtn" data-go="overlay:' + U.esc(s.overlay) + '" title="Open the overlay ' + U.esc(s.overlay) + '">' + U.esc(s.overlay) + '</button>' : s.overlay ? U.esc(s.overlay) : '') + ' ' + chip(s) + '</span>' +
      '<span class="outs">' + outsHtml(s) + '</span>' +
      '<span class="vw" data-vw="' + U.esc(s.name) + '" ' + (n != null ? 'title="Viewers on this server"' : '') + '>' + vwText(s) + '</span></div>';
  }

  function render() {
    if (!box) return;
    amber = 0;
    var f = U.view.filter || 'all', live = U.live(), off = U.streams().filter(function (s) { return !s.publishing; });
    var list = f === 'attention' ? live.filter(U.attention) : f === 'healthy' ? live.filter(function (s) { return !U.attention(s); }) : f === 'off' ? off : live;
    var anyViewers = live.some(function (s) { return U.viewers(s) != null; });
    // first visit: nothing to show yet, one way in
    if (!(U.overlays || []).length && !live.length) {
      box.innerHTML = '<div class="pnl firstrun"><h2>Burn a web page into a stream</h2><p class="muted">Four steps: the page, the renditions that carry it, the stream, and a look at what viewers get.</p>' +
        (U.readOnly ? U.roNote() : '<button type="button" class="btn primary" data-task>' + U.icon('plus') + 'Create a new overlay</button>') + '</div>';
      bind(); return;
    }
    var h = '<div class="home-h"><h2>' + TITLES[f] + U.esc(U.app) + '</h2><span class="small faint">What each template is configured to draw' + (anyViewers ? '; viewers on this server' : '') + '</span>' +
      '<span class="spacer"></span><button type="button" class="btn primary" data-write data-task>' + U.icon('plus') + 'Create a new overlay</button></div>';
    h += '<div class="pnl">' + (list.length ? '<div class="srow-h"><span></span><span>Stream</span><span class="c3">Overlay</span><span class="c4">Outputs, from the template</span><span>' + (anyViewers ? 'Viewers' : '') + '</span></div>' + list.map(row).join('')
      : '<p class="empty-sm">' + (f === 'all' ? 'No stream is publishing to ' + U.esc(U.app) + ' now. Streams show here while they publish.' : 'Nothing here.') + '</p>') + '</div>';
    if (f === 'all' && off.length) h += '<div class="offrow"><span class="small muted">Not publishing:</span>' + off.map(function (s) {
      return '<button type="button" class="chip' + (U.view.highlight === s.name ? ' new' : '') + '" data-go="stream:' + U.esc(s.name) + '"><span class="dot"></span>' + U.esc(s.name) + (s.overlay ? '<span class="k">' + U.esc(s.overlay) + ' when it starts</span>' : '') + '</button>';
    }).join('') + '</div>';
    var blocks = ((U.serverInfo && U.serverInfo.egress && U.serverInfo.egress.recentBlocks) || []).slice(-3).reverse();
    if (blocks.length) h += '<div class="pnl" style="margin-top:16px"><div class="pnl-h"><h3>Recent page blocks</h3><span class="small faint">Since WSE started</span><span class="spacer"></span>' +
      '<button type="button" class="btn sm" data-status-open="network">Network rules</button></div><ul class="loglist">' + blocks.map(function (b) {
        return '<li><span class="dot bad"></span><span class="faint">' + U.esc(U.hhmm(new Date(b.at))) + '</span><span>' + U.esc(b.host) + ' blocked: ' + U.esc(b.rule) + (b.capture ? ' (' + U.esc(b.capture) + ')' : '') + '</span></li>';
      }).join('') + '</ul></div>';
    box.innerHTML = h;
    bind();
  }
  function bind() {
    box.querySelectorAll('[data-task]').forEach(function (b) { b.onclick = function () { U.startTask(); }; });
  }

  U.screens.home = {
    init: function (b) { box = b; },
    show: function () { render(); },
    state: function () { if (U.current() === 'home') render(); },
    // only the counts change: the cells are updated in place
    viewers: function () {
      if (U.current() !== 'home' || !box) return;
      box.querySelectorAll('[data-vw]').forEach(function (c) { var r = U.streamRow(c.getAttribute('data-vw')), t = r ? vwText(r) : ''; if (c.textContent !== t) c.textContent = t; });
    },
    hide: function () { U.view.highlight = null; }
  };
})();

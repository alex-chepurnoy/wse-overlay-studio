// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// A fake WSE behind the Manager bridge, for the page tests. Answers what /enginemanager/overlaystudio-api answers:
// /status, /token and /rest/... for the module's resources, in the shapes the module's REST classes build. The
// templates are the validator's own output for the starters, and the overlays documents OverlayStore keeps as they
// are; module/src/test/.../UiFakeContractTest checks both. State changes (saving an overlay, assigning a template) are
// kept, so a flow sees its own writes. Every request is recorded; one the fake does not know answers 404 and is listed
// in `unknown`, which the tests require to be empty.
'use strict';
const fs = require('fs');
const path = require('path');

const FIXTURES = path.join(__dirname, 'fixtures');
const PIXEL = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFBQIAX8jx0gAAAABJRU5ErkJggg==';
const MODULE = '/v1/server/plugin/overlaystudio';
const VH = '/vhosts/_defaultVHost_';
// the version the page's script tags carry in ui/index.html; the installer stamps the release version over it
const PAGE_VERSION = /ovs-core\.js\?v=([^"&]+)/.exec(fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'index.html'), 'utf8'))[1];

// a 16 x 9 PNG, solid blue, standing in for every captured page
const PNG_16x9 = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAJCAIAAAC0SDtlAAAAFUlEQVR4nGOQizpBEmIY1RA1GDQAALUftAG29Z74AAAAAElFTkSuQmCC';

function read(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
}

// the start of what WSE Manager sends once its session has ended
const SIGN_IN = ['<!--Redirect to Login-->', '<!--Leave above comment for UI to redirect properly -->', '<!DOCTYPE html>',
  '<html><head><title>Wowza Streaming Engine Manager</title></head><body>Sign In</body></html>'].join('\n');

class FakeWse {
  constructor(opts = {}) {
    this.opts = Object.assign({ bridge: true, write: true, serviceRunning: true }, opts);
    this.requests = [];
    this.unknown = [];
    this.posts = [];
    this.revision = 1;
    this.token = 'token-1';
    this.overlays = read('overlays.json');
    // live values by overlay, then layer id, as LiveValues.get answers; the /format requests the page made
    this.live = {};
    this.formats = [];
    this.modified = {};
    Object.keys(this.overlays).forEach((n) => { this.modified[n] = 1767225600000; });
    const starters = read('templates.json');
    this.templates = {};
    Object.keys(starters).forEach((n) => { this.templates[n] = Object.assign({}, starters[n]); });
    // a master built from the CPU starter that carries the scoreboard, as the page's template editor makes one
    this.templates['scoreboard-cpu'] = Object.assign({}, starters['overlaystudio-cpu'], {
      overlay: 'Scoreboard',
      description: 'Managed by Overlay Studio: CPU decode and encode, 720p and 360p, with overlays.'
    });
    this.apps = [
      { name: 'live', installed: true, streamType: 'live', liveStreamTranscoder: 'transcoder', templates: '${SourceStreamName}.xml,scoreboard-cpu.xml', savedStreams: [] },
      { name: 'live2', installed: false, streamType: 'live', liveStreamTranscoder: '', templates: '${SourceStreamName}.xml,transrate.xml', savedStreams: [] },
      { name: 'vod', installed: false, streamType: 'default', liveStreamTranscoder: '', templates: '', savedStreams: [] }
    ];
    this.streams = [
      { name: 'cam1', publishing: true, pulled: false, arrives: 'encoder', restartNeeded: false, template: 'scoreboard-cpu.xml', own: false },
      { name: 'cam2', publishing: true, pulled: false, arrives: 'encoder', restartNeeded: false, template: null, own: false }
    ];
    this.captureLimit = { running: true, limit: 4, startLimit: 4, set: false, refused: null, ceiling: 16, memoryMaxMB: 2048, cpuLimit: 8, maxBrowserMemoryMB: 768, active: 1 };
  }

  /** Routes the bridge for a Playwright page. */
  async attach(page) {
    await page.route('**/enginemanager/overlaystudio-api/**', (route) => this.handle(route));
  }

  async handle(route) {
    const req = route.request();
    const url = new URL(req.url());
    const p = url.pathname.replace(/^\/enginemanager\/overlaystudio-api/, '');
    const method = req.method() === 'POST' ? (url.searchParams.get('method') || 'POST') : req.method();
    let body = null;
    if (req.method() === 'POST' && req.postData()) {
      try { body = JSON.parse(req.postData()); } catch (e) { body = req.postData(); }
    }
    this.requests.push({ method, path: p + url.search, body });
    // the Manager's session has ended: it answers every call with its sign-in page, as a 200
    if (this.signedOut) return route.fulfill({ status: 200, contentType: 'text/html', body: SIGN_IN });
    if (req.method() === 'POST' && url.searchParams.get('wowzaSecurityToken') !== this.token) {
      return route.fulfill({ status: 403, contentType: 'text/plain', body: 'CSRF token missing or wrong' });
    }
    // a slow font list, as on a server whose fonts take a moment to register
    if (this.fontsDelay && p.endsWith('/fonts')) await new Promise((res) => setTimeout(res, this.fontsDelay));
    const r = this.answer(method, p, url.searchParams, body);
    if (r === undefined) {
      this.unknown.push(method + ' ' + p);
      return route.fulfill({ status: 404, contentType: 'application/json', body: JSON.stringify({ success: false, message: 'Not found in the fake: ' + p }) });
    }
    const status = r && r.status ? r.status : 200;
    const json = r && r.status ? r.json : r;
    return route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(json) });
  }

  answer(method, p, q, body) {
    if (p === '/status') {
      return this.opts.bridge ? { bridge: 'overlaystudio', version: PAGE_VERSION, signedIn: true } : { status: 404, json: {} };
    }
    if (p === '/token') return { token: this.token };
    if (!p.startsWith('/rest' + MODULE)) return undefined;
    const r = p.slice(('/rest' + MODULE).length);
    let m;
    if (r === '/info' && method === 'GET') return this.info();
    if (r === '/capture/limit') {
      if (method === 'POST') { this.posts.push({ path: r, body }); this.captureLimit.limit = body.maxCaptures; this.captureLimit.set = true; }
      return this.captureLimit;
    }
    if (r === VH + '/applications' && method === 'GET') return { applications: this.apps };
    // as Fonts.list (plan 3.7)
    if (r === '/fonts' && method === 'GET') {
      return { available: true, problem: null, default: 'Fira Sans',
        bundled: ['Fira Sans', 'Fira Sans Condensed', 'Fira Mono', 'PT Serif'].map((family) => ({ family, styles: ['plain', 'bold', 'italic', 'bolditalic'], files: ['plain', 'bold'], shadowedBy: null })),
        logical: ['Serif', 'SansSerif', 'Monospaced', 'Dialog', 'DialogInput'], system: ['DejaVu Sans'] };
    }
    if (r === '/format' && method === 'GET') return this.format(q);
    if (r === VH + '/images') {
      if (method === 'POST') { this.posts.push({ path: r, body }); return { success: true }; }
      // one image whole, as ImageLibrary.data answers: a 1 x 1 PNG standing for a 200 x 100 picture
      if (q.get('file')) return { file: q.get('file'), width: 200, height: 100, data: 'data:image/png;base64,' + PIXEL };
      // as ImageLibrary.list: one upload of this module's, one picture put in content/ by hand
      return { images: [
        { file: 'overlaystudio/logo.png', width: 200, height: 100, uploaded: true, preview: 'data:image/png;base64,' + PIXEL },
        { file: 'house.png', width: 50, height: 50, uploaded: false, preview: 'data:image/png;base64,' + PIXEL }] };
    }
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/state$/.exec(r)) && method === 'GET') {
      return this.state(q.get('rev'));
    }
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/overlays\/([^/]+)$/.exec(r))) {
      const name = decodeURIComponent(m[2]);
      if (method === 'GET') {
        return this.overlays[name] ? Object.assign({ name }, this.overlays[name]) : { status: 404, json: { success: false, message: 'No overlay ' + name } };
      }
      if (method === 'POST') {
        this.posts.push({ path: r, body });
        this.overlays[name] = body;
        this.modified[name] = Date.now();
        this.revision++;
        return Object.assign({ success: true, name }, body, { updatedStreams: this.streams.filter((s) => s.publishing && this.overlayOf(s) === name).map((s) => s.name) });
      }
      if (method === 'DELETE') {
        delete this.overlays[name];
        this.revision++;
        return { success: true };
      }
    }
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/overlays\/([^/]+)\/preview$/.exec(r)) && method === 'POST') {
      // a draft page captured as it would draw, before it is saved
      const capture = { state: 'running', lastCaptureAt: Date.now() - 200, lastCaptureMs: 41, lastError: null, failures: 0, sandbox: 'on' };
      return Object.assign({ id: body && body.page ? body.page.id : null, capture, width: 16, height: 9, png: PNG_16x9 }, body && body.raw ? { rawPng: PNG_16x9 } : {});
    }
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/overlays\/([^/]+)\/objects\/([^/]+)\/capture$/.exec(r))) {
      const name = decodeURIComponent(m[2]), id = decodeURIComponent(m[3]);
      const page = ((this.overlays[name] || {}).objects || []).find((o) => o.id === id && o.type === 'webPage');
      if (!page) return { status: 404, json: { success: false, message: "No page '" + id + "' in overlay " + name } };
      if (method === 'POST') { this.posts.push({ path: r, body }); return { success: true, id, reload: true }; }
      return {
        id, raw: q.get('raw') === 'true',
        capture: { state: 'running', lastCaptureAt: Date.now() - 400, lastCaptureMs: 38, lastError: null, failures: 0, sandbox: 'on' },
        width: 16, height: 9, png: PNG_16x9
      };
    }
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/instances\/_definst_\/streams\/([^/]+)\/(status|preview)$/.exec(r)) && method === 'GET') {
      const s = this.streams.find((x) => x.name === decodeURIComponent(m[2]));
      return m[3] === 'status' ? this.streamStatus(s) : this.preview(m[1], s);
    }
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/overlays\/([^/]+)\/live$/.exec(r))) {
      const name = decodeURIComponent(m[2]);
      if (!this.overlays[name]) return { status: 404, json: { success: false, message: 'No overlay ' + name } };
      if (method === 'GET') return this.live[name] || {};
      if (method === 'POST') {
        this.posts.push({ path: r, body });
        const had = !!(this.live[name] && this.live[name][body.clear]);
        if (had) delete this.live[name][body.clear];
        return { success: true, cleared: had ? body.clear : null, updatedStreams: [] };
      }
    }
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/templates\/order$/.exec(r))) {
      if (method === 'POST') { this.posts.push({ path: r, body }); return { success: true }; }
      return { templates: this.apps[0].templates.split(',') };
    }
    return undefined;
  }

  // as FormatConfig: count samples from now, each with the moment it starts to show (each start of the pattern's
  // smallest unit, in UTC here), "next" after the last, or the error; a pattern holding "bad" is refused. A sample is
  // the pattern with the number of that unit since 1970, so a test can tell which one shows. formatFail answers that
  // many asks with a 500 first.
  format(q) {
    if (this.formatFail > 0) { this.formatFail--; return { status: 500, json: { success: false, message: 'format failed in the fake' } }; }
    const kind = q.get('kind') || 'time', pattern = q.get('pattern') || '', now = Number(q.get('now')), count = Math.min(120, Number(q.get('count') || 1));
    this.formats.push({ kind, pattern, zone: q.get('zone'), locale: q.get('locale'), target: q.get('target'), now, streamStart: Number(q.get('streamStart')) });
    const out = { defaultZone: 'America/New_York', defaultLocale: 'en-US', kind, pattern, samples: [], at: [], next: null, error: null };
    if (/bad/.test(pattern)) { out.error = 'Unknown pattern letter: b'; return out; }
    const plain = pattern.replace(/'[^']*'/g, '');
    const unit = /S|s/.test(plain) ? 1000 : /m/.test(plain) ? 60000 : /[HhKkaB]/.test(plain) ? 3600000 : 86400000;
    out.stepMs = Math.max(1000, unit);
    let at = now;
    for (let i = 0; i < count; i++) {
      // stream time counts from the stream's start; everything else by the clock
      out.samples.push(pattern + ' #' + Math.floor((kind === 'streamtime' ? at - Number(q.get('streamStart')) : at) / unit));
      out.at.push(at);
      at = Math.max((Math.floor(at / unit) + 1) * unit, at + 1000);
    }
    out.next = at;
    return out;
  }

  overlayOf(s) {
    const t = s.template && this.templates[s.template.replace(/\.xml$/, '')];
    return t ? t.overlay : null;
  }

  info() {
    return {
      version: PAGE_VERSION, wseVersion: '4.12.1+15', os: 'Linux', cpus: 8, accessMode: 'authorized',
      write: { allowed: this.opts.write, reason: this.opts.write ? null : 'Signed in as a read-only user; changes need an administrator.', user: 'producer' },
      streamingPorts: [{ port: 1935, ssl: false }],
      service: { running: this.opts.serviceRunning, message: this.opts.serviceRunning ? null : 'The capture socket did not answer.', socket: '/run/wse-overlay-studio/capture.sock', version: PAGE_VERSION, user: 'wse-overlay-studio' },
      chrome: this.opts.serviceRunning ? { found: true, version: '156.0.8078.80', path: '/opt/wse-overlay-studio/chrome-for-testing/156.0.8078.80/chrome-headless-shell' } : { found: false },
      chromeUpdate: null,
      captures: { active: 1, limit: this.captureLimit.limit, pagesPerBrowser: 4, browsers: 1, maxBrowserMemoryMB: 768, pagesCapturing: ['live/Scoreboard/score'], pagesFailing: [] },
      egress: { allowPrivate: false, allowHosts: [], blockHosts: [], blockChromeServices: true, ports: [80, 443], chromeServiceBlocks: 0, recentBlocks: [] },
      sandbox: 'on', sandboxProblem: null
    };
  }

  state(rev) {
    const revision = 'r' + this.revision;
    if (rev === revision) return { revision, unchanged: true, viewers: { cam1: 3, cam2: 0 } };
    const templates = Object.keys(this.templates).sort().map((n) => Object.assign({ name: n, template: n + '.xml', modified: 1767225600000, shipped: false }, this.templates[n], {
      listedBy: this.apps.filter((a) => a.templates.split(',').indexOf(n + '.xml') >= 0).map((a) => a.name)
    }));
    const streams = this.streams.map((s) => {
      const row = Object.assign({}, s, { viewers: s.publishing ? (s.name === 'cam1' ? 3 : 0) : 0 });
      const t = s.template && this.templates[s.template.replace(/\.xml$/, '')];
      if (t) Object.assign(row, { vif: t.vif, overlay: t.overlay, usable: t.usable, description: t.description });
      return row;
    });
    const overlays = Object.keys(this.overlays).sort().map((n) => ({
      name: n, pages: (this.overlays[n].objects || []).length, modified: this.modified[n],
      templates: Object.keys(this.templates).filter((t) => this.templates[t].overlay === n),
      showingOn: this.streams.filter((s) => s.publishing && this.overlayOf(s) === n).map((s) => s.name)
    }));
    return { revision, overlays, templates, streams, appTemplates: this.apps[0].templates, liveStreamTranscoder: 'transcoder' };
  }

  streamStatus(s) {
    if (!s || !s.publishing) return { stream: s ? s.name : null, configured: false, state: 'stopped' };
    const tpl = s.template && this.templates[s.template.replace(/\.xml$/, '')];
    // the shape of OverlayStudioModule.streamStatus
    if (!tpl) return { stream: s.name, configured: false, overlayIndex: null, transcoder: { state: 'not-started', decoderWidth: null, decoderHeight: null, encodes: [] }, template: null, overlayFrom: { source: null, name: null, missing: false }, draw: null };
    const pass = (e) => e.codec === 'PassThru';
    return {
      stream: s.name, configured: true, overlayIndex: 16,
      transcoder: { state: 'running', decoderWidth: 1920, decoderHeight: 1080,
        encodes: (tpl.encodes || []).map((e) => ({ name: e.name, outputStream: e.streamName.replace('${SourceStreamName}', s.name), width: 1280, height: 720, passThrough: pass(e), overlay: !pass(e), slots: pass(e) ? [] : [1] })) },
      template: { loaded: s.template, own: s.template === s.name + '.xml', list: '${SourceStreamName}.xml,transrate.xml', outputsChanged: false },
      overlayFrom: { source: tpl.overlay ? 'template' : 'stream', name: tpl.overlay || null, missing: !!tpl.overlay && !this.overlays[tpl.overlay] },
      draw: []
    };
  }

  preview(app, s) {
    const name = s ? s.name : 'none';
    const tpl = s && s.template && this.templates[s.template.replace(/\.xml$/, '')];
    const hls = (n) => 'http://{host}:1935/' + app + '/' + n + '/playlist.m3u8';
    return {
      source: { encode: null, stream: name, hls: hls(name) },
      outputs: ((tpl && tpl.encodes) || []).map((e) => { const n = e.streamName.replace('${SourceStreamName}', name); return { encode: e.name, stream: n, hls: hls(n) }; }),
      ports: { http: [1935], https: [] }, streamers: ['cupertinostreaming'], application: app
    };
  }
}

module.exports = { FakeWse, PAGE_VERSION };

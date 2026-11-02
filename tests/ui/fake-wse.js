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
const MODULE = '/v1/server/plugin/overlaystudio';
const VH = '/vhosts/_defaultVHost_';
// the version the page's script tags carry in ui/index.html; the installer stamps the release version over it
const PAGE_VERSION = /ovs-core\.js\?v=([^"&]+)/.exec(fs.readFileSync(path.join(__dirname, '..', '..', 'ui', 'index.html'), 'utf8'))[1];

// a 16 x 9 PNG, solid blue, standing in for every captured page
const PNG_16x9 = 'iVBORw0KGgoAAAANSUhEUgAAABAAAAAJCAIAAAC0SDtlAAAAFUlEQVR4nGOQizpBEmIY1RA1GDQAALUftAG29Z74AAAAAElFTkSuQmCC';

function read(name) {
  return JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
}

class FakeWse {
  constructor(opts = {}) {
    this.opts = Object.assign({ bridge: true, write: true, serviceRunning: true }, opts);
    this.requests = [];
    this.unknown = [];
    this.posts = [];
    this.revision = 1;
    this.token = 'token-1';
    this.overlays = read('overlays.json');
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
    if (req.method() === 'POST' && url.searchParams.get('wowzaSecurityToken') !== this.token) {
      return route.fulfill({ status: 403, contentType: 'text/plain', body: 'CSRF token missing or wrong' });
    }
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
    if (r === VH + '/images') {
      if (method === 'POST') { this.posts.push({ path: r, body }); return { success: true }; }
      return { images: [] };
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
    if ((m = /^\/vhosts\/_defaultVHost_\/applications\/([^/]+)\/templates\/order$/.exec(r))) {
      if (method === 'POST') { this.posts.push({ path: r, body }); return { success: true }; }
      return { templates: this.apps[0].templates.split(',') };
    }
    return undefined;
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
    if (!tpl) return { stream: s.name, configured: false, state: 'running', transcoder: false };
    return {
      stream: s.name, configured: true, overlayIndex: 16, state: 'running', decoderWidth: 1280, decoderHeight: 720,
      encodes: (tpl.encodes || []).map((e) => ({ name: e.name, outputStream: e.streamName.replace('${SourceStreamName}', s.name), width: 1280, height: 720, passThrough: e.codec === 'PassThru', overlay: true })),
      transcoder: true, outputsChanged: false, template: s.template,
      source: tpl.overlay ? { name: tpl.overlay, missing: !this.overlays[tpl.overlay] } : null,
      overlayFrom: tpl.overlay ? 'template' : null,
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

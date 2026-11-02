// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// The page as WSE Manager shows it, with no Manager: a host page at /enginemanager/ that holds ui/index.html the way
// the Manager inserts a plugin page, the plugin's files served from ui/, and the bridge answered by FakeWse. Nothing
// leaves the browser: any other request is aborted and recorded. Each test fails on a script error in the page, on a
// request the fake does not know, and on a request to anywhere else.
'use strict';
const fs = require('fs');
const path = require('path');
const base = require('@playwright/test');
const { FakeWse } = require('./fake-wse');

const UI = path.join(__dirname, '..', '..', 'ui');
const ORIGIN = 'http://wse.test';
const HOST = ORIGIN + '/enginemanager/overlay-studio';
const PLUGIN = '/enginemanager/wse-plugins/server/overlay-studio/';
const TYPES = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css', '.json': 'application/json', '.svg': 'image/svg+xml', '.png': 'image/png',
  '.ttf': 'font/ttf' };
// the installer copies the module's bundled fonts to the page's fonts/ folder; here they are served from where they live
const FONTS = path.join(__dirname, '..', '..', 'module', 'src', 'main', 'resources', 'fonts');

function hostPage() {
  // the Manager's own page around the plugin: a full document with the plugin's markup inside its content area
  return '<!doctype html><html><head><meta charset="utf-8"><title>WSE Manager</title></head><body><div id="manager-content">' +
    fs.readFileSync(path.join(UI, 'index.html'), 'utf8') + '</div></body></html>';
}

const test = base.test.extend({
  // options for the fake, set per file or per test with test.use({ wseOptions: {...} })
  wseOptions: [{}, { option: true }],
  wse: async ({ wseOptions }, use) => {
    await use(new FakeWse(wseOptions));
  },
  problems: async ({}, use) => {
    await use({ errors: [], outside: [] });
  },
  studio: async ({ page, wse, problems }, use) => {
    page.on('pageerror', (e) => problems.errors.push('pageerror: ' + e.message));
    // a failed load is logged by the browser too; requests are checked below, by what they asked for
    page.on('console', (m) => { if (m.type() === 'error' && !/^Failed to load resource/.test(m.text())) problems.errors.push('console: ' + m.text()); });
    await page.route('**/*', (route) => {
      const url = new URL(route.request().url());
      if (url.origin === ORIGIN && url.pathname === '/enginemanager/overlay-studio') {
        return route.fulfill({ status: 200, contentType: 'text/html', body: hostPage() });
      }
      if (url.origin === ORIGIN && url.pathname.startsWith(PLUGIN)) {
        const rel = decodeURIComponent(url.pathname.slice(PLUGIN.length));
        const file = rel.startsWith('fonts/') ? path.join(FONTS, rel.slice(6)) : path.join(UI, rel);
        if (!(file.startsWith(UI) || file.startsWith(FONTS)) || !fs.existsSync(file)) return route.fulfill({ status: 404, body: '' });
        return route.fulfill({ status: 200, contentType: TYPES[path.extname(file)] || 'application/octet-stream', body: fs.readFileSync(file) });
      }
      if (url.origin === ORIGIN && url.pathname.startsWith('/enginemanager/overlaystudio-api/')) return route.fallback();
      problems.outside.push(route.request().method() + ' ' + url.href);
      return route.abort();
    });
    await wse.attach(page);
    const studio = {
      /** Opens the page and waits until it has drawn its first screen. */
      async open() {
        await page.goto(HOST);
        await page.locator('[data-screen]:not([hidden])').first().waitFor();
      },
      root: page.locator('#ovs-root')
    };
    await use(studio);
  }
});

// every test: no script errors, nothing the fake does not know, nothing sent anywhere else; HLS players are the one
// expected outside request (the fake's streaming port), and their failures are not page errors
test.afterEach(async ({ wse, problems }) => {
  const outside = problems.outside.filter((u) => !/:1935\//.test(u));
  base.expect(problems.errors, 'script errors in the page').toEqual([]);
  base.expect(wse.unknown, 'requests the fake does not know').toEqual([]);
  base.expect(outside, 'requests to anywhere but the Manager').toEqual([]);
});

module.exports = { test, expect: base.expect };

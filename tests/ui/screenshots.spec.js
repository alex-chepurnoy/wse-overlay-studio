// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// The screenshots in docs/USAGE.md, taken from the page against the fake WSE (plan PR 12). Skipped unless
// OVS_SCREENSHOTS=1, so the normal run writes nothing:
//   OVS_SCREENSHOTS=1 npx playwright test screenshots.spec.js
// The fake has no video: the overlay editor is shown on a blank frame, the banner saying HLS cannot load from the
// fake's streaming port is cleared before each picture, and date and time samples are formatted here.
'use strict';
const path = require('path');
const { test, expect } = require('./harness');

const OUT = path.join(__dirname, '..', '..', 'docs', 'images');
async function shot(page, name) {
  // a banner only the fake causes (no streaming port to play HLS from)
  await page.evaluate(() => document.querySelectorAll('.banners [data-banner]').forEach((b) => b.remove()));
  await page.screenshot({ path: path.join(OUT, name + '.png') });
}
// a date and time sample as FormatConfig would give it, for the patterns the fixtures use
function formatted(pattern, ms) {
  const d = new Date(ms), p = (n) => String(n).padStart(2, '0');
  return pattern.replace(/HH|mm|ss/g, (x) => (x === 'HH' ? p(d.getHours()) : x === 'mm' ? p(d.getMinutes()) : p(d.getSeconds())));
}
const leftTab = (page, name) => page.locator('[data-tab=' + name + ']');

test.skip(!process.env.OVS_SCREENSHOTS, 'set OVS_SCREENSHOTS=1 to write docs/images');
test.use({ viewport: { width: 1280, height: 800 } });

// scoreboard-cpu, which cam1 runs, carries Scoreboard on every output and LowerThird on 720p
test.beforeEach(async ({ wse }) => {
  Object.assign(wse.templates['scoreboard-cpu'], { overlays: [{ name: 'Scoreboard', encodes: [], slot: 1 }, { name: 'LowerThird', encodes: ['720p'], slot: 2 }] });
  const format = wse.format.bind(wse);
  wse.format = (q) => {
    const r = format(q);
    if (r && r.samples) r.samples = r.at.map((at) => formatted(r.pattern, at));
    return r;
  };
});

test('usage-1-on-air', async ({ page, studio }) => {
  await studio.open();
  await expect(page.locator('.srow').filter({ hasText: 'cam1' })).toContainText('LowerThird');
  await shot(page, 'usage-1-on-air');
});

test('usage-2-create', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-task]').first().click();
  await expect(page.locator('[data-screen=task]')).toBeVisible();
  await page.waitForTimeout(300);
  await shot(page, 'usage-2-create');
});

test('usage-3-overlay', async ({ page, studio, wse }) => {
  // no stream publishing, so the canvas is a blank frame rather than a player with nothing to play
  wse.streams.forEach((s) => { s.publishing = false; });
  await studio.open();
  await leftTab(page, 'overlays').click();
  await page.locator('[data-list]').getByText('Mixed', { exact: true }).first().click();
  await expect(page.locator('[data-check]')).toHaveText('Matches the server\'s drawing.');
  await page.locator('[data-layers] .prow[data-id="score"]').click();
  await page.waitForTimeout(300);
  await shot(page, 'usage-3-overlay');
});

test('usage-4-template', async ({ page, studio }) => {
  await studio.open();
  await leftTab(page, 'templates').click();
  await page.locator('[data-list]').getByText('scoreboard-cpu', { exact: true }).first().click();
  await page.locator('[data-rowfor="tpladd:input"]').click();
  await page.getByRole('menuitem', { name: 'Overlay', exact: true }).click();
  await expect(page.locator('[data-ovpick] [data-ovpname="Mixed"] img')).toHaveAttribute('src', /^data:image/);
  await shot(page, 'usage-4-template');
});

test('usage-5-stream', async ({ page, studio }) => {
  await studio.open();
  await leftTab(page, 'streams').click();
  await page.locator('[data-list]').getByText('cam1', { exact: true }).click();
  await expect(page.locator('[data-screen=stream] .ovbadge')).toHaveCount(2);
  await page.waitForTimeout(500);
  await shot(page, 'usage-5-stream');
});

test('usage-6-settings', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-act=settings]').click();
  await expect(page.locator('[data-lim]')).toBeEnabled();
  await shot(page, 'usage-6-settings');
});

test('usage-server', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-status-open="capture"]').first().click();
  await page.waitForTimeout(500);
  await shot(page, 'usage-server');
});

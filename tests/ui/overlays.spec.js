// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// Every screen names every overlay a template carries (plan 3.9, 4.6, PR 11): On air, the Streams and Templates
// lists, a stream's pipeline and its verdict, the template-switch dry run, and the editor's "On air" badge. cam1
// publishes with scoreboard-cpu.
'use strict';
const { test, expect } = require('./harness');

const leftTab = (page, name) => page.locator('[data-tab=' + name + ']');
// scoreboard-cpu carries Scoreboard on every output and LowerThird on 720p only
function twoOverlays(wse, second) {
  Object.assign(wse.templates['scoreboard-cpu'], { overlays: [{ name: 'Scoreboard', encodes: [], slot: 1 }, { name: second || 'LowerThird', encodes: ['720p'], slot: 2 }] });
}

test('On air lists every overlay a stream\'s template names, and what each output carries', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  await studio.open();
  const row = page.locator('.srow').filter({ hasText: 'cam1' });
  await expect(row.locator('.ov')).toContainText('Scoreboard, LowerThird');
  await expect(row.locator('.ov [data-go="overlay:LowerThird"]')).toBeVisible();
  await expect(row.locator('.ochip').filter({ hasText: '720p' })).toContainText('2 overlays');
  await expect(row.locator('.ochip').filter({ hasText: '360p' })).toContainText('overlay');
  await expect(row.locator('.ochip').filter({ hasText: '720p' })).toHaveAttribute('title', 'Carries Scoreboard, LowerThird, configured');
});

test('the Streams and Templates lists name every overlay', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  await studio.open();
  await leftTab(page, 'streams').click();
  await expect(page.locator('[data-list] .li').filter({ hasText: 'cam1' })).toContainText('Scoreboard, LowerThird, scoreboard-cpu');
  await leftTab(page, 'templates').click();
  await expect(page.locator('[data-list] .li').filter({ hasText: 'scoreboard-cpu' }).first()).toContainText('Scoreboard, LowerThird');
});

test('a stream shows a badge per overlay, and each running output names the overlays it draws', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  await studio.open();
  await leftTab(page, 'streams').click();
  await page.locator('[data-list]').getByText('cam1', { exact: true }).click();
  const pipe = page.locator('[data-screen=stream]');
  await expect(pipe.locator('.ovbadge')).toHaveCount(2);
  await expect(pipe.locator('.ovbadge').nth(1)).toContainText('LowerThird');
  await expect(pipe.locator('[data-out="720p"]')).toContainText('Carries Scoreboard, LowerThird');
  await expect(pipe.locator('[data-out="360p"]')).toContainText('Carries Scoreboard');
  await pipe.locator('[data-out="720p"]').click();
  await expect(pipe).toContainText('720p is configured to carry Scoreboard, LowerThird');
});

test('an overlay a template names that does not exist is shown as missing, wherever the stream is', async ({ page, studio, wse }) => {
  twoOverlays(wse, 'Gone');
  await studio.open();
  await expect(page.locator('.srow').filter({ hasText: 'cam1' })).toContainText('Overlay missing');
  await leftTab(page, 'streams').click();
  await page.locator('[data-list]').getByText('cam1', { exact: true }).click();
  const pipe = page.locator('[data-screen=stream]');
  await expect(pipe).toContainText('Gone does not exist');
  await expect(pipe).toContainText('Gone does not exist in live, so it is not drawn.');
});

test('switching a stream\'s template names each overlay that leaves it', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  wse.templates.ladder = Object.assign({}, wse.templates['overlaystudio-cpu'], { managed: true, overlays: [{ name: 'Scoreboard', encodes: [], slot: 1 }] });
  await studio.open();
  await leftTab(page, 'streams').click();
  await page.locator('[data-list]').getByText('cam1', { exact: true }).click();
  await page.locator('.pn-tpl').getByRole('button', { name: 'Change template' }).click();
  await page.locator('[data-dtpl]').selectOption('ladder');
  await expect(page.locator('.dry')).toContainText('LowerThird disappears for 3 viewers on this server now. Scoreboard stays.');
  await expect(page.getByRole('region', { name: 'Unsaved changes' })).toContainText('LowerThird leaves cam1 now.');
});

test('the editor says On air for an overlay in a stream\'s second slot', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  await studio.open();
  await leftTab(page, 'overlays').click();
  await page.locator('[data-list]').getByText('LowerThird', { exact: true }).first().click();
  await page.locator('#ovs-pv').selectOption('cam1');
  await expect(page.locator('[data-caption]')).toContainText('On air on cam1');
});

test('the editor\'s background is a stream showing the overlay, in any slot', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  // cam2, which shows nothing, comes first in the list; cam1 shows LowerThird in slot 2
  wse.streams.reverse();
  await studio.open();
  await page.evaluate(() => { try { window.localStorage.clear(); } catch (e) { /* none */ } });
  await leftTab(page, 'overlays').click();
  await page.locator('[data-list]').getByText('LowerThird', { exact: true }).first().click();
  await expect(page.locator('#ovs-pv')).toHaveValue('cam1');
});

test('a stream\'s capture problem names the overlay and the page, in any slot', async ({ page, studio, wse }) => {
  twoOverlays(wse);
  const real = wse.streamStatus.bind(wse);
  wse.streamStatus = (s) => {
    const st = real(s);
    (st.overlays || []).forEach((x) => (x.objects || []).forEach((o) => { if (x.name === 'LowerThird' && o.capture) o.capture = { state: 'error', lastError: 'refused' }; }));
    return st;
  };
  await studio.open();
  await leftTab(page, 'streams').click();
  await page.locator('[data-list]').getByText('cam1', { exact: true }).click();
  await expect(page.locator('[data-screen=stream]')).toContainText('LowerThird name not capturing, so viewers see the last picture.');
});

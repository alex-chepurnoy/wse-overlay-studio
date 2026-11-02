// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// Smoke flows for the page as it is before the 2.0 page work: each screen opens against the fake bridge, the main
// writes send what they should, and the states the page has to show (no bridge, no capture service, read-only, an
// application without the module, a narrow window) show. harness.js fails any of them on a script error or an
// unexpected request.
'use strict';
const { test, expect } = require('./harness');

const MODULE = '/rest/v1/server/plugin/overlaystudio';

async function openTab(page, tab, item) {
  await page.locator('[data-tab=' + tab + ']').click();
  if (item) await page.locator('[data-list]').getByText(item, { exact: true }).first().click();
}

test('opens on On air for the application with the module', async ({ page, studio }) => {
  await studio.open();
  await expect(page.locator('[data-app]')).toHaveValue('live');
  await expect(page.locator('[data-screen=home]')).toBeVisible();
  const center = page.locator('[data-center]');
  await expect(center).toContainText('On air in live');
  await expect(center).toContainText('cam1');
  await expect(center).toContainText('Scoreboard');
  await expect(center).toContainText('3 viewers');
  await expect(page.locator('[data-strip]')).toContainText('Running, Chrome 156');
  await expect(page.locator('.banners [data-banner]')).toHaveCount(0);
  await expect(page.locator('[data-ro-chip]')).toBeHidden();
});

test.describe('without the bridge', () => {
  test.use({ wseOptions: { bridge: false } });
  test('says the bridge is not loaded and calls nothing else', async ({ page, studio, wse }) => {
    await page.goto('http://wse.test/enginemanager/overlay-studio');
    await expect(page.locator('.banners')).toContainText('The Overlay Studio bridge is not loaded in WSE Manager.');
    expect(wse.requests.filter((r) => r.path.startsWith('/rest'))).toEqual([]);
  });
});

test.describe('with the capture service stopped', () => {
  test.use({ wseOptions: { serviceRunning: false } });
  test('says every overlay is frozen', async ({ page, studio }) => {
    await studio.open();
    await expect(page.locator('.banners')).toContainText('The capture service is not running');
  });
});

test('an overlay edit is saved with the session token, and Discard drops one', async ({ page, studio, wse }) => {
  await studio.open();
  await openTab(page, 'overlays', 'Scoreboard');
  const url = page.locator('#ovs-f-url');
  await expect(url).toHaveValue('https://scores.example.com/bug');

  const bar = page.getByRole('region', { name: 'Unsaved changes' });
  // leaving the field first: clicking Save or Discard straight from the field is the known defect below
  await url.fill('https://scores.example.com/other');
  await url.press('Tab');
  await expect(bar).toContainText('score page url');
  await bar.getByRole('button', { name: 'Discard' }).click();
  await expect(bar).toBeHidden();
  await expect(url).toHaveValue('https://scores.example.com/bug');
  expect(wse.posts).toEqual([]);

  await url.fill('https://scores.example.com/bug2');
  await url.press('Tab');
  await expect(bar).toContainText('score page url');
  await bar.getByRole('button', { name: /^Save/ }).click();
  await expect(bar).toBeHidden();
  const saved = wse.posts.filter((p) => p.path.endsWith('/overlays/Scoreboard'));
  expect(saved).toHaveLength(1);
  expect(saved[0].body.objects[0].url).toBe('https://scores.example.com/bug2');
  expect(saved[0].body.objects[0].id).toBe('score');
  const sent = wse.requests.find((r) => r.method === 'POST' && r.path.startsWith(MODULE + '/vhosts/_defaultVHost_/applications/live/overlays/Scoreboard?'));
  expect(sent.path).toContain('wowzaSecurityToken=token-1');
});

// Known defect, found by this harness: leaving a field rebuilds the save bar, and the field is left on the mousedown
// of the click, so the button under the pointer is replaced before mouseup and the first click on Save or Discard
// does nothing. The second click works. Marked as failing until the page is fixed; Playwright reports it when it
// starts passing.
for (const button of ['Save', 'Discard']) {
  test('the first click on ' + button + ' straight from a field takes effect', async ({ page, studio, wse }) => {
    test.fail(true, 'known defect: the save bar is rebuilt on blur and eats the first click');
    await studio.open();
    await openTab(page, 'overlays', 'Scoreboard');
    const url = page.locator('#ovs-f-url');
    await url.click();
    await url.press('End');
    await page.keyboard.type('x');
    const bar = page.getByRole('region', { name: 'Unsaved changes' });
    await expect(bar).toContainText('score page url');
    await bar.getByRole('button', { name: button === 'Save' ? /^Save/ : 'Discard' }).click();
    await expect(bar).toBeHidden({ timeout: 2000 });
    if (button === 'Save') expect(wse.posts).toHaveLength(1);
    else await expect(url).toHaveValue('https://scores.example.com/bug');
  });
}

test('a save refused for an old token fetches a new one and goes through', async ({ page, studio, wse }) => {
  await studio.open();
  await openTab(page, 'overlays', 'Scoreboard');
  const url = page.locator('#ovs-f-url');
  const bar = page.getByRole('region', { name: 'Unsaved changes' });
  async function save(value) {
    await url.fill(value);
    await url.press('Tab');
    await expect(bar).toContainText('score page url');
    await bar.getByRole('button', { name: /^Save/ }).click();
    await expect(bar).toBeHidden();
  }
  await save('https://scores.example.com/bug3');
  // the Manager renewed its token with the session
  wse.token = 'token-2';
  await save('https://scores.example.com/bug4');
  const writes = wse.requests.filter((r) => r.method === 'POST' && r.path.includes('/overlays/Scoreboard?'));
  expect(writes.map((r) => /wowzaSecurityToken=([^&]+)/.exec(r.path)[1])).toEqual(['token-1', 'token-1', 'token-2']);
  expect(wse.posts.filter((p) => p.path.endsWith('/overlays/Scoreboard')).map((p) => p.body.objects[0].url))
    .toEqual(['https://scores.example.com/bug3', 'https://scores.example.com/bug4']);
});

test('a template opens with its outputs and its overlay', async ({ page, studio }) => {
  await studio.open();
  await openTab(page, 'templates', 'scoreboard-cpu');
  const screen = page.locator('[data-screen=template]');
  await expect(screen).toBeVisible();
  await expect(screen).toContainText('Master template');
  await expect(screen).toContainText('2 outputs');
  await expect(screen).toContainText('2 with Scoreboard');
});

test('a stream opens with its viewers and arrival', async ({ page, studio }) => {
  await studio.open();
  await openTab(page, 'streams', 'cam1');
  const screen = page.locator('[data-screen=stream]');
  await expect(screen).toBeVisible();
  await expect(screen).toContainText('3 viewers on this server');
  await expect(screen).toContainText('Pushed');
});

test.describe('read-only', () => {
  test.use({ wseOptions: { write: false } });
  test('shows the chip and offers no edits', async ({ page, studio, wse }) => {
    await studio.open();
    await expect(page.locator('[data-ro-chip]')).toBeVisible();
    await openTab(page, 'overlays', 'Scoreboard');
    await expect(page.locator('[data-insp]')).toContainText('https://scores.example.com/bug');
    await expect(page.locator('#ovs-f-url')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /^Save/ })).toHaveCount(0);
    expect(wse.requests.filter((r) => r.method !== 'GET')).toEqual([]);
  });
});

test('Settings reads and sets the pages captured at once', async ({ page, studio, wse }) => {
  await studio.open();
  await page.locator('[data-act=settings]').click();
  const lim = page.locator('[data-lim]');
  await expect(lim).toBeEnabled();
  await expect(lim).toHaveValue('4');
  await lim.fill('6');
  await page.locator('[data-limset]').click();
  await expect.poll(() => wse.posts.filter((p) => p.path === '/capture/limit').map((p) => p.body)).toEqual([{ maxCaptures: 6 }]);
});

test('Help opens and Escape closes it', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-act=help]').click();
  const dlg = page.getByRole('alertdialog', { name: 'How Overlay Studio works' });
  await expect(dlg).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dlg).toBeHidden();
});

test('an application without the module offers to add it', async ({ page, studio }) => {
  await studio.open();
  await page.locator('[data-app]').selectOption('live2');
  const screen = page.locator('[data-screen=nomodule]');
  await expect(screen).toBeVisible();
  await expect(screen).toContainText('live2');
});

test.describe('a phone-width window', () => {
  test.use({ viewport: { width: 390, height: 844 } });
  test('lists by a select instead of tabs', async ({ page, studio }) => {
    await studio.open();
    await expect(page.locator('[data-tab-select]')).toBeVisible();
    await expect(page.locator('.ltabs')).toBeHidden();
  });
});

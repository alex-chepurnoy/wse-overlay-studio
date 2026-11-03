// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// The ways in, as changed after trying the page in the Manager (2026-11-03): a stream opened from On air shows under
// Streams; a stream's template is changed from a button on its Template card, from its row and from Templates ("Use
// on a stream"); one Add layer menu with icons, and a banner on the canvas while placing; the capture speed only
// where a web page is.
'use strict';
const { test, expect } = require('./harness');

const leftTab = (page, name) => page.locator('[data-tab=' + name + ']');

test('a stream opened from On air shows under Streams', async ({ page, studio }) => {
  await studio.open();
  await expect(leftTab(page, 'onair')).toHaveAttribute('aria-selected', 'true');
  await page.locator('[data-center]').getByRole('button', { name: 'cam1', exact: true }).first().click();
  await expect(page.locator('[data-screen=stream]')).toBeVisible();
  await expect(leftTab(page, 'streams'), 'the list beside it is the Streams list').toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('[data-list] .li[aria-selected=true]')).toContainText('cam1');
});

test('a stream\'s template changes from a button on its Template card', async ({ page, studio }) => {
  await studio.open();
  await leftTab(page, 'streams').click();
  // each row says which template the stream runs
  await expect(page.locator('[data-list] .li').filter({ hasText: 'cam1' })).toContainText('scoreboard-cpu');
  await expect(page.locator('[data-list] .li').filter({ hasText: 'cam2' })).toContainText('no template');
  await page.locator('[data-list]').getByText('cam1', { exact: true }).click();
  await page.locator('.pn-tpl').getByRole('button', { name: 'Change template' }).click();
  await expect(page.locator('.dry-h')).toContainText('Give cam1 its own copy of');
  await expect(page.locator('.dry')).toContainText('stays linked to scoreboard-cpu');
  await expect(page.locator('.dry')).toContainText('cam1.xml');
});

test('Templates puts a master on a stream through the stream\'s dry run', async ({ page, studio }) => {
  await studio.open();
  await leftTab(page, 'templates').click();
  await page.locator('[data-rowfor="template:scoreboard-cpu"]').click();
  await page.getByRole('menuitem', { name: 'Use on a stream...' }).click();
  const dlg = page.getByRole('alertdialog', { name: 'Use scoreboard-cpu on a stream' });
  await dlg.locator('[data-usestream]').selectOption('cam2');
  await dlg.getByRole('button', { name: 'Continue' }).click();
  await expect(page.locator('[data-screen=stream]')).toBeVisible();
  await expect(leftTab(page, 'streams')).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('.dry-h')).toContainText('Give cam2 its own copy of');
  await expect(page.locator('[data-dtpl]')).toHaveValue('scoreboard-cpu');
  // nothing is written until it is saved
  await expect(page.getByRole('region', { name: 'Unsaved changes' })).toContainText('Use scoreboard-cpu on cam2');
});

test('one Add layer menu shows each kind with its icon, and placing shows a banner on the canvas', async ({ page, studio }) => {
  await studio.open();
  await leftTab(page, 'overlays').click();
  await page.locator('[data-list]').getByText('Scoreboard', { exact: true }).first().click();
  await expect(page.locator('[data-addbar]'), 'no second set of Add buttons over the canvas').toHaveCount(0);
  await page.locator('[data-rowfor="add:list"]').click();
  const items = page.getByRole('menuitem');
  // the twelve kinds, then From a preset...
  await expect(items).toHaveCount(13);
  expect(await items.evaluateAll((xs) => xs.every((x) => x.querySelector('svg')))).toBe(true);
  await page.getByRole('menuitem', { name: 'Ellipse', exact: true }).click();
  const banner = page.locator('[data-frame] [data-placehint]');
  await expect(banner).toBeVisible();
  await expect(banner).toContainText('Click to place the ellipse');
  await banner.getByRole('button', { name: 'Cancel' }).click();
  await expect(banner).toHaveCount(0);
  await expect(page.locator('.pg[data-id="shape-1"]'), 'Cancel placed nothing').toHaveCount(0);
});

test('the capture speed shows only with the web page, not under the overlay\'s other layers', async ({ page, studio }) => {
  await studio.open();
  await leftTab(page, 'overlays').click();
  await page.locator('[data-list]').getByText('Scoreboard', { exact: true }).first().click();
  await expect(page.locator('#ovs-f-refreshRate'), 'the page is selected').toHaveCount(1);
  await page.locator('[data-rowfor="add:list"]').click();
  await page.getByRole('menuitem', { name: 'Text', exact: true }).click();
  await page.keyboard.press('Enter');
  await expect(page.locator('[data-layers] .prow[aria-selected=true]')).toContainText('Text');
  await expect(page.locator('#ovs-f-refreshRate'), 'a text has nothing to capture').toHaveCount(0);
  await page.locator('[data-layers] .prow[data-id="score"]').click();
  await expect(page.locator('#ovs-f-refreshRate')).toHaveCount(1);
});

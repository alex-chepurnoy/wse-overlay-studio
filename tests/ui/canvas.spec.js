// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// The canvas draws every layer kind (plan 4.4, PR 7a): text, shapes and images as SVG in the bundled fonts, web pages
// from their captures; the layer list names layers by what they show; lines and triangles move by their points.
'use strict';
const { test, expect } = require('./harness');

async function openMixed(page, studio) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText('Mixed', { exact: true }).first().click();
  await expect(page.locator('.pg[data-id="score"] .lyr-svg')).toBeVisible();
}

function frame(page) { return page.locator('[data-frame]'); }

test('every kind draws as its own layer, with a name a screen reader can say', async ({ page, studio }) => {
  await openMixed(page, studio);
  const names = {
    score: 'Text: HOME 2 - 1', clock: 'Date and time: {time:HH:mm:ss}', bar: 'Rounded rectangle: Rounded rectangle', ring: 'Ellipse: Ellipse',
    arrow: 'Arrow: Arrow', tri: 'Triangle: Triangle', logo: 'Image: logo.png'
  };
  // the canvas is one Tab stop: each layer's box is an image it can make its active descendant (plan 4.1)
  for (const [id, label] of Object.entries(names)) {
    const box = page.locator('.pg[data-id="' + id + '"]');
    await expect(box, id).toHaveAttribute('role', 'img');
    expect((await box.getAttribute('aria-label')).startsWith(label + '. Id ' + id), id).toBe(true);
  }
  await expect(page.locator('.pg svg[role=img]'), 'the drawing leaves its name to the box').toHaveCount(0);
  await expect(page.locator('.pg[data-id="score"] text')).toHaveText('HOME 2 - 1');
  await expect(page.locator('.pg[data-id="clock"] text'), 'a token shows as written until samples come (PR 8)').toHaveText('{time:HH:mm:ss}');
  await expect(page.locator('.pg[data-id="bar"] rect').first()).toHaveAttribute('rx', '12');
  await expect(page.locator('.pg[data-id="ring"] ellipse').last()).toHaveAttribute('stroke-dasharray', '0.01 10');
  await expect(page.locator('.pg[data-id="arrow"] polygon'), 'a head at each end').toHaveCount(2);
  await expect(page.locator('.pg[data-id="logo"] image')).toHaveAttribute('href', /^data:image\/png;base64,/);
});

test('layers stack by zOrder, a page among them', async ({ page, studio }) => {
  await openMixed(page, studio);
  const order = await frame(page).locator('.pg').evaluateAll((els) => els.map((e) => e.getAttribute('data-id')));
  expect(order).toEqual(['tri', 'arrow', 'ring', 'bar', 'clock', 'score', 'logo']);
});

test('the bundled fonts load, and text is measured in them', async ({ page, studio }) => {
  await openMixed(page, studio);
  await page.evaluate(() => document.fonts.ready);
  expect(await page.evaluate(() => document.fonts.check('700 48px "Fira Sans"'))).toBe(true);
  expect(await page.evaluate(() => document.fonts.check('400 40px "Fira Mono"'))).toBe(true);
  // Fira Mono is monospaced: every character of the clock is as wide as every other
  const ratio = await page.evaluate(() => {
    const c = document.createElement('canvas').getContext('2d');
    c.font = '400 40px "Fira Mono"';
    return c.measureText('1111').width / c.measureText('WWWW').width;
  });
  expect(ratio).toBeCloseTo(1, 3);
});

test('an image with no size is boxed at its picture size', async ({ page, studio }) => {
  await openMixed(page, studio);
  // the canvas redraws once the picture arrives (and again as fonts arrive), so the box is measured in one step;
  // the fake's picture is 200 x 100 on a 1920 x 1080 frame
  await expect.poll(() => page.evaluate(() => {
    const b = document.querySelector('.pg[data-id="logo"]'), f = document.querySelector('[data-frame]');
    if (!b || !b.querySelector('image')) return null;
    const r = b.getBoundingClientRect(), fr = f.getBoundingClientRect();
    return [Math.round(1920 * r.width / fr.width), Math.round(1080 * r.height / fr.height)];
  })).toEqual([200, 100]);
});

test('the list names layers by what they show', async ({ page, studio }) => {
  await openMixed(page, studio);
  const rows = page.locator('[data-layers] .prow');
  await expect(rows.locator('.lname')).toHaveText(['logo.png', 'HOME 2 - 1', '{time:HH:mm:ss}', 'Rounded rectangle', 'Ellipse', 'Arrow', 'Triangle']);
  await expect(rows.filter({ hasText: 'HOME 2 - 1' }).locator('.sub')).toHaveText('Text, score');
  await expect(rows.filter({ hasText: 'HOME 2 - 1' })).toHaveAttribute('title', /the top layer draws on top/);
});

test('only boxed layers get corner grips; a line moves by both ends', async ({ page, studio, wse }) => {
  await openMixed(page, studio);
  await page.locator('[data-layers] .prow').filter({ hasText: 'Rounded rectangle' }).click();
  const corners = (id) => page.locator('.pg[data-id="' + id + '"] .h:not(.rot):not(.pt)');
  await expect(corners('bar')).toHaveCount(4);
  await page.locator('[data-layers] .prow').filter({ hasText: 'HOME 2 - 1' }).click();
  await expect(corners('score'), 'free text sizes by its font').toHaveCount(0);

  const arrow = page.locator('.pg[data-id="arrow"]'), b = await arrow.boundingBox(), f = await frame(page).boundingBox();
  const px = f.width / 1920;
  // Ctrl: exactly where it is dragged, not to a guide (PR 7c)
  await page.keyboard.down('Control');
  await page.mouse.move(b.x + b.width / 2, b.y + b.height / 2);
  await page.mouse.down();
  await page.mouse.move(b.x + b.width / 2 - 100 * px, b.y + b.height / 2 + 50 * px, { steps: 5 });
  await page.mouse.up();
  await page.keyboard.up('Control');
  const bar = page.getByRole('region', { name: 'Unsaved changes' });
  await bar.getByRole('button', { name: /^Save/ }).click();
  await expect(bar).toBeHidden();
  const saved = wse.posts.filter((p) => p.path.endsWith('/overlays/Mixed')).pop().body.objects.find((o) => o.id === 'arrow');
  expect(saved.x - 1000).toBeCloseTo(-100, -1);
  expect(saved.x2 - 1300).toBe(saved.x - 1000);
  expect(saved.y2 - 420).toBe(saved.y - 500);
  expect(saved.w, 'a line is not given a box').toBeUndefined();
});

test('nothing selected reads as a layer', async ({ page, studio }) => {
  await openMixed(page, studio);
  await page.keyboard.press('Escape');
  await expect(page.locator('[data-insp]')).toContainText('No layer selected');
});

test('the Place tab shows an image layer at its picture size once it arrives', async ({ page, studio }) => {
  await openMixed(page, studio);
  await page.locator('[data-layers] .prow').filter({ hasText: 'logo.png' }).click();
  await page.getByRole('tab', { name: 'Place' }).click();
  await expect(page.locator('#ovs-f-w')).toHaveValue('200');
  await expect(page.locator('#ovs-f-h')).toHaveValue('100');
});

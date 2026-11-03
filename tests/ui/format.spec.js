// This code and all components (c) Copyright 2006 - 2026, Wowza Media Systems, LLC.  All rights reserved.
// This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
//
// Date and time and the live canvas (plan 3.3, 3.7, 4.2, 4.4, PR 8b): the Format tab of a text with a token, the
// server's samples on the canvas and in the list, ticking; a countdown with no target; timing played on the canvas,
// Freeze; live values shown with their badge and cleared.
'use strict';
const { test, expect } = require('./harness');

async function openOverlay(page, studio, name) {
  await studio.open();
  await page.locator('[data-tab=overlays]').click();
  await page.locator('[data-list]').getByText(name, { exact: true }).first().click();
  await expect(page.locator('[data-frame] .pg').first()).toBeVisible();
}
const bar = (page) => page.getByRole('region', { name: 'Unsaved changes' });
const row = (page, text) => page.locator('[data-layers] .prow').filter({ hasText: text });
const tabs = (page) => page.locator('[data-insp] [role=tab]');
const field = (page, name) => page.locator('#ovs-f-' + name);
const clockText = (page) => page.locator('.pg[data-id="clock"] text');
async function saved(page, wse, name) {
  await bar(page).getByRole('button', { name: /^Save/ }).click();
  await expect(bar(page)).toBeHidden();
  return wse.posts.filter((p) => p.path.endsWith('/overlays/' + name)).pop().body.objects;
}
const obj = (objs, id) => objs.find((o) => o.id === id);
const CLOCK = /HH:mm:ss #\d+/;

test('a text with a token opens on its Format tab; a format rewrites the token, and its sample ticks', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, CLOCK).click();
  await expect(tabs(page)).toHaveText(['Format', 'Text', 'Place', 'Timing']);
  await expect(page.locator('[data-itab=format]')).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#ovs-fkind')).toHaveValue('time');
  await expect(page.locator('[data-fpreset="HH:mm:ss"]')).toHaveAttribute('aria-pressed', 'true');
  const preset = page.locator('[data-fpreset="h:mm:ss a"]');
  await expect(preset.locator('[data-fsample]'), 'each format shows its sample from the server').toHaveText(/^h:mm:ss a #\d+$/);
  await preset.click();
  await expect(clockText(page)).toHaveText(/^h:mm:ss a #\d+$/);
  await expect(page.locator('[data-fpreset="h:mm:ss a"]')).toHaveAttribute('aria-pressed', 'true');
  await expect(row(page, /h:mm:ss a #/), 'the list shows the sample too').toHaveCount(1);
  const first = await clockText(page).textContent();
  await expect.poll(() => clockText(page).textContent(), { timeout: 4000, message: 'the canvas ticks once a second' }).not.toBe(first);
  expect(obj(await saved(page, wse, 'Mixed'), 'clock').text).toBe('{time:h:mm:ss a}');
  const asked = wse.formats.filter((f) => f.pattern === 'h:mm:ss a');
  expect(asked.length).toBeGreaterThan(0);
  expect(asked[0].streamStart, 'stream time counts from when the overlay opened').toBeGreaterThan(0);
  expect(asked[0].target, 'a date and time has no target').toBeNull();
});

test('the pattern is checked by the server as it is typed; a stray brace never reaches the text', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, CLOCK).click();
  const pat = page.locator('[data-fpattern]'), err = page.locator('[data-ferr]');
  await pat.fill('HH:mm bad');
  await expect(err).toHaveText('Unknown pattern letter: b');
  await expect(pat).toHaveClass(/invalid/);
  await pat.fill("HH 'o}clock'");
  await expect(err).toHaveText('');
  await expect(clockText(page)).toHaveText(/^HH 'o}clock' #\d+$/);
  await pat.fill('HH}');
  await expect(err).toContainText('closes nothing');
  expect(obj(await saved(page, wse, 'Mixed'), 'clock').text, 'the last pattern that parsed').toBe("{time:HH 'o}clock'}");
});

test('a countdown with no target says so, draws faded from a sample, and takes a target with its offset', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, CLOCK).click();
  await page.locator('#ovs-fkind').selectOption('countdown');
  await expect(page.locator('[data-fpattern]'), 'a duration starts from its first format').toHaveValue('H:mm:ss');
  await expect(clockText(page)).toHaveText(/^H:mm:ss #\d+$/);
  await expect(row(page, /H:mm:ss #/).locator('[data-notarget]')).toHaveText('No target set');
  const name = await row(page, /H:mm:ss #/).locator('.lname').boundingBox();
  expect(name.width, 'the chips wrap rather than squeeze the name away').toBeGreaterThan(40);
  await expect(page.locator('[data-notargetnote]')).toBeVisible();
  await expect(page.locator('.pg[data-id="clock"] [data-ttag]')).toHaveText('No target set');
  await expect(page.locator('.pg[data-id="clock"] .lyr-svg')).toHaveCSS('opacity', '0.5');
  const nominal = wse.formats.filter((f) => f.kind === 'countdown').pop();
  expect(Date.parse(nominal.target) - nominal.streamStart, 'sampled against an hour after the editor opened').toBe(3600000);
  await expect(field(page, 'endText')).toBeVisible();

  await field(page, 'target').fill('tomorrow');
  await expect(page.locator('[data-err-for=target]')).toContainText('with its offset');
  await field(page, 'target').fill('2027-01-01T18:00:00-05:00');
  await expect(page.locator('[data-err-for=target]')).toHaveText('');
  await expect(page.locator('[data-notargetnote]')).toBeHidden();
  await expect(page.locator('[data-layers] [data-notarget]')).toHaveCount(0);
  await expect(page.locator('.pg[data-id="clock"] [data-ttag]')).toHaveCount(0);
  await field(page, 'endText').fill('Live now');
  await expect.poll(() => wse.formats.some((f) => f.target === '2027-01-01T18:00:00-05:00')).toBe(true);
  const o = obj(await saved(page, wse, 'Mixed'), 'clock');
  expect(o).toMatchObject({ text: '{countdown:H:mm:ss}', target: '2027-01-01T18:00:00-05:00', endText: 'Live now' });
});

test('the zone and language are checked, and the samples follow them', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, CLOCK).click();
  await expect(field(page, 'timeZone'), 'the server\'s zone, named').toHaveAttribute('placeholder', 'America/New_York');
  await field(page, 'timeZone').fill('Mars/Base');
  await expect(page.locator('[data-err-for=timeZone]')).toContainText('America/New_York');
  await field(page, 'timeZone').fill('Europe/Paris');
  await expect(page.locator('[data-err-for=timeZone]')).toHaveText('');
  await field(page, 'locale').fill('fr_FR');
  await expect(page.locator('[data-err-for=locale]')).toContainText('language tag');
  await field(page, 'locale').fill('fr-FR');
  await expect.poll(() => wse.formats.some((f) => f.zone === 'Europe/Paris' && f.locale === 'fr-FR')).toBe(true);
  expect(obj(await saved(page, wse, 'Mixed'), 'clock')).toMatchObject({ timeZone: 'Europe/Paris', locale: 'fr-FR' });
});

test('timing plays on the canvas: outside its show window a layer is faint and says when it shows', async ({ page, studio, wse }) => {
  await openOverlay(page, studio, 'Mixed');
  await row(page, 'Ellipse').click();
  await page.getByRole('tab', { name: 'Timing' }).click();
  const tag = page.locator('.pg[data-id="ring"] [data-ttag]'), drawing = page.locator('.pg[data-id="ring"] .lyr-svg');
  await field(page, 'showFrom').fill('stream+3600');
  await expect(tag).toHaveText('Hidden now, shows at stream+3600 (stream time is simulated here)');
  await expect(drawing).toHaveCSS('opacity', '0.25');
  await field(page, 'showFrom').fill('');
  await expect(tag).toHaveCount(0);
  await field(page, 'hideAt').fill('2020-01-01T00:00:00-05:00');
  await expect(tag).toHaveText(/^Hidden since Jan 1, 2020/);
  await field(page, 'hideAt').fill('');
  // a blink goes faint while the layer is selected, rather than away; checked every 100 ms, so a check never keeps
  // landing on the same half of a one-second blink
  await field(page, 'blinkSeconds').fill('1');
  await expect.poll(() => drawing.evaluate((e) => e.style.opacity), { timeout: 3000, intervals: [100] }).toBe('0.25');
  await expect.poll(() => drawing.evaluate((e) => e.style.opacity), { timeout: 3000, intervals: [100] }).toBe('');
  const o = obj(await saved(page, wse, 'Mixed'), 'ring');
  expect(o.blinkSeconds).toBe(1);
  expect([o.showFrom, o.hideAt], 'a cleared time is not set, not the text null').toEqual([undefined, undefined]);
});

test('Freeze stops the canvas clock and starts it again', async ({ page, studio }) => {
  await openOverlay(page, studio, 'Mixed');
  await expect(clockText(page)).toHaveText(CLOCK);
  const freeze = page.locator('[data-freeze]');
  await expect(freeze).toHaveAttribute('aria-pressed', 'false');
  await freeze.click();
  await expect(freeze).toHaveAttribute('aria-pressed', 'true');
  await expect(freeze).toHaveText('Frozen, resume time');
  await expect(freeze, 'the button keeps the focus').toBeFocused();
  const held = await clockText(page).textContent();
  await page.waitForTimeout(1600);
  await expect(clockText(page)).toHaveText(held);
  await freeze.click();
  await expect.poll(() => clockText(page).textContent(), { timeout: 4000 }).not.toBe(held);
});

test('a live value shows on the canvas with its badge, the saved values while selected, and Clear takes it off', async ({ page, studio, wse }) => {
  wse.live.Mixed = { score: { text: 'AWAY 3 - 1', color: 'red' } };
  await openOverlay(page, studio, 'Mixed');
  const text = page.locator('.pg[data-id="score"] text').last();
  await expect(text, 'the canvas shows what is on air').toHaveText('AWAY 3 - 1');
  const r = row(page, 'HOME 2 - 1');
  await expect(r.locator('[data-livebadge]')).toHaveText('Live: AWAY 3 - 1');
  await r.click();
  await expect(text, 'selected for editing, it shows its saved values').toHaveText('HOME 2 - 1');
  await expect(page.locator('[data-livenote]')).toContainText('Live text and color are on air instead of these.');
  await page.locator('[data-livenote]').getByRole('button', { name: 'Clear' }).click();
  await expect(page.locator('[data-livenote]')).toHaveCount(0);
  await expect(r.locator('[data-livebadge]')).toHaveCount(0);
  expect(wse.posts.filter((p) => p.path.endsWith('/overlays/Mixed/live')).pop().body).toEqual({ clear: 'score' });
});

// Copyright (c) 2026 Caleb L. Power
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

const { test, expect } = require('@playwright/test');

/*
 * T5 — the browser tier.
 *
 * The question only this tier answers: does a person, in a real browser, on a
 * real forum, get told the right thing?
 *
 * Every layer beneath is covered by checks that need no browser, and all of
 * them would still pass if the refusal never reached the page, or reached it as
 * a 500, or reached it worded as though the person had done something wrong.
 * That last one is why the WORDING is asserted here and not just the outcome.
 *
 * Nothing in this file changes the world. The policy rule and whether core is
 * answering are set by stack.sh, which runs this file once per world with a
 * --grep tag. These specs run inside the browser image; core runs in another
 * container and its CLI needs a JVM this image does not have, so a spec that
 * tried to arrange its own world could not work from where it runs.
 *
 * The tags are that contract. A mis-tagged spec runs against the wrong world
 * and must fail loudly rather than pass -- which is why the outage spec asserts
 * something that cannot hold while core is answering.
 */

/** Unique per run, and DERIVED rather than random, so a failure is reproducible. */
function uniqueName(prefix) {
  uniqueName.n = (uniqueName.n || 0) + 1;
  return `${prefix}${process.env.RUN_TAG || 'r'}${uniqueName.n}`;
}

async function register(page, name) {
  await page.goto('/');
  await page.getByRole('button', { name: /sign up/i }).click();
  const dialog = page.locator('.Modal');
  await dialog.getByLabel(/username/i).fill(name);
  await dialog.getByLabel(/email/i).fill(`${name}@example.com`);
  await dialog.getByLabel(/password/i).fill('a-long-enough-password');
  await dialog.getByRole('button', { name: /sign up/i }).click();
  return dialog;
}

/** No stack trace, no whoops page. A refusal is this extension working. */
async function expectNoServerError(page) {
  await expect(page.locator('body')).not.toContainText(
    /Fatal error|Stack trace|Whoops, looks like something went wrong/i
  );
}

test('@refused an unlinked account is refused, in core\'s own words', async ({ page }) => {
  const dialog = await register(page, uniqueName('unlinked'));

  // The refusal must reach the PERSON. A gate that denies correctly and renders
  // a blank modal has failed at the only job this tier tests.
  // CORE's own words, on the page.
  //
  // Flarum renders a response detail only for status 422 and a fixed sentence
  // otherwise, so the forum bundle puts the reason back. Asserting core's
  // wording rather than the connector's fallback translation is the point:
  // core knows which kinds are missing, and this connector does not.
  await expect(dialog).toContainText(/not linked to any other/i);

  // And emphatically NOT the outage wording. These are different types on
  // purpose: telling somebody the system is broken when they simply have not
  // linked an account sends them to wait instead of to act.
  await expect(dialog).not.toContainText(/problem on our side/i);
  await expectNoServerError(page);
});

test('@admitted the account is admitted once the rule allows it', async ({ page }) => {
  const name = uniqueName('allowed');
  await register(page, name);

  // Signed in, which is the observable form of "registration completed". The
  // pair with @refused is what stops a gate that denies everything from passing
  // the suite.
  await expect(page.locator('#header-secondary')).toContainText(name, { timeout: 30_000 });
});

test('@outage a dead core denies, and blames the system rather than the person',
  async ({ page }) => {
    const dialog = await register(page, uniqueName('outage'));

    // Fail CLOSED: the gate holds when the answer is unavailable.
    await expect(dialog).not.toContainText(/welcome/i);

    // The wording is the assertion this whole tier exists for. Somebody refused
    // because a server they have never heard of is unreachable must not be told
    // they are not allowed -- and every layer below this one would be green
    // either way.
    await expect(dialog).toContainText(/problem on our side/i);
    await expect(dialog).toContainText(/try again/i);
    await expect(dialog).not.toContainText(/you are not allowed|not permitted|denied/i);

    // And NOT the refusal wording. A person refused because a server they have
    // never heard of is unreachable must not be told to go and link an account
    // -- they would do it, and still be refused.
    await expect(dialog).not.toContainText(/needs a linked account/i);

    await expectNoServerError(page);
  });

test('@recovery the next attempt simply works, with no intervention', async ({ page }) => {
  // Looks like @admitted and is not: this runs immediately AFTER an outage, and
  // asserts the outage left nothing behind -- no poisoned cache entry, no wedged
  // state, no admin action. An outage that needs somebody to clean up after it
  // is an outage that becomes an incident, and @admitted cannot see that because
  // it never had an outage to recover from.
  const name = uniqueName('after');
  await register(page, name);
  await expect(page.locator('#header-secondary')).toContainText(name, { timeout: 30_000 });
});

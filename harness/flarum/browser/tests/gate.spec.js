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

/**
 * Fills a field and makes sure it stayed filled.
 *
 * Flarum's forms are Mithril, which re-renders on input. A fill can land on an
 * element that is replaced a moment later, and the value goes with it -- the
 * symptom is a field that reads empty, or worse, holds the value meant for the
 * field after it. Both happened here: a password that would not stick, and a
 * username that ended up holding an email address.
 *
 * Retrying the FILL is the right response, and it is not a weakened assertion:
 * the value must still be there afterwards, and the test still fails if it never
 * sticks. What is being tolerated is a re-render, which is a real property of
 * the UI rather than a defect this suite is meant to catch.
 */
async function fillStable(dialog, field, value) {
  const input = dialog.locator(`input[name="${field}"]`);
  await input.waitFor({ state: 'visible' });

  for (let attempt = 1; attempt <= 4; attempt++) {
    await input.fill(value);
    try {
      await expect(input).toHaveValue(value, { timeout: 2_000 });
      return;
    } catch (e) {
      if (attempt === 4) {
        throw new Error(
          `filling ${field} did not stick after ${attempt} attempts. The field exists and ` +
            `accepts input, so the form is re-rendering faster than it can be filled, or ` +
            `this is not the form these tests expect.`
        );
      }
    }
  }
}

async function register(page, name) {
  await page.goto('/');
  await page.getByRole('button', { name: /sign up/i }).click();
  const dialog = page.locator('.Modal');

  // input[name=...] because Flarum's sign-up inputs carry name attributes and no
  // <label>; a label lookup resolved username and email to the same input.
  const expected = {
    username: name,
    email: `${name}@example.com`,
    password: 'a-long-enough-password',
  };

  for (const [field, value] of Object.entries(expected)) {
    await fillStable(dialog, field, value);
  }

  // All three together, immediately before submitting. Each was verified when
  // written, but a later re-render can still undo an earlier field -- and
  // submitting a half-filled form produces a validation error that reads as a
  // problem with the forum.
  //
  // This REPAIRS drift rather than only reporting it. The previous version
  // asserted the three values once, with no refill, and its own comment
  // anticipated the exact failure it could not then survive: a re-render
  // crossing a field boundary left the password sitting in the username box and
  // reddened a whole reaper run.
  //
  // `retries: 0` in playwright.config.js is deliberate and stays. That policy
  // exists so a *gate* cannot pass on the second attempt; this is the
  // registration fixture, not the thing under test.
  //
  // Not a weakened assertion: every field must still hold its own value before
  // anything is submitted, and this still fails if a value will not stick. What
  // is tolerated is a re-render -- a property of Mithril, not a defect this
  // suite exists to catch. Same reasoning as fillStable above.
  for (let sweep = 1; sweep <= 3; sweep++) {
    const wrong = [];
    for (const [field, value] of Object.entries(expected)) {
      if ((await dialog.locator(`input[name="${field}"]`).inputValue()) !== value) {
        wrong.push(field);
      }
    }
    if (wrong.length === 0) {
      break;
    }
    if (sweep === 3) {
      throw new Error(
        `fields ${wrong.join(', ')} would not hold their values across ${sweep} sweeps. ` +
          `The form is re-rendering faster than it can be filled, or this is not the form ` +
          `these tests expect.`
      );
    }
    for (const field of wrong) {
      await fillStable(dialog, field, expected[field]);
    }
  }

  // The final word, after any repair: nothing is submitted half-filled.
  for (const [field, value] of Object.entries(expected)) {
    await expect(dialog.locator(`input[name="${field}"]`)).toHaveValue(value);
  }

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
  const dialog = await register(page, name);

  // NOT "the person is signed in".
  //
  // Flarum does not sign somebody in after registration by default -- it asks
  // them to confirm their email first. Asserting a signed-in header tests that
  // forum setting, not this gate, and it failed for exactly that reason.
  //
  // What this tier can honestly claim is that nothing REFUSED the registration.
  // That the account was actually created is asserted by the orchestrator
  // against the database, where the answer is unambiguous.
  await expect(dialog).not.toContainText(/not linked|needs a linked account/i);
  await expect(dialog).not.toContainText(/permission|problem on our side/i);
  await expectNoServerError(page);
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
  const dialog = await register(page, name);

  // Same reasoning as @admitted: "not refused" is the claim a browser can make
  // honestly. That the account exists is checked against the database by the
  // orchestrator.
  await expect(dialog).not.toContainText(/problem on our side/i);
  await expect(dialog).not.toContainText(/not linked|needs a linked account/i);
  await expectNoServerError(page);
});

/*
 * The gate item nothing else covers: a forum account LINKS, by typing a code
 * issued on another platform into the settings panel.
 *
 * Everything above proves the gates decide correctly. None of it touches the
 * half of the extension a member actually uses, and a connector that gates
 * perfectly and cannot link is a connector that only ever says no.
 *
 * The code is minted by the orchestrator for a GAME identity, so this is a real
 * cross-platform link and not a forum talking to itself.
 */
test('@link a member links this account by entering a code from another platform',
  async ({ page }) => {
    const code = process.env.LINK_CODE;
    if (!code) {
      throw new Error(
        'LINK_CODE was not set. The orchestrator mints it against a real core; ' +
          'without it this test would silently prove nothing.'
      );
    }

    // The admin account, because it is the one this install confirmed. A member
    // registered by an earlier pass cannot log in until they confirm an email
    // nobody will read.
    await page.goto('/');
    await page.getByRole('button', { name: /log in/i }).click();
    const login = page.locator('.Modal');
    await fillStable(login, 'identification', 'admin');
    await fillStable(login, 'password', 'harness-admin-password');
    await login.getByRole('button', { name: /log in/i }).click();

    // Logged in BEFORE going anywhere. /settings redirects away for a guest, so
    // a failed login and a missing panel look identical -- and the panel is the
    // thing under test, while the login is scaffolding.
    await expect(page.locator('#header-secondary'), 'the admin login did not take')
      .toContainText(/admin/i, { timeout: 30_000 });

    await page.goto('/settings');

    // The settings page itself first. If this is missing the navigation failed,
    // which is a different fault from the panel not rendering on it.
    await expect(page.locator('.SettingsPage'), 'the settings page did not load')
      .toBeVisible({ timeout: 30_000 });

    const panel = page.locator('.SoulbindPanel');
    await expect(panel, 'the settings page loaded but this extension added nothing to it')
      .toBeVisible({ timeout: 30_000 });

    // Not linked yet, and the panel must say so rather than showing the
    // unavailable state -- which would mean it never reached core.
    await expect(panel).toContainText(/not linked to anything yet/i);
    await expect(panel).not.toContainText(/problem on our side/i);

    await fillStable(panel, 'soulbind-code', code);
    await panel.getByRole('button', { name: /^link$/i }).click();

    // The panel re-reads its status from core after a successful redemption,
    // so this is core answering rather than the page congratulating itself.
    await expect(panel).toContainText(/linked to 1 other/i, { timeout: 30_000 });
    await expectNoServerError(page);
  });

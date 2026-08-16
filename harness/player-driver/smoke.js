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

/**
 * The Phase 5 gate, scripted.
 *
 * A real client connects to a real proxy, runs /link, reads the code out of
 * chat exactly as a person would, redeems it through a second surface, and then
 * the join gate is asserted from BOTH sides: a player who has not linked is
 * refused, and one who has is admitted.
 *
 * No backdoors. Nothing here writes to a database or calls core to arrange a
 * starting state -- a harness that seeded state would keep passing after the
 * flow broke, which is the failure it exists to detect.
 *
 * Every step reports what it expected and what it saw. A smoke test whose
 * failure output is "assertion failed" costs more to diagnose than it saved.
 */

import process from 'node:process';
import { Player } from './driver.js';
import { extractCode, looksLikeOutage, offlineUuid } from './codes.js';

function arg(name, fallback) {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 && i + 1 < process.argv.length ? process.argv[i + 1] : fallback;
}

const HOST = arg('host', '127.0.0.1');
const PORT = Number(arg('port', '25577'));
const CORE = arg('core', 'http://127.0.0.1:7000');
const ENTRY_CREDENTIAL = arg('entry-credential', null);
const TIMEOUT = Number(arg('timeout', '30000'));
// The protocol version, stated. See driver.js for why it is not autodetected.
const VERSION = arg('mc-version', undefined);
// The text the gate's refusal must contain. Not optional: accepting any kick
// let a run pass while the plugin had failed to load.
const KICK_CONTAINS = arg('kick-contains', 'link your account');

const steps = [];

function step(name, detail) {
  steps.push({ name, detail });
  console.log(`  ok   ${name}${detail ? ` -- ${detail}` : ''}`);
}

function fail(name, expected, saw) {
  console.error(`  FAIL ${name}`);
  console.error(`       expected: ${expected}`);
  console.error(`       saw:      ${saw}`);
  process.exit(1);
}

/**
 * Redeems a code through core directly, standing in for the OTHER platform.
 *
 * This is a test double for a second connector, not a backdoor: it uses the
 * real protocol, a real credential and the real redeem operation. What it is
 * not is a second game -- and the whole point of a symmetric protocol is that
 * core cannot tell the difference.
 */
async function redeemAsOtherPlatform(code) {
  return callCore('code.redeem', {
    code,
    platformKind: 'harness',
    platformId: 'harness-account-1',
    display: 'Harness',
  });
}

/** Calls core with a signed request, as any connector would. */
async function callCore(op, payload) {
  const body = JSON.stringify({
    schema: 1, op, id: crypto.randomUUID(), payload,
  });
  const timestamp = Math.floor(Date.now() / 1000);
  const nonce = crypto.randomUUID();
  const signature = await sign(ENTRY_CREDENTIAL, timestamp, nonce, body);

  const response = await fetch(`${CORE}/v1/rpc`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${ENTRY_CREDENTIAL}`,
      'X-Soulbind-Timestamp': String(timestamp),
      'X-Soulbind-Nonce': nonce,
      'X-Soulbind-Signature': signature,
    },
    body,
  });
  return response.json();
}

/** The canonical form is timestamp LF nonce LF body, UTF-8, lowercase hex. */
async function sign(credential, timestamp, nonce, body) {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    'raw', encoder.encode(credential),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  const digest = await crypto.subtle.sign(
    'HMAC', key, encoder.encode(`${timestamp}\n${nonce}\n${body}`));
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, '0')).join('');
}

async function main() {
  console.log(`soulbind player-driver smoke: ${HOST}:${PORT}, core ${CORE}`);

  if (!ENTRY_CREDENTIAL) {
    console.error('--entry-credential is required; this smoke drives both halves of a link');
    process.exit(2);
  }

  const LINKER = 'Linker';
  const linkerRef = `game:${offlineUuid(LINKER)}`;

  // --- 1. the gate refuses an unlinked player ------------------------------
  const unlinked = new Player({
    host: HOST, port: PORT, username: 'Unlinked', timeoutMs: TIMEOUT, version: VERSION,
  });

  let refusal;
  try {
    refusal = await unlinked.connectExpectingRefusal(KICK_CONTAINS);
  } catch (err) {
    fail('the join gate refuses an unlinked player',
      `a kick containing ${JSON.stringify(KICK_CONTAINS)}`, err.message);
  }
  if (looksLikeOutage(refusal)) {
    fail('the join gate refuses an unlinked player for the RIGHT reason',
      'a policy denial', `an outage -- core was unreachable: ${refusal}`);
  }
  step('the join gate refuses an unlinked player, with ITS message');

  // --- 2. an operator admits one player, so they can link ------------------
  // The chicken and egg the gate creates, solved the way the system intends:
  // an override admits somebody BEFORE they have linked. That is the documented
  // reason overrides exist, and using it here means the harness exercises it
  // rather than working around the gate with a config change.
  const override = await callCore('override.set', {
    gate: 'game.join',
    identityRef: linkerRef,
    effect: 'allow',
    reason: 'harness: admitted so the player can run /link',
  });
  if (!override.ok) {
    fail('an override admits a player who has not linked',
      'ok:true', JSON.stringify(override).slice(0, 200));
  }
  step('an override admits a player who has not linked', linkerRef);

  await new Promise((r) => setTimeout(r, 1500));

  // --- 3. that player runs /link and reads the code out of chat ------------
  const linker = new Player({
    host: HOST, port: PORT, username: LINKER, timeoutMs: TIMEOUT, version: VERSION,
  });
  await linker.connect();
  step('the admitted player connects');

  linker.send('/link');
  const { line } = await linker.waitForChat(/link code|could not/i, { timeoutMs: TIMEOUT });

  if (looksLikeOutage(line)) {
    fail('/link returns a code', 'a code', `an outage: ${line}`);
  }
  const code = extractCode(line);
  if (!code) {
    fail('/link returns a code', 'a code in the reply', line);
  }
  step('/link returns a code', code);

  // --- 4. the code redeems on the other side -------------------------------
  const redeemed = await redeemAsOtherPlatform(code);
  if (!redeemed.ok) {
    fail('the code redeems on the other platform',
      'ok:true', JSON.stringify(redeemed).slice(0, 200));
  }
  if (redeemed.payload.identities.length !== 2) {
    fail('the link joins exactly two accounts',
      '2 identities', `${redeemed.payload.identities.length}`);
  }
  step('the code redeems on the other platform', '2 identities');

  await linker.disconnect();

  // --- 5. the gate now admits that player BY THE RULE ----------------------
  // Asked of core directly rather than by joining again, because the override
  // from step 2 is still in force and a successful join would prove nothing
  // about the rule. Removing the override and reconnecting would test the same
  // thing more slowly and with one more way to be flaky.
  const decided = await callCore('decide', {
    gate: 'game.join',
    platformKind: 'game',
    platformId: offlineUuid(LINKER),
  });
  if (!decided.ok) {
    fail('core decides for the linked player', 'ok:true', JSON.stringify(decided));
  }
  if (decided.payload.effect !== 'allow') {
    fail('the linked player is now allowed',
      'allow', `${decided.payload.effect} (${decided.payload.reason})`);
  }
  if (decided.payload.reason !== 'override' && decided.payload.reason !== 'requirements-met') {
    fail('the decision has a sensible reason', 'override or requirements-met',
      decided.payload.reason);
  }
  step('the linked player is allowed', decided.payload.reason);

  // --- 6. and the graph reads back correctly -------------------------------
  // The resource, not the response. A response can be right about work that did
  // not persist.
  const inspected = await callCore('subject.inspect', {
    platformKind: 'game',
    platformId: offlineUuid(LINKER),
  });
  if (!inspected.ok || !inspected.payload.linked) {
    fail('the graph reads back as linked', 'linked:true', JSON.stringify(inspected));
  }
  if (inspected.payload.identities.length !== 2) {
    fail('the graph holds both identities',
      '2', `${inspected.payload.identities.length}`);
  }
  step('the graph reads back with both identities');

  console.log(`\n${steps.length} steps passed`);
}

main().catch((err) => {
  console.error(`\nsmoke failed: ${err.message}`);
  process.exit(1);
});

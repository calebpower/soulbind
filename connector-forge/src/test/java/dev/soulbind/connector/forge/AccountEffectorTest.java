/*
 * Copyright (c) 2026 Caleb L. Power
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.soulbind.connector.forge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.IdempotentApplier;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import dev.soulbind.sdk.transport.Transport;
import dev.soulbind.sdk.transport.TransportException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Keeping accounts in step with the gate, with no core and no forge.
 *
 * <p>Two properties carry the weight. <b>The cursor never moves past an event
 * this connector failed to act on</b>, because acknowledging one is forgetting
 * it: core will not send it again, and the account stays as it was with nothing
 * anywhere saying why. And <b>an event this effector is not for changes
 * nothing</b> — a subject spans several platforms, so events about somebody's
 * chat identity arrive here too, and acting on one would deactivate an account
 * named after a Discord snowflake.
 *
 * <p>Several tests assert a call that must NOT have happened, which is only
 * observable because {@link ScriptedSurface} counts.
 */
class AccountEffectorTest {

    private static final Clock CLOCK =
            Clock.fixed(Instant.ofEpochSecond(1_700_000_000L), ZoneOffset.UTC);
    private static final String KIND = "forge";
    private static final String GATE = "forge.register";

    private final List<String> logged = new ArrayList<>();
    private final List<Throwable> thrown = new ArrayList<>();

    private static String event(long sequence, String type, String gate, String ref, String key) {
        return "{\"sequence\":" + sequence + ",\"type\":\"" + type + "\",\"subjectId\":\"s-1\","
                + "\"identityRef\":\"" + ref + "\",\"gate\":\"" + gate + "\",\"payload\":{},"
                + "\"idempotencyKey\":\"" + key + "\",\"createdAtEpochSeconds\":1700000000}";
    }

    private static String met(long sequence, String username) {
        return event(sequence, "subject.requirements-met", GATE, KIND + ":" + username,
                "k-" + sequence);
    }

    private static String lost(long sequence, String username) {
        return event(sequence, "subject.requirements-lost", GATE, KIND + ":" + username,
                "k-" + sequence);
    }

    /** A core that serves these events and accepts an acknowledgement. */
    private static InMemoryTransport core(String... events) {
        String batch = "{\"schema\":1,\"ok\":true,\"payload\":{\"events\":["
                + String.join(",", events) + "],\"cursor\":0,\"highest\":0}}";
        String acked = "{\"schema\":1,\"ok\":true,\"payload\":{\"cursor\":1}}";
        return new InMemoryTransport(
                request -> request.contains("\"op\":\"event.ack\"") ? acked : batch);
    }

    private AccountEffector effector(InMemoryTransport transport, ForgeSurface forge) {
        return new AccountEffector(
                new SoulbindClient(transport, "a-credential", CLOCK, new DecisionCache()),
                forge, new IdempotentApplier(), KIND, GATE, (m, t) -> {
                    logged.add(m);
                    if (t != null) {
                        thrown.add(t);
                    }
                });
    }

    // --- the transitions -------------------------------------------------------

    @Test
    @DisplayName("requirements lapsing deactivates the account")
    void lostDeactivates() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        AccountEffector.Drained drained = effector(core(lost(7, "ada")), forge).drain();

        assertFalse(forge.isActive("ada"));
        assertEquals(new AccountEffector.Drained(1, 1, true), drained);
    }

    @Test
    @DisplayName("requirements returning activates it again")
    void metActivates() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");
        forge.setActive("ada", false);

        effector(core(met(8, "ada")), forge).drain();

        assertTrue(forge.isActive("ada"));
    }

    // --- events that are not ours ---------------------------------------------

    @Test
    @DisplayName("an event for another gate changes nothing")
    void otherGateIgnored() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        AccountEffector.Drained drained = effector(
                core(event(9, "subject.requirements-lost", "chat.gamelinked",
                        KIND + ":ada", "k-9")), forge).drain();

        assertTrue(forge.isActive("ada"), "an account was deactivated over another gate's rule");
        assertEquals(0, forge.setActiveCalls());
        assertTrue(drained.acknowledged(),
                "an event that is not ours is still an event we are done with; leaving it "
                        + "unacknowledged would stall the cursor behind it forever");
    }

    @Test
    @DisplayName("an event about another platform's identity changes nothing")
    void otherKindIgnored() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        effector(core(event(10, "subject.requirements-lost", GATE, "discord:ada", "k-10")),
                forge).drain();

        assertEquals(0, forge.setActiveCalls(),
                "an account was touched because a reference from another platform happened to "
                        + "carry the same name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no-colon-at-all", "forge:", ":ada", ""})
    @DisplayName("a reference that is not a reference is ignored, not guessed at")
    void malformedReferenceIgnored(String ref) {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        effector(core(event(11, "subject.requirements-lost", GATE, ref, "k-11")), forge).drain();

        assertEquals(0, forge.setActiveCalls());
    }

    @Test
    @DisplayName("an event type this effector has no business with changes nothing")
    void unrelatedTypeIgnored() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        effector(core(event(12, "identity.linked", GATE, KIND + ":ada", "k-12")), forge).drain();

        assertEquals(0, forge.setActiveCalls());
    }

    // --- at-least-once ---------------------------------------------------------

    @Test
    @DisplayName("a redelivered event applies once")
    void redeliveryAppliesOnce() {
        // Delivery is at-least-once and the key is stable across redeliveries,
        // so the second arrival must be recognised rather than re-applied.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");
        AccountEffector effector = effector(core(lost(13, "ada")), forge);

        assertEquals(1, effector.drain().applied());
        assertEquals(0, effector.drain().applied(), "the same event was applied twice");
        assertEquals(1, forge.setActiveCalls());
    }

    // --- the cursor ------------------------------------------------------------

    @Test
    @DisplayName("an outage acknowledges nothing, and says so exactly once")
    void outageAcknowledgesNothing() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");
        InMemoryTransport transport = core(lost(14, "ada")).goDown();
        AccountEffector effector = effector(transport, forge);

        assertEquals(new AccountEffector.Drained(0, 0, false), effector.drain());
        effector.drain();
        effector.drain();

        assertEquals(1, logged.size(),
                () -> "an outage was reported every cycle instead of once: " + logged);
        assertTrue(logged.get(0).contains("cannot poll"), logged::toString);

        transport.comeBack();
        effector.drain();
        assertEquals(2, logged.size(), logged::toString);
        assertTrue(logged.get(1).contains("recovered"),
                () -> "recovery was silent, so the log never closes the outage: " + logged);
    }

    @Test
    @DisplayName("a failure stops the batch and acknowledges only what came before it")
    void failureStopsAtTheFirstBadEvent() {
        // 'grace' has no account, so the forge refuses. The event for 'ada'
        // precedes it and is clean; the one after must not be applied, and the
        // cursor must stop at 20.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada").withAccount("zoe");
        InMemoryTransport transport = core(lost(20, "ada"), lost(21, "grace"), lost(22, "zoe"));

        AccountEffector.Drained drained = effector(transport, forge).drain();

        assertEquals(3, drained.seen());
        assertEquals(1, drained.applied());
        assertFalse(forge.isActive("ada"));
        assertTrue(forge.isActive("zoe"),
                "an event after the failure was applied, so a later change overtook an earlier "
                        + "one that never happened");
        assertTrue(transport.sent().stream().anyMatch(r -> r.contains("\"through\":20")),
                () -> "the cursor did not stop at the last clean event: " + transport.sent());
        assertTrue(transport.sent().stream().noneMatch(r -> r.contains("\"through\":22")),
                () -> "the cursor moved past an event that was never applied: "
                        + transport.sent());
    }

    @Test
    @DisplayName("a failure on the very first event acknowledges nothing at all")
    void firstEventFailingAcknowledgesNothing() {
        ScriptedSurface forge = new ScriptedSurface();
        InMemoryTransport transport = core(lost(30, "nobody"));

        AccountEffector.Drained drained = effector(transport, forge).drain();

        assertFalse(drained.acknowledged());
        assertTrue(transport.sent().stream().noneMatch(r -> r.contains("event.ack")),
                () -> "something was acknowledged although nothing was applied: "
                        + transport.sent());
    }

    @Test
    @DisplayName("an empty poll acknowledges nothing")
    void emptyPollAcknowledgesNothing() {
        AccountEffector.Drained drained =
                effector(core(), new ScriptedSurface()).drain();

        assertEquals(new AccountEffector.Drained(0, 0, false), drained);
    }

    @Test
    @DisplayName("the reason an outage happened is carried into the log, not dropped")
    void outageReasonIsCarried() {
        // A sentinel this test owns, rather than the in-memory transport's own
        // wording: the property is that the transport's detail survives the
        // trip into the log line, and asserting the fake's prose would pass
        // just as well if describe() hard-coded it.
        String sentinel = "the-disk-caught-fire-at-0300";
        Transport throwing = new Transport() {
            @Override
            public String send(String requestBody) throws TransportException {
                throw new TransportException(sentinel);
            }

            @Override
            public boolean isConnected() {
                return false;
            }

            @Override
            public void close() {
                // nothing to release
            }
        };

        new AccountEffector(
                new SoulbindClient(throwing, "a-credential", CLOCK, new DecisionCache()),
                new ScriptedSurface(), new IdempotentApplier(), KIND, GATE,
                (m, t) -> logged.add(m)).drain();

        assertTrue(logged.get(0).contains(sentinel),
                () -> "a log line that says something went wrong and not what: " + logged);
    }

    @Test
    @DisplayName("core REFUSING the poll is reported with core's own reason")
    void refusedPollNamesTheReason() {
        // Different from an outage: this is core answering, and the answer is
        // usually a missing capability. A connector told "no" that logged only
        // "cannot poll" would leave an operator grepping for a permissions
        // problem they were never shown.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");
        InMemoryTransport transport = InMemoryTransport.always(
                "{\"schema\":1,\"ok\":false,\"error\":{\"code\":\"missing-capability\","
                        + "\"message\":\"effector is not granted\"}}");

        AccountEffector.Drained drained = effector(transport, forge).drain();

        assertEquals(new AccountEffector.Drained(0, 0, false), drained);
        assertTrue(logged.get(0).contains("missing-capability"), logged::toString);
        assertTrue(logged.get(0).contains("effector is not granted"), logged::toString);
    }

    @Test
    @DisplayName("an event with no identity reference at all is ignored")
    void absentReferenceIgnored() {
        // Distinct from a malformed one: the field is missing rather than
        // unparseable, which is what a rule-shaped event looks like.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");
        String noRef = "{\"sequence\":60,\"type\":\"subject.requirements-lost\","
                + "\"subjectId\":\"s-1\",\"gate\":\"" + GATE + "\",\"payload\":{},"
                + "\"idempotencyKey\":\"k-60\",\"createdAtEpochSeconds\":1700000000}";

        effector(core(noRef), forge).drain();

        assertEquals(0, forge.setActiveCalls());
        assertTrue(forge.isActive("ada"));
    }

    @Test
    @DisplayName("a refusal from the forge says which direction it refused")
    void failureNamesTheDirection() {
        // The message carries the only clue to what was being attempted, and a
        // message that said "activate" when it meant "deactivate" would send
        // somebody looking at the wrong half of this connector.
        ScriptedSurface forge = new ScriptedSurface();

        effector(core(lost(70, "nobody")), forge).drain();

        assertEquals(1, thrown.size(), logged::toString);
        assertTrue(thrown.get(0).getMessage().contains("deactivate"),
                () -> "the failure named the wrong direction: " + thrown.get(0).getMessage());
        assertTrue(thrown.get(0).getMessage().contains("nobody"),
                () -> thrown.get(0).getMessage());
    }

    @Test
    @DisplayName("drainQuietly catches what drain itself cannot")
    void drainQuietlyCatchesATransportFault() {
        // drain() handles a failure to APPLY an event. A transport that throws
        // something other than a transport fault escapes it entirely, and a
        // scheduled task that throws is one that stops running.
        InMemoryTransport hostile = new InMemoryTransport(request -> {
            throw new IllegalStateException("the transport itself came apart");
        });

        effector(hostile, new ScriptedSurface()).drainQuietly();

        assertTrue(logged.stream().anyMatch(m -> m.contains("event drain failed")),
                () -> "a fault below the event loop was swallowed without a word: " + logged);
    }

    // --- the known gap, said out loud -----------------------------------------

    @Test
    @DisplayName("a rule change is reported rather than silently ignored")
    void ruleChangeIsReported() {
        // This effector cannot enumerate accounts, so it cannot reconcile them
        // against a rule that got stricter. That is a limitation; a limitation
        // nobody is told about is a silence.
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");
        AccountEffector effector =
                effector(core(event(40, "rule.changed", GATE, "", "k-40")), forge);

        effector.drain();

        assertEquals(1, logged.size(), logged::toString);
        assertTrue(logged.get(0).contains("NOT"), logged::toString);
        assertTrue(logged.get(0).contains(GATE), logged::toString);
        assertEquals(0, forge.setActiveCalls());

        effector.drain();
        assertEquals(1, logged.size(), () -> "the same limitation was reported twice: " + logged);
    }

    @Test
    @DisplayName("another gate's rule change is not even reported")
    void otherGatesRuleChangeIsSilent() {
        ScriptedSurface forge = new ScriptedSurface().withAccount("ada");

        effector(core(event(41, "rule.changed", "chat.gamelinked", "", "k-41")), forge).drain();

        assertEquals(List.of(), logged,
                "a rule change for a gate this connector has nothing to do with was reported");
    }

    // --- the scheduler's entry point ------------------------------------------

    @Test
    @DisplayName("drainQuietly reports a failure instead of throwing at the scheduler")
    void drainQuietlySwallowsAndReports() {
        // A scheduled task that throws is a scheduled task that stops running,
        // in some executors silently.
        ForgeSurface explodes = new ForgeSurface() {
            @Override
            public Presence presence(String username) {
                throw new IllegalStateException("boom");
            }

            @Override
            public Creation create(Account account) {
                throw new IllegalStateException("boom");
            }

            @Override
            public boolean setActive(String username, boolean active) {
                throw new IllegalStateException("boom");
            }
        };

        effector(core(lost(50, "ada")), explodes).drainQuietly();

        assertTrue(logged.stream().anyMatch(m -> m.contains("could not apply event 50")),
                logged::toString);
    }
}

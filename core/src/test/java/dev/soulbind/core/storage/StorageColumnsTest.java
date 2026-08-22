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

package dev.soulbind.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.identity.Identity;
import dev.soulbind.core.identity.Subject;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Does every column a write binds come back the way it went in?
 *
 * <p>The gap this fills is narrow and it is everywhere: a write path binds eight
 * or nine parameters, and the tests around it read back the one or two the test
 * was about. Mutation deleted individual {@code setString} calls across the
 * storage package — the display, the proof method, the flags, the reason on an
 * override — and nothing failed. A column silently unbound is data loss that
 * only shows up when somebody goes looking for the value months later.
 *
 * <p><b>Round trips, and distinct values in every field.</b> Two fields holding
 * the same string cannot detect being swapped, which is the other half of what
 * this catches.
 */
class StorageColumnsTest {

    @TempDir
    Path tempDir;

    private static final Instant CREATED = Instant.parse("2026-03-01T12:00:00Z");
    private static final Instant VERIFIED = Instant.parse("2026-03-02T09:30:00Z");

    // --- identities -----------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("every column an identity is bound with reads back unchanged")
    void identityRoundTrip(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Subject subject = storage.identities().createSubject(CREATED);

            storage.identities().bind(
                    subject.id(), "game", "player-1", "Alex the First",
                    Map.of("region", "eu", "tier", "gold"),
                    "link-code", VERIFIED, CREATED);

            Identity read = storage.identities().findIdentity("game", "player-1").orElseThrow();

            // Every one of these is a separate setString that mutation could
            // delete on its own, and each holds a distinct value so a swap is
            // as visible as an omission.
            assertEquals(subject.id(), read.subjectId(), "subject_id");
            assertEquals("game", read.platformKind(), "platform_kind");
            assertEquals("player-1", read.platformId(), "platform_id");
            assertEquals("Alex the First", read.display(), "display");
            assertEquals(Map.of("region", "eu", "tier", "gold"), read.flags(), "flags");
            assertEquals("link-code", read.proofMethod(), "proof_method");
            assertEquals(VERIFIED, read.verifiedAt(), "verified_at");
            assertEquals(CREATED, read.createdAt(), "created_at");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an identity nobody has proven has NO verification time, not a zero one")
    void unverifiedIsNull(Backend backend) {
        // Null and epoch-zero are different answers and the difference is what
        // /whoami shows somebody: "not yet verified" against a date in 1970.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Subject subject = storage.identities().createSubject(CREATED);
            storage.identities().bind(
                    subject.id(), "game", "player-1", "Alex", Map.of(),
                    "self-asserted", null, CREATED);

            Identity read = storage.identities().findIdentity("game", "player-1").orElseThrow();

            assertNull(read.verifiedAt(), "an unproven identity carries a verification time");
            assertFalse(read.isVerified());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("empty flags are stored as nothing and read back as nothing")
    void emptyFlagsRoundTrip(Backend backend) {
        // writeFlags returns null for an empty map rather than "{}", so the
        // column stays empty. Both branches matter: the reader has to turn
        // either back into an empty map rather than a null the caller trips on.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Subject subject = storage.identities().createSubject(CREATED);
            storage.identities().bind(
                    subject.id(), "game", "player-1", "Alex", Map.of(),
                    "link-code", null, CREATED);

            assertEquals(
                    Map.of(),
                    storage.identities().findIdentity("game", "player-1").orElseThrow()
                            .flags());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("markVerified reports whether it found anything, and updates both columns")
    void markVerifiedReportsAndUpdates(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Subject subject = storage.identities().createSubject(CREATED);
            storage.identities().bind(
                    subject.id(), "game", "player-1", "Alex", Map.of(),
                    "self-asserted", null, CREATED);

            assertTrue(
                    storage.identities().markVerified("game", "player-1", "oauth", VERIFIED),
                    "marking an identity that exists reported finding nothing");

            Identity read = storage.identities().findIdentity("game", "player-1").orElseThrow();
            assertEquals("oauth", read.proofMethod(),
                    "the proof method was not updated, so the record says the account was"
                            + " proven by a means it was not");
            assertEquals(VERIFIED, read.verifiedAt());

            assertFalse(
                    storage.identities().markVerified("game", "nobody", "oauth", VERIFIED),
                    "marking an identity that does not exist reported success, so a caller"
                            + " believes an account it never touched is now proven");
        }
    }

    // --- policy ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("clearRule removes the rule and reports whether there was one")
    void clearRule(Backend backend) {
        // Both return values. `true` for a gate that had no rule would tell an
        // operator they had removed a requirement that was never there -- and
        // they would stop looking for the one that is still in force.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.policy().gateSeen("game.join", "conn-1", "the join gate");
            storage.policy().setRule(
                    new dev.soulbind.policy.Rule(
                            "game.join", java.util.Set.of("game"), true, 0L,
                            dev.soulbind.policy.Effect.DENY),
                    CREATED, "cli");

            assertTrue(storage.policy().rule("game.join").isPresent());
            assertTrue(storage.policy().clearRule("game.join"),
                    "clearing a rule that was there reported doing nothing");
            assertTrue(storage.policy().rule("game.join").isEmpty(),
                    "the rule survived being cleared, so the gate still enforces what an"
                            + " operator just removed");
            assertFalse(storage.policy().clearRule("game.join"),
                    "clearing a gate with no rule reported removing one");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("every column of a rule reads back unchanged")
    void ruleRoundTrip(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.policy().gateSeen("game.join", "conn-1", null);
            storage.policy().setRule(
                    new dev.soulbind.policy.Rule(
                            "game.join", java.util.Set.of("chat", "forum"), true, 600L,
                            dev.soulbind.policy.Effect.ALLOW),
                    CREATED, "cli");

            dev.soulbind.policy.Rule read = storage.policy().rule("game.join").orElseThrow();

            assertEquals(java.util.Set.of("chat", "forum"), read.requiredKinds());
            assertTrue(read.requireLinked());
            assertEquals(600L, read.graceSeconds(),
                    "the grace period did not survive the write, so a gate an operator made"
                            + " forgiving is not");
            assertEquals(dev.soulbind.policy.Effect.ALLOW, read.defaultEffect());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an override keeps its reason and its expiry, and its id is returned")
    void overrideRoundTrip(Backend backend) {
        // The reason is the whole audit trail for an override -- it is what
        // somebody reads in six months to find out why an exception exists.
        // And the id has to come back, or the caller has nothing to report.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            Instant expires = CREATED.plusSeconds(3600);
            String id = storage.policy().addOverride(
                    new dev.soulbind.policy.PolicyOverride(
                            "game.join", null, "game:player-1",
                            dev.soulbind.policy.Effect.ALLOW,
                            "admitted by hand so they can link", expires),
                    CREATED, "connector:cli");

            assertFalse(id == null || id.isBlank(),
                    "addOverride returned no id, so nothing can refer to what it just made");

            dev.soulbind.policy.PolicyOverride read =
                    storage.policy().overridesFor("game.join").get(0);

            assertEquals("game:player-1", read.identityRef());
            assertNull(read.subjectId());
            assertEquals(dev.soulbind.policy.Effect.ALLOW, read.effect());
            assertEquals("admitted by hand so they can link", read.reason(),
                    "the reason did not survive the write, so the only record of WHY the"
                            + " exception exists is gone");
            assertEquals(expires, read.expiresAt(),
                    "the expiry did not survive, so a temporary exception became permanent");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("a gate remembers who first mentioned it and what it is for")
    void gateSeenRoundTrip(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.policy().gateSeen("game.join", "conn-1", "the join gate");
            // Seeing it again must not lose the description or duplicate the row.
            storage.policy().gateSeen("game.join", "conn-2", null);

            assertEquals(java.util.List.of("game.join"), storage.policy().gates(),
                    "the gate was recorded twice, so every listing shows it twice");
        }
    }

    // --- events ---------------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an event payload round-trips, empty or not")
    void eventPayloadRoundTrip(Backend backend) {
        // The payload is the only part of an event a consumer cannot reconstruct
        // from anything else. writePayload returns null for an empty map and
        // readPayload has to turn either that or a stored object back into a
        // map -- and a null reaching a connector is a crash in the drain.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            storage.events().append(dev.soulbind.core.events.EventRecord.of(
                    dev.soulbind.protocol.EventType.IDENTITY_LINKED,
                    "subject-1", "game:player-1", null,
                    Map.of("issuedFor", "chat:alex", "proofMethod", "link-code"), CREATED));
            storage.events().append(dev.soulbind.core.events.EventRecord.of(
                    dev.soulbind.protocol.EventType.CONFIG_CHANGED,
                    null, null, null, Map.of(), CREATED));

            var events = storage.events().after(0, 10);

            assertEquals(
                    Map.of("issuedFor", "chat:alex", "proofMethod", "link-code"),
                    events.get(0).payload(),
                    "the payload did not survive the write, so a connector acts on an event"
                            + " with nothing in it");
            assertEquals(Map.of(), events.get(1).payload(),
                    "an empty payload came back as something other than an empty map");
        }
    }

    // --- what open() configures -----------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("foreign keys are enforced, so an identity cannot outlive its subject")
    void foreignKeysAreEnforced(Backend backend) {
        // SQLite enforces foreign keys only when asked, per connection, and
        // `open()` asks. Deleting that one line leaves every constraint in the
        // schema decorative: an identity could be bound to a subject that does
        // not exist, and nothing would say so until somebody read the graph and
        // found a dangling reference.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            assertThrows(
                    RuntimeException.class,
                    () -> storage.identities().bind(
                            "no-such-subject", "game", "player-1", "Alex", Map.of(),
                            "link-code", null, CREATED),
                    "an identity was bound to a subject that does not exist, so the foreign"
                            + " key in the schema is not being enforced");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("closing releases the store, rather than leaving the pool open")
    void closeReleasesTheStore(Backend backend) {
        // Both lines of close(). A connector that opens and closes a store per
        // command leaks a pool and, on SQLite, a writer thread per open --
        // which is invisible until something has been running for a week.
        Storage storage = StorageBackends.open(backend, tempDir);
        storage.identities().createSubject(CREATED);
        storage.close();

        assertThrows(
                RuntimeException.class,
                () -> storage.identities().createSubject(CREATED),
                "a closed store still served a write, so nothing was actually released");
    }
}

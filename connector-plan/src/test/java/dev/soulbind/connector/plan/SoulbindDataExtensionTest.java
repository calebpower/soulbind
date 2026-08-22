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

package dev.soulbind.connector.plan;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.djrapitops.plan.extension.CallEvents;
import com.djrapitops.plan.extension.table.Table;
import dev.soulbind.sdk.DecisionCache;
import dev.soulbind.sdk.SoulbindClient;
import dev.soulbind.sdk.transport.InMemoryTransport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The provider bodies, executed.
 *
 * <p>{@link SoulbindDataExtension} is annotation-driven, so nothing about it
 * fails loudly: a provider that returns the wrong units, or a placeholder where
 * a value belongs, renders a plausible-looking panel. Plan reports no error
 * because there is none -- the method ran and returned a value of the right
 * type. These tests run the bodies so a wrong value is a red test rather than a
 * page somebody has to notice is lying.
 *
 * <p>The Plan API is on the test classpath for exactly this reason; see the
 * comment on {@code testImplementation} in this module's build file.
 */
class SoulbindDataExtensionTest {

    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, ZoneOffset.UTC);

    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID CAROL = UUID.fromString("00000000-0000-0000-0000-00000000ca01");

    /** Epoch seconds, deliberately not a round number of days. */
    private static final long VERIFIED_AT = 1_760_000_000L;

    private static String ok(String payload) {
        return "{\"schema\":1,\"ok\":true,\"payload\":" + payload + "}";
    }

    private static String linkedPayload(String subjectId, String kind, String proof) {
        return ok("{\"linked\":true,\"subjectId\":\"" + subjectId + "\",\"identities\":["
                + "{\"platformKind\":\"" + kind + "\",\"proofMethod\":\"" + proof + "\","
                + "\"verifiedAtEpochSeconds\":" + VERIFIED_AT + "}]}");
    }

    private static final String UNLINKED = ok("{\"linked\":false,\"identities\":[]}");

    private LinkDataSource source(InMemoryTransport transport, boolean showSubjectId) {
        return new LinkDataSource(
                new SoulbindClient(transport, "cred", FIXED, new DecisionCache()),
                "game",
                Duration.ofSeconds(30),
                showSubjectId,
                FIXED);
    }

    /** A roster supplier over an ordered map, so table row order is decided here. */
    private static Map<UUID, String> roster(Object... pairs) {
        Map<UUID, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((UUID) pairs[i], (String) pairs[i + 1]);
        }
        return map;
    }

    private SoulbindDataExtension extension(
            InMemoryTransport transport, boolean showSubjectId, Map<UUID, String> roster) {
        return new SoulbindDataExtension(source(transport, showSubjectId), () -> roster);
    }

    // --- units --------------------------------------------------------------

    @Test
    @DisplayName("linkedSince is milliseconds, because Plan's date formats are")
    void linkedSinceIsMilliseconds() {
        SoulbindDataExtension ext = extension(
                InMemoryTransport.always(linkedPayload("s1", "chat", "code")), false, roster());

        assertEquals(
                VERIFIED_AT * 1000L,
                ext.linkedSince(ALICE),
                "core speaks epoch seconds and Plan's DATE_YEAR format expects milliseconds; "
                        + "returning seconds renders 1970 on every page, which reads as a data "
                        + "problem rather than a units one");
    }

    @Test
    @DisplayName("linkedSince is 0 when there is no verification instant to report")
    void linkedSinceIsZeroWhenAbsent() {
        SoulbindDataExtension ext =
                extension(InMemoryTransport.always(UNLINKED), false, roster());

        assertEquals(0L, ext.linkedSince(BOB), "no instant means no date, not a date of zero");
    }

    // --- placeholders vs values ---------------------------------------------

    @Test
    @DisplayName("platforms and proof join what is there, and dash what is not")
    void listsRenderOrDash() {
        SoulbindDataExtension linked = extension(
                InMemoryTransport.always(linkedPayload("s1", "chat", "code")), false, roster());
        assertEquals("chat", linked.platforms(ALICE));
        assertEquals("code", linked.proof(ALICE));

        SoulbindDataExtension unlinked =
                extension(InMemoryTransport.always(UNLINKED), false, roster());
        assertEquals("-", unlinked.platforms(BOB), "an empty list is a dash, not an empty cell");
        assertEquals("-", unlinked.proof(BOB));
    }

    @Test
    @DisplayName("subject is withheld unless the operator opted in")
    void subjectIsGatedOnTheOptIn() {
        InMemoryTransport transport = InMemoryTransport.always(linkedPayload("s-secret", "chat", "code"));

        assertEquals(
                "-",
                extension(transport, false, roster()).subject(ALICE),
                "the subject id is an identifier that links a player across platforms; it is "
                        + "off the page until an operator says otherwise");
        assertEquals("s-secret", extension(transport, true, roster()).subject(ALICE));
    }

    // --- the distinction the whole module exists to keep ---------------------

    @Test
    @DisplayName("during an outage linked() is false but linkStatus() says unknown")
    void anOutageIsNotReportedAsNotLinked() {
        SoulbindDataExtension ext = extension(
                InMemoryTransport.always(UNLINKED).goDown(), false, roster());

        assertFalse(ext.linked(ALICE), "a boolean has no third value, so it must fail closed");
        assertTrue(
                ext.linkStatus(ALICE).contains("unknown"),
                () -> "the string provider carries the third value the boolean cannot; without "
                        + "it the page shows a confident 'not linked' it never learned. Got: "
                        + ext.linkStatus(ALICE));
    }

    // --- when Plan calls us --------------------------------------------------

    @Test
    @DisplayName("providers are never called on player join")
    void nothingRunsOnTheJoinPath() {
        List<CallEvents> events = Arrays.asList(
                extension(InMemoryTransport.always(UNLINKED), false, roster())
                        .callExtensionMethodsOn());

        assertFalse(
                events.contains(CallEvents.PLAYER_JOIN),
                "a join is both the moment a player is least likely to have just linked and the "
                        + "one path a proxy plugin must never make slower");
        assertTrue(events.contains(CallEvents.PLAYER_LEAVE));
        assertTrue(events.contains(CallEvents.SERVER_PERIODICAL));
    }

    // --- server-wide ---------------------------------------------------------

    /** Answers per player, keyed off the platform id core is asked about. */
    private static InMemoryTransport byPlayer() {
        return new InMemoryTransport(request -> {
            if (request.contains(ALICE.toString())) {
                return linkedPayload("s-alice", "chat", "code");
            }
            return UNLINKED;
        });
    }

    @Test
    @DisplayName("the three counts are taken over the caller's roster")
    void countsComeFromTheRoster() {
        SoulbindDataExtension ext = extension(
                byPlayer(), false, roster(ALICE, "Alice", BOB, "Bob", CAROL, "Carol"));

        assertEquals(1L, ext.linkedPlayers());
        assertEquals(2L, ext.unlinkedPlayers());
        assertEquals(0L, ext.unknownPlayers());
    }

    @Test
    @DisplayName("unknown is its own count, so the numbers add up to the roster")
    void unknownIsCountedSeparately() {
        SoulbindDataExtension ext = extension(
                byPlayer().goDown(), false, roster(ALICE, "Alice", BOB, "Bob", CAROL, "Carol"));

        assertEquals(3L, ext.unknownPlayers());
        assertEquals(0L, ext.linkedPlayers());
        assertEquals(
                0L,
                ext.unlinkedPlayers(),
                "counting an unreachable core as unlinked is the same lie as the string provider "
                        + "saying 'not linked', just aggregated");
    }

    @Test
    @DisplayName("the table lists the unlinked by name, and nobody else")
    void theTableListsTheUnlinked() {
        // Names deliberately NOT in roster order, and not in uuid order either.
        // Mapped Alice/Bob/Carol in sequence, all three orderings coincide and
        // the order half of the assertion below cannot fail.
        SoulbindDataExtension ext = extension(
                byPlayer(), false, roster(ALICE, "Alice", BOB, "Zoe", CAROL, "Mallory"));

        Table table = ext.unlinkedTable();
        List<Object[]> rows = table.getRows();

        // getColumns() is a fixed-width array padded with nulls -- Plan sizes it
        // to its column maximum, not to what was declared -- so the declared
        // count is getMaxColumnSize(), not the array length.
        assertEquals(
                1,
                table.getMaxColumnSize(),
                "one column, because a name is the only thing an operator acts on here");
        assertEquals("Player", table.getColumns()[0]);
        assertEquals(2, rows.size(), () -> "expected Mallory and Zoe; got " + describe(rows));
        assertEquals(
                List.of("Mallory", "Zoe"),
                rows.stream().map(row -> (String) row[0]).toList(),
                () -> "the linked player must not appear on a list of who to chase; got "
                        + describe(rows));
    }

    @Test
    @DisplayName("the table is empty rather than absent when everyone is linked")
    void theTableIsEmptyWhenNobodyIsUnlinked() {
        SoulbindDataExtension ext = extension(
                InMemoryTransport.always(linkedPayload("s1", "chat", "code")),
                false,
                roster(ALICE, "Alice"));

        assertEquals(
                0,
                ext.unlinkedTable().getRows().size(),
                "an empty table says 'nobody'; a missing one says nothing at all");
    }

    private static String describe(List<Object[]> rows) {
        return rows.stream().map(Arrays::toString).toList().toString();
    }

    @Test
    @DisplayName("a linked player reads as linked, not merely 'not unknown'")
    void linkedIsTrueForALinkedPlayer() {
        // `linked()` could return false unconditionally and survive: the only
        // test touching it was the outage case, where false is CORRECT. So the
        // boolean every Plan page renders as a tick or a cross was asserted in
        // one direction only.
        SoulbindDataExtension extension = extension(
                InMemoryTransport.always(ok(
                        "{\"linked\":true,\"identities\":[{\"platformKind\":\"game\"},"
                                + "{\"platformKind\":\"chat\"}]}")),
                false, Map.of());

        assertTrue(extension.linked(UUID.fromString("11111111-2222-3333-4444-555555555555")),
                "a player core reported as linked rendered as not linked");
    }
}

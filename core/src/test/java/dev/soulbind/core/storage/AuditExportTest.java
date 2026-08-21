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
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.audit.AuditPage;
import dev.soulbind.core.audit.AuditQuery;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Reading the whole audit log out of a bounded operation.
 *
 * <p>The bound is not negotiable — an unbounded read from an authenticated
 * endpoint is a way to exhaust memory — so an export is a loop, and the loop is
 * only correct if the page can say it stopped early. These assert the two
 * halves: that the loop terminates having seen every row exactly once, and that
 * {@code more} is honest at the boundary, which is the one place an
 * off-by-one turns "complete export" into "silently the first N rows".
 */
class AuditExportTest {

    @TempDir Path tempDir;

    private static void appendRows(Storage storage, int count) {
        for (int i = 0; i < count; i++) {
            storage.audit().append(new AuditEntry(
                    0L, Instant.ofEpochSecond(1_700_000_000L + i),
                    "actor", "row", "subject", null, null,
                    Map.of("i", String.valueOf(i))));
        }
    }

    /** The loop an operator's export tool runs, written once. */
    private static List<AuditEntry> exportAll(Storage storage, int pageSize) {
        List<AuditEntry> all = new ArrayList<>();
        long cursor = 0L;
        boolean more = true;
        while (more) {
            AuditPage page = storage.audit().page(AuditQuery.from(cursor, pageSize));
            all.addAll(page.entries());
            more = page.more();
            cursor = page.lastSequence();
        }
        return all;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("an export reads a log longer than the cap, completely and in order")
    void exportReadsEverything(Backend backend) {
        // Deliberately more rows than MAX_LIMIT. A log short enough to fit in
        // one page proves nothing about the operation an export exists for.
        int rows = AuditQuery.MAX_LIMIT + 137;
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            appendRows(storage, rows);

            List<AuditEntry> exported = exportAll(storage, 250);

            assertEquals(rows, exported.size(),
                    "the export did not read every row");
            for (int i = 0; i < exported.size(); i++) {
                assertEquals(i + 1L, exported.get(i).sequence(),
                        "a gap or a repeat at position " + i + ": paging lost its place");
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("more is honest at the exact boundary, in both directions")
    void moreIsHonestAtTheBoundary(Backend backend) {
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            appendRows(storage, 10);

            // Exactly the page size, with nothing after it. The tempting
            // implementation -- more = (entries.size() == limit) -- says true
            // here, and an export tool believing it makes one extra request
            // that returns nothing. Harmless. The mirror mistake is not.
            AuditPage exact = storage.audit().page(AuditQuery.from(0L, 10));
            assertEquals(10, exact.entries().size());
            assertFalse(exact.more(),
                    "claimed more rows exist when the page ended exactly at the last row");
            assertEquals(10L, exact.lastSequence());

            // One short of the log. Saying false here loses row 10 from every
            // export, permanently and silently, which is the failure this
            // whole shape exists to prevent.
            AuditPage short_ = storage.audit().page(AuditQuery.from(0L, 9));
            assertEquals(9, short_.entries().size());
            assertTrue(short_.more(),
                    "claimed the log ended while a row remained: an export would drop it");
            assertEquals(9L, short_.lastSequence());

            // Past the end. An export tool that keeps a cursor across runs
            // starts here every time nothing has happened since.
            AuditPage past = storage.audit().page(AuditQuery.from(10L, 10));
            assertTrue(past.entries().isEmpty());
            assertFalse(past.more());
            assertEquals(10L, past.lastSequence(),
                    "an empty page forgot the cursor, so the next run would re-export"
                            + " the entire log");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("dev.soulbind.core.storage.StorageBackends#available")
    @DisplayName("the cursor composes with the filters rather than replacing them")
    void cursorComposesWithFilters(Backend backend) {
        // An export of one subject's history is the common incident-review ask,
        // and it is served by the same loop. If the cursor were applied instead
        // of the filter the answer would be plausible and wrong.
        try (Storage storage = StorageBackends.open(backend, tempDir)) {
            for (int i = 0; i < 20; i++) {
                storage.audit().append(new AuditEntry(
                        0L, Instant.ofEpochSecond(1_700_000_000L + i),
                        "actor", i % 2 == 0 ? "even" : "odd", "subject", null, null,
                        Map.of()));
            }

            AuditPage page = storage.audit().page(
                    new AuditQuery(null, null, null, null, "even", 100, 10L));

            assertEquals(5, page.entries().size(),
                    "the cursor and the action filter did not both apply");
            assertTrue(page.entries().stream().allMatch(e -> "even".equals(e.action())));
            assertTrue(page.entries().stream().allMatch(e -> e.sequence() > 10L));
        }
    }
}

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

package dev.soulbind.guards;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.soulbind.core.registry.Authorizer;
import dev.soulbind.protocol.Capability;
import dev.soulbind.protocol.EventType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The protocol-document sync guard.
 *
 * <p>{@code docs/protocol.md} is the human-readable contract, and humans are the
 * ones who read it before writing a connector. A document that has drifted from
 * the code is worse than no document: it is confidently wrong, and the person it
 * misleads has no reason to doubt it.
 *
 * <p>So the document is held to {@link Authorizer.Operation} mechanically, in
 * both directions — an operation added in code without a row fails, and a row
 * with no operation behind it fails too. The comparison is against the
 * <em>reflected enum</em>, not a re-parse of the Java source: the enum is what
 * actually runs, and a source-text reading could agree with the document while
 * both disagreed with the compiled behaviour.
 *
 * <p><b>What this does NOT prove:</b> that the prose around the tables is
 * accurate. No guard can. It proves the tables — the part a connector author
 * codes against — cannot silently diverge.
 */
class ProtocolDocSyncGuardTest {

    /** A table row: {@code | `name` | `capability` |} or {@code | `name` | *(any registered)* |}. */
    private static final Pattern ROW = Pattern.compile(
            "^\\|\\s*`([a-z][a-z0-9.]*)`\\s*\\|\\s*(?:`([a-z-]+)`|\\*\\(any registered\\)\\*)\\s*\\|\\s*$");

    /** A capability-table row: {@code | `capability` | prose |}. */
    private static final Pattern CAPABILITY_ROW =
            Pattern.compile("^\\|\\s*`([a-z-]+)`\\s*\\|\\s*[^|]+\\|\\s*$");

    /** An event-table row: {@code | `type` | prose |}. */
    private static final Pattern EVENT_ROW =
            Pattern.compile("^\\|\\s*`([a-z][a-z0-9.-]*)`\\s*\\|\\s*[^|]+\\|\\s*$");

    private static final String OPERATIONS_HEADING = "## Operations";
    private static final String CAPABILITIES_HEADING = "## Capabilities";

    @Test
    @DisplayName("every declared operation appears in the document, with the same capability")
    void operationsMatchTheDocument() {
        Map<String, Optional<String>> documented =
                parseOperations(SourceTree.repoRoot().resolve("docs/protocol.md"));

        List<String> violations = compare(documented, declaredOperations());

        assertTrue(
                violations.isEmpty(),
                () -> "docs/protocol.md has drifted from Authorizer.Operation. A connector "
                        + "author codes against that document; a stale row sends them to write "
                        + "a request the server will refuse, with no reason to doubt the "
                        + "instructions.\n  " + String.join("\n  ", violations));
    }

    @Test
    @DisplayName("every declared capability appears in the document's capability table")
    void capabilitiesMatchTheDocument() {
        Set<String> documented =
                parseCapabilities(SourceTree.repoRoot().resolve("docs/protocol.md"));

        Set<String> declared = new TreeSet<>();
        for (Capability capability : Capability.values()) {
            declared.add(capability.wireName());
        }

        assertEquals(
                declared,
                new TreeSet<>(documented),
                "the capability table and the Capability enum disagree. Both are read as the "
                        + "vocabulary of the protocol, so a difference means one of them is "
                        + "lying to somebody.");
    }

    @Test
    @DisplayName("every event type appears in the document, and every documented type exists")
    void eventTypesMatchTheDocument() {
        // T3's "every-event-documented-and-consumed". An event type nobody
        // documented is a side effect nobody can audit -- events are the one
        // place a connector acts on something core said happened, so an
        // undocumented one is a change to the world that no reader can trace.
        Set<String> documented =
                parseEvents(SourceTree.repoRoot().resolve("docs/protocol.md"));

        Set<String> declared = new TreeSet<>();
        for (EventType type : EventType.values()) {
            declared.add(type.wireName());
        }

        assertEquals(
                declared,
                new TreeSet<>(documented),
                "docs/protocol.md's event table and the EventType enum disagree. A connector "
                        + "author reads that table to decide what to handle; a type missing "
                        + "from it is a type nobody will handle, and one that is only in it is "
                        + "a handler waiting for something that never arrives.");
    }

    @Test
    @DisplayName("GUARD FIRES: a document that has drifted is rejected")
    void driftFixtureIsRejected() {
        // The fixture carries three separate drifts, and each must be named:
        // a row whose capability is wrong, a row for an operation that does not
        // exist, and a missing row for one that does. A guard that caught only
        // the first would pass on the two that are easier to introduce.
        Path fixture = SourceTree.repoRoot()
                .resolve("guards/src/test/resources/fixtures/protocol-doc-drift/docs/protocol.md");

        List<String> violations = compare(parseOperations(fixture), declaredOperations());

        assertFalse(
                violations.isEmpty(),
                "the must-fail fixture was not rejected: either it stopped drifting, or the "
                        + "guard stopped detecting drift");
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("code.redeem")),
                () -> "expected the wrong-capability row to be named: " + violations);
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("subject.teleport")),
                () -> "expected the invented operation to be named: " + violations);
        assertTrue(
                violations.stream().anyMatch(v -> v.contains("audit.query")),
                () -> "expected the missing row to be named: " + violations);
    }

    @Test
    @DisplayName("the parser actually reads rows -- an empty parse would pass every comparison")
    void parserReadsTheRealDocument() {
        // Without this, a regex that matched nothing would make the drift guard
        // report "documented == {}" against a non-empty code table -- which
        // fails loudly -- but would ALSO make a future refactor to a
        // subset-comparison silently vacuous. Stating the count here means the
        // parser has to keep working.
        Map<String, Optional<String>> documented =
                parseOperations(SourceTree.repoRoot().resolve("docs/protocol.md"));
        assertEquals(
                Authorizer.Operation.values().length,
                documented.size(),
                "the operations table parsed to a different number of rows than there are "
                        + "operations; if the table's formatting changed, fix the parser rather "
                        + "than loosening the comparison");
    }

    // --- engine, shared by the real document and the fixture -----------------

    private static Map<String, Optional<String>> declaredOperations() {
        Map<String, Optional<String>> out = new LinkedHashMap<>();
        for (Authorizer.Operation operation : Authorizer.Operation.values()) {
            out.put(operation.wireName(), operation.required().map(Capability::wireName));
        }
        return out;
    }

    /** Both directions, each difference named individually so the fix is obvious. */
    private static List<String> compare(
            Map<String, Optional<String>> documented, Map<String, Optional<String>> declared) {
        List<String> violations = new ArrayList<>();

        for (Map.Entry<String, Optional<String>> entry : declared.entrySet()) {
            String operation = entry.getKey();
            if (!documented.containsKey(operation)) {
                violations.add("`%s` is declared in code but has no row in the document"
                        .formatted(operation));
                continue;
            }
            Optional<String> documentedCapability = documented.get(operation);
            if (!documentedCapability.equals(entry.getValue())) {
                violations.add(
                        "`%s`: document says %s, code says %s".formatted(
                                operation,
                                documentedCapability.orElse("(any registered)"),
                                entry.getValue().orElse("(any registered)")));
            }
        }

        for (String operation : documented.keySet()) {
            if (!declared.containsKey(operation)) {
                violations.add("`%s` has a row in the document but is declared nowhere in code"
                        .formatted(operation));
            }
        }

        return violations;
    }

    private static Map<String, Optional<String>> parseOperations(Path doc) {
        Map<String, Optional<String>> out = new LinkedHashMap<>();
        for (String line : sectionOf(doc, OPERATIONS_HEADING)) {
            Matcher m = ROW.matcher(line.strip());
            if (m.matches()) {
                out.put(m.group(1), Optional.ofNullable(m.group(2)));
            }
        }
        return out;
    }

    private static Set<String> parseEvents(Path doc) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : sectionOf(doc, "## Events")) {
            Matcher m = EVENT_ROW.matcher(line.strip());
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    private static Set<String> parseCapabilities(Path doc) {
        Set<String> out = new LinkedHashSet<>();
        for (String line : sectionOf(doc, CAPABILITIES_HEADING)) {
            Matcher m = CAPABILITY_ROW.matcher(line.strip());
            if (m.matches()) {
                out.add(m.group(1));
            }
        }
        return out;
    }

    /**
     * The lines between a heading and the next one at the same level.
     *
     * <p>Section-scoped rather than whole-file, because the two tables use the
     * same row shape. A whole-file scan would let a capability row satisfy the
     * operations comparison and vice versa — the guard would then pass on a
     * document where each table had been pasted over the other.
     */
    private static List<String> sectionOf(Path doc, String heading) {
        List<String> out = new ArrayList<>();
        boolean inSection = false;
        for (String line : SourceTree.read(doc).split("\n", -1)) {
            if (line.startsWith("## ")) {
                inSection = line.strip().equals(heading);
                continue;
            }
            if (inSection) {
                out.add(line);
            }
        }
        return out;
    }
}

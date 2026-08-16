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
package dev.soulbind.protocol;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * The events core emits, v1.
 *
 * <p>A closed set, and closed deliberately. Events are the one place where a
 * connector acts on something core said happened, so an event type nobody
 * documented is a side effect nobody can audit. A structural guard asserts that
 * every constant here appears in {@code docs/protocol.md} and vice versa.
 *
 * <p>The wire form is stated rather than derived, for the same reason as every
 * other enum on this wire: renaming a constant must not silently become a
 * protocol change.
 */
public enum EventType {

    /** Two platform accounts became one subject. */
    IDENTITY_LINKED("identity.linked"),

    /** An identity was removed from its subject. */
    IDENTITY_UNLINKED("identity.unlinked"),

    /** An identity established proof. */
    IDENTITY_VERIFIED("identity.verified"),

    /**
     * A subject now satisfies a gate it previously did not.
     *
     * <p>Emitted per gate, not once per subject. An effector granting a role
     * needs to know WHICH gate opened; telling it only that something changed
     * would make it re-evaluate everything on every link.
     */
    SUBJECT_REQUIREMENTS_MET("subject.requirements-met"),

    /** A subject stopped satisfying a gate it previously did. */
    SUBJECT_REQUIREMENTS_LOST("subject.requirements-lost"),

    /** A rule changed, so cached decisions for its gate are suspect. */
    RULE_CHANGED("rule.changed"),

    /** Runtime configuration changed. */
    CONFIG_CHANGED("config.changed"),

    /** A connector registered. */
    CONNECTOR_REGISTERED("connector.registered");

    private final String wireName;

    EventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<EventType> fromWireName(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String needle = s.strip().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.wireName.equals(needle)).findFirst();
    }
}

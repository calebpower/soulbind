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

package dev.soulbind.sdk;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How a connector reads an answer out of core.
 *
 * <p>Every connector in this repository reads responses through these
 * accessors, and a mutation sweep found the module that owns them asserting
 * none of it — exercised twenty-odd times from other modules, which is how a
 * type comes to be trusted without being tested.
 *
 * <p>The contract worth pinning is what happens on the fields that are
 * <em>not</em> there. Every accessor is total: absent means empty, zero or
 * false, never an exception. That is deliberate — a connector reading an
 * optional field must not have to guard every call — but it also means a
 * misread field looks exactly like an absent one, so {@link Payload#has} is
 * the only way to tell "core said no" from "core said nothing".
 */
class PayloadTest {

    private static Payload of(String json) {
        try {
            return new Payload(new ObjectMapper().readTree(json));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("present fields read back as themselves")
    void presentFields() {
        Payload p = of("""
                {"name":"alex","count":42,"ok":true,"kinds":["a","b"],
                 "items":[{"id":"1"},{"id":"2"}]}""");

        assertEquals("alex", p.text("name"));
        assertEquals(42L, p.number("count"));
        assertTrue(p.flag("ok"));
        assertEquals(List.of("a", "b"), p.texts("kinds"));
        assertEquals(2, p.size("items"));
        assertEquals(List.of("1", "2"), p.items("items").stream().map(i -> i.text("id")).toList());
        assertTrue(p.has("name"));
    }

    @Test
    @DisplayName("an absent field is empty, zero or false -- never an exception")
    void absentFields() {
        // Total by design: a connector reading an optional field should not
        // have to guard every call. The cost is that a MISREAD field looks
        // exactly like an absent one, which is what has() exists to separate.
        Payload p = of("{\"present\":1}");

        assertEquals("", p.text("nope"));
        assertEquals(0L, p.number("nope"));
        assertFalse(p.flag("nope"));
        assertEquals(0, p.size("nope"));
        assertTrue(p.texts("nope").isEmpty());
        assertTrue(p.items("nope").isEmpty());
        assertFalse(p.has("nope"), "has() must be the one accessor that can say 'absent'");
        assertTrue(p.has("present"));
    }

    @Test
    @DisplayName("a null body behaves like an empty one, because an outage is not a payload")
    void nullNode() {
        // Reached when a call could not be made at all. Every accessor still
        // answers, so a connector handling an outage does not also have to
        // handle a null payload -- but has() reports false for everything,
        // which is how the outage stays distinguishable.
        Payload p = new Payload(null);

        assertEquals("", p.text("anything"));
        assertEquals(0L, p.number("anything"));
        assertFalse(p.flag("anything"));
        assertEquals(0, p.size("anything"));
        assertTrue(p.texts("anything").isEmpty());
        assertTrue(p.items("anything").isEmpty());
        assertFalse(p.has("anything"));
        assertEquals("{}", p.toString());
    }

    @Test
    @DisplayName("the collections handed out cannot be edited by the caller")
    void collectionsAreImmutable() {
        Payload p = of("{\"kinds\":[\"a\"],\"items\":[{\"id\":\"1\"}]}");

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> p.texts("kinds").add("b"));
        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class, () -> p.items("items").add(p));
    }

    @Test
    @DisplayName("toString renders the body, because it lands in refusal messages")
    void toStringRendersTheBody() {
        // Connectors put this in log lines and thrown messages when core says
        // something unexpected. A toString that dropped the content would make
        // every one of those reports useless.
        assertTrue(of("{\"why\":\"because\"}").toString().contains("because"));
    }
}

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

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * A response payload, without exposing how it was parsed.
 *
 * <p>The SDK returns <b>values</b>, not a JSON tree. Handing a connector a
 * {@code JsonNode} would make Jackson part of the connector contract: every
 * connector would compile against it, and swapping the parser later would be a
 * breaking change to every one of them for a reason none of them cares about.
 *
 * <p>It would also not compile — Jackson is an {@code implementation}
 * dependency here precisely so it does not leak, and the first connector to try
 * reading a payload found that out.
 *
 * <p>Missing fields return empty or zero rather than throwing. A connector
 * reading a field a newer core stopped sending should degrade, not crash: the
 * alternative is a plugin that dies on a rolling upgrade.
 */
public final class Payload {

    private final JsonNode node;

    Payload(JsonNode node) {
        this.node = node;
    }

    /** A text field, or empty when absent. */
    public String text(String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    /** A numeric field, or zero when absent. */
    public long number(String field) {
        return node == null ? 0L : node.path(field).asLong(0L);
    }

    /** A boolean field, or false when absent. */
    public boolean flag(String field) {
        return node != null && node.path(field).asBoolean(false);
    }

    /** The length of an array field, or zero. */
    public int size(String field) {
        return node == null ? 0 : node.path(field).size();
    }

    /** An array of strings, or empty. */
    public List<String> texts(String field) {
        List<String> out = new ArrayList<>();
        if (node != null) {
            node.path(field).forEach(n -> out.add(n.asText()));
        }
        return List.copyOf(out);
    }

    /** An array of objects, as payloads. */
    public List<Payload> items(String field) {
        List<Payload> out = new ArrayList<>();
        if (node != null) {
            node.path(field).forEach(n -> out.add(new Payload(n)));
        }
        return List.copyOf(out);
    }

    /** Whether a field is present at all, for the rare case where absence differs from empty. */
    public boolean has(String field) {
        return node != null && node.has(field);
    }

    @Override
    public String toString() {
        return node == null ? "{}" : node.toString();
    }
}

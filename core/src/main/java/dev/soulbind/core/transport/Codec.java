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

package dev.soulbind.core.transport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.SchemaVersion;
import dev.soulbind.protocol.Wire;
import java.util.Optional;

/**
 * Turns messages into bytes and back, one way, for both transports.
 *
 * <p>One codec rather than one per transport: a refusal must read identically
 * whether it arrived over a socket or a request, and two renderings would
 * drift — the one exercised less drifting further and being noticed last.
 */
public final class Codec {

    private final ObjectMapper mapper;

    public Codec() {
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // A field the DTO does not declare is a caller sending something
                // this build does not understand. Ignoring it silently is how a
                // protocol mismatch turns into a missing side effect nobody can
                // trace; the envelope reports it instead.
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    /** The parsed shell of a request, before any of it is trusted. */
    public record ParsedRequest(int schema, String op, String id, JsonNode payload) {}

    /**
     * Parses the envelope only.
     *
     * <p>The payload stays a {@link JsonNode}: which type it should become
     * depends on the operation, and that is not known until the operation has
     * been resolved and authorised. Binding it earlier would mean parsing a body
     * on behalf of a caller that has not proved it may send one.
     */
    public Optional<ParsedRequest> parseRequest(String json) {
        try {
            JsonNode root = mapper.readTree(json);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            JsonNode schema = root.get(Wire.SCHEMA);
            if (schema == null || !schema.isInt()) {
                return Optional.empty();
            }
            JsonNode op = root.get(Wire.OP);
            if (op == null || !op.isTextual()) {
                return Optional.empty();
            }
            JsonNode id = root.get(Wire.ID);
            return Optional.of(new ParsedRequest(
                    schema.intValue(),
                    op.textValue(),
                    id != null && id.isTextual() ? id.textValue() : null,
                    root.get(Wire.PAYLOAD)));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** Binds a payload to its operation's type, or empty when it does not fit. */
    public <T> Optional<T> bind(JsonNode payload, Class<T> type) {
        try {
            // A null payload binds as an all-defaults object rather than
            // failing: "absent" and "empty" mean the same thing on this wire,
            // and a caller should not have to send `"payload": {}` to say
            // nothing.
            JsonNode node = payload == null ? mapper.createObjectNode() : payload;
            return Optional.ofNullable(mapper.treeToValue(node, type));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Renders a response, echoing the caller's correlation id unchanged. */
    public String renderResponse(String id, WireResponse response) {
        ObjectNode root = mapper.createObjectNode();
        root.put(Wire.SCHEMA, SchemaVersion.CURRENT);
        if (id != null) {
            root.put(Wire.ID, id);
        }
        root.put(Wire.OK, response.ok());

        if (response.ok()) {
            root.set(Wire.PAYLOAD, mapper.valueToTree(
                    response.payload() == null ? mapper.createObjectNode() : response.payload()));
        } else {
            ObjectNode error = root.putObject(Wire.ERROR);
            error.put(Wire.ERROR_CODE,
                    (response.code() == null ? ErrorCode.INTERNAL : response.code()).wireName());
            if (response.message() != null) {
                error.put(Wire.ERROR_MESSAGE, response.message());
            }
            if (response.capability() != null) {
                error.put(Wire.ERROR_CAPABILITY, response.capability().wireName());
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            // Renders from a tree that was just built here, so this cannot
            // normally happen -- and if it does, a hand-written refusal is still
            // better than an empty body the peer cannot interpret.
            return "{\"" + Wire.SCHEMA + "\":" + SchemaVersion.CURRENT + ",\"" + Wire.OK
                    + "\":false,\"" + Wire.ERROR + "\":{\"" + Wire.ERROR_CODE + "\":\""
                    + ErrorCode.INTERNAL.wireName() + "\"}}";
        }
    }

    /** The underlying mapper, for tests that need to read a rendered response back. */
    public ObjectMapper mapper() {
        return mapper;
    }
}

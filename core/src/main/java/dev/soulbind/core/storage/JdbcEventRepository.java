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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.soulbind.core.events.EventRecord;
import dev.soulbind.protocol.EventType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC outbox storage. */
final class JdbcEventRepository implements EventRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbc jdbc;

    JdbcEventRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public EventRecord append(EventRecord event) {
        return jdbc.write("event.append", c -> {
            // Allocated by UPDATE, not SELECT MAX -- the same mechanism as audit
            // sequences, adopted here from the start rather than after a
            // multi-writer backend produced 45 distinct sequences out of 200.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE event_seq SET next_seq = next_seq + 1 WHERE id = 1")) {
                if (ps.executeUpdate() != 1) {
                    throw new SQLException(
                            "event sequence allocator row is missing; the schema is not in the "
                                    + "state this build expects");
                }
            }

            long next;
            try (PreparedStatement ps =
                            c.prepareStatement("SELECT next_seq FROM event_seq WHERE id = 1");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                next = rs.getLong(1);
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO event_outbox (seq, type, subject_id, identity_ref, gate_name,"
                            + " payload, idempotency_key, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, next);
                ps.setString(2, event.type().wireName());
                ps.setString(3, event.subjectId());
                ps.setString(4, event.identityRef());
                ps.setString(5, event.gate());
                ps.setString(6, writePayload(event.payload()));
                ps.setString(7, event.idempotencyKey());
                ps.setLong(8, event.createdAt().toEpochMilli());
                ps.executeUpdate();
            }
            return event.withSequence(next);
        });
    }

    @Override
    public List<EventRecord> after(long position, int limit) {
        return jdbc.read("event.after", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT seq, type, subject_id, identity_ref, gate_name, payload,"
                            + " idempotency_key, created_at FROM event_outbox"
                            + " WHERE seq > ? ORDER BY seq LIMIT ?")) {
                ps.setLong(1, position);
                ps.setInt(2, Math.max(1, limit));
                try (ResultSet rs = ps.executeQuery()) {
                    List<EventRecord> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(map(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public long highestSequence() {
        return jdbc.read("event.highest", c -> {
            try (PreparedStatement ps =
                            c.prepareStatement("SELECT COALESCE(MAX(seq), 0) FROM event_outbox");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    @Override
    public long cursorOf(String connectorId) {
        return jdbc.read("event.cursor", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT position FROM event_cursor WHERE connector_id = ?")) {
                ps.setString(1, connectorId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : 0L;
                }
            }
        });
    }

    @Override
    public long acknowledge(String connectorId, long position, Instant at) {
        return jdbc.write("event.acknowledge", c -> {
            long current = 0L;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT position FROM event_cursor WHERE connector_id = ?")) {
                ps.setString(1, connectorId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        current = rs.getLong(1);
                    }
                }
            }

            // Never backwards. A cursor that could move back would let a buggy
            // acknowledgement replay the entire history -- survivable, since
            // delivery is at-least-once and keys exist, but a very different
            // amount of work arriving without warning.
            long target = Math.max(current, position);
            if (target == current && current != 0L) {
                return current;
            }

            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE event_cursor SET position = ?, updated_at = ?"
                            + " WHERE connector_id = ?")) {
                update.setLong(1, target);
                update.setLong(2, at.toEpochMilli());
                update.setString(3, connectorId);
                if (update.executeUpdate() == 1) {
                    return target;
                }
            }
            try (PreparedStatement insert = c.prepareStatement(
                    "INSERT INTO event_cursor (connector_id, position, updated_at)"
                            + " VALUES (?, ?, ?)")) {
                insert.setString(1, connectorId);
                insert.setLong(2, target);
                insert.setLong(3, at.toEpochMilli());
                insert.executeUpdate();
            }
            return target;
        });
    }

    private static EventRecord map(ResultSet rs) throws SQLException {
        long seq = rs.getLong("seq");
        String rawType = rs.getString("type");

        // An unreadable type is a row this build cannot deliver. It THROWS
        // rather than skipping: silently dropping an event is precisely the
        // failure the outbox exists to prevent, and a subscriber would have no
        // way to notice the gap.
        EventType type = EventType.fromWireName(rawType).orElseThrow(() ->
                new IllegalStateException(
                        "outbox row " + seq + " has an event type this build does not know: "
                                + rawType + ". Delivering the rest and skipping this one would "
                                + "hide it; refusing makes the version mismatch visible."));

        return new EventRecord(
                seq,
                type,
                Jdbc.nullableString(rs, "subject_id"),
                Jdbc.nullableString(rs, "identity_ref"),
                Jdbc.nullableString(rs, "gate_name"),
                readPayload(Jdbc.nullableString(rs, "payload")),
                rs.getString("idempotency_key"),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private static String writePayload(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return "{\"_payloadSerialisationFailed\":true}";
        }
    }

    private static Map<String, Object> readPayload(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("_payloadDeserialisationFailed", true);
        }
    }
}

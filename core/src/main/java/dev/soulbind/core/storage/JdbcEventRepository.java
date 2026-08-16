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

            // CHECK-THEN-ACT REVIEWED: the read above is not a check.
            //
            // The UPDATE preceding it took an exclusive row lock on the
            // allocator and has already incremented it; this SELECT reads that
            // transaction's own uncommitted value, and a concurrent allocator
            // blocks at its own UPDATE until this one commits. The read cannot
            // observe a value another writer will also claim.
            //
            // The dangerous shape is read-then-decide-then-write. This is
            // write-then-read-what-I-wrote, which is the inversion that makes it
            // safe -- and the reason the sequence is allocated by UPDATE at all.
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
            // The predicate travels WITH the update. This was SELECT-then-
            // compute-then-UPDATE, which loses under concurrency: two threads
            // read 5, one writes 10, the other writes 7, and the cursor goes
            // backwards -- redelivering events the connector already applied.
            //
            // Invisible on SQLite, whose write transactions are serialised by
            // the engine, so the interleaving cannot occur there at all. Found
            // by the check-then-act guard rather than by a test, because no test
            // on this workstation could reach it.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE event_cursor SET position = ?, updated_at = ?"
                            + " WHERE connector_id = ? AND position < ?")) {
                ps.setLong(1, position);
                ps.setLong(2, at.toEpochMilli());
                ps.setString(3, connectorId);
                ps.setLong(4, position);
                ps.executeUpdate();
            }

            // Insert if this connector has no cursor yet, tolerating a
            // concurrent insert the same way every other insert-if-absent does.
            Jdbc.ensureExists(
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO event_cursor (connector_id, position, updated_at)"
                                        + " VALUES (?, ?, ?)")) {
                            ps.setString(1, connectorId);
                            ps.setLong(2, position);
                            ps.setLong(3, at.toEpochMilli());
                            ps.executeUpdate();
                        }
                        return null;
                    },
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT 1 FROM event_cursor WHERE connector_id = ?")) {
                            ps.setString(1, connectorId);
                            try (ResultSet rs = ps.executeQuery()) {
                                return rs.next();
                            }
                        }
                    },
                    c);

            // Read back what the cursor ACTUALLY is, rather than returning what
            // this caller hoped for. Under concurrency another acknowledgement
            // may legitimately have moved it further on, and reporting a
            // position lower than the truth would have a connector re-poll
            // events it has already acknowledged.
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT position FROM event_cursor WHERE connector_id = ?")) {
                ps.setString(1, connectorId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : position;
                }
            }
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

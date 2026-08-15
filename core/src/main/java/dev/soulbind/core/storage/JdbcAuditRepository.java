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
import dev.soulbind.core.audit.AuditEntry;
import dev.soulbind.core.audit.AuditQuery;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/**
 * JDBC audit storage.
 *
 * <p>Notice what is absent: there is no update statement and no delete
 * statement anywhere in this file. That is the append-only guarantee, and it is
 * structural rather than documented -- a caller cannot reach a capability that
 * does not exist.
 */
final class JdbcAuditRepository implements AuditRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbc jdbc;

    JdbcAuditRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public AuditEntry append(AuditEntry entry) {
        return jdbc.write("audit.append", c -> {
            // The sequence is read and written inside one transaction. On SQLite
            // the single-writer executor also serialises this; on MariaDB the
            // transaction is what prevents two appenders choosing the same seq.
            long next;
            try (PreparedStatement ps =
                            c.prepareStatement("SELECT COALESCE(MAX(seq), 0) + 1 FROM audit");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                next = rs.getLong(1);
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO audit (seq, at, actor, action, subject_id, identity_ref, gate,"
                            + " detail) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    Statement.NO_GENERATED_KEYS)) {
                ps.setLong(1, next);
                ps.setLong(2, entry.at().toEpochMilli());
                ps.setString(3, entry.actor());
                ps.setString(4, entry.action());
                ps.setString(5, entry.subjectId());
                ps.setString(6, entry.identityRef());
                ps.setString(7, entry.gate());
                ps.setString(8, writeDetail(entry.detail()));
                ps.executeUpdate();
            }
            return entry.withSequence(next);
        });
    }

    @Override
    public List<AuditEntry> query(AuditQuery q) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (q.from() != null) {
            sql.append(" AND at >= ?");
            args.add(q.from().toEpochMilli());
        }
        if (q.to() != null) {
            sql.append(" AND at <= ?");
            args.add(q.to().toEpochMilli());
        }
        if (q.actor() != null) {
            sql.append(" AND actor = ?");
            args.add(q.actor());
        }
        if (q.subjectId() != null) {
            sql.append(" AND subject_id = ?");
            args.add(q.subjectId());
        }
        if (q.action() != null) {
            sql.append(" AND action = ?");
            args.add(q.action());
        }
        // Oldest first: audit is read as a narrative, and a narrative told
        // backwards is harder to follow. The limit is always present -- an
        // unbounded query from an authenticated endpoint is a way to exhaust
        // memory.
        sql.append(" ORDER BY seq ASC LIMIT ?");
        args.add(q.limit());

        return jdbc.read("audit.query", c -> {
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = 0; i < args.size(); i++) {
                    ps.setObject(i + 1, args.get(i));
                }
                return Jdbc.mapAll(ps, JdbcAuditRepository::mapRow);
            }
        });
    }

    @Override
    public long highestSequence() {
        return jdbc.read("audit.highestSequence", c -> {
            try (PreparedStatement ps =
                            c.prepareStatement("SELECT COALESCE(MAX(seq), 0) FROM audit");
                    ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private static AuditEntry mapRow(ResultSet rs) throws java.sql.SQLException {
        return new AuditEntry(
                rs.getLong("seq"),
                Instant.ofEpochMilli(rs.getLong("at")),
                rs.getString("actor"),
                rs.getString("action"),
                Jdbc.nullableString(rs, "subject_id"),
                Jdbc.nullableString(rs, "identity_ref"),
                Jdbc.nullableString(rs, "gate"),
                readDetail(Jdbc.nullableString(rs, "detail")));
    }

    private static String writeDetail(Map<String, Object> detail) {
        if (detail == null || detail.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(detail);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Detail that cannot be serialised must not silently vanish from an
            // audit entry, and must not prevent the entry being recorded either.
            // Record the failure IN the detail: the event still lands, and the
            // reader can see that something was lost rather than assuming there
            // was nothing to see.
            return "{\"_detailSerialisationFailed\":\"" + e.getClass().getSimpleName() + "\"}";
        }
    }

    private static Map<String, Object> readDetail(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("_detailUnreadable", json);
        }
    }
}

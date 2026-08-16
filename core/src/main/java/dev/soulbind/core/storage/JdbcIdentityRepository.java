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
import dev.soulbind.core.identity.Identity;
import dev.soulbind.core.identity.Subject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC identity graph storage. */
final class JdbcIdentityRepository implements IdentityRepository {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Jdbc jdbc;

    JdbcIdentityRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public Subject createSubject(Instant at) {
        String id = UUID.randomUUID().toString();
        return jdbc.write("subject.create", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO subject (id, created_at, status) VALUES (?, ?, ?)")) {
                ps.setString(1, id);
                ps.setLong(2, at.toEpochMilli());
                ps.setString(3, Subject.Status.ACTIVE.name());
                ps.executeUpdate();
            }
            return new Subject(id, at, Subject.Status.ACTIVE);
        });
    }

    @Override
    public Optional<Subject> findSubject(String subjectId) {
        return jdbc.read("subject.find", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, created_at, status FROM subject WHERE id = ?")) {
                ps.setString(1, subjectId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapSubject(rs)) : Optional.<Subject>empty();
                }
            }
        });
    }

    @Override
    public Identity bind(
            String subjectId,
            String platformKind,
            String platformId,
            String display,
            Map<String, Object> flags,
            String proofMethod,
            Instant verifiedAt,
            Instant at) {

        String id = UUID.randomUUID().toString();
        return jdbc.write("identity.bind", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO identity (id, subject_id, platform_kind, platform_id, display,"
                            + " flags, proof_method, verified_at, created_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, subjectId);
                ps.setString(3, platformKind);
                ps.setString(4, platformId);
                ps.setString(5, display);
                ps.setString(6, writeFlags(flags));
                ps.setString(7, proofMethod);
                if (verifiedAt == null) {
                    ps.setNull(8, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(8, verifiedAt.toEpochMilli());
                }
                ps.setLong(9, at.toEpochMilli());
                ps.executeUpdate();
            }
            // The uniqueness of (platform_kind, platform_id) is a database
            // constraint, not a check before the insert. A check would race, and
            // the race -- two connectors binding the same account at once -- is
            // exactly the case that matters.
            return new Identity(
                    id, subjectId, platformKind, platformId, display,
                    flags == null ? Map.of() : flags, proofMethod, verifiedAt, at);
        });
    }

    @Override
    public Optional<Identity> findIdentity(String platformKind, String platformId) {
        return jdbc.read("identity.find", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    selectIdentity() + " WHERE platform_kind = ? AND platform_id = ?")) {
                ps.setString(1, platformKind);
                ps.setString(2, platformId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapIdentity(rs)) : Optional.<Identity>empty();
                }
            }
        });
    }

    @Override
    public List<Identity> identitiesOf(String subjectId) {
        return jdbc.read("identity.ofSubject", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    selectIdentity() + " WHERE subject_id = ? ORDER BY created_at, id")) {
                ps.setString(1, subjectId);
                try (ResultSet rs = ps.executeQuery()) {
                    List<Identity> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapIdentity(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public boolean unlink(String platformKind, String platformId) {
        return jdbc.write("identity.unlink", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM identity WHERE platform_kind = ? AND platform_id = ?")) {
                ps.setString(1, platformKind);
                ps.setString(2, platformId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean markVerified(
            String platformKind, String platformId, String proofMethod, Instant verifiedAt) {
        return jdbc.write("identity.markVerified", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE identity SET proof_method = ?, verified_at = ?"
                            + " WHERE platform_kind = ? AND platform_id = ?")) {
                ps.setString(1, proofMethod);
                ps.setLong(2, verifiedAt.toEpochMilli());
                ps.setString(3, platformKind);
                ps.setString(4, platformId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public Optional<Subject> subjectOf(String platformKind, String platformId) {
        return jdbc.read("identity.subjectOf", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.id, s.created_at, s.status FROM subject s"
                            + " JOIN identity i ON i.subject_id = s.id"
                            + " WHERE i.platform_kind = ? AND i.platform_id = ?")) {
                ps.setString(1, platformKind);
                ps.setString(2, platformId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapSubject(rs)) : Optional.<Subject>empty();
                }
            }
        });
    }

    private static String selectIdentity() {
        return "SELECT id, subject_id, platform_kind, platform_id, display, flags,"
                + " proof_method, verified_at, created_at FROM identity";
    }

    private static Subject mapSubject(ResultSet rs) throws SQLException {
        return new Subject(
                rs.getString("id"),
                Instant.ofEpochMilli(rs.getLong("created_at")),
                Subject.Status.valueOf(rs.getString("status")));
    }

    private static Identity mapIdentity(ResultSet rs) throws SQLException {
        // wasNull() reports on the LAST column read, so it is captured here,
        // immediately. Reading it after the proof_method fetch below -- which is
        // how this was first written -- makes it answer a question about a
        // different column, and an unverified identity would come back verified
        // exactly when proof_method happened to be present.
        long verified = rs.getLong("verified_at");
        boolean unverified = rs.wasNull();

        return new Identity(
                rs.getString("id"),
                rs.getString("subject_id"),
                rs.getString("platform_kind"),
                rs.getString("platform_id"),
                Jdbc.nullableString(rs, "display"),
                readFlags(Jdbc.nullableString(rs, "flags")),
                Jdbc.nullableString(rs, "proof_method"),
                unverified ? null : Instant.ofEpochMilli(verified),
                Instant.ofEpochMilli(rs.getLong("created_at")));
    }

    private static String writeFlags(Map<String, Object> flags) {
        if (flags == null || flags.isEmpty()) {
            return null;
        }
        try {
            return JSON.writeValueAsString(flags);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Same rule as audit detail: flags that cannot be serialised must
            // not silently vanish, and must not stop the identity being bound.
            // The reader sees that something was lost rather than assuming
            // there was nothing to see.
            return "{\"_flagSerialisationFailed\":true}";
        }
    }

    private static Map<String, Object> readFlags(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of("_flagDeserialisationFailed", true);
        }
    }
}

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

import dev.soulbind.policy.Effect;
import dev.soulbind.policy.PolicyOverride;
import dev.soulbind.policy.Rule;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC policy storage. */
final class JdbcPolicyRepository implements PolicyRepository {

    private final Jdbc jdbc;

    JdbcPolicyRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public void gateSeen(String gateName, String registeredBy, String description) {
        jdbc.write("gate.seen", c -> {
            // Same shape and same reason as platform kinds: a SELECT-then-INSERT
            // races, and eight connectors calling `decide` at once against a
            // multi-writer backend turned that race into a 500.
            Jdbc.ensureExists(
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO gate (name, registered_by, description,"
                                        + " first_seen_at) VALUES (?, ?, ?, ?)")) {
                            ps.setString(1, gateName);
                            ps.setString(2, registeredBy);
                            ps.setString(3, description);
                            ps.setLong(4, Instant.now().toEpochMilli());
                            ps.executeUpdate();
                        }
                        return null;
                    },
                    conn -> {
                        try (PreparedStatement ps =
                                conn.prepareStatement("SELECT 1 FROM gate WHERE name = ?")) {
                            ps.setString(1, gateName);
                            try (ResultSet rs = ps.executeQuery()) {
                                return rs.next();
                            }
                        }
                    },
                    c);
            return null;
        });
    }

    @Override
    public List<String> gates() {
        return jdbc.read("gate.list", c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM gate ORDER BY name");
                    ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
                return out;
            }
        });
    }

    @Override
    public Optional<Rule> rule(String gateName) {
        return jdbc.read("rule.find", c -> {
            try (PreparedStatement ps = c.prepareStatement(selectRule() + " WHERE gate_name = ?")) {
                ps.setString(1, gateName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(mapRule(rs)) : Optional.<Rule>empty();
                }
            }
        });
    }

    @Override
    public void setRule(Rule rule, Instant at, String updatedVia) {
        jdbc.write("rule.set", c -> {
            try (PreparedStatement delete =
                    c.prepareStatement("DELETE FROM rule WHERE gate_name = ?")) {
                delete.setString(1, rule.gateName());
                delete.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO rule (gate_name, required_kinds, require_linked, grace_seconds,"
                            + " default_effect, updated_at, updated_via)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, rule.gateName());
                ps.setString(2, rule.requiredKinds().isEmpty()
                        ? null
                        : String.join(",", new java.util.TreeSet<>(rule.requiredKinds())));
                ps.setInt(3, rule.requireLinked() ? 1 : 0);
                ps.setLong(4, rule.graceSeconds());
                ps.setString(5, rule.defaultEffect().name());
                ps.setLong(6, at.toEpochMilli());
                ps.setString(7, updatedVia);
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public boolean clearRule(String gateName) {
        return jdbc.write("rule.clear", c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("DELETE FROM rule WHERE gate_name = ?")) {
                ps.setString(1, gateName);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public List<Rule> rules() {
        return jdbc.read("rule.list", c -> {
            try (PreparedStatement ps = c.prepareStatement(selectRule() + " ORDER BY gate_name");
                    ResultSet rs = ps.executeQuery()) {
                List<Rule> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapRule(rs));
                }
                return out;
            }
        });
    }

    @Override
    public List<PolicyOverride> overridesFor(String gateName) {
        return jdbc.read("override.forGate", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT gate_name, subject_id, identity_ref, effect, reason, expires_at"
                            + " FROM policy_override WHERE gate_name = ? ORDER BY created_at")) {
                ps.setString(1, gateName);
                try (ResultSet rs = ps.executeQuery()) {
                    List<PolicyOverride> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(mapOverride(rs));
                    }
                    return out;
                }
            }
        });
    }

    @Override
    public String addOverride(PolicyOverride override, Instant at, String createdBy) {
        String id = UUID.randomUUID().toString();
        return jdbc.write("override.add", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO policy_override (id, gate_name, subject_id, identity_ref,"
                            + " effect, reason, expires_at, created_at, created_by)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, override.gateName());
                ps.setString(3, override.subjectId());
                ps.setString(4, override.identityRef());
                ps.setString(5, override.effect().name());
                ps.setString(6, override.reason());
                if (override.expiresAt() == null) {
                    ps.setNull(7, java.sql.Types.BIGINT);
                } else {
                    ps.setLong(7, override.expiresAt().toEpochMilli());
                }
                ps.setLong(8, at.toEpochMilli());
                ps.setString(9, createdBy);
                ps.executeUpdate();
            }
            return id;
        });
    }

    @Override
    public boolean removeOverride(String overrideId) {
        return jdbc.write("override.remove", c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("DELETE FROM policy_override WHERE id = ?")) {
                ps.setString(1, overrideId);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int purgeExpiredOverrides(Instant before) {
        return jdbc.write("override.purge", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM policy_override WHERE expires_at IS NOT NULL"
                            + " AND expires_at < ?")) {
                ps.setLong(1, before.toEpochMilli());
                return ps.executeUpdate();
            }
        });
    }

    private static String selectRule() {
        return "SELECT gate_name, required_kinds, require_linked, grace_seconds, default_effect"
                + " FROM rule";
    }

    private static Rule mapRule(ResultSet rs) throws SQLException {
        String kinds = Jdbc.nullableString(rs, "required_kinds");
        Set<String> required = kinds == null || kinds.isBlank()
                ? Set.of()
                : new LinkedHashSet<>(Arrays.asList(kinds.split(",")));

        return new Rule(
                rs.getString("gate_name"),
                required,
                rs.getInt("require_linked") == 1,
                rs.getLong("grace_seconds"),
                // An unreadable effect becomes DENY rather than throwing. A
                // policy row this build cannot parse must not open a gate, and
                // refusing to start would take the whole deployment down over
                // one row.
                Effect.fromConfigName(rs.getString("default_effect")).orElse(Effect.DENY));
    }

    private static PolicyOverride mapOverride(ResultSet rs) throws SQLException {
        long expires = rs.getLong("expires_at");
        boolean permanent = rs.wasNull();

        return new PolicyOverride(
                rs.getString("gate_name"),
                Jdbc.nullableString(rs, "subject_id"),
                Jdbc.nullableString(rs, "identity_ref"),
                Effect.fromConfigName(rs.getString("effect")).orElse(Effect.DENY),
                rs.getString("reason"),
                permanent ? null : Instant.ofEpochMilli(expires));
    }
}

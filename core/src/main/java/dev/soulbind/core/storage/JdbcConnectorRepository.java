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

import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.protocol.Capability;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC connector registry storage. */
final class JdbcConnectorRepository implements ConnectorRepository {

    private final Jdbc jdbc;

    JdbcConnectorRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public ConnectorRecord register(String name, String credentialHash, Set<Capability> caps) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        return jdbc.write("connector.register", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO connector (id, name, credential_hash, status, registered_at)"
                            + " VALUES (?, ?, ?, ?, ?)")) {
                ps.setString(1, id);
                ps.setString(2, name);
                ps.setString(3, credentialHash);
                ps.setString(4, ConnectorRecord.Status.ACTIVE.name());
                ps.setLong(5, now.toEpochMilli());
                ps.executeUpdate();
            }
            if (caps != null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO connector_capability (connector_id, capability)"
                                + " VALUES (?, ?)")) {
                    for (Capability cap : caps) {
                        ps.setString(1, id);
                        ps.setString(2, cap.wireName());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            return new ConnectorRecord(
                    id, name, credentialHash, ConnectorRecord.Status.ACTIVE,
                    caps == null ? Set.of() : caps, now, null);
        });
    }

    @Override
    public Optional<ConnectorRecord> findByCredentialHash(String credentialHash) {
        return findBy("credential_hash", credentialHash);
    }

    @Override
    public Optional<ConnectorRecord> findByName(String name) {
        return findBy("name", name);
    }

    private Optional<ConnectorRecord> findBy(String column, String value) {
        if (value == null) {
            return Optional.empty();
        }
        // The column name is a compile-time constant from this class, never
        // caller input -- the two call sites above are the only ones. Values are
        // always bound as parameters.
        String sql = "SELECT * FROM connector WHERE " + column + " = ?";
        return jdbc.read("connector.findBy" + column, c -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, value);
                List<ConnectorRecord> found = Jdbc.mapAll(ps, rs -> mapRow(c, rs));
                return found.isEmpty() ? Optional.<ConnectorRecord>empty()
                        : Optional.of(found.get(0));
            }
        });
    }

    @Override
    public List<ConnectorRecord> list() {
        return jdbc.read("connector.list", c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("SELECT * FROM connector ORDER BY registered_at ASC")) {
                return Jdbc.mapAll(ps, rs -> mapRow(c, rs));
            }
        });
    }

    @Override
    public void touchLastSeen(String connectorId, Instant at) {
        jdbc.write("connector.touchLastSeen", c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("UPDATE connector SET last_seen_at = ? WHERE id = ?")) {
                ps.setLong(1, at.toEpochMilli());
                ps.setString(2, connectorId);
                ps.executeUpdate();
            }
            return null;
        });
    }

    private static ConnectorRecord mapRow(java.sql.Connection c, ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        Set<Capability> caps = new LinkedHashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT capability FROM connector_capability WHERE connector_id = ?")) {
            ps.setString(1, id);
            try (ResultSet crs = ps.executeQuery()) {
                while (crs.next()) {
                    // An unrecognised capability in the database is DROPPED rather
                    // than guessed at. It can only arrive from a newer version that
                    // knew a capability this one does not; treating it as some
                    // default would grant or deny a permission nobody chose.
                    Capability.fromWireName(crs.getString(1)).ifPresent(caps::add);
                }
            }
        }
        long lastSeen = rs.getLong("last_seen_at");
        boolean lastSeenNull = rs.wasNull();
        return new ConnectorRecord(
                id,
                rs.getString("name"),
                rs.getString("credential_hash"),
                ConnectorRecord.Status.valueOf(rs.getString("status")),
                caps,
                Instant.ofEpochMilli(rs.getLong("registered_at")),
                lastSeenNull ? null : Instant.ofEpochMilli(lastSeen));
    }
}

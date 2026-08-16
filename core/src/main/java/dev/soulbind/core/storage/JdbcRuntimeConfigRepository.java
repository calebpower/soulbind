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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC runtime config storage. */
final class JdbcRuntimeConfigRepository implements RuntimeConfigRepository {

    private final Jdbc jdbc;

    JdbcRuntimeConfigRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public Optional<String> get(String key) {
        return jdbc.read("runtimeConfig.get", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT config_value FROM runtime_config WHERE config_key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.ofNullable(rs.getString(1))
                            : Optional.<String>empty();
                }
            }
        });
    }

    @Override
    public Map<String, String> all() {
        return jdbc.read("runtimeConfig.all", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                            "SELECT config_key, config_value FROM runtime_config"
                                    + " ORDER BY config_key");
                    ResultSet rs = ps.executeQuery()) {
                Map<String, String> out = new LinkedHashMap<>();
                while (rs.next()) {
                    out.put(rs.getString(1), rs.getString(2));
                }
                return out;
            }
        });
    }

    @Override
    public void set(String key, String value, Instant at, String updatedVia) {
        jdbc.write("runtimeConfig.set", c -> {
            // UPDATE first, INSERT if it changed nothing. The predicate travels
            // with the write, and the insert tolerates a concurrent one -- the
            // shape the check-then-act guard requires, and the reason it exists.
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE runtime_config SET config_value = ?, updated_at = ?,"
                            + " updated_via = ? WHERE config_key = ?")) {
                update.setString(1, value);
                update.setLong(2, at.toEpochMilli());
                update.setString(3, updatedVia);
                update.setString(4, key);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }

            Jdbc.ensureExists(
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO runtime_config (config_key, config_value,"
                                        + " updated_at, updated_via) VALUES (?, ?, ?, ?)")) {
                            ps.setString(1, key);
                            ps.setString(2, value);
                            ps.setLong(3, at.toEpochMilli());
                            ps.setString(4, updatedVia);
                            ps.executeUpdate();
                        }
                        return null;
                    },
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT 1 FROM runtime_config WHERE config_key = ?")) {
                            ps.setString(1, key);
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
    public boolean clear(String key) {
        return jdbc.write("runtimeConfig.clear", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM runtime_config WHERE config_key = ?")) {
                ps.setString(1, key);
                return ps.executeUpdate() == 1;
            }
        });
    }
}

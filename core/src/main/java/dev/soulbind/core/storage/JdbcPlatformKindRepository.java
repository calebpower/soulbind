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
import java.sql.SQLException;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC platform-kind storage. Kinds are learned, never enumerated. */
final class JdbcPlatformKindRepository implements PlatformKindRepository {

    private final Jdbc jdbc;

    JdbcPlatformKindRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public void seen(String kind, String registeredBy) {
        if (kind == null || kind.isBlank()) {
            return;
        }
        jdbc.write("platformKind.seen", c -> {
            // Insert, and if that fails, ask whether the kind is known now.
            // See Jdbc.ensureExists for why this shape rather than catching a
            // uniqueness violation by its error code.
            Jdbc.ensureExists(
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO platform_kind (kind, registered_by, first_seen_at)"
                                        + " VALUES (?, ?, ?)")) {
                            ps.setString(1, kind);
                            ps.setString(2, registeredBy);
                            ps.setLong(3, Instant.now().toEpochMilli());
                            ps.executeUpdate();
                        }
                        return null;
                    },
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT 1 FROM platform_kind WHERE kind = ?")) {
                            ps.setString(1, kind);
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
    public List<String> list() {
        return jdbc.read("platformKind.list", c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("SELECT kind FROM platform_kind ORDER BY kind ASC")) {
                List<String> out = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
                return out;
            }
        });
    }

    @Override
    public boolean isKnown(String kind) {
        if (kind == null) {
            return false;
        }
        return jdbc.read("platformKind.isKnown", c -> {
            try (PreparedStatement ps =
                    c.prepareStatement("SELECT 1 FROM platform_kind WHERE kind = ?")) {
                ps.setString(1, kind);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }
}

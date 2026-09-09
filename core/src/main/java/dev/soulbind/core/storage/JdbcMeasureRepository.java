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
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC measure storage. */
final class JdbcMeasureRepository implements MeasureRepository {

    private final Jdbc jdbc;

    JdbcMeasureRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public void report(
            String identityRef,
            String name,
            long value,
            long windowSeconds,
            Instant observedAt,
            String reportedBy) {
        jdbc.write("measure.report", c -> {
            // UPDATE first, INSERT only if it changed nothing -- the shape the
            // check-then-act guard requires. Not an upsert: ON CONFLICT and ON
            // DUPLICATE KEY are spelled differently on the two backends, and a
            // backend-conditional branch is the one thing the storage seam
            // exists to keep out of this package's callers.
            try (PreparedStatement update = c.prepareStatement(
                    "UPDATE measure SET value = ?, window_seconds = ?, observed_at = ?,"
                            + " reported_by = ? WHERE identity_ref = ? AND name = ?")) {
                update.setLong(1, value);
                update.setLong(2, windowSeconds);
                update.setLong(3, observedAt.toEpochMilli());
                update.setString(4, reportedBy);
                update.setString(5, identityRef);
                update.setString(6, name);
                if (update.executeUpdate() == 1) {
                    return null;
                }
            }

            Jdbc.ensureExists(
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "INSERT INTO measure (identity_ref, name, value, window_seconds,"
                                        + " observed_at, reported_by) VALUES (?, ?, ?, ?, ?, ?)")) {
                            ps.setString(1, identityRef);
                            ps.setString(2, name);
                            ps.setLong(3, value);
                            ps.setLong(4, windowSeconds);
                            ps.setLong(5, observedAt.toEpochMilli());
                            ps.setString(6, reportedBy);
                            ps.executeUpdate();
                        }
                        return null;
                    },
                    conn -> {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT 1 FROM measure WHERE identity_ref = ? AND name = ?")) {
                            ps.setString(1, identityRef);
                            ps.setString(2, name);
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
    public List<MeasureRecord> forRefs(Collection<String> identityRefs, String name) {
        if (identityRefs.isEmpty()) {
            // `IN ()` is not valid SQL on either backend, and a subject with no
            // identities is an ordinary state rather than a caller's mistake.
            return List.of();
        }
        List<String> refs = List.copyOf(identityRefs);

        return jdbc.read("measure.forRefs", c -> {
            StringBuilder sql = new StringBuilder(
                    "SELECT identity_ref, name, value, window_seconds, observed_at, reported_by"
                            + " FROM measure WHERE identity_ref IN (");
            for (int i = 0; i < refs.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
            }
            sql.append(')');
            if (name != null) {
                sql.append(" AND name = ?");
            }
            sql.append(" ORDER BY name, identity_ref");

            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int index = 1;
                for (String ref : refs) {
                    ps.setString(index++, ref);
                }
                if (name != null) {
                    ps.setString(index, name);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    List<MeasureRecord> out = new ArrayList<>();
                    while (rs.next()) {
                        out.add(new MeasureRecord(
                                rs.getString(1),
                                rs.getString(2),
                                rs.getLong(3),
                                rs.getLong(4),
                                Instant.ofEpochMilli(rs.getLong(5)),
                                rs.getString(6)));
                    }
                    return List.copyOf(out);
                }
            }
        });
    }

    @Override
    public int forget(String identityRef) {
        return jdbc.write("measure.forget", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM measure WHERE identity_ref = ?")) {
                ps.setString(1, identityRef);
                return ps.executeUpdate();
            }
        });
    }
}

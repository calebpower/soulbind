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

import dev.soulbind.core.identity.LinkCodeRecord;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/** JDBC link code storage. */
final class JdbcLinkCodeRepository implements LinkCodeRepository {

    private final Jdbc jdbc;

    JdbcLinkCodeRepository(DataSource ds, ExecutorService writeExecutor) {
        this.jdbc = new Jdbc(ds, writeExecutor);
    }

    @Override
    public void issue(LinkCodeRecord code) {
        jdbc.write("linkCode.issue", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO link_code (code, issued_by_connector, issued_for_kind,"
                            + " issued_for_id, issued_for_display, issued_at, expires_at)"
                            + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, code.code());
                ps.setString(2, code.issuedByConnector());
                ps.setString(3, code.issuedForKind());
                ps.setString(4, code.issuedForId());
                ps.setString(5, code.issuedForDisplay());
                ps.setLong(6, code.issuedAt().toEpochMilli());
                ps.setLong(7, code.expiresAt().toEpochMilli());
                ps.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public Optional<LinkCodeRecord> find(String normalisedCode) {
        return jdbc.read("linkCode.find", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT code, issued_by_connector, issued_for_kind, issued_for_id,"
                            + " issued_for_display, issued_at, expires_at, redeemed_at,"
                            + " redeemed_by_connector FROM link_code WHERE code = ?")) {
                ps.setString(1, normalisedCode);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(map(rs))
                            : Optional.<LinkCodeRecord>empty();
                }
            }
        });
    }

    @Override
    public boolean claim(String normalisedCode, String redeemedByConnector, Instant at) {
        return jdbc.write("linkCode.claim", c -> {
            // ONE statement, carrying its own predicate. The database decides
            // the winner; nothing here reads first and writes after, because
            // that races and the race is the whole point of single use.
            //
            // Expiry is deliberately absent from the predicate. A caller
            // redeeming an expired code must be told it expired, not told it
            // was already used -- different problems, different fixes, and
            // collapsing them sends the person to ask the wrong question.
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE link_code SET redeemed_at = ?, redeemed_by_connector = ?"
                            + " WHERE code = ? AND redeemed_at IS NULL")) {
                ps.setLong(1, at.toEpochMilli());
                ps.setString(2, redeemedByConnector);
                ps.setString(3, normalisedCode);
                return ps.executeUpdate() == 1;
            }
        });
    }

    @Override
    public int purgeExpired(Instant before) {
        return jdbc.write("linkCode.purgeExpired", c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM link_code WHERE expires_at < ?")) {
                ps.setLong(1, before.toEpochMilli());
                return ps.executeUpdate();
            }
        });
    }

    private static LinkCodeRecord map(ResultSet rs) throws SQLException {
        long redeemedAt = rs.getLong("redeemed_at");
        boolean notRedeemed = rs.wasNull();

        return new LinkCodeRecord(
                rs.getString("code"),
                rs.getString("issued_by_connector"),
                rs.getString("issued_for_kind"),
                rs.getString("issued_for_id"),
                Jdbc.nullableString(rs, "issued_for_display"),
                Instant.ofEpochMilli(rs.getLong("issued_at")),
                Instant.ofEpochMilli(rs.getLong("expires_at")),
                notRedeemed ? null : Instant.ofEpochMilli(redeemedAt),
                Jdbc.nullableString(rs, "redeemed_by_connector"));
    }
}

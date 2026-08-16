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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import javax.sql.DataSource;

/**
 * JDBC plumbing shared by the repository implementations.
 *
 * <p>Package-private on purpose. This is the only place {@link java.sql} types
 * appear outside the individual repositories, and nothing above this package
 * may see one — a guard asserts it.
 */
final class Jdbc {

    /** Thrown instead of the checked {@link SQLException}, which would leak the seam. */
    static final class StorageException extends RuntimeException {
        StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    @FunctionalInterface
    interface Work<T> {
        T apply(Connection c) throws SQLException;
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }

    private final DataSource dataSource;
    private final ExecutorService writeExecutor;

    Jdbc(DataSource dataSource, ExecutorService writeExecutor) {
        this.dataSource = dataSource;
        this.writeExecutor = writeExecutor;
    }

    /**
     * Ensures a row exists, tolerating a concurrent insert of the same key.
     *
     * <p>A SELECT-then-INSERT races: two callers see "not present" and both
     * insert, and on a multi-writer backend one gets a constraint violation
     * that escapes as a 5xx. That defect was invisible for two phases, because
     * the single-writer backend serialises every write.
     *
     * <p>The obvious fix — catch the exception and check whether it is a
     * uniqueness violation — does not work portably. One driver reports
     * SQLState class 23; the other reports {@code null} and puts the detail in
     * a vendor result code. Matching on either would put dialect knowledge in
     * the seam.
     *
     * <p>So this asserts the OUTCOME instead of classifying the error: attempt
     * the insert, and if it fails, ask whether the row is there now. If it is,
     * the thing the caller wanted is true, whoever made it true — which is
     * exactly what "ensure it exists" means. If it is not, the failure was
     * something else and is rethrown.
     *
     * @param insert the insert to attempt
     * @param exists whether the row is present
     */
    static void ensureExists(Work<Void> insert, Work<Boolean> exists, Connection c)
            throws SQLException {
        try {
            insert.apply(c);
        } catch (SQLException e) {
            if (!Boolean.TRUE.equals(exists.apply(c))) {
                throw e;
            }
            // Somebody else got there first. That is the desired end state.
        }
    }

    /** Runs read-only work. Never serialised: concurrent readers are fine on both backends. */
    <T> T read(String what, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            return work.apply(c);
        } catch (SQLException e) {
            throw new StorageException(what + " failed", e);
        }
    }

    /**
     * Runs work that writes.
     *
     * <p>On a backend with a single-writer executor, hops onto it. That is the
     * entire mechanism by which SQLite's one-writer reality is handled without
     * any caller knowing about it.
     */
    <T> T write(String what, Work<T> work) {
        if (writeExecutor == null) {
            return inTransaction(what, work);
        }
        try {
            return writeExecutor.submit(() -> inTransaction(what, work)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StorageException(what + " interrupted", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new StorageException(what + " failed", cause);
        }
    }

    private <T> T inTransaction(String what, Work<T> work) {
        try (Connection c = dataSource.getConnection()) {
            boolean previousAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.apply(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    // Report the ORIGINAL failure, with the rollback failure
                    // attached. Losing the cause behind a rollback error is how a
                    // constraint violation gets misread as a connectivity problem.
                    e.addSuppressed(rollbackFailure);
                }
                if (e instanceof RuntimeException re) {
                    throw re;
                }
                throw new StorageException(what + " failed", e);
            } finally {
                c.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException e) {
            throw new StorageException(what + " could not obtain a connection", e);
        }
    }

    /** Maps every row of a query. */
    static <T> List<T> mapAll(PreparedStatement ps, RowMapper<T> mapper) throws SQLException {
        List<T> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(mapper.map(rs));
            }
        }
        return out;
    }

    /** Nullable string read, so a NULL column does not become the string "null". */
    static String nullableString(ResultSet rs, String column) throws SQLException {
        String v = rs.getString(column);
        return rs.wasNull() ? null : v;
    }
}

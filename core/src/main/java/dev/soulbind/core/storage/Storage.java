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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/**
 * The storage seam's entry point: opens a backend and hands out repositories.
 *
 * <p>Callers name a {@link Backend} once, here, and never again. Above this
 * class nothing knows which database is in use — no SQL string, no JDBC type,
 * and no backend-conditional branch escapes this package, which is what a guard
 * asserts.
 */
public final class Storage implements AutoCloseable {

    private final Backend backend;
    private final HikariDataSource dataSource;
    private final ExecutorService writeExecutor;

    private final AuditRepository audit;
    private final ConnectorRepository connectors;
    private final PlatformKindRepository platformKinds;

    private Storage(Backend backend, HikariDataSource dataSource, ExecutorService writeExecutor) {
        this.backend = backend;
        this.dataSource = dataSource;
        this.writeExecutor = writeExecutor;
        this.audit = new JdbcAuditRepository(dataSource, writeExecutor);
        this.connectors = new JdbcConnectorRepository(dataSource, writeExecutor);
        this.platformKinds = new JdbcPlatformKindRepository(dataSource, writeExecutor);
    }

    /**
     * Opens a backend, runs migrations, and returns a ready store.
     *
     * <p>Migrations run on every boot and are idempotent: a second boot rebuilds
     * nothing. That is asserted against both backends, because "it worked on the
     * one I tested" is how a migration bug reaches production.
     */
    public static Storage open(Backend backend, String jdbcUrl, String user, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        if (user != null) {
            cfg.setUsername(user);
        }
        if (password != null) {
            cfg.setPassword(password);
        }
        cfg.setPoolName("soulbind-" + backend.configName());

        ExecutorService writeExecutor;
        switch (backend) {
            case SQLITE -> {
                // SQLite permits exactly one writer. A pool of them does not make
                // that untrue; it makes it intermittent, surfacing as SQLITE_BUSY
                // under load rather than as a clear constraint. So: one physical
                // connection, and every write serialised through one thread.
                //
                // This is handled HERE rather than left to callers, because a
                // caller who does not know the backend cannot know to serialise,
                // and the whole point of the seam is that they should not have to.
                cfg.setMaximumPoolSize(1);
                cfg.addDataSourceProperty("journal_mode", "WAL");
                cfg.addDataSourceProperty("busy_timeout", "5000");
                cfg.addDataSourceProperty("foreign_keys", "true");
                writeExecutor = Executors.newSingleThreadExecutor(
                        r -> {
                            Thread t = new Thread(r, "soulbind-sqlite-writer");
                            t.setDaemon(true);
                            return t;
                        });
            }
            case MARIADB -> {
                cfg.setMaximumPoolSize(10);
                // No serialising executor: the backend handles concurrent writers,
                // and forcing them through one thread would throw that away.
                writeExecutor = null;
            }
            default -> throw new IllegalStateException("unhandled backend: " + backend);
        }

        HikariDataSource ds = new HikariDataSource(cfg);
        migrate(backend, ds);
        return new Storage(backend, ds, writeExecutor);
    }

    private static void migrate(Backend backend, DataSource ds) {
        // Common DDL first, then the per-dialect directory. The per-dialect
        // directories are deliberately near-empty: a difference there is a
        // dialect genuinely forcing one, not a convenience.
        Flyway.configure()
                .dataSource(ds)
                .locations(
                        "classpath:db/migration/common",
                        "classpath:db/migration/" + backend.configName())
                .baselineOnMigrate(true)
                .load()
                .migrate();
    }

    public Backend backend() {
        return backend;
    }

    public AuditRepository audit() {
        return audit;
    }

    public ConnectorRepository connectors() {
        return connectors;
    }

    public PlatformKindRepository platformKinds() {
        return platformKinds;
    }

    @Override
    public void close() {
        if (writeExecutor != null) {
            writeExecutor.shutdown();
        }
        dataSource.close();
    }
}

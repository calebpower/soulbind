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
    private final IdentityRepository identities;
    private final LinkCodeRepository linkCodes;
    private final PolicyRepository policy;
    private final EventRepository events;
    private final RuntimeConfigRepository runtimeConfig;

    private Storage(Backend backend, HikariDataSource dataSource, ExecutorService writeExecutor) {
        this.backend = backend;
        this.dataSource = dataSource;
        this.writeExecutor = writeExecutor;
        this.audit = new JdbcAuditRepository(dataSource, writeExecutor);
        this.connectors = new JdbcConnectorRepository(dataSource, writeExecutor);
        this.platformKinds = new JdbcPlatformKindRepository(dataSource, writeExecutor);
        this.identities = new JdbcIdentityRepository(dataSource, writeExecutor);
        this.linkCodes = new JdbcLinkCodeRepository(dataSource, writeExecutor);
        this.policy = new JdbcPolicyRepository(dataSource, writeExecutor);
        this.events = new JdbcEventRepository(dataSource, writeExecutor);
        this.runtimeConfig = new JdbcRuntimeConfigRepository(dataSource, writeExecutor);
    }

    /**
     * Opens a backend, runs migrations, and returns a ready store.
     *
     * <p>Migrations run on every boot and are idempotent: a second boot rebuilds
     * nothing. That is asserted against both backends, because "it worked on the
     * one I tested" is how a migration bug reaches production.
     */
    public static Storage open(Backend backend, String jdbcUrl, String user, String password) {
        return open(backend, jdbcUrl, user, password, true);
    }

    /**
     * Opens a store with write serialisation DISABLED. Test scaffolding.
     *
     * <p>The single-writer executor exists because SQLite permits one writer. It
     * is a <b>deployment</b> necessity, not a correctness mechanism — and the
     * distinction matters, because for two phases it silently supplied
     * correctness the repositories had not earned.
     *
     * <p>Three real defects hid behind it: audit sequence assignment that raced,
     * and two insert-if-absent paths that were SELECT-then-INSERT. Each was
     * invisible on SQLite, where the executor serialised every write, and each
     * surfaced the first time a multi-writer backend ran. The second pair
     * reached a live 500 before anything noticed.
     *
     * <p>So the invariant is stated and tested rather than hoped for:
     * <b>repository correctness must not depend on the executor.</b> Opening
     * without it — with a real connection pool, WAL and a busy timeout — lets
     * writes genuinely interleave on SQLite, so a check-then-act fails on the
     * workstation instead of waiting for a session.
     *
     * <p>Not public, and not a configuration option. A deployment running SQLite
     * without serialisation would meet SQLITE_BUSY under load, which is exactly
     * the intermittent failure the executor exists to prevent. This is for tests
     * that need the races to be reachable.
     */
    static Storage openWithoutWriteSerialisation(
            Backend backend, String jdbcUrl, String user, String password) {
        return open(backend, jdbcUrl, user, password, false);
    }

    private static Storage open(
            Backend backend,
            String jdbcUrl,
            String user,
            String password,
            boolean serialiseWrites) {
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
                cfg.setMaximumPoolSize(serialiseWrites ? 1 : 4);
                cfg.addDataSourceProperty("journal_mode", "WAL");
                cfg.addDataSourceProperty("busy_timeout", "5000");
                cfg.addDataSourceProperty("foreign_keys", "true");
                writeExecutor = serialiseWrites
                        ? Executors.newSingleThreadExecutor(
                                r -> {
                                    Thread t = new Thread(r, "soulbind-sqlite-writer");
                                    t.setDaemon(true);
                                    return t;
                                })
                        : null;
            }
            case MARIADB -> {
                cfg.setMaximumPoolSize(10);
                // The connection charset, said out loud.
                //
                // Connector/J 3.5 happens to issue `SET NAMES utf8mb4` of its own
                // accord today, so in practice this line changes nothing yet. It
                // is here because "the driver happens to" is not a property this
                // schema can afford to inherit: the columns are utf8mb4 (see the
                // dialect migration), and a connection negotiated down to the
                // server's default -- latin1 on plenty of long-lived
                // installations -- would mangle four-byte text on the way in
                // while every column definition still looked correct.
                //
                // Only utf8mb4 collations are accepted by this driver, which is
                // why the value is not configurable: there is no valid
                // alternative, and offering the choice would only let a
                // deployment get it wrong.
                cfg.addDataSourceProperty("connectionCollation", "utf8mb4_unicode_ci");
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

    /**
     * The pool the repositories actually use.
     *
     * <p>Package-private and read-only, for tests that must assert about the
     * connection rather than about a connection. {@code SchemaCharsetTest} is
     * the case: opening its own {@code DriverManager} connection would prove
     * that <em>a</em> connection to that server speaks utf8mb4, which is not the
     * claim -- the claim is about the one core writes through.
     */
    javax.sql.DataSource dataSource() {
        return dataSource;
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

    public IdentityRepository identities() {
        return identities;
    }

    public LinkCodeRepository linkCodes() {
        return linkCodes;
    }

    public PolicyRepository policy() {
        return policy;
    }

    public EventRepository events() {
        return events;
    }

    public RuntimeConfigRepository runtimeConfig() {
        return runtimeConfig;
    }

    @Override
    public void close() {
        if (writeExecutor != null) {
            writeExecutor.shutdown();
        }
        dataSource.close();
    }
}

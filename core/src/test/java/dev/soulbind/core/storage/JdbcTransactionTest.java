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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The transaction and retry machinery, driven directly.
 *
 * <p>Every one of these behaviours is load-bearing on a multi-writer backend and
 * <b>dead code on SQLite</b>, which serialises write transactions and cannot
 * produce a serialisation failure at all. That is exactly why they went
 * untested: the workstation runs SQLite, so the storage suite exercised the
 * retry loop's happy path and nothing else. Mutation could delete the commit,
 * the rollback, or the retryable check and no test noticed.
 *
 * <p><b>A proxied {@link Connection} rather than a database.</b> The question
 * here is not what the engine does — it is what this class does when the engine
 * says no, and a real engine that says no on demand is harder to arrange than
 * one that is not there at all.
 */
class JdbcTransactionTest {

    /** Records what was called on the connection, and can be told to fail. */
    private static final class Recording {
        private final List<String> calls = new ArrayList<>();
        private Supplier<SQLException> failOnCommit;
        private SQLException failOnRollback;

        Connection connection() {
            InvocationHandler handler = (proxy, method, args) -> {
                calls.add(method.getName());
                switch (method.getName()) {
                    case "getAutoCommit" -> {
                        return Boolean.TRUE;
                    }
                    case "commit" -> {
                        if (failOnCommit != null) {
                            throw failOnCommit.get();
                        }
                        return null;
                    }
                    case "rollback" -> {
                        if (failOnRollback != null) {
                            throw failOnRollback;
                        }
                        return null;
                    }
                    case "close", "setAutoCommit" -> {
                        return null;
                    }
                    case "toString" -> {
                        return "recording-connection";
                    }
                    default -> {
                        return null;
                    }
                }
            };
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                    handler);
        }

        DataSource dataSource() {
            Connection connection = connection();
            InvocationHandler handler = (proxy, method, args) ->
                    "getConnection".equals(method.getName()) ? connection : null;
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                    handler);
        }

        long count(String call) {
            return calls.stream().filter(call::equals).count();
        }
    }

    private static Jdbc jdbc(Recording recording) {
        // No write executor: the retry loop runs on the calling thread, which is
        // the multi-writer configuration and the one where any of this matters.
        return new Jdbc(recording.dataSource(), null);
    }

    @Test
    @DisplayName("work that succeeds is committed, once, and never rolled back")
    void successCommits() {
        Recording recording = new Recording();

        assertEquals("done", jdbc(recording).write("t", c -> "done"));

        assertEquals(1, recording.count("commit"),
                "the work succeeded and was not committed, so every write is discarded when"
                        + " the connection closes: " + recording.calls);
        assertEquals(0, recording.count("rollback"), recording.calls::toString);
    }

    @Test
    @DisplayName("autocommit is turned off for the transaction and restored afterwards")
    void autoCommitIsRestored() {
        // Both halves. Without the first, every statement commits on its own and
        // the rollback below has nothing to undo. Without the second, a pooled
        // connection goes back with autocommit off and the NEXT caller's writes
        // silently never commit -- a fault that appears far from its cause.
        Recording recording = new Recording();

        jdbc(recording).write("t", c -> "done");

        assertEquals(2, recording.count("setAutoCommit"),
                "autocommit was not set and restored around the transaction: "
                        + recording.calls);
        assertTrue(recording.calls.indexOf("setAutoCommit") < recording.calls.indexOf("commit"),
                recording.calls::toString);
    }

    @Test
    @DisplayName("work that throws is rolled back, and the original failure is what escapes")
    void failureRollsBack() {
        Recording recording = new Recording();
        IllegalStateException thrown = new IllegalStateException("the work said no");

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class,
                () -> jdbc(recording).write("t", c -> {
                    throw thrown;
                }));

        assertSame(thrown, escaped,
                "the caller's own exception was replaced, so a constraint violation reads as"
                        + " a storage failure and the message that would explain it is gone");
        assertEquals(1, recording.count("rollback"),
                "failed work was not rolled back: " + recording.calls);
        assertEquals(0, recording.count("commit"), recording.calls::toString);
    }

    @Test
    @DisplayName("a rollback that itself fails is attached, not substituted")
    void rollbackFailureIsSuppressed() {
        // Losing the cause behind a rollback error is how a constraint violation
        // gets misread as a connectivity problem.
        Recording recording = new Recording();
        recording.failOnRollback = new SQLException("connection already gone");
        IllegalStateException thrown = new IllegalStateException("the work said no");

        IllegalStateException escaped = assertThrows(
                IllegalStateException.class,
                () -> jdbc(recording).write("t", c -> {
                    throw thrown;
                }));

        assertSame(thrown, escaped);
        assertEquals(1, escaped.getSuppressed().length,
                "the rollback failure was dropped, so nothing anywhere records that the"
                        + " transaction could not be undone");
    }

    @Test
    @DisplayName("a serialisation failure is retried, and the retry's success is returned")
    void deadlockIsRetried() {
        // The behaviour a real multi-writer backend needs and SQLite can never
        // exercise: twelve threads acknowledging one cursor deadlocked, and the
        // caller saw the exception.
        Recording recording = new Recording();
        int[] attempts = {0};

        String result = jdbc(recording).write("t", c -> {
            if (++attempts[0] < 3) {
                throw new Jdbc.StorageException(
                        "deadlocked", new SQLTransactionRollbackException("40001"));
            }
            return "eventually";
        });

        assertEquals("eventually", result);
        assertEquals(3, attempts[0], "the work was not retried after a serialisation failure");
        assertEquals(2, recording.count("rollback"), recording.calls::toString);
    }

    @Test
    @DisplayName("a failure that is NOT a serialisation failure is thrown at once")
    void ordinaryFailureIsNotRetried() {
        // Retrying a constraint violation four times just breaks it four times,
        // slowly, and buries the message under a deadlock report.
        Recording recording = new Recording();
        int[] attempts = {0};

        assertThrows(
                Jdbc.StorageException.class,
                () -> jdbc(recording).write("t", c -> {
                    attempts[0]++;
                    throw new Jdbc.StorageException("constraint", new SQLException("unique"));
                }));

        assertEquals(1, attempts[0],
                "an ordinary failure was retried, which turns one clear error into four and a"
                        + " misleading summary");
    }

    @Test
    @DisplayName("a serialisation failure nested behind other causes is still recognised")
    void retryableIsFoundThroughTheCauseChain() {
        // The engine's exception arrives wrapped. Checking only the top of the
        // chain would leave the retry dead in exactly the case it was written
        // for.
        Recording recording = new Recording();
        int[] attempts = {0};

        String result = jdbc(recording).write("t", c -> {
            if (++attempts[0] < 2) {
                throw new Jdbc.StorageException(
                        "wrapped",
                        new SQLException(
                                "outer", new SQLTransactionRollbackException("40001")));
            }
            return "ok";
        });

        assertEquals("ok", result);
        assertEquals(2, attempts[0]);
    }

    @Test
    @DisplayName("a deadlock that never clears gives up, rather than retrying forever")
    void persistentDeadlockGivesUp() {
        // Bounded. An unbounded retry turns a persistent deadlock into a hang,
        // and a hung write is worse to diagnose than a failed one.
        Recording recording = new Recording();
        int[] attempts = {0};

        Jdbc.StorageException thrown = assertThrows(
                Jdbc.StorageException.class,
                () -> jdbc(recording).write("t", c -> {
                    attempts[0]++;
                    throw new Jdbc.StorageException(
                            "deadlocked", new SQLTransactionRollbackException("40001"));
                }));

        assertEquals(4, attempts[0],
                "the retry bound moved; four attempts is what the message below promises");
        assertTrue(thrown.getMessage().contains("4 times"),
                "the failure does not say how many times it tried: " + thrown.getMessage());
    }
}

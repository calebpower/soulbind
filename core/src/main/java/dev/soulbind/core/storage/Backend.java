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

import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Which relational backend is in use.
 *
 * <p>Two exist, and both are first-class: every storage test runs against both
 * by parameterisation. A test naming one backend is the exception that needs a
 * stated reason — the single-writer scenarios are the expected such case,
 * because there the backend's concurrency model is the thing under test.
 *
 * <p>This enum is the <em>only</em> place backend-conditional logic is
 * permitted to be visible outside the two implementations, and even here it is
 * a selector rather than a branch. A guard asserts that no SQL string and no
 * JDBC type escapes this package.
 */
public enum Backend {

    /**
     * Embedded, single-file, single-writer.
     *
     * <p>Chosen as a real deployment option rather than a test fixture: a small
     * community running one connector should not have to operate a database
     * server. The single-writer reality is handled inside the implementation —
     * WAL mode, a busy timeout, and a serialising executor — rather than left
     * for callers to discover under load.
     */
    /** A file on this machine. Needs a writable directory; needs no credentials. */
    SQLITE("sqlite", true, false),

    /** Networked, multi-writer. */
    /** A server reached over the network. Needs credentials; stores nothing here. */
    MARIADB("mariadb", false, true);

    private final String configName;

    private final boolean localFiles;

    private final boolean credentials;

    Backend(String configName, boolean localFiles, boolean credentials) {
        this.configName = configName;
        this.localFiles = localFiles;
        this.credentials = credentials;
    }

    /**
     * Whether this backend needs a username.
     *
     * <p>Here rather than at the call site because the caller asking is the
     * doctor, and a doctor that says "SQLite ignores storage.user" has learned
     * which database is in use -- which is the thing the storage seam exists to
     * prevent, and the seam guard caught it doing exactly that.
     */
    public boolean usesCredentials() {
        return credentials;
    }

    /**
     * The directory this backend needs to be able to write, if any.
     *
     * <p>Empty for a backend that keeps nothing on this machine, and empty for
     * a URL that names no file -- an in-memory database, say. The parsing lives
     * here for the same reason as {@link #usesCredentials()}: the shape of a
     * connection URL is a fact about a particular database, and outside this
     * package nothing may know it.
     *
     * @param url the configured connection URL
     * @return the directory that must exist and be writable
     */
    public Optional<Path> writableDirectory(String url) {
        if (!localFiles || url == null) {
            return Optional.empty();
        }
        String prefix = "jdbc:" + configName + ":";
        if (!url.startsWith(prefix)) {
            return Optional.empty();
        }
        String path = url.substring(prefix.length());
        if (path.isBlank() || path.startsWith(":")) {
            return Optional.empty();
        }
        return Optional.ofNullable(Path.of(path).toAbsolutePath().getParent());
    }

    /**
     * Whether the configured location is relative, and so resolves against
     * whatever the working directory happens to be.
     *
     * @param url the configured connection URL
     * @return true when this backend keeps a local file at a relative path
     */
    public boolean isRelativeLocation(String url) {
        if (!localFiles || url == null) {
            return false;
        }
        String prefix = "jdbc:" + configName + ":";
        if (!url.startsWith(prefix)) {
            return false;
        }
        String path = url.substring(prefix.length());
        return !path.isBlank() && !path.startsWith(":") && !Path.of(path).isAbsolute();
    }

    /** The name that appears in configuration and in the environment override. */
    public String configName() {
        return configName;
    }

    /** Parses a configured name; empty rather than a default, so a typo is loud. */
    public static Optional<Backend> fromConfigName(String s) {
        if (s == null) {
            return Optional.empty();
        }
        String needle = s.strip().toLowerCase(Locale.ROOT);
        for (Backend b : values()) {
            if (b.configName.equals(needle)) {
                return Optional.of(b);
            }
        }
        return Optional.empty();
    }
}

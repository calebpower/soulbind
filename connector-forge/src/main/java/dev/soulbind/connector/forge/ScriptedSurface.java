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
package dev.soulbind.connector.forge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A forge that exists only in memory.
 *
 * <p>In {@code src/main} rather than {@code src/test} deliberately, as the chat
 * connector's scripted surface is: the battery drives the real connector from
 * another process, and a double that lived in the test source set could not be
 * reached from there.
 *
 * <p>It records what was asked of it as well as answering, because several of
 * the properties worth asserting are about calls that must NOT happen — a
 * denied gate must create nothing, and an absence is only observable if
 * somebody counted.
 */
public final class ScriptedSurface implements ForgeSurface {

    private final Map<String, Boolean> accounts = new LinkedHashMap<>();
    private final List<String> created = new ArrayList<>();
    private boolean reachable = true;
    private int presenceCalls;
    private int createCalls;
    private int setActiveCalls;

    /** Pretends the forge is unreachable from now on. */
    public ScriptedSurface goDown() {
        reachable = false;
        return this;
    }

    public ScriptedSurface comeBack() {
        reachable = true;
        return this;
    }

    /** Seeds an account that already exists, active. */
    public ScriptedSurface withAccount(String username) {
        accounts.put(username, true);
        return this;
    }

    @Override
    public Presence presence(String username) {
        presenceCalls++;
        if (!reachable) {
            return Presence.UNKNOWN;
        }
        return accounts.containsKey(username) ? Presence.PRESENT : Presence.ABSENT;
    }

    @Override
    public Creation create(Account account) {
        createCalls++;
        if (!reachable) {
            return Creation.FAILED;
        }
        if (accounts.containsKey(account.username())) {
            return Creation.ALREADY_EXISTS;
        }
        accounts.put(account.username(), true);
        created.add(account.username());
        return Creation.CREATED;
    }

    @Override
    public boolean setActive(String username, boolean active) {
        setActiveCalls++;
        if (!reachable || !accounts.containsKey(username)) {
            return false;
        }
        accounts.put(username, active);
        return true;
    }

    /** Usernames created, in order. */
    public List<String> created() {
        return List.copyOf(created);
    }

    public boolean isActive(String username) {
        return Boolean.TRUE.equals(accounts.get(username));
    }

    public boolean exists(String username) {
        return accounts.containsKey(username);
    }

    public int presenceCalls() {
        return presenceCalls;
    }

    public int createCalls() {
        return createCalls;
    }

    public int setActiveCalls() {
        return setActiveCalls;
    }
}

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

package dev.soulbind.config;

import java.util.List;

/**
 * Every problem found while loading a configuration, reported together.
 *
 * <p>Deliberately not fail-on-first. An operator fixing a configuration one
 * error at a time, with a service restart between each, is an operator who
 * stops reading the message and starts guessing.
 */
public final class ConfigException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<String> problems;

    public ConfigException(String source, List<String> problems) {
        super(buildMessage(source, problems));
        this.problems = List.copyOf(problems);
    }

    private static String buildMessage(String source, List<String> problems) {
        StringBuilder sb = new StringBuilder(problems.size() == 1
                ? "configuration problem in " + source + ":"
                : problems.size() + " configuration problems in " + source + ":");
        for (String problem : problems) {
            sb.append("\n  - ").append(problem);
        }
        return sb.toString();
    }

    /** The problems, individually, for a caller that wants to render them itself. */
    public List<String> problems() {
        return problems;
    }
}

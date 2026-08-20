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

package dev.soulbind.sim;

/**
 * One thing an actor does, fully determined so a trace can be read and replayed.
 *
 * @param kind what is being attempted
 * @param actor who is attempting it
 * @param subject the identity ref or code the action is about; may be null
 * @param detail a second parameter — a gate name, a target ref — or null
 */
public record Action(ActionKind kind, Actor actor, String subject, String detail) {

    /** One line, as it appears in a trace. */
    @Override
    public String toString() {
        StringBuilder line = new StringBuilder(actor.name()).append(' ').append(kind);
        if (subject != null) {
            line.append(' ').append(subject);
        }
        if (detail != null) {
            line.append(" -> ").append(detail);
        }
        if (kind.isNemesis()) {
            line.append("   [nemesis]");
        }
        return line.toString();
    }
}

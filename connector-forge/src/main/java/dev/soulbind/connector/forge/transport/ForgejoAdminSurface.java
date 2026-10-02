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
package dev.soulbind.connector.forge.transport;

import dev.soulbind.connector.forge.ForgeSurface;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.BiConsumer;

/**
 * The forge, over its own administrative interface.
 *
 * <p>The production half of {@link ForgeSurface}. Everything the connector
 * decides happens above this class and is tested against the scripted surface
 * instead; what is here is three requests and the mapping from their status
 * codes to the three answers the seam defines.
 *
 * <p>Those mappings come from the host's published schema rather than from
 * guesswork: creation answers <b>201</b>, a validation failure <b>422</b>, a
 * patch <b>200</b>, and a lookup <b>200</b> or <b>404</b>.
 */
public final class ForgejoAdminSurface implements ForgeSurface {

    private final HttpClient http;
    private final String base;
    private final String token;
    private final Duration timeout;
    private final BiConsumer<String, Throwable> log;

    public ForgejoAdminSurface(
            String baseUrl, String token, Duration timeout, BiConsumer<String, Throwable> log) {
        this.base = baseUrl.endsWith("/")
                ? baseUrl.substring(0, baseUrl.length() - 1) + "/api/v1"
                : baseUrl + "/api/v1";
        this.token = token;
        this.timeout = timeout;
        this.log = log;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Presence presence(String username) {
        HttpResponse<String> response = send(
                request("/users/" + encode(username)).GET(), "look up '" + username + "'");
        if (response == null) {
            return Presence.UNKNOWN;
        }
        return switch (response.statusCode()) {
            case 200 -> Presence.PRESENT;
            case 404 -> Presence.ABSENT;
            // Anything else is the forge declining to answer, which is not the
            // same as answering no. Saying ABSENT here is how an account lands
            // on top of one that already exists.
            default -> {
                log.accept("the forge answered " + response.statusCode()
                        + " when asked about '" + username + "'; treating that as unknown", null);
                yield Presence.UNKNOWN;
            }
        };
    }

    @Override
    public Creation create(Account account) {
        String body = json(
                "username", account.username(),
                "email", account.email(),
                "password", account.password(),
                "must_change_password", "false");

        HttpResponse<String> response = send(
                request("/admin/users")
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)),
                "create '" + account.username() + "'");
        if (response == null) {
            return Creation.FAILED;
        }
        if (response.statusCode() == 201) {
            return Creation.CREATED;
        }
        if (response.statusCode() == 422) {
            // 422 covers a taken name AND a rejected email, and the difference
            // matters: one means somebody else owns this name and the other
            // means the person mistyped. Told apart by asking, rather than by
            // reading the host's prose, which is not a contract.
            if (presence(account.username()) == Presence.PRESENT) {
                return Creation.ALREADY_EXISTS;
            }
            log.accept("the forge refused to create '" + account.username()
                    + "' and the name is still free, so something in the request was not"
                    + " acceptable to it", null);
            return Creation.FAILED;
        }
        log.accept("the forge answered " + response.statusCode() + " when creating '"
                + account.username() + "'", null);
        return Creation.FAILED;
    }

    @Override
    public boolean setActive(String username, boolean active) {
        HttpResponse<String> response = send(
                request("/admin/users/" + encode(username))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(
                                json("active", String.valueOf(active)),
                                StandardCharsets.UTF_8)),
                (active ? "activate '" : "deactivate '") + username + "'");
        if (response == null) {
            return false;
        }
        if (response.statusCode() != 200) {
            // The status is the one fact that points at the host rather than at
            // this connector. Without it, a refusal to deactivate somebody is
            // visible only as the effector declining to acknowledge an event,
            // which says nothing about why.
            log.accept("the forge answered " + response.statusCode() + " when asked to "
                    + (active ? "activate '" : "deactivate '") + username + "'", null);
            return false;
        }
        return true;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path))
                .timeout(timeout)
                // The scheme the host publishes. Never logged: this token can
                // create and deactivate any account on the forge.
                .header("Authorization", "token " + token)
                .header("Accept", "application/json");
    }

    /** Null when the forge could not be reached at all, which callers treat as unknown. */
    private HttpResponse<String> send(HttpRequest.Builder builder, String what) {
        try {
            return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            log.accept("could not reach the forge to " + what, e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.accept("interrupted while trying to " + what, e);
            return null;
        }
    }

    /**
     * A JSON object from alternating keys and values.
     *
     * <p>Hand-rolled rather than pulled from a mapper: four string fields and
     * one boolean do not justify holding a JSON library on this seam, and the
     * one thing that must be right -- that a value cannot break out of its
     * string -- is a six-character escape, asserted directly.
     *
     * <p>A value of exactly {@code true} or {@code false} is written as a JSON
     * boolean, which is the only non-string the host's schema wants here.
     */
    static String json(String... keysAndValues) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < keysAndValues.length; i += 2) {
            if (i > 0) {
                out.append(',');
            }
            String value = keysAndValues[i + 1];
            out.append('"').append(escape(keysAndValues[i])).append("\":");
            if ("true".equals(value) || "false".equals(value)) {
                out.append(value);
            } else {
                out.append('"').append(escape(value)).append('"');
            }
        }
        return out.append('}').toString();
    }

    static String escape(String raw) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static String encode(String segment) {
        // A username is a path segment here, and the form already refuses
        // whitespace and slashes -- but this class is also callable from the
        // effector with whatever core sent, so it encodes rather than trusting.
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}

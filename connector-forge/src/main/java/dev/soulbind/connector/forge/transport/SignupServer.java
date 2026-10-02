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

import dev.soulbind.connector.forge.Registration;
import dev.soulbind.connector.forge.SignupForm;
import dev.soulbind.connector.forge.SignupPage;
import dev.soulbind.connector.forge.SignupResponse;
import io.javalin.Javalin;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * The listener the signup form arrives on.
 *
 * <p>Routing and request decoding, and deliberately nothing else. The form's
 * checks, the outcome-to-status mapping and the page's escaping are all pure
 * and live outside this package, so what is here is the part that genuinely
 * cannot be expressed without naming a transport type — which is the bargain
 * the transport seam's exemption is for.
 *
 * <p>Loopback by default, with a reverse proxy in front: the proxy already
 * terminates TLS for the name people type, and a second listener on a public
 * interface would be a second thing to get right.
 */
public final class SignupServer implements AutoCloseable {

    private final Registration registration;
    private final String path;
    private final BiConsumer<String, Throwable> log;
    private Javalin app;

    public SignupServer(
            Registration registration, String path, BiConsumer<String, Throwable> log) {
        this.registration = registration;
        this.path = path;
        this.log = log;
    }

    /** Binds and begins serving. Returns itself so a caller can keep the handle. */
    public SignupServer start(String bind, int port) {
        app = Javalin.create(config -> {
            config.showJavalinBanner = false;
            config.http.defaultContentType = "text/html; charset=utf-8";
        });

        app.get(path, ctx -> ctx.html(SignupPage.form(path, List.of(), Map.of())));
        app.post(path, ctx -> {
            Map<String, String> fields = new LinkedHashMap<>();
            ctx.formParamMap().forEach((name, values) -> {
                if (!values.isEmpty()) {
                    fields.put(name, values.get(0));
                }
            });

            switch (SignupForm.parse(fields)) {
                case SignupForm.Parsed.Problems problems -> {
                    // 422 rather than 400: the request was understood, its
                    // contents were not acceptable. A 400 reads as "your
                    // browser is broken" to anybody reading a proxy log.
                    ctx.status(422);
                    ctx.html(SignupPage.form(path, problems.problems(), keepNonSecret(fields)));
                }
                case SignupForm.Parsed.Ready ready -> {
                    SignupResponse response = attempt(ready.form());
                    ctx.status(response.status());
                    ctx.html(SignupPage.result(response));
                }
            }
        });

        // An exception reaching Javalin's default handler is a 500 with a stack
        // trace, on a page somebody is standing in front of. Mapped to the same
        // wording an outage gets, because to the person it is one.
        app.exception(Exception.class, (e, ctx) -> {
            log.accept("the signup form failed to render a response", e);
            SignupResponse response = SignupResponse.of(new Registration.Outcome.Unavailable(
                    "Something went wrong on our side, so nothing was changed. Please try again"
                            + " shortly."));
            ctx.status(response.status());
            ctx.html(SignupPage.result(response));
        });

        app.start(bind, port);
        return this;
    }

    /** The port actually bound, which is the only way to learn it when 0 was asked for. */
    public int port() {
        return app.port();
    }

    @Override
    public void close() {
        if (app != null) {
            app.stop();
        }
    }

    /**
     * Link, then register, in the order the two steps have to happen.
     *
     * <p>A refused or unavailable link is returned as it is rather than pressed
     * on from: registering after a failed link would ask the gate about a name
     * bound to nothing, and the denial would name every identity as missing.
     */
    private SignupResponse attempt(SignupForm form) {
        Registration.Outcome linked = registration.link(form.code(), form.account().username());
        if (!(linked instanceof Registration.Outcome.Linked)) {
            return SignupResponse.of(linked);
        }
        return SignupResponse.of(registration.register(form.account()));
    }

    /**
     * What a redisplayed form may carry back.
     *
     * <p>An allowlist rather than a removal, so a field added to the form later
     * is absent from the redisplay until somebody decides it belongs there. The
     * removal form of this has the opposite default, and the field it forgets
     * is always the secret one.
     */
    private static Map<String, String> keepNonSecret(Map<String, String> fields) {
        Map<String, String> kept = new LinkedHashMap<>();
        for (String name : List.of(SignupForm.CODE, SignupForm.USERNAME, SignupForm.EMAIL)) {
            String value = fields.get(name);
            if (value != null) {
                kept.put(name, value);
            }
        }
        return kept;
    }
}

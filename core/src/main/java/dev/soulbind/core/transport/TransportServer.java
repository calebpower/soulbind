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

package dev.soulbind.core.transport;

import dev.soulbind.core.registry.Authenticator;
import dev.soulbind.core.registry.ConnectorRecord;
import dev.soulbind.protocol.ErrorCode;
import dev.soulbind.protocol.Wire;
import io.javalin.Javalin;
import io.javalin.http.Context;
import io.javalin.websocket.WsContext;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Both transports, one dispatcher.
 *
 * <p>Two transports ship because connectors come in two shapes. A daemon — a
 * bot, a proxy plugin — holds a socket open and authenticates once. A
 * request-lifecycle connector — a web application that only exists while
 * serving a page — cannot hold anything open, so each of its requests stands
 * alone and is signed.
 *
 * <p>They carry <b>the same protocol</b> and share <b>one</b>
 * {@link Dispatcher}. Nothing about authorization, operation resolution or
 * refusal wording lives in either transport, which is what stops the two from
 * developing different ideas about who may do what.
 *
 * <p>This class is the only place in core that names an HTTP or WebSocket type.
 * A guard asserts that.
 */
public final class TransportServer implements AutoCloseable {

    /** Where signed request-lifecycle calls arrive. */
    public static final String RPC_PATH = "/v1/rpc";

    /** Where daemon connectors hold a socket open. */
    public static final String SOCKET_PATH = "/v1/socket";

    /** Liveness only. Unauthenticated on purpose, and says nothing about state. */
    public static final String HEALTH_PATH = "/health";

    private final Javalin app;
    private final Dispatcher dispatcher;
    private final Codec codec;
    private final Authenticator authenticator;
    private final SignedRequestVerifier verifier;
    private final Clock clock;

    /** Sockets that completed authentication, and the connector each resolved to. */
    private final Map<WsContext, ConnectorRecord> authenticated = new ConcurrentHashMap<>();

    public TransportServer(
            Dispatcher dispatcher,
            Codec codec,
            Authenticator authenticator,
            Duration signatureWindow,
            NonceStore nonces,
            Clock clock) {
        this.dispatcher = dispatcher;
        this.codec = codec;
        this.authenticator = authenticator;
        this.verifier = new SignedRequestVerifier(signatureWindow, nonces);
        this.clock = clock;
        this.app = Javalin.create(config -> config.showJavalinBanner = false);
        wire();
    }

    private void wire() {
        app.get(HEALTH_PATH, ctx -> ctx.result("ok"));
        app.post(RPC_PATH, this::handleSigned);
        app.ws(SOCKET_PATH, ws -> {
            ws.onConnect(this::onSocketConnect);
            ws.onMessage(this::onSocketMessage);
            ws.onClose(ctx -> authenticated.remove(ctx));
            ws.onError(ctx -> authenticated.remove(ctx));
        });
    }

    // --- signed request-lifecycle transport ----------------------------------

    private void handleSigned(Context ctx) {
        String body = ctx.body();
        Optional<String> token = Authenticator.bearerToken(ctx.header(Wire.HEADER_AUTHORIZATION));

        // Resolve the credential first: the signature is keyed on it, so there
        // is nothing to verify against until we know which credential is
        // claimed. An unknown credential is refused here and never reaches the
        // signing check.
        Optional<ConnectorRecord> connector = token.flatMap(authenticator::authenticate);
        if (connector.isEmpty()) {
            respond(ctx, null, WireResponse.error(
                    ErrorCode.UNKNOWN_CREDENTIAL, "no registered connector for this credential"));
            return;
        }

        SignedRequestVerifier.Outcome outcome = verifier.verify(
                connector.get(),
                token.get(),
                ctx.header(Wire.HEADER_TIMESTAMP),
                ctx.header(Wire.HEADER_NONCE),
                ctx.header(Wire.HEADER_SIGNATURE),
                body,
                clock.instant());

        if (outcome instanceof SignedRequestVerifier.Outcome.Refused refused) {
            respond(ctx, null, WireResponse.error(refused.code(), refused.message()));
            return;
        }

        Optional<Codec.ParsedRequest> parsed = codec.parseRequest(body);
        if (parsed.isEmpty()) {
            respond(ctx, null, WireResponse.error(
                    ErrorCode.MALFORMED, "body is not a protocol request"));
            return;
        }

        Codec.ParsedRequest request = parsed.get();
        WireResponse response = dispatcher.dispatch(
                request.schema(), request.op(), token.get(), request.payload());
        respond(ctx, request.id(), response);
    }

    private void respond(Context ctx, String id, WireResponse response) {
        // Always HTTP 200 with the outcome in the body. A protocol refusal is
        // not a transport failure, and mapping refusals onto status codes means
        // every intermediary -- proxy, CDN, corporate filter -- gets an opinion
        // about them. The one exception is nothing: even an internal fault is
        // reported in the envelope, because a peer that can read one response
        // shape can read them all.
        ctx.status(200);
        ctx.contentType("application/json");
        ctx.result(codec.renderResponse(id, response));
    }

    // --- socket transport ----------------------------------------------------

    private void onSocketConnect(WsContext ctx) {
        // Authenticate at connect, once. A socket that cannot present a valid
        // credential is closed rather than left open in an unauthenticated
        // state: an open socket is a resource, and one that can never do
        // anything is a resource an unauthenticated peer is holding.
        Optional<ConnectorRecord> connector =
                Authenticator.bearerToken(ctx.header(Wire.HEADER_AUTHORIZATION))
                        .flatMap(authenticator::authenticate);

        if (connector.isEmpty()) {
            ctx.send(codec.renderResponse(null, WireResponse.error(
                    ErrorCode.UNKNOWN_CREDENTIAL, "no registered connector for this credential")));
            ctx.closeSession(1008, "unauthenticated");
            return;
        }
        authenticated.put(ctx, connector.get());
    }

    private void onSocketMessage(io.javalin.websocket.WsMessageContext ctx) {
        ConnectorRecord connector = authenticated.get(ctx);
        if (connector == null) {
            ctx.send(codec.renderResponse(null, WireResponse.error(
                    ErrorCode.UNKNOWN_CREDENTIAL, "this socket is not authenticated")));
            return;
        }

        Optional<Codec.ParsedRequest> parsed = codec.parseRequest(ctx.message());
        if (parsed.isEmpty()) {
            ctx.send(codec.renderResponse(null, WireResponse.error(
                    ErrorCode.MALFORMED, "message is not a protocol request")));
            return;
        }

        // Re-read the credential from the header rather than caching the
        // plaintext: the dispatcher authenticates for itself, so authorization
        // has exactly one entry point regardless of transport. A socket whose
        // credential was suspended mid-session therefore starts being refused at
        // its next message rather than at its next reconnect.
        Codec.ParsedRequest request = parsed.get();
        String token = Authenticator.bearerToken(ctx.header(Wire.HEADER_AUTHORIZATION))
                .orElse(null);

        WireResponse response = dispatcher.dispatch(
                request.schema(), request.op(), token, request.payload());
        ctx.send(codec.renderResponse(request.id(), response));
    }

    // --- lifecycle -----------------------------------------------------------

    /** Starts on the given host and port, returning the port actually bound. */
    public int start(String host, int port) {
        app.start(host, port);
        return app.port();
    }

    @Override
    public void close() {
        app.stop();
    }
}

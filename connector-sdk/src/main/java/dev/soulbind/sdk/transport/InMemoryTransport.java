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
package dev.soulbind.sdk.transport;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * A transport that calls a function instead of a network.
 *
 * <p>The reason the seam exists. Every piece of protocol logic in this SDK —
 * signing, refusal handling, cache population, fail-mode fallback — is
 * exercised through this, so those tests run in milliseconds and can express
 * conditions a real network cannot be asked for on demand: a core that is
 * reachable for one call and gone for the next, a response that is truncated, a
 * refusal arriving where a success was expected.
 *
 * <p>Not a mock framework. It is a {@link Function} and a list of what was
 * sent, because a connector test wants to assert what went out and control what
 * comes back, and anything more would be a second thing to learn.
 */
public final class InMemoryTransport implements Transport {

    /**
     * What was sent, in order — and safe to send from several threads.
     *
     * <p>A plain {@code ArrayList} here threw
     * {@code ArrayIndexOutOfBoundsException} from inside {@code add} when a
     * connector test drove eight threads through one transport, and the stack
     * trace pointed at this class rather than at the code under test. A test
     * double that corrupts under concurrent use makes every concurrency test
     * built on it untrustworthy: the loud failure is an exception, and the quiet
     * one is {@link #sent()} returning a list that is missing entries nobody
     * will notice.
     *
     * <p>A queue rather than a copy-on-write list, because a test that sends
     * thousands of requests would otherwise copy the whole array on every one.
     */
    private final Queue<String> sent = new ConcurrentLinkedQueue<>();
    private Function<String, String> responder;
    private boolean connected = true;
    private TransportException failure;

    public InMemoryTransport(Function<String, String> responder) {
        this.responder = responder;
    }

    /** A transport that answers everything with one canned body. */
    public static InMemoryTransport always(String responseBody) {
        return new InMemoryTransport(request -> responseBody);
    }

    @Override
    public String send(String requestBody) throws TransportException {
        sent.add(requestBody);
        if (failure != null) {
            throw failure;
        }
        return responder.apply(requestBody);
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    /**
     * Makes every subsequent send fail, as an unreachable core does.
     *
     * <p>The condition the fail-closed default exists for, and the one hardest
     * to arrange against a real server at the moment a test wants it.
     */
    public InMemoryTransport goDown() {
        this.connected = false;
        this.failure = new TransportException("core is unreachable (simulated)");
        return this;
    }

    /** Brings it back, so a test can assert recovery rather than only failure. */
    public InMemoryTransport comeBack() {
        this.connected = true;
        this.failure = null;
        return this;
    }

    public InMemoryTransport respondWith(Function<String, String> next) {
        this.responder = next;
        return this;
    }

    /**
     * Every request body sent, in order.
     *
     * <p>A snapshot. Taken while other threads may still be sending, it is
     * whatever had arrived by then — which is the honest answer, and the reason
     * an assertion on this should be made after the senders have finished.
     */
    public List<String> sent() {
        return List.copyOf(sent);
    }

    public int sendCount() {
        return sent.size();
    }

    @Override
    public void close() {
        // Nothing to release. Stated rather than left empty so a reader does not
        // wonder whether something was forgotten.
    }
}

package com.dixansh.mini_payment_gateway;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A throwaway merchant webhook receiver on a random local port (JDK {@link HttpServer}, no extra
 * dependency). It records every request it receives and answers 500 for the first
 * {@code failuresBeforeSuccess} calls, then 200 - so a test can script "fail, fail, succeed" or, with
 * {@link Integer#MAX_VALUE}, "always fail".
 */
final class StubMerchant implements AutoCloseable {

    record Received(String webhookId, String signature, String body) {
    }

    private final HttpServer server;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final AtomicInteger failuresLeft;

    StubMerchant(int failuresBeforeSuccess) throws IOException {
        this.failuresLeft = new AtomicInteger(failuresBeforeSuccess);
        this.server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/hook", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                received.add(new Received(
                        exchange.getRequestHeaders().getFirst("X-Webhook-Id"),
                        exchange.getRequestHeaders().getFirst("X-Signature"),
                        body));
            }
            int status = failuresLeft.getAndDecrement() > 0 ? 500 : 200;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    String url() {
        return "http://localhost:" + server.getAddress().getPort() + "/hook";
    }

    List<Received> received() {
        return received;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}

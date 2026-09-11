package com.mineplus.pack;

import com.mineplus.pack.compile.PackCache;
import com.mineplus.util.DebugLogger;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Bounded local HTTP byte endpoint serving compiled artifacts (LOCAL delivery
 * mode). One handler, two request threads, streamed file bodies (artifacts
 * are never held in memory), strict path validation, no application logic —
 * compilation and player state live elsewhere.
 *
 * <p>Port auto-probing: when the configured port is busy, the next few ports
 * are tried so a default-configured server still gets a working endpoint
 * without operator intervention; {@link #port()} reports the port actually
 * bound (URLs must use it, not the configured value).</p>
 */
final class LocalPackEndpoint {

    private static final Pattern ARTIFACT_NAME = Pattern.compile("mp-[a-f0-9]{1,12}\\.zip");
    /** How many consecutive ports to try when the configured one cannot be bound. */
    private static final int PORT_PROBE_COUNT = 10;

    private final PackCache cache;
    private final String host;
    private final int configuredPort;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "mineplus-pack-http");
        thread.setDaemon(true);
        return thread;
    });
    private HttpServer server;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int boundPort = -1;

    LocalPackEndpoint(PackCache cache, String host, int port) {
        this.cache = cache;
        this.host = host;
        this.configuredPort = port;
    }

    boolean start() {
        if (running.get()) {
            return true;
        }
        String lastFailure = "unknown";
        for (int probe = 0; probe < PORT_PROBE_COUNT; probe++) {
            int candidate = configuredPort + probe;
            try {
                server = HttpServer.create(new InetSocketAddress(host, candidate), 0);
            } catch (IOException blocked) {
                lastFailure = blocked.getMessage();
                server = null;
                continue;
            }
            server.setExecutor(workers);
            server.createContext("/", this::serve);
            server.start();
            running.set(true);
            boundPort = candidate;
            return true;
        }
        DebugLogger.warning("[PackDelivery] Local endpoint could not bind " + host
                + ":" + configuredPort + " (tried " + PORT_PROBE_COUNT
                + " consecutive ports; last error: " + lastFailure + ").");
        server = null;
        return false;
    }

    void stop() {
        if (running.compareAndSet(true, false) && server != null) {
            server.stop(0);
            server = null;
        }
        boundPort = -1;
        workers.shutdownNow();
    }

    /** True while the HTTP endpoint accepts downloads (URL building gate). */
    boolean isRunning() {
        return running.get();
    }

    /** The port actually bound, or -1 when not running. */
    int port() {
        return running.get() ? boundPort : -1;
    }

    /** True when the endpoint had to fall back to a port above the configured one. */
    boolean portDrifted() {
        return running.get() && boundPort != configuredPort;
    }

    private void serve(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Method not allowed");
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String name = path.startsWith("/pack/") ? path.substring("/pack/".length()) : "";
            if (!ARTIFACT_NAME.matcher(name).matches()) {
                respond(exchange, 404, "Not found");
                return;
            }
            File file = new File(cache.folder(), name);
            if (!file.isFile() || !file.getCanonicalPath().startsWith(cache.folder().getCanonicalPath())) {
                respond(exchange, 404, "Not found");
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Content-Length", String.valueOf(file.length()));
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=86400");
            exchange.sendResponseHeaders(200, file.length());
            try (InputStream in = Files.newInputStream(file.toPath());
                 OutputStream out = exchange.getResponseBody()) {
                in.transferTo(out);
            }
        } finally {
            exchange.close();
        }
    }

    private void respond(HttpExchange exchange, int status, String message) throws IOException {
        byte[] body = message.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}

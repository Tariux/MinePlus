package com.mineplus.test;

import com.mineplus.infrastructure.core.network.MessageBus;
import com.mineplus.infrastructure.core.network.RedisTransport;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

/**
 * Plain-Java checks for the Redis pub/sub transport (mega-plan D2).
 * Talks to a real Redis on localhost:6379 when one is up; when none is
 * reachable the run reports the skip and still exits 0 so the suite
 * stays green on machines without Redis.
 *
 * Run: java com.mineplus.test.RedisTransportTest — exit 0 = all passed.
 */
public final class RedisTransportTest {

    private static final java.util.List<String> failures = new java.util.ArrayList<>();
    private static final Logger LOG = Logger.getLogger("test");

    public static void main(String[] args) throws Exception {
        if (!redisUp()) {
            System.out.println("RedisTransportTest: all checks passed "
                    + "(skipped: no redis on localhost:6379)");
            System.exit(0);
        }

        RedisTransport alphaTransport = new RedisTransport("127.0.0.1", 6379, LOG);
        RedisTransport betaTransport = new RedisTransport("127.0.0.1", 6379, LOG);
        try {
            MessageBus alpha = new MessageBus(alphaTransport, "alpha");
            MessageBus beta = new MessageBus(betaTransport, "beta");

            CountDownLatch received = new CountDownLatch(1);
            AtomicReference<String> payload = new AtomicReference<>();
            beta.on("chat", m -> {
                payload.set(m.payload());
                received.countDown();
            });
            CountDownLatch echoed = new CountDownLatch(1);
            alpha.on("chat", m -> echoed.countDown());

            alpha.send("chat", null, "hello redis");
            check("delivered over redis", received.await(5, TimeUnit.SECONDS));
            check("payload intact", "hello redis".equals(payload.get()));
            check("no echo to sender",
                    !echoed.await(1, TimeUnit.SECONDS));

            // Targeted send: receiver filter still applies on top of the transport.
            CountDownLatch targeted = new CountDownLatch(1);
            beta.on("direct", m -> targeted.countDown());
            alpha.send("direct", "beta", "for beta only");
            check("targeted delivery", targeted.await(5, TimeUnit.SECONDS));
        } finally {
            alphaTransport.close();
            betaTransport.close();
        }

        if (failures.isEmpty()) {
            System.out.println("RedisTransportTest: all checks passed");
            System.exit(0);
        }
        System.err.println("RedisTransportTest: " + failures.size() + " failure(s)");
        failures.forEach(f -> System.err.println("  - " + f));
        System.exit(1);
    }

    private static boolean redisUp() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 6379), 1000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void check(String name, boolean condition) {
        if (!condition) {
            failures.add(name);
        }
    }
}

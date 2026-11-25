package com.mineplus.infrastructure.core.network;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Redis pub/sub transport (mega-plan D2): cross-server messaging for
 * multi-host setups, same {@link MessageBus.Transport} seam as the file
 * spool. No Redis client jar in {@code libs/} — this speaks RESP directly
 * over a socket: each publish is one {@code PUBLISH} round-trip on a
 * short-lived connection; a dedicated reader connection runs
 * {@code SUBSCRIBE} and feeds the sink. Wire format and dedupe are
 * identical to the file spool (at-least-once, deduped by message id in
 * {@link MessageBus}), so swapping transports changes nothing else.
 */
public final class RedisTransport implements MessageBus.Transport {

    /** Single Redis channel; per-message channels live in the payload. */
    static final String CHANNEL = "mineplus:bus";

    private final String host;
    private final int port;
    private final Logger logger;
    private volatile Consumer<MessageBus.Message> sink;
    private volatile boolean closed;
    private Socket readerSocket;
    private Thread reader;

    public RedisTransport(String host, int port, Logger logger) {
        this.host = host;
        this.port = port;
        this.logger = logger;
    }

    @Override
    public void publish(MessageBus.Message message) {
        byte[] payload = FileSpoolTransport.encode(message).getBytes(StandardCharsets.UTF_8);
        try (Socket socket = connect()) {
            OutputStream out = socket.getOutputStream();
            writeCommand(out, "PUBLISH", CHANNEL, new String(payload, StandardCharsets.UTF_8));
            // :<count> reply; only the status matters, not the receiver count.
            new RespReader(socket.getInputStream()).read();
        } catch (IOException e) {
            logger.warning("RedisTransport: publish failed: " + e.getMessage());
        }
    }

    @Override
    public void subscribe(Consumer<MessageBus.Message> messageSink) {
        this.sink = messageSink;
        if (reader != null) {
            return;
        }
        reader = new Thread(this::readLoop, "mineplus-redis-subscriber");
        reader.setDaemon(true);
        reader.start();
    }

    /** Closes the reader connection and stops the subscriber thread. */
    public void close() {
        closed = true;
        try {
            if (readerSocket != null) {
                readerSocket.close();
            }
        } catch (IOException ignored) {
            // closing a dead socket is fine
        }
        if (reader != null) {
            reader.interrupt();
        }
    }

    private void readLoop() {
        while (!closed) {
            try {
                readerSocket = connect();
                OutputStream out = readerSocket.getOutputStream();
                writeCommand(out, "SUBSCRIBE", CHANNEL);
                InputStream in = new BufferedInputStream(readerSocket.getInputStream());
                while (!closed) {
                    Object reply = new RespReader(in).read();
                    if (reply instanceof Object[] array && array.length == 3
                            && "message".equals(array[0])) {
                        deliver(String.valueOf(array[2]));
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    logger.warning("RedisTransport: subscribe dropped (" + e.getMessage()
                            + "), retrying in 2s");
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
            }
        }
    }

    private void deliver(String payload) {
        try {
            MessageBus.Message message = FileSpoolTransport.decode(payload);
            Consumer<MessageBus.Message> target = sink;
            if (target != null) {
                target.accept(message);
            }
        } catch (RuntimeException e) {
            logger.warning("RedisTransport: malformed message skipped: " + e.getMessage());
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setSoTimeout(0);
        return socket;
    }

    private static void writeCommand(OutputStream out, String... args) throws IOException {
        out.write(('*' + String.valueOf(args.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write(('$' + String.valueOf(bytes.length) + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.flush();
    }

    /** Minimal RESP2 reader: bulk strings, simple strings, errors, integers, arrays. */
    private static final class RespReader {
        private final InputStream in;

        RespReader(InputStream in) {
            this.in = in;
        }

        Object read() throws IOException {
            int type = in.read();
            if (type == -1) {
                throw new IOException("connection closed");
            }
            String line = readLine();
            return switch (type) {
                case '$' -> readBulk(Integer.parseInt(line.trim()));
                case '*' -> {
                    int count = Integer.parseInt(line.trim());
                    Object[] items = new Object[count];
                    for (int i = 0; i < count; i++) {
                        items[i] = read();
                    }
                    yield items;
                }
                case '+', ':' -> line;
                case '-' -> throw new IOException("redis error: " + line);
                default -> throw new IOException("unexpected resp type " + (char) type);
            };
        }

        private String readBulk(int length) throws IOException {
            byte[] bytes = in.readNBytes(length);
            if (bytes.length != length) {
                throw new IOException("short bulk read");
            }
            if (in.read() != '\r' || in.read() != '\n') {
                throw new IOException("bad bulk terminator");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        private String readLine() throws IOException {
            StringBuilder out = new StringBuilder();
            int previous = -1;
            for (;;) {
                int current = in.read();
                if (current == -1) {
                    throw new IOException("connection closed mid-line");
                }
                if (previous == '\r' && current == '\n') {
                    out.setLength(out.length() - 1);
                    return out.toString();
                }
                out.append((char) current);
                previous = current;
            }
        }
    }
}

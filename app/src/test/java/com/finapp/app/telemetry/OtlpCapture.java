package com.finapp.app.telemetry;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Two stand-ins for a collector on loopback, for the export tests (`P15-TSK-023`): one that takes every OTLP request and keeps its
 * bytes, so a test reads what really left the process; and one that accepts a connection and never answers, so a test can see
 * whether anything waits on it. OTLP/HTTP carries protobuf, whose strings are their UTF-8 bytes, so a needle or a marker is found
 * in what was sent without decoding a message.
 */
final class OtlpCapture {

    private OtlpCapture() {}

    /** Keeps every request's body; answers 200 with an empty message, which OTLP reads as accepted. */
    static final class Recording implements AutoCloseable {
        private final HttpServer server;
        private final List<byte[]> bodies = new CopyOnWriteArrayList<>();
        private final List<String> paths = new CopyOnWriteArrayList<>();

        Recording() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/", exchange -> {
                try (InputStream in = exchange.getRequestBody()) {
                    bodies.add(in.readAllBytes());
                }
                paths.add(exchange.getRequestURI().getPath());
                exchange.getResponseHeaders().add("Content-Type", "application/x-protobuf");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            server.start();
        }

        String endpoint(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        /** Everything received on {@code path}, as one text: protobuf's strings are their own bytes. */
        String received(String path) {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (int i = 0; i < bodies.size(); i++) {
                if (paths.get(i).equals(path)) {
                    all.writeBytes(bodies.get(i));
                    all.write('\n');
                }
            }
            return all.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** Accepts and never answers: the collector that is up at the TCP level and stuck above it. */
    static final class Hanging implements AutoCloseable {
        private final ServerSocket socket;
        private final List<Socket> held = new ArrayList<>();
        private final Thread acceptor;

        Hanging() {
            try {
                socket = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            acceptor = Thread.ofPlatform().daemon().name("hanging-collector").start(() -> {
                while (!socket.isClosed()) {
                    try {
                        Socket accepted = socket.accept();
                        synchronized (held) {
                            held.add(accepted);
                        }
                    } catch (IOException closed) {
                        return;
                    }
                }
            });
        }

        String endpoint(String path) {
            return "http://127.0.0.1:" + socket.getLocalPort() + path;
        }

        int accepted() {
            synchronized (held) {
                return held.size();
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
            synchronized (held) {
                for (Socket each : held) {
                    each.close();
                }
            }
            acceptor.interrupt();
        }
    }
}

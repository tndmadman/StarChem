package com.tndmadman.rts;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Loopback byte proxy used by integration validators to create real TCP faults and backpressure. */
final class TcpFaultProxy implements AutoCloseable {
    private final ServerSocket listener;
    private final InetSocketAddress upstreamAddress;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Object downstreamGate = new Object();
    private final AtomicLong clientToServerBytes = new AtomicLong();
    private final AtomicLong serverToClientBytes = new AtomicLong();
    private volatile Bridge active;
    private volatile boolean downstreamPaused;
    private volatile long downstreamBytesPerSecond;

    TcpFaultProxy(InetAddress upstreamAddress, int upstreamPort) throws IOException {
        this.upstreamAddress = new InetSocketAddress(upstreamAddress, upstreamPort);
        this.listener = new ServerSocket();
        listener.setReuseAddress(true);
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        Thread accept = new Thread(this::acceptLoop, "starchem-tcp-fault-proxy-accept-" + listenPort());
        accept.setDaemon(true);
        accept.start();
    }

    int listenPort() { return listener.getLocalPort(); }
    long clientToServerBytes() { return clientToServerBytes.get(); }
    long serverToClientBytes() { return serverToClientBytes.get(); }
    boolean activeConnection() { Bridge bridge = active; return bridge != null && bridge.open.get(); }

    void pauseServerToClient() { downstreamPaused = true; }

    void resumeServerToClient() {
        downstreamPaused = false;
        synchronized (downstreamGate) { downstreamGate.notifyAll(); }
    }

    void throttleServerToClient(long bytesPerSecond) {
        downstreamBytesPerSecond = Math.max(0, bytesPerSecond);
    }

    void clearThrottle() { downstreamBytesPerSecond = 0; }

    void dropActiveConnection() {
        Bridge bridge = active;
        if (bridge != null) bridge.close();
    }

    @Override public void close() {
        if (!running.compareAndSet(true, false)) return;
        resumeServerToClient();
        closeQuietly(listener);
        Bridge bridge = active;
        if (bridge != null) bridge.close();
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket client = listener.accept();
                configure(client);
                Socket upstream = new Socket();
                upstream.connect(upstreamAddress, 2_000);
                configure(upstream);
                Bridge bridge = new Bridge(client, upstream);
                Bridge previous = active;
                active = bridge;
                if (previous != null) previous.close();
                bridge.start();
            } catch (SocketException ex) {
                if (running.get()) System.err.println("TCP fault proxy accept failed: " + ex.getMessage());
            } catch (IOException ex) {
                if (running.get()) System.err.println("TCP fault proxy connection failed: " + ex.getMessage());
            }
        }
    }

    private void configure(Socket socket) throws SocketException {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setReceiveBufferSize(32 * 1024);
        socket.setSendBufferSize(32 * 1024);
    }

    private void awaitDownstream() throws InterruptedException {
        synchronized (downstreamGate) {
            while (running.get() && downstreamPaused) downstreamGate.wait(100);
        }
    }

    private void throttle(int bytes, long startedNanos) throws InterruptedException {
        long rate = downstreamBytesPerSecond;
        if (rate <= 0 || bytes <= 0) return;
        long requiredNanos = (long) ((bytes / (double) rate) * 1_000_000_000L);
        long remaining = requiredNanos - (System.nanoTime() - startedNanos);
        if (remaining <= 0) return;
        long millis = remaining / 1_000_000L;
        int nanos = (int) (remaining % 1_000_000L);
        Thread.sleep(millis, nanos);
    }

    private static void closeQuietly(Closeable value) {
        if (value == null) return;
        try { value.close(); } catch (IOException ignored) { }
    }

    private final class Bridge {
        private final Socket client;
        private final Socket upstream;
        private final AtomicBoolean open = new AtomicBoolean(true);

        Bridge(Socket client, Socket upstream) {
            this.client = client;
            this.upstream = upstream;
        }

        void start() {
            startPump(client, upstream, false, "up");
            startPump(upstream, client, true, "down");
        }

        void close() {
            if (!open.compareAndSet(true, false)) return;
            closeQuietly(client);
            closeQuietly(upstream);
            synchronized (downstreamGate) { downstreamGate.notifyAll(); }
        }

        private void startPump(Socket from, Socket to, boolean downstream, String suffix) {
            Thread thread = new Thread(() -> pump(from, to, downstream),
                    "starchem-tcp-fault-proxy-" + suffix + '-' + listenPort());
            thread.setDaemon(true);
            thread.start();
        }

        private void pump(Socket from, Socket to, boolean downstream) {
            byte[] buffer = new byte[16 * 1024];
            try (InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream()) {
                while (running.get() && open.get()) {
                    if (downstream) awaitDownstream();
                    if (!running.get() || !open.get()) break;
                    int read = input.read(buffer);
                    if (read < 0) break;
                    long started = System.nanoTime();
                    output.write(buffer, 0, read);
                    output.flush();
                    if (downstream) {
                        serverToClientBytes.addAndGet(read);
                        throttle(read, started);
                    } else {
                        clientToServerBytes.addAndGet(read);
                    }
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }
    }
}

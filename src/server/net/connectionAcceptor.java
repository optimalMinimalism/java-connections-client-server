package server.net;

import server.core.protocolRouter;
import server.session.playerSession;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.InetAddress;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

public class connectionAcceptor implements Runnable {

    private final protocolRouter protocol_router;
    private final ExecutorService worker_pool;
    private final int read_timeout_millis;
    private final Map<String, playerSession> live_sessions;

    private final java.util.Set<Socket> sockets = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private final ServerSocket server_socket;

    public connectionAcceptor(
            String bind_host,
            int port,
            int read_timeout_millis,
            protocolRouter protocol_router,
            ExecutorService worker_pool,
            Map<String, playerSession> live_sessions
    ) throws IOException {
        this.server_socket = new ServerSocket(port, 50, InetAddress.getByName(bind_host));
        this.read_timeout_millis = read_timeout_millis;
        this.protocol_router = protocol_router;
        this.worker_pool = worker_pool;
        this.live_sessions = live_sessions;
        this.running = true;
    }

    @Override
    public void run() {
        try (ServerSocket ss = server_socket) {
            while (running) {
                Socket socket = ss.accept();
                sockets.add(socket);
                if (!running) { socket.close(); break; }
                try {
                    socket.setSoTimeout(read_timeout_millis);
                    worker_pool.execute(() -> {
                        try {
                            new clientHandler(socket, protocol_router, live_sessions).run();
                        } finally {
                            sockets.remove(socket);
                        }
                    });
                } catch (RejectedExecutionException | IOException e) {
                    // A pool pieno non accumuliamo connessioni in attesa senza limite.
                    sockets.remove(socket);
                    try { socket.close(); } catch (IOException ignored) { }
                }
            }
        } catch (IOException e) {
            if (running) {
                throw new RuntimeException("Server acceptor failed: " + e.getMessage(), e);
            }
        }
    }

    public void stop() {
        running = false;
        for (Socket socket : sockets) {
            try { socket.close(); } catch (IOException ignored) { }
        }
        if (server_socket != null && !server_socket.isClosed()) {
            try {
                server_socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}

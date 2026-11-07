import client.net.tcpClient;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

public class clientConnectionTestMain {

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    public static void main(String[] args) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try {
            // Due chiamanti sulla stessa connessione devono ricevere ciascuno la propria risposta.
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                Future<?> echo = threads.submit(() -> {
                    try (Socket socket = server.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                        socket.setSoTimeout(5000);

                        for (int i = 0; i < 20; i++) {
                            String line = reader.readLine();
                            if (line == null) throw new IOException("Richiesta mancante");
                            writer.write(line);
                            writer.newLine();
                            writer.flush();
                        }
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                tcpClient client = new tcpClient("127.0.0.1", server.getLocalPort());
                client.connect();
                try {
                    Future<?> first = threads.submit(() -> exchange(client, "first"));
                    Future<?> second = threads.submit(() -> exchange(client, "second"));
                    first.get(5, TimeUnit.SECONDS);
                    second.get(5, TimeUnit.SECONDS);
                    echo.get(5, TimeUnit.SECONDS);
                    boolean closed = false;
                    try { client.sendAndReceive("after-close"); }
                    catch (IOException e) { closed = true; }
                    check(closed && !client.isConnected());
                } finally { client.close(); }
            }

            // Il server legge la richiesta ma non risponde. close deve sbloccare la read del client.
            try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
                CountDownLatch received = new CountDownLatch(1);
                Future<?> silent = threads.submit(() -> {
                    try (Socket socket = server.accept();
                         BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                        socket.setSoTimeout(5000);
                        check(reader.readLine().equals("waiting"));
                        received.countDown();
                        check(reader.read() == -1);
                    } catch (IOException e) { throw new UncheckedIOException(e); }
                });
                tcpClient client = new tcpClient("127.0.0.1", server.getLocalPort());
                client.connect();
                Future<?> waiting = threads.submit(() -> {
                    try {
                        client.sendAndReceive("waiting");
                        throw new AssertionError("Il server non avrebbe dovuto rispondere");
                    } catch (IOException expected) { }
                });
                check(received.await(3, TimeUnit.SECONDS));
                Future<?> closing = threads.submit(client::close);
                closing.get(3, TimeUnit.SECONDS);
                waiting.get(3, TimeUnit.SECONDS);
                silent.get(3, TimeUnit.SECONDS);
                check(!client.isConnected());
            }

            System.out.println("PASS: richieste TCP concorrenti, EOF e chiusura durante lettura bloccata");
        } finally {
            threads.shutdownNow();
        }
    }

    private static void exchange(tcpClient client, String name) {
        try {
            for (int i = 0; i < 10; i++) {
                String message = name + "-" + i;
                check(client.sendAndReceive(message).equals(message));
            }
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
}

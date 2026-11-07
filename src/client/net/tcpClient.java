package client.net;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class tcpClient {

    private final String host;
    private final int port;

    private volatile SocketChannel socket_channel;
    private BufferedReader reader;
    private BufferedWriter writer;

    public tcpClient(String host, int port) {
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalArgumentException("Indirizzo o porta TCP non validi");
        }

        this.host = host;
        this.port = port;
    }

    public synchronized void connect() throws IOException {
        if (isConnected()) {
            return;
        }

        socket_channel = SocketChannel.open();

        try {
            socket_channel.configureBlocking(true);
            socket_channel.connect(new InetSocketAddress(host, port));
        } catch (IOException e) {
            close();
            throw e;
        }

        reader = new BufferedReader(
                new InputStreamReader(
                        Channels.newInputStream(socket_channel),
                        StandardCharsets.UTF_8
                )
        );

        writer = new BufferedWriter(
                new OutputStreamWriter(
                        Channels.newOutputStream(socket_channel),
                        StandardCharsets.UTF_8
                )
        );
    }

    public boolean isConnected() {
        return socket_channel != null && socket_channel.isOpen() && socket_channel.isConnected();
    }

    public synchronized String sendAndReceive(String json_request) throws IOException {
        if (!isConnected()) {
            throw new IOException("TCP client is not connected");
        }

        try {
            writer.write(json_request);
            writer.newLine();
            writer.flush();

            String response = reader.readLine();
            if (response == null) throw new IOException("Server closed the connection");
            return response;
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    public void close() {
        // La chiusura del canale sblocca la read in attesa.
        SocketChannel current_channel = socket_channel;
        if (current_channel == null) return;

        try {
            current_channel.close();
        } catch (IOException ignored) {
        }
    }
}

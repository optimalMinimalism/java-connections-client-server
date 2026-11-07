package server.net;

import common.protocol.jsonCodec;
import common.protocol.notificationMessage;
import server.session.playerSession;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;

public class udpNotifier {

    public void sendToSession(playerSession session, notificationMessage message) {
        if (session.isLoggedIn() && session.isConnected() && session.getUdpPort() > 0)
            send(session.getClientIp(), session.getUdpPort(), message);
    }

    private void send(String ip, int port, notificationMessage message) {
        byte[] data = jsonCodec.toJson(message).getBytes(StandardCharsets.UTF_8);

        String event_id = UUID.randomUUID().toString();
        int count = (data.length + notificationMessage.FRAGMENT_BYTES - 1) / notificationMessage.FRAGMENT_BYTES;
        try (DatagramSocket socket = new DatagramSocket()) {
            for (int index = 0; index < count; index++) {
                if (Thread.currentThread().isInterrupted()) return;
                JsonObject fragment = new JsonObject();
                fragment.addProperty("eventId", event_id);
                fragment.addProperty("gameId", message.getGameId());
                fragment.addProperty("index", index);
                fragment.addProperty("count", count);
                int start = index * notificationMessage.FRAGMENT_BYTES;
                int end = Math.min(data.length, start + notificationMessage.FRAGMENT_BYTES);
                byte[] chunk = Arrays.copyOfRange(data, start, end);
                fragment.addProperty("data", Base64.getEncoder().encodeToString(chunk));
                byte[] packet = fragment.toString().getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(packet, packet.length, InetAddress.getByName(ip), port));
            }
        } catch (IOException e) {
            System.err.println("Invio UDP fallito: " + e.getMessage());
        }
    }
}

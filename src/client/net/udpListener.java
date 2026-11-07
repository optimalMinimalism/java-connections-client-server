package client.net;

import common.protocol.jsonCodec;
import common.protocol.notificationMessage;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

public class udpListener implements Runnable {

    private static final int MAX_PENDING_EVENTS = 8;
    private static final int MAX_RECENT_EVENTS = 128;
    private static final int DATAGRAM_BYTES = 1200;

    // Queste mappe sono usate soltanto dal thread UDP.
    private final Map<String, byte[][]> fragments = new HashMap<>();
    private final Map<String, Long> received_at = new HashMap<>();
    private final Map<String, Long> recent_events = new LinkedHashMap<>();
    private final int udp_port;
    private final long fragment_timeout_millis;
    private final int receive_timeout_millis;
    private volatile boolean running = true;
    private volatile DatagramSocket socket;

    public udpListener(int udp_port, long fragment_timeout_millis, int receive_timeout_millis) {
        if (fragment_timeout_millis <= 0 || receive_timeout_millis <= 0) {
            throw new IllegalArgumentException("I timeout UDP devono essere positivi");
        }
        this.udp_port = udp_port;
        this.fragment_timeout_millis = fragment_timeout_millis;
        this.receive_timeout_millis = receive_timeout_millis;
    }

    @Override
    public void run() {
        // Un byte extra per riconoscere i pacchetti troppo grandi.
        byte[] buffer = new byte[DATAGRAM_BYTES + 1];
        try (DatagramSocket ds = new DatagramSocket(udp_port)) {
            socket = ds;
            if (!running) return;
            ds.setSoTimeout(receive_timeout_millis);
            while (running) {
                discardExpiredFragments(System.nanoTime() / 1_000_000L);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    ds.receive(packet);
                } catch (SocketTimeoutException e) {
                    continue; // La pulizia avviene anche quando non arrivano altri pacchetti.
                }
                if (packet.getLength() > DATAGRAM_BYTES) continue;
                String json = new String(packet.getData(), packet.getOffset(), packet.getLength(),
                    StandardCharsets.UTF_8);
                try {
                    notificationMessage message = acceptFragment(json, System.nanoTime() / 1_000_000L);
                    if (message != null) {
                        System.out.print(client.ui.notificationPrinter.format(message));
                        System.out.print("> ");
                    }
                } catch (RuntimeException e) {
                    // Ignora il pacchetto malformato.
                }
            }
        } catch (IOException e) {
            if (running) System.out.println("UDP listener stopped: " + e.getMessage());
        } finally {
            fragments.clear();
            received_at.clear();
            recent_events.clear();
        }
    }

    notificationMessage acceptFragment(String json, long now) {
        discardExpiredFragments(now);
        JsonObject envelope = jsonCodec.parseObject(json);
        if (!envelope.has("eventId") || !envelope.get("eventId").isJsonPrimitive()
                || !envelope.getAsJsonPrimitive("eventId").isString()
                || !envelope.has("count") || !envelope.get("count").isJsonPrimitive()
                || !envelope.getAsJsonPrimitive("count").isNumber()
                || !envelope.has("index") || !envelope.get("index").isJsonPrimitive()
                || !envelope.getAsJsonPrimitive("index").isNumber()
                || !envelope.has("data") || !envelope.get("data").isJsonPrimitive()
                || !envelope.getAsJsonPrimitive("data").isString()) {
            return null;
        }

        String event_id = envelope.get("eventId").getAsString();
        if (event_id.isEmpty() || event_id.length() > 64 || recent_events.containsKey(event_id)) return null;
        int count;
        int index;
        try {
            count = envelope.get("count").getAsBigDecimal().intValueExact();
            index = envelope.get("index").getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return null;
        }

        if (count < 1 || count > notificationMessage.MAX_FRAGMENTS || index < 0 || index >= count) {
            forgetEvent(event_id, now);
            System.out.println("\nNotifica UDP non ricomponibile: richiedere stato e statistiche dal menu.");
            return null;
        }

        String encoded = envelope.get("data").getAsString();
        if (encoded.length() > 4 * ((notificationMessage.FRAGMENT_BYTES + 2) / 3)) return null;
        byte[] data = Base64.getDecoder().decode(encoded);
        if (data.length == 0 || data.length > notificationMessage.FRAGMENT_BYTES) return null;

        byte[][] parts = fragments.get(event_id);
        if (parts == null) {
            if (fragments.size() >= MAX_PENDING_EVENTS) {
                forgetEvent(event_id, now);
                System.out.println("\nTroppe notifiche UDP incomplete: richiedere stato e statistiche dal menu.");
                return null;
            }
            parts = new byte[count][];
            fragments.put(event_id, parts);
            received_at.put(event_id, now);
        }
        if (parts.length != count || (parts[index] != null && !Arrays.equals(parts[index], data))) {
            forgetEvent(event_id, now);
            return null;
        }
        parts[index] = data;
        for (byte[] part : parts) {
            if (part == null) return null;
        }

        ByteArrayOutputStream assembled = new ByteArrayOutputStream();
        for (byte[] part : parts) assembled.writeBytes(part);
        forgetEvent(event_id, now);
        notificationMessage message = jsonCodec.fromJson(assembled.toString(StandardCharsets.UTF_8),
            notificationMessage.class);
        if (message == null || message.getType() == null || message.getMessage() == null) return null;
        return message;
    }

    int discardExpiredFragments(long now) {
        Iterator<Map.Entry<String, Long>> recent = recent_events.entrySet().iterator();
        while (recent.hasNext()) {
            if (now - recent.next().getValue() >= fragment_timeout_millis) recent.remove();
        }

        int expired = 0;
        Iterator<Map.Entry<String, Long>> pending = received_at.entrySet().iterator();
        while (pending.hasNext()) {
            Map.Entry<String, Long> entry = pending.next();
            if (now - entry.getValue() >= fragment_timeout_millis) {
                String event_id = entry.getKey();
                pending.remove();
                fragments.remove(event_id);
                rememberEvent(event_id, now);
                expired++;
            }
        }
        if (expired > 0) {
            System.out.println("\nNotifica UDP incompleta: richiedere stato e statistiche dal menu.");
        }

        return expired;
    }

    private void forgetEvent(String event_id, long now) {
        fragments.remove(event_id);
        received_at.remove(event_id);
        rememberEvent(event_id, now);
    }

    private void rememberEvent(String event_id, long now) {
        recent_events.put(event_id, now);
        if (recent_events.size() > MAX_RECENT_EVENTS) {
            recent_events.remove(recent_events.keySet().iterator().next());
        }
    }

    public void stop() {
        running = false;
        DatagramSocket current_socket = socket;
        if (current_socket != null) current_socket.close();
    }
}

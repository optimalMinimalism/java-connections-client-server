package client.net;

import common.protocol.jsonCodec;
import common.protocol.notificationMessage;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

public class udpFragmentTestMain {

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static String fragment(String id, int index, int count, byte[] data) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("eventId", id);
        envelope.addProperty("gameId", 1);
        envelope.addProperty("index", index);
        envelope.addProperty("count", count);
        envelope.addProperty("data", Base64.getEncoder().encodeToString(data));
        String json = envelope.toString();
        check(json.getBytes(StandardCharsets.UTF_8).length <= 1200);
        return json;
    }

    public static void main(String[] args) {
        udpListener listener = new udpListener(0, 100, 10);
        String message = "parole e categorie: è già così ".repeat(50);
        byte[] bytes = jsonCodec.toJson(new notificationMessage("GAME_ENDED", 1, message)).getBytes(StandardCharsets.UTF_8);
        int chunk = notificationMessage.FRAGMENT_BYTES;
        int count = (bytes.length + chunk - 1) / chunk;
        String[] parts = new String[count];
        for (int i = 0; i < count; i++) {
            parts[i] = fragment("out-of-order", i, count, Arrays.copyOfRange(bytes, i * chunk, Math.min(bytes.length, (i
                + 1) * chunk)));
        }
        // Ordine inverso e duplicati prima e dopo la ricomposizione.
        check(listener.acceptFragment(parts[count - 1], 0) == null);
        check(listener.acceptFragment(parts[count - 1], 1) == null);
        notificationMessage result = null;
        for (int i = count - 2; i >= 0; i--) result = listener.acceptFragment(parts[i], 2);
        check(result != null && result.getMessage().equals(message));
        for (String part : parts) check(listener.acceptFragment(part, 3) == null);

        check(listener.acceptFragment("{\"eventId\":\"fraction\",\"count\":1.5,\"index\":0,\"data\":\"ew==\"}", 4) == null);
        check(listener.acceptFragment("{\"eventId\":1,\"count\":1,\"index\":0,\"data\":\"ew==\"}", 4) == null);
        check(listener.acceptFragment("{\"eventId\":\"types\",\"count\":\"1\",\"index\":0,\"data\":42}", 4) == null);

        byte[] first = Arrays.copyOfRange(bytes, 0, chunk);
        byte[] second = Arrays.copyOfRange(bytes, chunk, Math.min(bytes.length, 2 * chunk));
        check(listener.acceptFragment(fragment("missing", 0, count, first), 5) == null);
        check(listener.discardExpiredFragments(105) == 1);
        check(listener.acceptFragment(fragment("missing", 1, count, second), 106) == null);
        // Il duplicato non prolunga la vita di una notifica incompleta.
        check(listener.acceptFragment(fragment("fixed-deadline", 0, count, first), 110) == null);
        check(listener.acceptFragment(fragment("fixed-deadline", 0, count, first), 190) == null);
        check(listener.discardExpiredFragments(210) == 1);

        check(listener.acceptFragment(fragment("conflict", 0, count, first), 220) == null);
        check(listener.acceptFragment(fragment("conflict", 1, count + 1, second), 221) == null);
        check(listener.acceptFragment(fragment("conflict", 1, count, second), 222) == null);
        check(listener.acceptFragment(fragment("too-large", 0, notificationMessage.MAX_FRAGMENTS + 1, first), 223) == null);
        check(listener.acceptFragment(fragment("negative", -1, count, first), 224) == null);

        for (int i = 0; i < 8; i++) {
            check(listener.acceptFragment(fragment("pending-" + i, 0, count, first), 230) == null);
        }
        check(listener.acceptFragment(fragment("overflow", 0, count, first), 231) == null);
        check(listener.discardExpiredFragments(330) == 8);
        byte[] small = jsonCodec.toJson(new notificationMessage("NEW_GAME_STARTED", 2,
            "nuove parole")).getBytes(StandardCharsets.UTF_8);
        check(listener.acceptFragment(fragment("after-cleanup", 0, 1, small), 331) != null);
        check(listener.acceptFragment(fragment("after-cleanup", 0, 1, small), 332) == null);
        System.out.println("PASS: frammenti UDP riordinati, duplicati, UTF-8, perdita, scadenza, limiti e recupero dopo pulizia");
    }
}

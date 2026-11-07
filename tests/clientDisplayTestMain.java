import client.net.tcpClient;
import client.state.clientState;
import client.ui.commandLoop;
import client.ui.notificationPrinter;
import common.protocol.jsonCodec;
import common.protocol.notificationMessage;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class clientDisplayTestMain {

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    public static void main(String[] args) {
        JsonObject payload = jsonCodec.parseObject("""
                {"gameInfo":{"outcome":"WON","solutionGroups":[
                    {"theme":"Weather","words":["RAIN","SNOW","SLEET","HAIL"]}]},
                 "gameStats":{"participants":4,"averageScore":6},
                 "gameLeaderboard":[
                    {"rank":1,"username":"alice","score":18},
                    {"rank":2,"username":"bob","score":12},
                    {"rank":3,"username":"carol","score":6},
                    {"rank":4,"username":"dave","score":-12}]}
                """);
        String notification = notificationPrinter.format(
                new notificationMessage("GAME_ENDED", 1, payload.toString()));
        check(notification.contains("Weather: RAIN, SNOW, SLEET, HAIL"));
        check(notification.contains("dave") && notification.contains("Average score: 6"));

        // Le due voci del menu devono mostrare anche la classifica ricevuta via TCP.
        tcpClient connection = new tcpClient("localhost", 5000) {
            @Override
            public String sendAndReceive(String request) {
                check(jsonCodec.parseObject(request).get("operation").getAsString().equals("requestGameStats"));
                JsonObject response = new JsonObject();
                response.addProperty("status", "ok");
                response.add("payload", payload);
                return response.toString();
            }
        };
        clientState state = new clientState();
        state.setLoggedIn(true);
        state.setUsername("alice");

        InputStream original_input = System.in;
        PrintStream original_output = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setIn(new ByteArrayInputStream("3\n9\n1\n0\n".getBytes(StandardCharsets.UTF_8)));
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            new commandLoop(connection, state).run();
        } finally {
            System.setIn(original_input);
            System.setOut(original_output);
        }
        String displayed = output.toString(StandardCharsets.UTF_8);
        check(displayed.split("dave", -1).length == 3);
        System.out.println("PASS: soluzione UDP e classifiche complete nel menu TCP");
    }
}

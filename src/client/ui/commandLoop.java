package client.ui;

import client.net.tcpClient;
import client.state.clientState;
import common.protocol.jsonCodec;
import common.protocol.requestMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

public class commandLoop {

    private final tcpClient tcp_client;
    private final clientState state;
    private final Scanner scanner;

    public commandLoop(tcpClient tcp_client, clientState state) {
        this.tcp_client = tcp_client;
        this.state = state;
        this.scanner = new Scanner(System.in);
    }

    public void run() {
        while (true) {
            if (!state.isLoggedIn()) {
                printWelcomeMenu();
                if (!scanner.hasNextLine()) return;
                String choice = scanner.nextLine().trim();

                switch (choice) {
                    case "1" -> doRegister();
                    case "2" -> doLogin();
                    case "0" -> {
                        System.out.println("Bye.");
                        return;
                    }
                    default -> printError("Invalid option.");
                }
            } else {
                printLoggedMenu();
                if (!scanner.hasNextLine()) return;
                String choice = scanner.nextLine().trim();

                switch (choice) {
                    case "1" -> doSubmitProposal();
                    case "2" -> doRequestCurrentGameInfo();
                    case "3" -> doRequestCurrentGameStats();
                    case "4" -> doRequestLeaderboard();
                    case "5" -> doRequestPlayerStats();
                    case "6" -> doUpdateCredentials();
                    case "7" -> doLogout();
                    case "8" -> doRequestHistoricalGameInfo();
                    case "9" -> doRequestHistoricalGameStats();
                    case "0" -> {
                        System.out.println("Bye.");
                        return;
                    }
                    default -> printError("Invalid option.");
                }
            }
        }
    }

    private void printWelcomeMenu() {
        System.out.println();
        System.out.println("  +----+----+----+----+");
        System.out.println("  |    |    |    |    |");
        System.out.println("  +----+----+----+----+");
        System.out.println("  |    |    |    |    |");
        System.out.println("  +----+----+----+----+");
        System.out.println();
        System.out.println("+------------------------------------+");
        System.out.println("|         CONNECTIONS CLIENT         |");
        System.out.println("+------------------------------------+");
        System.out.println("| 1) Register                        |");
        System.out.println("| 2) Login                           |");
        System.out.println("| 0) Quit                            |");
        System.out.println("+------------------------------------+");
        System.out.print("> ");
    }

    private void printLoggedMenu() {
        System.out.println();
        System.out.println("+------------------------------------+");
        System.out.println("|             CONNECTIONS            |");
        System.out.println("+------------------------------------+");
        System.out.println("  Logged as: " + state.getUsername());
        System.out.println("+------------------------------------+");
        System.out.println("| 1) Submit proposal                 |");
        System.out.println("| 2) Show current game               |");
        System.out.println("| 3) Show current game stats         |");
        System.out.println("| 4) Show leaderboard                |");
        System.out.println("| 5) Show my stats                   |");
        System.out.println("| 6) Update credentials              |");
        System.out.println("| 7) Logout                          |");
        System.out.println("| 8) Show historical game info       |");
        System.out.println("| 9) Show historical game stats      |");
        System.out.println("+------------------------------------+");
        System.out.println("| 0) Quit                            |");
        System.out.println("+------------------------------------+");
        System.out.print("> ");
    }

    private void doRegister() {
        try {
            System.out.print("Username: ");
            String username = scanner.nextLine().trim();
            String password = inputParser.readPassword(scanner, "Password: ");

            JsonObject req = new JsonObject();
            req.addProperty("username", username);
            req.addProperty("psw", password);

            String response = send("register", req);
            handleGenericResponse(response);
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doLogin() {
        try {
            System.out.print("Username: ");
            String username = scanner.nextLine().trim();
            String password = inputParser.readPassword(scanner, "Password: ");

            JsonObject req = new JsonObject();
            req.addProperty("username", username);
            req.addProperty("psw", password);
            req.addProperty("udpPort", state.getUdpPort());

            String response = send("login", req);
            JsonObject root = parse(response);

            if (isOk(root)) {
                state.setLoggedIn(true);
                state.setUsername(username);

                printSuccess(getMessage(root));

                JsonObject payload = getObject(root, "payload");
                if (payload != null) {
                    JsonObject current_game = getObject(payload, "currentGame");
                    if (current_game != null) {
                        printGameInfo(current_game);
                    }
                }
            } else {
                printError(getMessage(root));
            }
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doSubmitProposal() {
        try {
            JsonObject info_request = new JsonObject();
            info_request.addProperty("gameId", -1);
            JsonObject info_response = parse(send("requestGameInfo", info_request));
            if (!isOk(info_response)) {
                printError(getMessage(info_response));
                return;
            }

            JsonObject info_payload = getObject(info_response, "payload");
            JsonObject game_info = info_payload == null ? null : getObject(info_payload, "gameInfo");
            if (game_info == null || !game_info.has("visibleWords")) {
                printError("No playable board available.");
                return;
            }

            JsonArray board = game_info.getAsJsonArray("visibleWords");
            printWordGrid(board);
            System.out.println("Enter 4 words separated by commas, or 4 numbers (example: 1 3 7 12).");
            System.out.print("> ");
            String line = scanner.nextLine().trim();

            JsonObject req = new JsonObject();
            JsonArray arr = new JsonArray();
            if (line.matches("[0-9,\\s]+")) {
                String[] numbers = line.split("[,\\s]+", -1);
                java.util.Set<Integer> selected = new java.util.HashSet<>();
                if (numbers.length != 4) {
                    printError("Select exactly 4 different numbers.");
                    return;
                }

                for (String number : numbers) {
                    int index;
                    try {
                        index = Integer.parseInt(number) - 1;
                    } catch (NumberFormatException e) {
                        printError("Enter numbers from 1 to " + board.size() + ".");
                        return;
                    }
                    if (index < 0 || index >= board.size() || !selected.add(index)) {
                        printError("Select 4 different numbers from 1 to " + board.size() + ".");
                        return;
                    }
                    arr.add(board.get(index).getAsString());
                }
            } else {
                for (String part : line.split(",")) {
                    String word = part.trim();
                    if (!word.isEmpty()) arr.add(word);
                }
            }

            req.add("words", arr);
            req.addProperty("gameId", game_info.get("gameId").getAsInt());

            String response = send("submitProposal", req);
            JsonObject root = parse(response);

            if (!isOk(root)) {
                printError(getMessage(root));
                return;
            }

            printSuccess(getMessage(root));

            JsonObject payload = getObject(root, "payload");
            JsonObject result = payload != null ? getObject(payload, "result") : null;

            if (result == null) {
                return;
            }

            String type = result.has("type") ? result.get("type").getAsString() : "unknown";
            String msg = result.has("message") ? result.get("message").getAsString() : "";

            System.out.println();
            System.out.println("=== PROPOSAL RESULT ===");

            switch (type.toLowerCase()) {
                case "correct" -> {
                    System.out.println("Correct group.");
                    if (!msg.isBlank()) System.out.println(msg);
                }
                case "wrong" -> {
                    System.out.println("Wrong group.");
                    if (!msg.isBlank()) System.out.println(msg);
                }
                case "malformed" -> {
                    System.out.println("Malformed proposal.");
                    if (!msg.isBlank()) System.out.println(msg);
                }
                default -> {
                    System.out.println("Result: " + type.toUpperCase());
                    if (!msg.isBlank()) System.out.println(msg);
                }
            }

            printIfPresent(result, "theme", "Theme");
            printIfPresent(result, "correctGroupsFound", "Correct groups found");
            printIfPresent(result, "wrongProposals", "Wrong proposals");
            printIfPresent(result, "currentScore", "Current score");
            printIfPresent(result, "finished", "Finished");
            printIfPresent(result, "outcome", "Outcome");

            System.out.println("=======================");
            JsonObject final_info = getObject(payload, "gameInfo");
            if (final_info != null) printGameInfo(final_info);
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doRequestCurrentGameInfo() {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("gameId", -1);

            String response = send("requestGameInfo", req);
            JsonObject root = parse(response);

            if (!isOk(root)) {
                printError(getMessage(root));
                return;
            }

            JsonObject payload = getObject(root, "payload");
            JsonObject game_info = payload != null ? getObject(payload, "gameInfo") : null;

            if (game_info != null) {
                printGameInfo(game_info);
            } else {
                printError("No current game info returned.");
            }
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doRequestCurrentGameStats() {
        try {
            JsonObject req = new JsonObject();
            req.addProperty("gameId", -1);

            String response = send("requestGameStats", req);
            JsonObject root = parse(response);

            if (!isOk(root)) {
                printError(getMessage(root));
                return;
            }

            JsonObject payload = getObject(root, "payload");
            JsonObject stats = payload != null ? getObject(payload, "gameStats") : null;

            if (stats == null) {
                printError("No current game stats returned.");
                return;
            }

            System.out.println();
            System.out.println("=== CURRENT GAME STATS ===");
            printIfPresent(stats, "gameId", "Game ID");
            printIfPresent(stats, "remainingSeconds", "Remaining seconds");
            printIfPresent(stats, "playersStillPlaying", "Players still playing");
            printIfPresent(stats, "playersFinished", "Players finished");
            printIfPresent(stats, "playersWon", "Players won");
            printIfPresent(stats, "participants", "Participants");
            printIfPresent(stats, "averageScore", "Average score");
            printGameLeaderboard(payload);
            System.out.println("==========================");
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doRequestLeaderboard() {
        try {
            System.out.println("1) Full leaderboard");
            System.out.println("2) Top K players");
            System.out.println("3) Single player rank");
            System.out.print("> ");

            String choice = scanner.nextLine().trim();

            JsonObject req = new JsonObject();

            switch (choice) {
                case "1" -> {
                }
                case "2" -> {
                    System.out.print("Top how many players? ");
                    int k = Integer.parseInt(scanner.nextLine().trim());
                    req.addProperty("topPlayers", k);
                }
                case "3" -> {
                    System.out.print("Player username: ");
                    String player_name = scanner.nextLine().trim();
                    req.addProperty("playerName", player_name);
                }
                default -> {
                    printError("Invalid option.");
                    return;
                }
            }

            String response = send("requestLeaderboard", req);
            JsonObject root = parse(response);

            if (!isOk(root)) {
                printError(getMessage(root));
                return;
            }

            JsonObject payload = getObject(root, "payload");

            System.out.println();
            System.out.println("=========== LEADERBOARD ===========");

            if (payload != null && payload.has("playerRank")) {
                JsonObject obj = payload.getAsJsonObject("playerRank");
                int rank = obj.has("rank") ? obj.get("rank").getAsInt() : 0;
                String username = obj.has("username") ? obj.get("username").getAsString() : "?";
                int total_score = obj.has("totalScore") ? obj.get("totalScore").getAsInt() : 0;
                System.out.printf("%2d) %-15s | score: %-4d%n", rank, username, total_score);
                System.out.println("===================================");
                return;
            }

            JsonArray leaderboard = payload != null && payload.has("leaderboard")
                    ? payload.getAsJsonArray("leaderboard")
                    : null;

            if (leaderboard == null || leaderboard.isEmpty()) {
                System.out.println("No leaderboard data.");
                System.out.println("===================================");
                return;
            }

            for (JsonElement el : leaderboard) {
                JsonObject obj = el.getAsJsonObject();
                int rank = obj.has("rank") ? obj.get("rank").getAsInt() : 0;
                String username = obj.has("username") ? obj.get("username").getAsString() : "?";
                int total_score = obj.has("totalScore") ? obj.get("totalScore").getAsInt() : 0;

                System.out.printf("%2d) %-15s | score: %-4d%n", rank, username, total_score);
            }

            System.out.println("===================================");
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doRequestPlayerStats() {
        try {
            String response = send("requestPlayerStats", new JsonObject());
            JsonObject root = parse(response);

            if (!isOk(root)) {
                printError(getMessage(root));
                return;
            }

            JsonObject payload = getObject(root, "payload");
            JsonObject stats = payload != null ? getObject(payload, "playerStats") : null;

            if (stats == null) {
                printError("No player stats returned.");
                return;
            }

            System.out.println();
            System.out.println("============ MY STATS ============");
            for (String key : stats.keySet()) {
                JsonElement value = stats.get(key);
                System.out.printf("%-24s : %s%n", key, value.toString().replace("\"", ""));
            }

            System.out.println("==================================");
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doUpdateCredentials() {
        try {
            System.out.print("Old username: ");
            String old_username = scanner.nextLine().trim();
            String old_password = inputParser.readPassword(scanner, "Old password: ");
            System.out.print("New username (leave blank to keep): ");
            String new_username = scanner.nextLine().trim();
            String new_password = inputParser.readPassword(scanner, "New password (leave blank to keep): ");

            JsonObject req = new JsonObject();
            req.addProperty("oldUsername", old_username);
            req.addProperty("oldPsw", old_password);

            if (!new_username.isBlank()) {
                req.addProperty("newUsername", new_username);
            }
            if (!new_password.isBlank()) {
                req.addProperty("newPsw", new_password);
            }

            String response = send("updateCredentials", req);
            JsonObject root = parse(response);

            if (isOk(root)) {
                printSuccess(getMessage(root));
                if (!new_username.isBlank() && old_username.equals(state.getUsername())) {
                    state.setUsername(new_username);
                }
            } else {
                printError(getMessage(root));
            }
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doLogout() {
        try {
            String response = send("logout", new JsonObject());
            JsonObject root = parse(response);

            if (isOk(root)) {
                printSuccess(getMessage(root));
                state.clearLogin();
            } else {
                printError(getMessage(root));
            }
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doRequestHistoricalGameInfo() {
        try {
            System.out.print("Historical game ID: ");
            int game_id = Integer.parseInt(scanner.nextLine().trim());

            JsonObject req = new JsonObject();
            req.addProperty("gameId", game_id);

            String response = send("requestGameInfo", req);
            JsonObject root = parse(response);

            if (!isOk(root)) {
                printError(getMessage(root));
                return;
            }

            JsonObject payload = getObject(root, "payload");
            JsonObject game_info = payload != null ? getObject(payload, "gameInfo") : null;

            if (game_info != null) {
                printGameInfo(game_info);
            } else {
                printError("No historical game info returned.");
            }
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void doRequestHistoricalGameStats() {
        try {
            System.out.print("Historical game ID: ");
            int game_id = Integer.parseInt(scanner.nextLine().trim());

            JsonObject req = new JsonObject();
            req.addProperty("gameId", game_id);

            String response = send("requestGameStats", req);
            JsonObject root = parse(response);

            if (!isOk(root)) {
                printError(getMessage(root));
                return;
            }

            JsonObject payload = getObject(root, "payload");
            JsonObject stats = payload != null ? getObject(payload, "gameStats") : null;

            if (stats == null) {
                printError("No historical game stats returned.");
                return;
            }

            System.out.println();
            System.out.println("======= HISTORICAL GAME STATS =======");
            for (String key : stats.keySet()) {
                JsonElement value = stats.get(key);
                System.out.printf("%-24s : %s%n", key, value.toString().replace("\"", ""));
            }

            System.out.println("=====================================");
            printGameLeaderboard(payload);
        } catch (Exception e) {
            printError(e.getMessage());
        }
    }

    private void printGameLeaderboard(JsonObject payload) {
        if (!payload.has("gameLeaderboard")) return;

        System.out.println("Game leaderboard:");
        for (JsonElement element : payload.getAsJsonArray("gameLeaderboard")) {
            JsonObject entry = element.getAsJsonObject();
            System.out.printf("%2d) %-15s | score: %d%n", entry.get("rank").getAsInt(),
                    entry.get("username").getAsString(), entry.get("score").getAsInt());
        }
    }

    private void printGameInfo(JsonObject game_info) {
        System.out.println();
        System.out.println("============= GAME INFO =============");
        printIfPresent(game_info, "gameId", "Game ID");
        printIfPresent(game_info, "status", "Status");
        printIfPresent(game_info, "remainingSeconds", "Remaining seconds");
        printIfPresent(game_info, "wrongProposals", "Wrong proposals");
        printIfPresent(game_info, "currentScore", "Current score");
        printIfPresent(game_info, "correctGroupsFound", "Correct groups found");
        printIfPresent(game_info, "canSubmit", "Can submit");
        printIfPresent(game_info, "outcome", "Outcome");

        if (game_info.has("foundGroupKeys")) {
            JsonArray found = game_info.getAsJsonArray("foundGroupKeys");
            System.out.println();
            System.out.println("Found groups (" + found.size() + "/3 to win):");
            if (found.isEmpty()) {
                System.out.println("  No groups found yet.");
            } else {
                int number = 1;
                for (JsonElement el : found) {
                    String[] words = el.getAsString().split("\\|");
                    printGroupBox("Group " + number + " - found", List.of(words));
                    number++;
                }
            }
        }

        if (game_info.has("visibleWords")) {
            JsonArray words = game_info.getAsJsonArray("visibleWords");
            printWordGrid(words);
        }

        if (game_info.has("solutionGroups")) {
            JsonArray groups = game_info.getAsJsonArray("solutionGroups");
            printSolutionGroups(groups);
        }

        System.out.println("=====================================");
    }

    private void printWordGrid(JsonArray words) {
        System.out.println();
        System.out.println("Remaining words (" + words.size() + "):");
        if (words.isEmpty()) {
            System.out.println("  No words remaining.");
            return;
        }

        int columns = Math.min(4, words.size());
        int width = 12;
        for (int i = 0; i < words.size(); i++) {
            width = Math.max(width, ((i + 1) + ") " + words.get(i).getAsString()).length());
        }

        String border = "+" + ("-".repeat(width + 2) + "+").repeat(columns);
        System.out.println(border);
        for (int row = 0; row < words.size(); row += columns) {
            System.out.print("|");
            for (int column = 0; column < columns; column++) {
                int index = row + column;
                String word = index < words.size() ? (index + 1) + ") " + words.get(index).getAsString() : "";
                int left = (width - word.length()) / 2;
                int right = width - word.length() - left;
                System.out.print(" " + " ".repeat(left) + word + " ".repeat(right) + " |");
            }

            System.out.println();
            System.out.println(border);
        }
    }

    private void printSolutionGroups(JsonArray groups) {
        System.out.println();
        System.out.println("Solution groups:");
        int idx = 1;
        for (JsonElement el : groups) {
            JsonObject g = el.getAsJsonObject();
            String key = g.has("theme") ? g.get("theme").getAsString() : ("GROUP_" + idx);
            JsonArray words = g.has("words") ? g.getAsJsonArray("words") : new JsonArray();

            List<String> items = new ArrayList<>();
            for (JsonElement w : words) {
                items.add(w.getAsString());
            }

            printGroupBox(idx + ". " + key, items);
            idx++;
        }
    }

    private void printGroupBox(String title, List<String> words) {
        int width = title.length();
        for (String word : words) {
            width = Math.max(width, word.length());
        }

        String border = "+-" + "-".repeat(width) + "-+";
        System.out.println(border);
        System.out.printf("| %-" + width + "s |%n", title);
        System.out.println(border);
        for (String word : words) {
            System.out.printf("| %-" + width + "s |%n", word);
        }

        System.out.println(border);
    }

    private void handleGenericResponse(String response) {
        JsonObject root = parse(response);
        if (isOk(root)) {
            printSuccess(getMessage(root));
        } else {
            printError(getMessage(root));
        }
    }

    private String send(String operation, JsonObject payload) throws IOException {
        requestMessage request = new requestMessage();
        request.setOperation(operation);
        request.setPayload(payload);
        return tcp_client.sendAndReceive(jsonCodec.toJson(requestToWire(request)));
    }

    private JsonObject requestToWire(requestMessage request) {
        JsonObject obj = new JsonObject();
        obj.addProperty("operation", request.getOperation());

        JsonObject payload = request.getPayload();
        if (payload != null) {
            for (String key : payload.keySet()) {
                obj.add(key, payload.get(key));
            }
        }

        return obj;
    }

    private JsonObject parse(String response) {
        return com.google.gson.JsonParser.parseString(response).getAsJsonObject();
    }

    private boolean isOk(JsonObject root) {
        return root.has("status") && "ok".equalsIgnoreCase(root.get("status").getAsString());
    }

    private String getMessage(JsonObject root) {
        return root.has("message") ? root.get("message").getAsString() : "No message";
    }

    private JsonObject getObject(JsonObject root, String key) {
        if (root == null || !root.has(key) || root.get(key).isJsonNull()) {
            return null;
        }

        return root.getAsJsonObject(key);
    }

    private void printIfPresent(JsonObject obj, String key, String label) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            System.out.printf("%-20s : %s%n", label, obj.get(key).toString().replace("\"", ""));
        }
    }

    private void printSuccess(String msg) {
        System.out.println();
        System.out.println("" + msg);
    }

    private void printError(String msg) {
        System.out.println();
        System.out.println("Error: " + msg);
    }
}

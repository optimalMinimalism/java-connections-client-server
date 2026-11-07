package client.ui;

import common.protocol.jsonCodec;
import common.protocol.notificationMessage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class notificationPrinter {

    private notificationPrinter() {
    }

    public static String format(notificationMessage message) {
        String title = switch (message.getType()) {
            case "NEW_GAME_STARTED" -> "New game started";
            case "GAME_ENDED" -> "Game ended";
            case "PLAYER_FINISHED" -> "A player finished";
            default -> "Game update";
        };
        StringBuilder text = new StringBuilder("\n+--- Game notification ---+\n");
        text.append(title).append(" | Game #").append(message.getGameId()).append('\n');
        try {
            JsonObject payload = jsonCodec.parseObject(message.getMessage());
            JsonObject info = object(payload, "gameInfo");
            field(text, info, "outcome", "Your result");
            field(text, info, "currentScore", "Your score");
            field(text, info, "correctGroupsFound", "Groups found");
            field(text, info, "remainingSeconds", "Seconds remaining");
            if (info.has("visibleWords")) {
                text.append("Words: ");
                words(text, info.getAsJsonArray("visibleWords"));
            }
            if (info.has("solutionGroups")) {
                text.append("Solution:\n");
                for (JsonElement element : info.getAsJsonArray("solutionGroups")) {
                    JsonObject group = element.getAsJsonObject();
                    text.append("  ").append(group.get("theme").getAsString()).append(": ");
                    words(text, group.getAsJsonArray("words"));
                }
            }
            JsonObject stats = object(payload, "gameStats");
            field(text, stats, "playersStillPlaying", "Still playing");
            field(text, stats, "playersFinished", "Finished");
            field(text, stats, "playersWon", "Winners");
            field(text, stats, "participants", "Participants");
            field(text, stats, "averageScore", "Average score");
            if (payload.has("gameLeaderboard") && payload.get("gameLeaderboard").isJsonArray()) {
                int shown = 0;
                for (JsonElement element : payload.getAsJsonArray("gameLeaderboard")) {
                    if (!element.isJsonObject()) continue;
                    JsonObject entry = element.getAsJsonObject();
                    if (!entry.has("rank") || !entry.has("username") || !entry.has("score")) continue;
                    if (shown++ == 0) text.append("Game leaderboard:\n");
                    text.append("  ").append(entry.get("rank").getAsInt()).append(") ")
                            .append(entry.get("username").getAsString()).append(" | ")
                            .append(entry.get("score").getAsInt()).append(" points\n");
                }
            }
        } catch (RuntimeException e) {
            text.append("Details unavailable. Refresh game info from the menu.\n");
        }
        text.append("+-------------------------+\n");
        return text.toString();
    }

    private static void words(StringBuilder text, Iterable<JsonElement> words) {
        boolean first = true;
        for (JsonElement word : words) {
            if (!first) text.append(", ");
            text.append(word.getAsString());
            first = false;
        }
        text.append('\n');
    }

    private static JsonObject object(JsonObject parent, String key) {
        return parent.has(key) && parent.get(key).isJsonObject()
                ? parent.getAsJsonObject(key) : new JsonObject();
    }

    private static void field(StringBuilder text, JsonObject source, String key, String label) {
        if (source.has(key) && source.get(key).isJsonPrimitive()) {
            text.append(label).append(": ").append(source.get(key).getAsString()).append('\n');
        }
    }
}

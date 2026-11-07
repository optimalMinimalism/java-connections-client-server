package server.core;

import common.model.gameInstance;
import common.model.playerGameState;
import common.model.user;
import common.model.userStats;
import common.model.enums.playerOutcome;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class statsService {

    private final userManager user_manager;

    public statsService(userManager user_manager) {
        this.user_manager = user_manager;
    }

    public JsonObject buildPlayerStats(String account_id) {
        user account = user_manager.getUserById(account_id);
        if (account == null) {
            return null;
        }

        userStats stats = account.getStats();
        JsonObject root = new JsonObject();

        root.addProperty("username", account.getUsername());
        root.addProperty("totalScore", account.getTotalScore());
        root.addProperty("puzzlesCompleted", stats.getGamesPlayed());
        root.addProperty("winRate", stats.getWinRate());
        root.addProperty("lossRate", stats.getLossRate());
        root.addProperty("currentStreak", stats.getCurrentStreak());
        root.addProperty("maxStreak", stats.getMaxStreak());
        root.addProperty("perfectPuzzles", stats.getPerfectPuzzles());

        JsonArray histogram = new JsonArray();
        for (int value : stats.getMistakeHistogram()) {
            histogram.add(value);
        }
        root.add("mistakeHistogram", histogram);

        return root;
    }

    public JsonObject buildCurrentGameStats(gameInstance game_instance) {
        JsonObject root = new JsonObject();
        root.addProperty("gameId", game_instance.getGameId());
        root.addProperty("remainingSeconds", game_instance.getRemainingSeconds());

        int still_playing = 0;
        int finished = 0;
        int winners = 0;

        for (playerGameState state : game_instance.getPlayerStates().values()) {
            if (state.getOutcome() == playerOutcome.PLAYING) {
                still_playing++;
            } else {
                finished++;
                if (state.getOutcome() == playerOutcome.WON) {
                    winners++;
                }
            }
        }

        root.addProperty("playersStillPlaying", still_playing);
        root.addProperty("playersFinished", finished);
        root.addProperty("playersWon", winners);
        return root;
    }

    public JsonObject buildFinishedGameStats(gameInstance game_instance) {
        JsonObject root = new JsonObject();
        root.addProperty("gameId", game_instance.getGameId());

        int participants = game_instance.getParticipantsCount();
        int finished = 0;
        int winners = 0;
        double score_sum = 0.0;

        for (playerGameState state : game_instance.getPlayerStates().values()) {
            if (state.getOutcome() != playerOutcome.PLAYING) {
                finished++;
            }
            if (state.getOutcome() == playerOutcome.WON) {
                winners++;
            }
            score_sum += state.getCurrentScore();
        }

        root.addProperty("participants", participants);
        root.addProperty("playersFinished", finished);
        root.addProperty("playersWon", winners);
        root.addProperty("averageScore", participants == 0 ? 0.0 : score_sum / participants);

        return root;
    }
}

package common.model;

import com.google.gson.annotations.SerializedName;

import common.model.enums.gameStatus;

import java.util.Collections;
import java.util.Map;
import java.util.HashMap;

public class gameInstance {

    @SerializedName("gameId")
    private int game_id;
    @SerializedName("startTimeEpochMillis")
    private long start_time_epoch_millis;
    @SerializedName("endTimeEpochMillis")
    private long end_time_epoch_millis;
    private gameStatus status;
    private gameDefinition definition;
    @SerializedName("playerStates")
    private Map<String, playerGameState> player_states;

    public gameInstance() {
        this.status = gameStatus.ACTIVE;
        this.player_states = new HashMap<>();
    }

    public gameInstance(gameDefinition definition, long start_time_epoch_millis, long end_time_epoch_millis) {
        this();
        this.definition = definition;
        this.game_id = definition.getGameId();
        this.start_time_epoch_millis = start_time_epoch_millis;
        this.end_time_epoch_millis = end_time_epoch_millis;
    }

    public int getGameId() {
        return game_id;
    }

    public void setGameId(int game_id) {
        this.game_id = game_id;
    }

    public long getStartTimeEpochMillis() {
        return start_time_epoch_millis;
    }

    public void setStartTimeEpochMillis(long start_time_epoch_millis) {
        this.start_time_epoch_millis = start_time_epoch_millis;
    }

    public long getEndTimeEpochMillis() {
        return end_time_epoch_millis;
    }

    public void setEndTimeEpochMillis(long end_time_epoch_millis) {
        this.end_time_epoch_millis = end_time_epoch_millis;
    }

    public gameStatus getStatus() {
        return status;
    }

    public void setStatus(gameStatus status) {
        this.status = status;
    }

    public gameDefinition getDefinition() {
        return definition;
    }

    public void setDefinition(gameDefinition definition) {
        this.definition = definition;
    }

    public Map<String, playerGameState> getPlayerStates() {
        return Collections.unmodifiableMap(player_states);
    }

    public void setPlayerStates(Map<String, playerGameState> player_states) {
        this.player_states = new HashMap<>(player_states);
    }

    public playerGameState getOrCreatePlayerState(String account_id) {
        return player_states.computeIfAbsent(account_id, playerGameState::new);
    }

    public playerGameState getPlayerState(String account_id) {
        return player_states.get(account_id);
    }

    public boolean isActive() {
        return status == gameStatus.ACTIVE;
    }

    public int getParticipantsCount() {
        return player_states.size();
    }

    public long getRemainingSeconds() {
        long now = System.currentTimeMillis();
        long remaining_millis = end_time_epoch_millis - now;
        return Math.max(0L, remaining_millis / 1000L);
    }

    @Override
    public String toString() {
        return "GameInstance{" +
                "gameId=" + game_id +
                ", startTimeEpochMillis=" + start_time_epoch_millis +
                ", endTimeEpochMillis=" + end_time_epoch_millis +
                ", status=" + status +
                ", definition=" + definition +
                ", participants=" + player_states.size() +
                '}';
    }
}

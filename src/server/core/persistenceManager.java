package server.core;

import com.google.gson.annotations.SerializedName;

import common.model.gameInstance;
import common.model.user;
import common.model.userStats;
import common.model.playerGameState;
import common.model.groupDefinition;
import common.model.enums.playerOutcome;
import common.util.canonicalizer;
import common.protocol.jsonCodec;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

public class persistenceManager {

    public static class persistedState {
        private Map<String, user> users;
        @SerializedName("currentPuzzleIndex")
        private int current_puzzle_index;
        @SerializedName("currentGame")
        private gameInstance current_game;
        @SerializedName("historicalGames")
        private List<gameInstance> historical_games;

        public persistedState() {
            this.users = new HashMap<>();
            this.historical_games = new ArrayList<>();
        }

        public Map<String, user> getUsers() {
            return users;
        }

        public void setUsers(Map<String, user> users) {
            this.users = users;
        }

        public int getCurrentPuzzleIndex() {
            return current_puzzle_index;
        }

        public void setCurrentPuzzleIndex(int current_puzzle_index) {
            this.current_puzzle_index = current_puzzle_index;
        }

        public gameInstance getCurrentGame() {
            return current_game;
        }

        public void setCurrentGame(gameInstance current_game) {
            this.current_game = current_game;
        }

        public List<gameInstance> getHistoricalGames() {
            return historical_games;
        }

        public void setHistoricalGames(List<gameInstance> historical_games) {
            this.historical_games = historical_games;
        }
    }

    public persistedState load(String games_state_path) throws IOException {
        try {
            Path snapshot = Path.of(games_state_path + ".snapshot.json");
            if (!Files.notExists(snapshot)) {
                JsonObject root = jsonCodec.parseObject(Files.readString(snapshot, StandardCharsets.UTF_8));
                if (!root.has("users") || !root.get("users").isJsonObject()
                        || !root.has("historicalGames") || !root.get("historicalGames").isJsonArray()
                        || !root.has("currentPuzzleIndex")
                            || !root.get("currentPuzzleIndex").isJsonPrimitive()
                        || !root.getAsJsonPrimitive("currentPuzzleIndex").isNumber()) {
                    throw new IOException("Snapshot incompleto: " + snapshot);
                }

                int puzzle_index = root.get("currentPuzzleIndex").getAsBigDecimal().intValueExact();
                if (puzzle_index < 0) throw new IOException("Indice puzzle negativo: " + snapshot);
                persistedState loaded = jsonCodec.gson().fromJson(root, persistedState.class);
                validateState(loaded);
                return loaded;
            }

            return new persistedState();
        } catch (RuntimeException e) {
            throw new IOException("JSON di persistenza non valido per " + games_state_path, e);
        }
    }

    private void validateState(persistedState state) {
        Set<String> account_ids = new HashSet<>();
        for (Map.Entry<String, user> entry : state.users.entrySet()) {
            user account = entry.getValue();
            if (account == null || account.getUsername() == null || account.getUsername().isBlank()
                    || !entry.getKey().equals(account.getUsername()) || account.getAccountId() == null
                    || account.getAccountId().isBlank() || !account_ids.add(account.getAccountId())
                    || account.getPassword() == null || account.getPassword().isBlank()) {
                throw new IllegalArgumentException("Account persistito non valido");
            }

            userStats stats = account.getStats();
            if (stats == null || stats.getMistakeHistogram() == null || stats.getMistakeHistogram().length != 6
                    || stats.getGamesPlayed() < 0 || stats.getGamesWon() < 0 || stats.getGamesLost() < 0
                    || stats.getGamesUnfinished() < 0 || stats.getCurrentStreak() < 0
                    || stats.getMaxStreak() < stats.getCurrentStreak()
                        || stats.getMaxStreak() > stats.getGamesWon()
                    || stats.getPerfectPuzzles() < 0 || stats.getPerfectPuzzles() > stats.getGamesWon()) {
                throw new IllegalArgumentException("Statistiche persistite non valide");
            }

            long histogram_total = 0;
            for (int count : stats.getMistakeHistogram()) {
                if (count < 0) throw new IllegalArgumentException("Istogramma negativo");
                histogram_total += count;
            }

            int[] histogram = stats.getMistakeHistogram();
            long histogram_wins = (long) histogram[0] + histogram[1] + histogram[2] + histogram[3];
            if (histogram_total != stats.getGamesPlayed() || histogram_wins != stats.getGamesWon()
                    || histogram[4] != stats.getGamesLost() || histogram[5] != stats.getGamesUnfinished()
                    || histogram[0] != stats.getPerfectPuzzles()
                    || (long) stats.getGamesWon() + stats.getGamesLost()
                        + stats.getGamesUnfinished() != stats.getGamesPlayed()) {
                throw new IllegalArgumentException("Conteggi delle statistiche incoerenti");
            }
        }

        Map<Integer, gameInstance> history = new HashMap<>();
        for (gameInstance game : state.historical_games) {
            validateGame(game, account_ids);
            if (game.isActive() || history.put(game.getGameId(), game) != null) {
                throw new IllegalArgumentException("Storico attivo o ID partita duplicato");
            }
        }
        if (state.current_game != null) {
            validateGame(state.current_game, account_ids);
            gameInstance archived = history.get(state.current_game.getGameId());
            if (archived != null && (state.current_game.isActive()
                    || !jsonCodec.gson().toJsonTree(archived).equals(jsonCodec.gson().toJsonTree(state.current_game)))) {
                throw new IllegalArgumentException("Partita corrente in conflitto con lo storico");
            }
            if (!state.current_game.isActive() && archived == null) {
                throw new IllegalArgumentException("Partita conclusa assente dallo storico");
            }
            history.put(state.current_game.getGameId(), state.current_game);
        }

        Map<String, Long> scores = new HashMap<>();
        Map<String, Integer> completed = new HashMap<>();
        for (gameInstance game : history.values()) {
            for (playerGameState player : game.getPlayerStates().values()) {
                if (!player.isFinished()) continue;
                String account_id = player.getAccountId();
                scores.put(account_id, scores.getOrDefault(account_id, 0L) + player.getCurrentScore());
                completed.put(account_id, completed.getOrDefault(account_id, 0) + 1);
            }
        }

        for (user account : state.users.values()) {
            String account_id = account.getAccountId();
            if (account.getTotalScore() != scores.getOrDefault(account_id, 0L)
                    || account.getStats().getGamesPlayed() != completed.getOrDefault(account_id, 0)) {
                throw new IllegalArgumentException("Punteggi o conteggi non corrispondenti alle partite");
            }
        }
    }

    private void validateGame(gameInstance game, Set<String> account_ids) {
        if (game == null || game.getGameId() < 1 || game.getStatus() == null || game.getDefinition() == null
                || game.getStartTimeEpochMillis() < 0
                    || game.getEndTimeEpochMillis() <= game.getStartTimeEpochMillis()) {
            throw new IllegalArgumentException("Partita persistita non valida");
        }
        game.getDefinition().validate();
        Set<String> group_keys = new HashSet<>();
        for (groupDefinition group : game.getDefinition().getGroups()) {
            group_keys.add(canonicalizer.canonicalizeWords(group.getWords()));
        }

        for (Map.Entry<String, playerGameState> entry : game.getPlayerStates().entrySet()) {
            playerGameState player = entry.getValue();
            if (player == null || !account_ids.contains(entry.getKey()) || !entry.getKey().equals(player.getAccountId())
                    || player.getOutcome() == null || player.getCorrectGroupsFound() < 0
                        || player.getCorrectGroupsFound() > 3
                    || player.getWrongProposals() < 0 || player.getWrongProposals() > 4
                    || player.getFoundGroupKeys().size() != player.getCorrectGroupsFound()
                    || !group_keys.containsAll(player.getFoundGroupKeys())
                    || player.getCurrentScore() != player.getCorrectGroupsFound() * 6 - player.getWrongProposals() * 4) {
                throw new IllegalArgumentException("Partecipazione persistita non valida");
            }
            playerOutcome outcome = player.getOutcome();
            if ((!game.isActive() && outcome == playerOutcome.PLAYING)
                    || (game.isActive() && outcome == playerOutcome.TIMEOUT)
                    || (outcome == playerOutcome.WON && (player.getCorrectGroupsFound() != 3
                        || player.getWrongProposals() == 4))
                    || (outcome == playerOutcome.LOST && player.getWrongProposals() != 4)
                    || (outcome != playerOutcome.WON && player.getCorrectGroupsFound() == 3)
                    || (outcome != playerOutcome.LOST && player.getWrongProposals() == 4)) {
                throw new IllegalArgumentException("Esito persistito incoerente");
            }
        }
    }

    public void save(
            String games_state_path,
            Map<String, user> users,
            int current_puzzle_index,
            gameInstance current_game,
            List<gameInstance> historical_games
    ) throws IOException {
        // Il chiamante deve tenere state_lock durante il salvataggio.
        // Utenti e partite vengono salvati insieme.
        persistedState state = new persistedState();
        state.setUsers(users);
        state.setCurrentPuzzleIndex(current_puzzle_index);
        state.setCurrentGame(current_game);
        state.setHistoricalGames(historical_games);
        Path target = Path.of(games_state_path + ".snapshot.json").toAbsolutePath();
        Path temporary = Files.createTempFile(target.getParent(), "snapshot-", ".tmp");
        try {
            byte[] bytes = jsonCodec.toJson(state).getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            // Richiede un filesystem che supporti ATOMIC_MOVE.
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}

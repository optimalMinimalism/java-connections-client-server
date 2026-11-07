package server.core;

import common.model.gameDefinition;
import common.model.gameInstance;
import common.model.groupDefinition;
import common.model.playerGameState;
import common.model.user;
import common.model.userStats;
import common.model.enums.gameStatus;
import common.model.enums.playerOutcome;
import common.util.canonicalizer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.function.BiConsumer;

// Accessi protetti da state_lock.
public class gameManager {

    private final puzzleRepository puzzle_repository;
    private final userManager user_manager;

    private final Map<Integer, gameInstance> historical_games;
    private gameInstance current_game;
    private final long game_duration_seconds;
    private BiConsumer<Integer, String> game_event_listener = (id, type) -> {};

    public void setGameEventListener(BiConsumer<Integer, String> listener) {
        game_event_listener = listener;
    }

    public boolean finishExpiredGame() {
        if (current_game == null || !current_game.isActive()
                || System.currentTimeMillis() < current_game.getEndTimeEpochMillis()) {
            return false;
        }
        endCurrentGame();
        return true;
    }

    public gameManager(puzzleRepository puzzle_repository, userManager user_manager, long game_duration_seconds) {
        if (game_duration_seconds <= 0) throw new IllegalArgumentException("Durata partita non valida");
        Math.addExact(System.currentTimeMillis(), Math.multiplyExact(game_duration_seconds, 1000L));

        this.puzzle_repository = puzzle_repository;
        this.user_manager = user_manager;
        this.game_duration_seconds = game_duration_seconds;
        this.historical_games = new HashMap<>();
    }

    public void startNewGame() {
        gameDefinition definition = puzzle_repository.getNextPuzzle();
        long start = System.currentTimeMillis();
        long end = Math.addExact(start, Math.multiplyExact(game_duration_seconds, 1000L));

        int next_id = 1;
        for (Integer id : historical_games.keySet()) next_id = Math.max(next_id, Math.addExact(id, 1));
        if (current_game != null) next_id = Math.max(next_id, Math.addExact(current_game.getGameId(), 1));

        current_game = new gameInstance(definition, start, end);
        current_game.setGameId(next_id);
        current_game.setStatus(gameStatus.ACTIVE);
    }

    public void endCurrentGame() {
        if (current_game == null || !current_game.isActive()) {
            return;
        }

        for (playerGameState state : current_game.getPlayerStates().values()) {
            if (state.getOutcome() == playerOutcome.PLAYING) {
                finishPlayer(state, playerOutcome.TIMEOUT);
            }
        }

        current_game.setStatus(gameStatus.ENDED);
        historical_games.put(current_game.getGameId(), current_game);
        // Notifica dopo aver chiuso la partita.
        game_event_listener.accept(current_game.getGameId(), "GAME_ENDED");
    }

    public void ensureCurrentGameExists() {
        if (current_game == null) {
            startNewGame();
        }
    }

    public gameInstance getCurrentGame() {
        return current_game;
    }

    public gameInstance getGameById(int game_id) {
        if (current_game != null && current_game.getGameId() == game_id) {
            return current_game;
        }

        return historical_games.get(game_id);
    }

    public List<gameInstance> getHistoricalGames() {
        return new ArrayList<>(historical_games.values());
    }

    public void restoreState(gameInstance restored_current_game, List<gameInstance> restored_historical_games) {
        this.current_game = restored_current_game;
        this.historical_games.clear();

        if (restored_historical_games != null) {
            for (gameInstance instance : restored_historical_games) {
                this.historical_games.put(instance.getGameId(), instance);
            }
        }
    }

    public playerGameState joinCurrentGame(String account_id) {
        ensureCurrentGameExists();
        if (!current_game.isActive()) throw new IllegalArgumentException("NO_ACTIVE_GAME");
        return current_game.getOrCreatePlayerState(account_id);
    }

    public JsonObject submitProposal(String account_id, List<String> words) {
        ensureCurrentGameExists();

        JsonObject result = new JsonObject();
        // Il callback può cambiare partita: la proposta scaduta va rifiutata.
        boolean expired = finishExpiredGame();
        if (expired || !current_game.isActive()) {
            result.addProperty("type", "error");
            result.addProperty("code", "NO_ACTIVE_GAME");
            result.addProperty("message", "No active game for this proposal");
            return result;
        }

        playerGameState state = current_game.getOrCreatePlayerState(account_id);

        if (!state.canSubmit()) {
            result.addProperty("type", "error");
            result.addProperty("code", "PLAYER_ALREADY_FINISHED");
            result.addProperty("message", "Player already finished this game");
            return result;
        }

        // Le richieste malformate non modificano lo stato.
        if (words == null || words.size() != 4) {
            result.addProperty("type", "malformed");
            result.addProperty("stateChanged", false);
            result.addProperty("message", "Proposal must contain exactly 4 words");
            return result;
        }

        List<String> normalized_words = new ArrayList<>();
        for (String word : words) {
            normalized_words.add(canonicalizer.normalizeWord(word));
        }

        java.util.Set<String> unique_words = new java.util.HashSet<>(normalized_words);
        if (unique_words.size() != 4) {
            result.addProperty("type", "malformed");
            result.addProperty("stateChanged", false);
            result.addProperty("message", "Proposal contains duplicate words");
            return result;
        }

        if (!allWordsBelongToPuzzle(normalized_words)) {
            result.addProperty("type", "malformed");
            result.addProperty("stateChanged", false);
            result.addProperty("message", "One or more words do not belong to this puzzle");
            return result;
        }

        if (containsAlreadySolvedWordForPlayer(state, normalized_words)) {
            result.addProperty("type", "malformed");
            result.addProperty("stateChanged", false);
            result.addProperty("message", "Proposal contains already assigned words");
            return result;
        }

        String proposal_key = canonicalizer.canonicalizeWords(normalized_words);
        if (state.hasFoundGroupKey(proposal_key)) {
            result.addProperty("type", "malformed");
            result.addProperty("stateChanged", false);
            result.addProperty("message", "Group already found by this player");
            return result;
        }

        groupDefinition matched_group = findMatchingGroup(normalized_words);
        if (matched_group != null) {
            state.addFoundGroupKey(proposal_key);
            state.incrementCorrectGroups();

            result.addProperty("type", "correct");
            result.addProperty("stateChanged", true);
            result.addProperty("theme", matched_group.getTheme());
            result.addProperty("correctGroupsFound", state.getCorrectGroupsFound());
            result.addProperty("wrongProposals", state.getWrongProposals());
            result.addProperty("currentScore", state.getCurrentScore());

            if (state.getCorrectGroupsFound() >= 3) {
                finishPlayer(state, playerOutcome.WON);
                result.addProperty("finished", true);
                result.addProperty("outcome", "WON");
                game_event_listener.accept(current_game.getGameId(), "PLAYER_FINISHED");
            } else {
                result.addProperty("finished", false);
            }

            return result;
        }

        // Parole valide, gruppo sbagliato.
        state.incrementWrongProposals();

        result.addProperty("type", "wrong");
        result.addProperty("stateChanged", true);
        result.addProperty("message", "Valid proposal, but not a correct group");
        result.addProperty("correctGroupsFound", state.getCorrectGroupsFound());
        result.addProperty("wrongProposals", state.getWrongProposals());
        result.addProperty("currentScore", state.getCurrentScore());

        if (state.getWrongProposals() >= 4) {
            finishPlayer(state, playerOutcome.LOST);
            result.addProperty("finished", true);
            result.addProperty("outcome", "LOST");
            game_event_listener.accept(current_game.getGameId(), "PLAYER_FINISHED");
        } else {
            result.addProperty("finished", false);
        }

        return result;
    }

    public JsonObject getGameInfo(String account_id, Integer game_id) {
        gameInstance target = resolveTargetGame(game_id);
        if (target == null) {
            return null;
        }

        JsonObject root = new JsonObject();
        root.addProperty("gameId", target.getGameId());
        root.addProperty("status", target.getStatus().name());

        playerGameState participant = target.getPlayerState(account_id);
        if (target.isActive() && (participant == null || !participant.isFinished())) {
            // Consultare una partita non deve creare una partecipazione.
            playerGameState state = participant == null ? new playerGameState(account_id) : participant;

            root.addProperty("remainingSeconds", target.getRemainingSeconds());
            root.addProperty("wrongProposals", state.getWrongProposals());
            root.addProperty("currentScore", state.getCurrentScore());
            root.addProperty("correctGroupsFound", state.getCorrectGroupsFound());
            root.addProperty("canSubmit", state.canSubmit());
            root.addProperty("outcome", state.getOutcome().name());

            JsonArray solved_groups = new JsonArray();
            for (String key : state.getFoundGroupKeys()) {
                solved_groups.add(key);
            }
            root.add("foundGroupKeys", solved_groups);

            List<String> visible_words = getRemainingWordsForPlayer(target, state);
            JsonArray words_array = new JsonArray();
            for (String word : visible_words) {
                words_array.add(word);
            }
            root.add("visibleWords", words_array);
        } else {
            JsonArray groups_array = new JsonArray();
            for (groupDefinition group : target.getDefinition().getGroups()) {
                JsonObject group_json = new JsonObject();
                group_json.addProperty("theme", group.getTheme());

                JsonArray words_json = new JsonArray();
                for (String word : group.getWords()) {
                    words_json.add(word);
                }
                group_json.add("words", words_json);
                groups_array.add(group_json);
            }
            root.add("solutionGroups", groups_array);

            playerGameState state = target.getPlayerState(account_id);
            if (state != null) {
                root.addProperty("wrongProposals", state.getWrongProposals());
                root.addProperty("currentScore", state.getCurrentScore());
                root.addProperty("correctGroupsFound", state.getCorrectGroupsFound());
                root.addProperty("outcome", state.getOutcome().name());
            }
        }

        return root;
    }

    private gameInstance resolveTargetGame(Integer game_id) {
        if (game_id == null || game_id == -1) {
            return current_game;
        }

        return getGameById(game_id);
    }

    private boolean allWordsBelongToPuzzle(List<String> normalized_words) {
        List<String> all_puzzle_words = new ArrayList<>();
        for (String w : current_game.getDefinition().getAllWords()) {
            all_puzzle_words.add(canonicalizer.normalizeWord(w));
        }

        return all_puzzle_words.containsAll(normalized_words);
    }

    private boolean containsAlreadySolvedWordForPlayer(playerGameState state, List<String> normalized_words) {
        for (String key : state.getFoundGroupKeys()) {
            String[] solved_words = key.split("\\|");
            for (String submitted : normalized_words) {
                for (String solved : solved_words) {
                    if (submitted.equals(solved)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private groupDefinition findMatchingGroup(List<String> normalized_words) {
        String submitted_key = canonicalizer.canonicalizeWords(normalized_words);

        for (groupDefinition group : current_game.getDefinition().getGroups()) {
            List<String> normalized_group_words = new ArrayList<>();
            for (String word : group.getWords()) {
                normalized_group_words.add(canonicalizer.normalizeWord(word));
            }

            String group_key = canonicalizer.canonicalizeWords(normalized_group_words);
            if (submitted_key.equals(group_key)) {
                return group;
            }
        }

        return null;
    }

    private List<String> getRemainingWordsForPlayer(gameInstance game, playerGameState state) {
        List<String> remaining = new ArrayList<>();
        List<String> all_words = new ArrayList<>(game.getDefinition().getAllWords());

        for (String word : all_words) {
            String normalized = canonicalizer.normalizeWord(word);
            boolean already_solved = false;

            for (String key : state.getFoundGroupKeys()) {
                String[] solved_words = key.split("\\|");
                for (String solved : solved_words) {
                    if (normalized.equals(solved)) {
                        already_solved = true;
                        break;
                    }
                }
                if (already_solved) {
                    break;
                }
            }

            if (!already_solved) {
                remaining.add(word);
            }
        }

        Collections.shuffle(remaining);
        return remaining;
    }

    public JsonArray getGameLeaderboard(int game_id) {
        gameInstance game = getGameById(game_id);
        JsonArray ranking = new JsonArray();
        if (game == null) return ranking;
        List<playerGameState> players = new ArrayList<>(game.getPlayerStates().values());
        players.sort(java.util.Comparator.comparingInt(playerGameState::getCurrentScore).reversed()
                .thenComparing(player -> getPlayerName(player.getAccountId())));
        int rank = 1;
        for (playerGameState player : players) {
            JsonObject entry = new JsonObject();
            entry.addProperty("rank", rank++);
            entry.addProperty("username", getPlayerName(player.getAccountId()));
            entry.addProperty("score", player.getCurrentScore());
            ranking.add(entry);
        }

        return ranking;
    }

    public String getPlayerName(String id) {
        user account = user_manager.getUserById(id);
        return account == null ? id : account.getUsername();
    }

    private void finishPlayer(playerGameState state, playerOutcome outcome) {
        // Evita di contare due volte la stessa partecipazione.
        if (state.isFinished()) return;
        switch (outcome) {
            case WON -> state.markWon();
            case LOST -> state.markLost();
            case TIMEOUT -> state.markTimeout();
            default -> throw new IllegalArgumentException("Esito finale non valido");
        }
        applyUserFinalStats(state);
    }

    private void applyUserFinalStats(playerGameState state) {
        user account = user_manager.getUserById(state.getAccountId());
        if (account == null) {
            return;
        }

        account.addToTotalScore(state.getCurrentScore());

        userStats stats = account.getStats();
        stats.setGamesPlayed(stats.getGamesPlayed() + 1);

        switch (state.getOutcome()) {
            case WON -> {
                stats.setGamesWon(stats.getGamesWon() + 1);
                stats.setCurrentStreak(stats.getCurrentStreak() + 1);
                stats.setMaxStreak(Math.max(stats.getMaxStreak(), stats.getCurrentStreak()));

                if (state.getWrongProposals() == 0) {
                    stats.setPerfectPuzzles(stats.getPerfectPuzzles() + 1);
                }

                int idx = Math.min(state.getWrongProposals(), 3);
                stats.getMistakeHistogram()[idx]++;
            }
            case LOST -> {
                stats.setGamesLost(stats.getGamesLost() + 1);
                stats.setCurrentStreak(0);
                stats.getMistakeHistogram()[4]++;
            }
            case TIMEOUT -> {
                stats.setGamesUnfinished(stats.getGamesUnfinished() + 1);
                stats.setCurrentStreak(0);
                stats.getMistakeHistogram()[5]++;
            }
            default -> {
            }
        }
    }

}

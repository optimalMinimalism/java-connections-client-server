import server.core.*;
import server.session.playerSession;
import common.model.*;
import common.protocol.*;
import java.nio.file.*;
import java.util.*;

public class coreTestMain {

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("connections-core-");
        try {
            Path dataset = directory.resolve("puzzles.json");
            Files.copy(Path.of("data/puzzles.json"), dataset);
            puzzleRepository puzzles = new puzzleRepository();
            puzzles.load(dataset.toString());
            userManager users = new userManager();
            user account = users.register("first", "pw");
            String id = account.getAccountId();
            gameManager games = new gameManager(puzzles, users, 60);
            games.ensureCurrentGameExists();
            List<String> first_events = new ArrayList<>();
            games.setGameEventListener((game_id, type) -> first_events.add(type));
            games.joinCurrentGame(id);
            List<groupDefinition> groups = games.getCurrentGame().getDefinition().getGroups();
            check(games.submitProposal(id, List.of("bad", "bad", "bad",
                "bad")).get("type").getAsString().equals("malformed"));
            check(games.getCurrentGame().getPlayerState(id).getWrongProposals() == 0);
            for (int i = 0; i < 3; i++) games.submitProposal(id, groups.get(i).getWords());
            check(games.getGameInfo(id, -1).getAsJsonArray("solutionGroups").size() == 4);
            check(first_events.equals(List.of("PLAYER_FINISHED")));
            users.updateCredentials("first", "pw", "second", "newpw");
            check(users.authenticate("second", "newpw").getAccountId().equals(id));
            check(games.joinCurrentGame(id).getCurrentScore() == 18);
            check(games.submitProposal(id,
                groups.get(3).getWords()).get("code").getAsString().equals("PLAYER_ALREADY_FINISHED"));
            // Uno username uguale all'ID altrui non deve confondere le due ricerche.
            user homonym = users.register(id, "pw");
            check(users.getUserByUsername(id) == homonym);
            check(users.getUserById(id) == account);
            check(users.getUserById("second") == null);
            check(users.getUserByUsername("first") == null);
            check(users.getUserByUsername("second") == account);
            String player_json = jsonCodec.toJson(games.getCurrentGame().getPlayerState(id));
            check(player_json.contains("\"accountId\":\"" + id + "\""));
            check(!player_json.contains("\"username\""));
            check(!player_json.contains("account_id"));
            check(jsonCodec.fromJson(player_json, playerGameState.class).getAccountId().equals(id));
            games.endCurrentGame();
            games.endCurrentGame();
            check(account.getStats().getGamesPlayed() == 1);
            check(account.getTotalScore() == 18);
            check(first_events.equals(List.of("PLAYER_FINISHED", "GAME_ENDED")));
            games.startNewGame();
            int second = games.getCurrentGame().getGameId();
            games.joinCurrentGame(id);
            games.getCurrentGame().setEndTimeEpochMillis(System.currentTimeMillis() - 1);
            games.getCurrentGame().setStartTimeEpochMillis(games.getCurrentGame().getEndTimeEpochMillis() - 1);
            check(games.submitProposal(id,
                groups.get(0).getWords()).get("code").getAsString().equals("NO_ACTIVE_GAME"));
            check(games.getCurrentGame().getPlayerState(id).getCorrectGroupsFound() == 0);
            check(users.getUserById(id).getStats().getGamesPlayed() == 2);
            check(!games.finishExpiredGame());
            check(games.getGameInfo(id, -1).get("status").getAsString().equals("ENDED"));
            check(first_events.equals(List.of("PLAYER_FINISHED", "GAME_ENDED", "GAME_ENDED")));
            persistenceManager persistence = new persistenceManager();
            String state_path = directory.resolve("games.json").toString();
            persistence.save(state_path, users.snapshotUsers(), puzzles.getCurrentPuzzleIndex(), games.getCurrentGame(),
                games.getHistoricalGames());
            var loaded = persistence.load(state_path);
            check(loaded.getUsers().get("second").getTotalScore() == 18);
            check(loaded.getCurrentGame().getGameId() == second);
            check(loaded.getCurrentGame().getEndTimeEpochMillis() == games.getCurrentGame().getEndTimeEpochMillis());
            Files.writeString(directory.resolve("snapshot-interrupted.tmp"), "{partial");
            check(persistence.load(state_path).getUsers().get("second").getTotalScore() == 18);
            check(loaded.getHistoricalGames().size() == 2);
            // Uno snapshot corrotto deve essere rifiutato senza azzerare gli utenti.
            Path snapshot_path = Path.of(state_path + ".snapshot.json");
            String valid_snapshot = Files.readString(snapshot_path);
            for (String invalid : List.of("{partial", "{}", "null", "[]",
                    "{\"users\":{},\"historicalGames\":[],\"currentPuzzleIndex\":-1}",
                    "{\"users\":{},\"historicalGames\":[],\"currentPuzzleIndex\":1.5}")) {
                Files.writeString(snapshot_path, invalid);
                boolean rejected = false;
                try { persistence.load(state_path); }
                catch (java.io.IOException e) { rejected = true; }
                check(rejected);
                check(Files.readString(snapshot_path).equals(invalid));
            }

            Files.writeString(snapshot_path, valid_snapshot);
            check(persistence.load(state_path).getUsers().get("second").getTotalScore() == 18);
            puzzleRepository resumed = new puzzleRepository();
            resumed.load(dataset.toString());
            resumed.setCurrentPuzzleIndex(loaded.getCurrentPuzzleIndex());
            check(resumed.getNextPuzzle().getGameId() == puzzles.getNextPuzzle().getGameId());
            resumed.close();
            userManager restored_users = new userManager();
            user restored_account = restored_users.register("restored", "pw");
            gameInstance restored_game = new gameInstance(games.getCurrentGame().getDefinition(), 0, Long.MAX_VALUE);
            restored_game.getOrCreatePlayerState(restored_account.getAccountId()).incrementWrongProposals();
            gameManager restored_games = new gameManager(puzzles, restored_users, 60);
            restored_games.restoreState(restored_game, List.of());
            check(restored_games.joinCurrentGame(restored_account.getAccountId()).getWrongProposals() == 1);
            check(restored_game.getParticipantsCount() == 1);
            // Scadenza rilevata da una richiesta, senza scheduler: esito e callback una volta sola.
            List<String> events = new ArrayList<>();
            restored_games.setGameEventListener((game_id, type) -> {
                events.add(type);
                if ("GAME_ENDED".equals(type)) {
                    restored_games.startNewGame();
                    restored_games.joinCurrentGame(restored_account.getAccountId());
                }
            });
            restored_game.setEndTimeEpochMillis(System.currentTimeMillis() - 1);
            Object state_lock = new Object();
            protocolRouter router = new protocolRouter(state_lock, restored_users, restored_games,
                    new statsService(restored_users), new leaderboardService(restored_users));
            playerSession session = new playerSession("127.0.0.1");
            session.setAccountId(restored_account.getAccountId());
            session.setLoggedIn(true);
            requestMessage request = new requestMessage();
            request.setOperation("requestGameStats");
            com.google.gson.JsonObject payload = new com.google.gson.JsonObject();
            payload.addProperty("gameId", restored_game.getGameId());
            request.setPayload(payload);
            synchronized (state_lock) {
                responseMessage response = router.route(request, session);
                check(response.getPayload().getAsJsonObject("gameStats").get("playersFinished").getAsInt() == 1);
                check(events.equals(List.of("GAME_ENDED")));
                check(restored_account.getStats().getGamesPlayed() == 1);
                check(restored_account.getTotalScore() == -4);
                check(!restored_games.finishExpiredGame());
                check(events.size() == 1);
                int participants = restored_games.getCurrentGame().getParticipantsCount();
                restored_games.getGameInfo("spectator", -1);
                check(restored_games.getCurrentGame().getParticipantsCount() == participants);
                // Se scade dentro submitProposal, il callback apre la nuova partita ma la proposta viene rifiutata.
                restored_games.getCurrentGame().setEndTimeEpochMillis(System.currentTimeMillis() - 1);
                check(restored_games.submitProposal(restored_account.getAccountId(),
                        restored_game.getDefinition().getGroups().get(0).getWords()).get("code").getAsString().equals("NO_ACTIVE_GAME"));
                check(restored_games.getCurrentGame().getPlayerState(restored_account.getAccountId()).getCorrectGroupsFound() == 0);
                check(events.equals(List.of("GAME_ENDED", "GAME_ENDED")));
                check(restored_account.getStats().getGamesPlayed() == 2);
            }
            puzzles.close();
            System.out.println("PASS: regole, identità, scadenze senza scheduler, callback, statistiche senza duplicazioni, snapshot e ripristino");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

import common.model.*;
import common.protocol.*;
import common.util.canonicalizer;
import server.core.*;
import server.session.playerSession;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public class robustnessTestMain {

    private static void check(boolean condition) {
        if (!condition) throw new AssertionError();
    }

    private static responseMessage request(protocolRouter router, playerSession session, String operation, String json) {
        requestMessage message = new requestMessage(operation);
        message.setPayload(jsonCodec.parseObject(json));
        synchronized (router.getStateLock()) {
            return router.route(message, session);
        }
    }

    private static void rejectSnapshot(persistenceManager persistence, String path, JsonObject root) throws IOException {
        Path file = Path.of(path + ".snapshot.json");
        String invalid = root.toString();
        Files.writeString(file, invalid);
        boolean rejected = false;
        try { persistence.load(path); }
        catch (IOException e) { rejected = true; }
        check(rejected && Files.readString(file).equals(invalid));
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("connections-robustness-");
        Locale original_locale = Locale.getDefault();
        try {
            List<groupDefinition> groups = new ArrayList<>();
            for (int group = 0; group < 4; group++) {
                List<String> words = new ArrayList<>();
                for (int word = 0; word < 4; word++) words.add("g" + group + "w" + word);
                groups.add(new groupDefinition("theme" + group, words));
            }
            gameDefinition definition = new gameDefinition(1, groups);
            Path dataset = directory.resolve("puzzles.json");
            String valid_dataset = jsonCodec.toJson(List.of(definition));
            Files.writeString(dataset, valid_dataset);
            puzzleRepository puzzles = new puzzleRepository();
            puzzles.load(dataset.toString());
            userManager users = new userManager();
            gameManager games = new gameManager(puzzles, users, 600);
            games.ensureCurrentGameExists();
            Object state_lock = new Object();
            protocolRouter router = new protocolRouter(state_lock, users, games,
                    new statsService(users), new leaderboardService(users));
            playerSession session = new playerSession("127.0.0.1");

            check(request(router, session, "register",
                "{\"username\":42,\"psw\":\"pw\"}").getCode().equals("INVALID_REQUEST"));
            check(request(router, session, "register",
                "{\"username\":\"alice\",\"psw\":[]} ").getCode().equals("INVALID_REQUEST"));
            check(users.getAllUsers().isEmpty());
            check(request(router, session, "register",
                "{\"username\":\"alice\",\"psw\":\"pw\"}").getStatus().equals("ok"));
            for (String port : List.of("1.5", "65536", "-1", "\"5001\"", "true")) {
                check(request(router, session, "login", "{\"username\":\"alice\",\"psw\":\"pw\",\"udpPort\":" + port
                    + "}").getCode().equals("INVALID_REQUEST"));
                check(!session.isLoggedIn() && games.getCurrentGame().getParticipantsCount() == 0);
            }
            check(request(router, session, "login",
                "{\"username\":\"alice\",\"psw\":\"pw\",\"udpPort\":0}").getStatus().equals("ok"));
            for (String game_id : List.of("1.5", "2147483648", "\"-1\"", "[]", "true", "null")) {
                check(request(router, session, "requestGameInfo", "{\"gameId\":" + game_id
                    + "}").getCode().equals("INVALID_REQUEST"));
            }
            check(request(router, session, "requestGameStats", "{}").getCode().equals("INVALID_REQUEST"));
            check(request(router, session, "requestLeaderboard",
                "{\"topPlayers\":-1}").getCode().equals("INVALID_REQUEST"));
            check(request(router, session, "submitProposal",
                "{\"words\":[1,2,3,4]}").getCode().equals("MALFORMED_PROPOSAL"));
            check(request(router, session, "submitProposal",
                "{\"words\":[\"g0w0\"]}").getCode().equals("MALFORMED_PROPOSAL"));
            check(games.getCurrentGame().getPlayerState(session.getAccountId()).getWrongProposals() == 0);
            check(request(router, session, "updateCredentials",
                "{\"oldUsername\":\"alice\",\"oldPsw\":\"pw\",\"newUsername\":\" \"}").getCode().equals("INVALID_REQUEST"));
            check(users.authenticate("alice", "pw") != null);

            // Il dataset contiene un solo puzzle: le parole si ripetono ma l'ID della partita cambia.
            int old_id = games.getCurrentGame().getGameId();
            games.setGameEventListener((id, type) -> {
                if ("GAME_ENDED".equals(type)) {
                    games.startNewGame();
                    games.joinCurrentGame(session.getAccountId());
                }
            });
            games.getCurrentGame().setStartTimeEpochMillis(System.currentTimeMillis() - 10);
            games.getCurrentGame().setEndTimeEpochMillis(System.currentTimeMillis() - 1);
            String proposal = "{\"gameId\":" + old_id + ",\"words\":" + jsonCodec.toJson(groups.get(0).getWords())
                + "}";
            check(request(router, session, "submitProposal", proposal).getCode().equals("NO_ACTIVE_GAME"));
            check(games.getCurrentGame().getGameId() > old_id);
            check(games.getCurrentGame().getPlayerState(session.getAccountId()).getCorrectGroupsFound() == 0);

            persistenceManager persistence = new persistenceManager();
            String path = directory.resolve("state.json").toString();
            persistence.save(path, users.snapshotUsers(), puzzles.getCurrentPuzzleIndex(), games.getCurrentGame(),
                games.getHistoricalGames());
            Path snapshot_file = Path.of(path + ".snapshot.json");
            String saved = Files.readString(snapshot_file);
            check(persistence.load(path).getUsers().get("alice").getStats().getGamesPlayed() == 1);
            JsonObject root = jsonCodec.parseObject(saved);
            root.getAsJsonObject("users").getAsJsonObject("alice").remove("accountId");
            rejectSnapshot(persistence, path, root);
            root = jsonCodec.parseObject(saved);
            root.getAsJsonArray("historicalGames").add(root.getAsJsonArray("historicalGames").get(0).deepCopy());
            rejectSnapshot(persistence, path, root);
            root = jsonCodec.parseObject(saved);
            root.getAsJsonObject("users").getAsJsonObject("alice").addProperty("totalScore", 1000);
            rejectSnapshot(persistence, path, root);
            root = jsonCodec.parseObject(saved);
            root.getAsJsonObject("currentGame").getAsJsonObject("playerStates")
                    .getAsJsonObject(session.getAccountId()).addProperty("accountId", "unknown");
            rejectSnapshot(persistence, path, root);
            root = jsonCodec.parseObject(saved);
            root.getAsJsonObject("users").getAsJsonObject("alice").getAsJsonObject("stats")
                    .add("mistakeHistogram", new JsonArray());
            rejectSnapshot(persistence, path, root);
            Files.writeString(snapshot_file, saved);
            check(persistence.load(path).getHistoricalGames().size() == 1);
            puzzles.close();

            // Un record errato non deve avanzare: il file può essere corretto e riletto.
            JsonObject invalid_puzzle = jsonCodec.gson().toJsonTree(definition).getAsJsonObject();
            invalid_puzzle.getAsJsonArray("groups").get(0).getAsJsonObject().getAsJsonArray("words").set(1,
                new JsonPrimitive("g0w0"));
            Files.writeString(dataset, "[" + invalid_puzzle + "]");
            puzzles.load(dataset.toString());
            boolean rejected = false;
            try { puzzles.getNextPuzzle(); }
            catch (IllegalStateException e) { rejected = true; }
            check(rejected && puzzles.getCurrentPuzzleIndex() == 0);
            Files.writeString(dataset, valid_dataset);
            check(puzzles.getNextPuzzle().getGroups().size() == 4);
            check(puzzles.getCurrentPuzzleIndex() == 1);
            puzzles.close();
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            check(canonicalizer.normalizeWord("rain").equals("RAIN"));
            System.out.println("PASS: tipi JSON, login atomico, board scaduta, snapshot incoerenti, puzzle invalidi e locale");
        } finally {
            Locale.setDefault(original_locale);
            try (var files = Files.walk(directory)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
}

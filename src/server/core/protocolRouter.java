package server.core;

import common.model.leaderboardEntry;
import common.model.gameInstance;
import common.model.playerGameState;
import common.model.user;
import common.model.enums.errorCode;
import common.protocol.requestMessage;
import common.protocol.responseMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import server.session.playerSession;

import java.util.ArrayList;
import java.util.List;

public class protocolRouter {

    private final Object state_lock;
    private final userManager user_manager;
    private final gameManager game_manager;
    private final statsService stats_service;
    private final leaderboardService leaderboard_service;

    public protocolRouter(
            Object state_lock,
            userManager user_manager,
            gameManager game_manager,
            statsService stats_service,
            leaderboardService leaderboard_service
    ) {
        this.state_lock = state_lock;
        this.user_manager = user_manager;
        this.game_manager = game_manager;
        this.stats_service = stats_service;
        this.leaderboard_service = leaderboard_service;
    }

    // Il chiamante tiene state_lock fino all'aggiornamento delle sessioni.
    public responseMessage route(requestMessage request, playerSession session) {
        if (request == null || request.getOperation() == null || request.getOperation().isBlank()) {
            return responseMessage.error(errorCode.INVALID_REQUEST, "Missing operation");
        }

        try {
            // Controlla la scadenza anche tra due esecuzioni dello scheduler.
            game_manager.finishExpiredGame();
            return switch (request.getOperation()) {
                case "register" -> handleRegister(request);
                case "updateCredentials" -> handleUpdateCredentials(request, session);
                case "login" -> handleLogin(request, session);
                case "logout" -> handleLogout(session);
                case "submitProposal" -> handleSubmitProposal(request, session);
                case "requestGameInfo" -> handleRequestGameInfo(request, session);
                case "requestGameStats" -> handleRequestGameStats(request, session);
                case "requestLeaderboard" -> handleRequestLeaderboard(request, session);
                case "requestPlayerStats" -> handleRequestPlayerStats(session);
                default -> responseMessage.error(errorCode.INVALID_OPERATION, "Unknown operation: "
                    + request.getOperation());
            };
        } catch (IllegalArgumentException e) {
            return mapIllegalArgument(e);
        } catch (Exception e) {
            System.err.println("Errore durante " + request.getOperation() + ": " + e);
            return responseMessage.error(errorCode.INTERNAL_ERROR, "Internal server error");
        }
    }

    // Lock condiviso con lo scheduler.
    public Object getStateLock() { return state_lock; }

    private responseMessage handleRegister(requestMessage request) {
        String username = request.getString("username");
        String password = request.getString("psw");

        user user = user_manager.register(username, password);

        responseMessage response = responseMessage.ok("registered");
        response.addProperty("username", user.getUsername());
        return response;
    }

    private responseMessage handleUpdateCredentials(requestMessage request, playerSession session) {
        String old_username = request.getString("oldUsername");
        String old_password = request.getString("oldPsw");
        String new_username = request.getString("newUsername");
        String new_password = request.getString("newPsw");

        user updated = user_manager.updateCredentials(old_username, old_password, new_username, new_password);

        responseMessage response = responseMessage.ok("credentials updated");
        response.addProperty("username", updated.getUsername());
        return response;
    }

    private responseMessage handleLogin(requestMessage request, playerSession session) {
        if (session.isLoggedIn()) {
            return responseMessage.error(errorCode.USER_ALREADY_LOGGED_IN, "User already logged in on this session");
        }

        String username = request.getString("username");
        String password = request.getString("psw");
        Integer udp_port = request.getInt("udpPort");

        if (udp_port != null && (udp_port < 0 || udp_port > 65535)) {
            return responseMessage.error(errorCode.INVALID_REQUEST, "Invalid UDP port");
        }

        user user = user_manager.authenticate(username, password);
        playerGameState state = game_manager.joinCurrentGame(user.getAccountId());
        JsonObject current_game_info = game_manager.getGameInfo(user.getAccountId(), -1);

        session.setAccountId(user.getAccountId());
        session.setLoggedIn(true);
        if (udp_port != null && udp_port > 0 && udp_port <= 65535) {
            session.setUdpPort(udp_port);
        }

        responseMessage response = responseMessage.ok("login successful");
        response.addProperty("username", user.getUsername());
        response.getPayload().add("currentGame", current_game_info);
        response.addProperty("currentScore", state.getCurrentScore());
        return response;
    }

    private responseMessage handleLogout(playerSession session) {
        ensureLoggedIn(session);

        String old_username = user_manager.getUserById(session.getAccountId()).getUsername();
        session.clearLogin();

        responseMessage response = responseMessage.ok("logout successful");
        response.addProperty("username", old_username);
        return response;
    }

    private responseMessage handleSubmitProposal(requestMessage request, playerSession session) {
        ensureLoggedIn(session);

        Integer game_id = request.getInt("gameId");
        if (game_id != null && game_id != game_manager.getCurrentGame().getGameId()) {
            return responseMessage.error(errorCode.NO_ACTIVE_GAME, "The board has changed; request the current game");
        }

        JsonArray words_array = request.getArray("words");
        if (words_array == null || words_array.size() != 4) {
            return responseMessage.error(errorCode.MALFORMED_PROPOSAL, "Proposal must contain four words");
        }

        List<String> words = new ArrayList<>();
        if (words_array != null) {
            for (JsonElement element : words_array) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    return responseMessage.error(errorCode.MALFORMED_PROPOSAL, "Words must be strings");
                }
                words.add(element.getAsString());
            }
        }

        JsonObject result = game_manager.submitProposal(session.getAccountId(), words);

        String type = result.has("type") ? result.get("type").getAsString() : "error";
        if ("error".equals(type)) {
            String code = result.has("code") ? result.get("code").getAsString() : errorCode.INTERNAL_ERROR.name();
            return responseMessage.error(
                    safeErrorCode(code),
                    result.has("message") ? result.get("message").getAsString() : "error"
            );
        }

        responseMessage response = responseMessage.ok("proposal processed");
        response.getPayload().add("result", result);
        if (result.has("finished") && result.get("finished").getAsBoolean()) {
            response.getPayload().add("gameInfo",
                    game_manager.getGameInfo(session.getAccountId(), game_manager.getCurrentGame().getGameId()));
        }
        return response;
    }

    private responseMessage handleRequestGameInfo(requestMessage request, playerSession session) {
        ensureLoggedIn(session);

        Integer game_id = request.getInt("gameId");
        if (game_id == null) return responseMessage.error(errorCode.INVALID_REQUEST, "Missing gameId");
        JsonObject info = game_manager.getGameInfo(session.getAccountId(), game_id);

        if (info == null) {
            return responseMessage.error(errorCode.GAME_NOT_FOUND, "Game not found");
        }

        responseMessage response = responseMessage.ok("game info");
        response.getPayload().add("gameInfo", info);
        return response;
    }

    private responseMessage handleRequestGameStats(requestMessage request, playerSession session) {
        ensureLoggedIn(session);

        Integer game_id = request.getInt("gameId");
        if (game_id == null) return responseMessage.error(errorCode.INVALID_REQUEST, "Missing gameId");
        gameInstance target = (game_id == null || game_id == -1)
                ? game_manager.getCurrentGame()
                : game_manager.getGameById(game_id);

        if (target == null) {
            return responseMessage.error(errorCode.GAME_NOT_FOUND, "Game not found");
        }

        JsonObject stats = target.isActive()
                ? stats_service.buildCurrentGameStats(target)
                : stats_service.buildFinishedGameStats(target);

        responseMessage response = responseMessage.ok("game stats");
        response.getPayload().add("gameStats", stats);
        response.getPayload().add("gameLeaderboard", game_manager.getGameLeaderboard(target.getGameId()));
        return response;
    }

    private responseMessage handleRequestLeaderboard(requestMessage request, playerSession session) {
        ensureLoggedIn(session);

        String player_name = request.getString("playerName");
        Integer top_players = request.getInt("topPlayers");
        if (top_players != null && top_players < 0) {
            return responseMessage.error(errorCode.INVALID_REQUEST, "topPlayers must be zero or positive");
        }

        responseMessage response = responseMessage.ok("leaderboard");

        if (player_name != null && !player_name.isBlank()) {
            leaderboardEntry entry = leaderboard_service.getPlayerRank(player_name);
            if (entry == null) {
                return responseMessage.error(errorCode.USER_NOT_FOUND, "Player not found");
            }
            response.getPayload().add("playerRank", toJson(entry));
            return response;
        }

        List<leaderboardEntry> entries = (top_players != null)
                ? leaderboard_service.getTopPlayers(top_players)
                : leaderboard_service.getFullLeaderboard();

        JsonArray arr = new JsonArray();
        for (leaderboardEntry entry : entries) {
            arr.add(toJson(entry));
        }
        response.getPayload().add("leaderboard", arr);
        return response;
    }

    private responseMessage handleRequestPlayerStats(playerSession session) {
        ensureLoggedIn(session);

        JsonObject stats = stats_service.buildPlayerStats(session.getAccountId());
        if (stats == null) {
            return responseMessage.error(errorCode.USER_NOT_FOUND, "Player not found");
        }

        responseMessage response = responseMessage.ok("player stats");
        response.getPayload().add("playerStats", stats);
        return response;
    }

    private void ensureLoggedIn(playerSession session) {
        if (session == null || !session.isLoggedIn() || session.getAccountId() == null) {
            throw new IllegalArgumentException(errorCode.USER_NOT_LOGGED_IN.name());
        }
    }

    private responseMessage mapIllegalArgument(IllegalArgumentException e) {
        String message = e.getMessage();
        if (message == null) {
            return responseMessage.error(errorCode.INVALID_REQUEST, "Invalid request");
        }

        errorCode code = safeErrorCode(message);
        String human_message = switch (code) {
            case USERNAME_ALREADY_EXISTS -> "Username already exists";
            case USER_NOT_FOUND -> "User not found";
            case INVALID_CREDENTIALS -> "Invalid credentials";
            case NEW_USERNAME_ALREADY_EXISTS -> "New username already exists";
            case USER_NOT_LOGGED_IN -> "User not logged in";
            case USER_ALREADY_LOGGED_IN -> "User already logged in";
            case GAME_NOT_FOUND -> "Game not found";
            case NO_ACTIVE_GAME -> "No active game";
            case PLAYER_ALREADY_FINISHED -> "Player already finished the current game";
            case MALFORMED_PROPOSAL -> "Malformed proposal";
            case INVALID_OPERATION -> "Invalid operation";
            case INVALID_REQUEST -> "Invalid request";
            default -> "Internal error";
        };

        return responseMessage.error(code, human_message);
    }

    private errorCode safeErrorCode(String code) {
        try {
            return errorCode.valueOf(code);
        } catch (Exception ignored) {
            return errorCode.INTERNAL_ERROR;
        }
    }

    private JsonObject toJson(leaderboardEntry entry) {
        JsonObject obj = new JsonObject();
        obj.addProperty("rank", entry.getRank());
        obj.addProperty("username", entry.getUsername());
        obj.addProperty("totalScore", entry.getTotalScore());
        return obj;
    }
}

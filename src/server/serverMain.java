package server;

import common.model.gameInstance;
import common.protocol.notificationMessage;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import common.util.configLoader;
import server.core.gameManager;
import server.core.leaderboardService;
import server.core.persistenceManager;
import server.core.protocolRouter;
import server.core.puzzleRepository;
import server.core.statsService;
import server.core.userManager;
import server.net.connectionAcceptor;
import server.net.udpNotifier;
import server.session.playerSession;

import java.io.IOException;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;

public class serverMain {

    public static void main(String[] args) throws Exception {
        new serverMain().start();
    }

    public void start() throws Exception {
        configLoader config = new configLoader("config/server-config.properties");

        String puzzles_path = config.getString("server.data.puzzles.path");
        String games_path = config.getString("server.data.games.path");

        String bind_host = config.getString("server.host");
        int tcp_port = config.getInt("server.tcp.port");
        int read_timeout_millis = Math.toIntExact(Math.multiplyExact(
                config.getLong("server.tcp.read.timeout.seconds"), 1000L));
        int thread_pool_size = config.getInt("server.threadpool.size");
        long game_duration_seconds = config.getLong("server.game.duration.seconds");
        long persistence_period_seconds = config.getLong("server.persistence.period.seconds");

        if (game_duration_seconds <= 0 || persistence_period_seconds <= 0 || thread_pool_size <= 0
                || read_timeout_millis <= 0 || tcp_port < 1 || tcp_port > 65535) {
            throw new IllegalArgumentException("Configurazione server non valida");
        }

        puzzleRepository puzzle_repository = new puzzleRepository();
        puzzle_repository.load(puzzles_path);

        persistenceManager persistence_manager = new persistenceManager();
        persistenceManager.persistedState persisted_state = safeLoadPersistence(persistence_manager, games_path);

        // Lock comune per richieste, partite e salvataggi.
        Object state_lock = new Object();
        userManager user_manager = new userManager(persisted_state.getUsers());
        if (persisted_state.getCurrentPuzzleIndex() > 0) {
            puzzle_repository.setCurrentPuzzleIndex(persisted_state.getCurrentPuzzleIndex());
        }

        gameManager game_manager = new gameManager(puzzle_repository, user_manager, game_duration_seconds);
        game_manager.restoreState(
                persisted_state.getCurrentGame(),
                persisted_state.getHistoricalGames()
        );
        game_manager.ensureCurrentGameExists();
        game_manager.finishExpiredGame();
        if (!game_manager.getCurrentGame().isActive()) game_manager.startNewGame();

        statsService stats_service = new statsService(user_manager);
        leaderboardService leaderboard_service = new leaderboardService(user_manager);

        Map<String, playerSession> live_sessions = new HashMap<>();
        udpNotifier udp_notifier = new udpNotifier();

        protocolRouter router = new protocolRouter(
                state_lock,
                user_manager,
                game_manager,
                stats_service,
                leaderboard_service
        );

        // Si cambia partita solo dopo GAME_ENDED.
        game_manager.setGameEventListener((game_id, type) -> {
            notifyGame(game_manager, stats_service, udp_notifier, live_sessions, game_id, type);
            if ("GAME_ENDED".equals(type)) {
                startNextGame(game_manager, stats_service, udp_notifier, live_sessions);
            }
        });

        // Se il pool è pieno si chiude la nuova connessione.
        ExecutorService worker_pool = new ThreadPoolExecutor(
                thread_pool_size, thread_pool_size, 0L, TimeUnit.MILLISECONDS, new SynchronousQueue<>());
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        connectionAcceptor acceptor = new connectionAcceptor(
                bind_host,
                tcp_port,
                read_timeout_millis,
                router,
                worker_pool,
                live_sessions
        );

        scheduler.scheduleAtFixedRate(
                () -> saveStateQuietly(
                        state_lock,
                        persistence_manager,
                        games_path,
                        user_manager,
                        puzzle_repository,
                        game_manager
                ),
                persistence_period_seconds,
                persistence_period_seconds,
                TimeUnit.SECONDS
        );

        scheduler.scheduleAtFixedRate(
                () -> {
                    synchronized (state_lock) {
                        try {
                            if (game_manager.getCurrentGame().isActive()) {
                                game_manager.finishExpiredGame();
                            } else {
                                startNextGame(game_manager, stats_service, udp_notifier,
                                    live_sessions);
                            }
                        } catch (RuntimeException e) {
                            // Lo scheduler deve continuare anche dopo un errore.
                            System.err.println("Rotazione fallita: " + e.getMessage());
                        }
                    }
                },
                0,
                1,
                TimeUnit.SECONDS
        );

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            acceptor.stop();
            scheduler.shutdownNow();
            worker_pool.shutdownNow();
            boolean interrupted = false;
            while (!worker_pool.isTerminated() || !scheduler.isTerminated()) {
                try {
                    worker_pool.awaitTermination(1, TimeUnit.SECONDS);
                    scheduler.awaitTermination(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
            saveStateQuietly(
                    state_lock,
                    persistence_manager,
                    games_path,
                    user_manager,
                    puzzle_repository,
                    game_manager
            );
            synchronized (state_lock) {
                try { puzzle_repository.close(); } catch (IOException e) { System.err.println(e.getMessage()); }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }));

        Thread acceptor_thread = new Thread(acceptor, "connections-acceptor");
        acceptor_thread.start();
        System.out.println("Connections server started on TCP port " + tcp_port);
    }

    private persistenceManager.persistedState safeLoadPersistence(
            persistenceManager persistence_manager,
            String games_path
    ) {
        try {
            return persistence_manager.load(games_path);
        } catch (IOException e) {
            throw new IllegalStateException("Persistenza illeggibile: avvio interrotto", e);
        }
    }

    private void saveStateQuietly(
            Object state_lock,
            persistenceManager persistence_manager,
            String games_path,
            userManager user_manager,
            puzzleRepository puzzle_repository,
            gameManager game_manager
    ) {
        synchronized (state_lock) {
            // Il lock resta acquisito anche durante la scrittura.
            try {
                persistence_manager.save(
                        games_path,
                        user_manager.snapshotUsers(),
                        puzzle_repository.getCurrentPuzzleIndex(),
                        game_manager.getCurrentGame(),
                        game_manager.getHistoricalGames()
                );
            } catch (IOException e) {
                System.err.println("Salvataggio fallito: " + e.getMessage());
            }
        }
    }

    private void startNextGame(
            gameManager game_manager,
            statsService stats_service,
            udpNotifier udp_notifier,
            Map<String, playerSession> live_sessions
    ) {
        game_manager.startNewGame();
        for (playerSession session : live_sessions.values()) {
            if (session.isLoggedIn() && session.isConnected()) game_manager.joinCurrentGame(session.getAccountId());
        }
        notifyGame(game_manager, stats_service, udp_notifier, live_sessions,
                game_manager.getCurrentGame().getGameId(), "NEW_GAME_STARTED");
    }

    private static void notifyGame(gameManager manager, statsService stats, udpNotifier notifier,
            Map<String, playerSession> sessions, int id, String type) {
        gameInstance game = manager.getGameById(id);
        JsonArray ranking = manager.getGameLeaderboard(id);
        for (playerSession session : sessions.values()) {
            if (!session.isLoggedIn() || !session.isConnected()
                || game.getPlayerState(session.getAccountId()) == null) continue;
            JsonObject payload = new JsonObject();
            payload.add("gameInfo", manager.getGameInfo(session.getAccountId(), id));
            payload.add("gameStats",
                game.isActive() ? stats.buildCurrentGameStats(game) : stats.buildFinishedGameStats(game));
            payload.add("gameLeaderboard", ranking);
            notifier.sendToSession(session, new notificationMessage(type, id, payload.toString()));
        }
    }
}

package server.core;

import common.model.leaderboardEntry;
import common.model.user;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class leaderboardService {

    private final userManager user_manager;

    public leaderboardService(userManager user_manager) {
        this.user_manager = user_manager;
    }

    public List<leaderboardEntry> getFullLeaderboard() {
        return rankUsers(user_manager.getAllUsers());
    }

    public List<leaderboardEntry> getTopPlayers(int k) {
        List<leaderboardEntry> all = getFullLeaderboard();
        if (k <= 0 || k >= all.size()) {
            return all;
        }

        return new ArrayList<>(all.subList(0, k));
    }

    public leaderboardEntry getPlayerRank(String username) {
        List<leaderboardEntry> all = getFullLeaderboard();
        for (leaderboardEntry entry : all) {
            if (entry.getUsername().equals(username)) {
                return entry;
            }
        }

        return null;
    }

    private List<leaderboardEntry> rankUsers(List<user> users) {
        users.sort(Comparator
                .comparingInt(user::getTotalScore).reversed()
                .thenComparing(user::getUsername, String.CASE_INSENSITIVE_ORDER));

        List<leaderboardEntry> result = new ArrayList<>();
        int rank = 1;
        for (user account : users) {
            result.add(new leaderboardEntry(rank++, account.getUsername(), account.getTotalScore()));
        }

        return result;
    }
}

package common.model;

import com.google.gson.annotations.SerializedName;

public class leaderboardEntry {

    private int rank;
    private String username;
    @SerializedName("totalScore")
    private int total_score;

    public leaderboardEntry() {
    }

    public leaderboardEntry(int rank, String username, int total_score) {
        this.rank = rank;
        this.username = username;
        this.total_score = total_score;
    }

    public int getRank() {
        return rank;
    }

    public void setRank(int rank) {
        this.rank = rank;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public int getTotalScore() {
        return total_score;
    }

    public void setTotalScore(int total_score) {
        this.total_score = total_score;
    }

    @Override
    public String toString() {
        return "LeaderboardEntry{" +
                "rank=" + rank +
                ", username='" + username + '\'' +
                ", totalScore=" + total_score +
                '}';
    }
}

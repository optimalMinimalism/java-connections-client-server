package common.model;

import com.google.gson.annotations.SerializedName;

import java.util.Objects;

public class user {

    @SerializedName("accountId")
    private String account_id;
    private String username;
    private String password;
    @SerializedName("totalScore")
    private int total_score;
    private userStats stats;

    public user() {
        this.stats = new userStats();
    }

    public user(String username, String password) {
        this.username = username;
        this.password = password;
        this.total_score = 0;
        this.stats = new userStats();
    }

    public void setAccountId(String account_id) { this.account_id = account_id; }

    public String getAccountId() {
        return account_id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public boolean passwordMatches(String candidate) {
        return Objects.equals(password, candidate);
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getTotalScore() {
        return total_score;
    }

    public void setTotalScore(int total_score) {
        this.total_score = total_score;
    }

    public void addToTotalScore(int delta) {
        this.total_score += delta;
    }

    public userStats getStats() {
        return stats;
    }

    public void setStats(userStats stats) {
        this.stats = stats;
    }

    @Override
    public String toString() {
        return "User{" +
                "username='" + username + '\'' +
                ", totalScore=" + total_score +
                ", stats=" + stats +
                '}';
    }
}

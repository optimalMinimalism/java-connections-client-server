package common.model;

import com.google.gson.annotations.SerializedName;

import java.util.Arrays;

public class userStats {

    @SerializedName("gamesPlayed")
    private int games_played;
    @SerializedName("gamesWon")
    private int games_won;
    @SerializedName("gamesLost")
    private int games_lost;
    @SerializedName("gamesUnfinished")
    private int games_unfinished;

    @SerializedName("currentStreak")
    private int current_streak;
    @SerializedName("maxStreak")
    private int max_streak;
    @SerializedName("perfectPuzzles")
    private int perfect_puzzles;

    // [0]=wins with 0 mistakes, [1]=wins with 1 mistake, [2]=wins with 2,
    // [3]=wins with 3, [4]=losses with 4 mistakes, [5]=unfinished/timeouts
    @SerializedName("mistakeHistogram")
    private int[] mistake_histogram;

    public userStats() {
        this.mistake_histogram = new int[6];
    }

    public int getGamesPlayed() {
        return games_played;
    }

    public void setGamesPlayed(int games_played) {
        this.games_played = games_played;
    }

    public int getGamesWon() {
        return games_won;
    }

    public void setGamesWon(int games_won) {
        this.games_won = games_won;
    }

    public int getGamesLost() {
        return games_lost;
    }

    public void setGamesLost(int games_lost) {
        this.games_lost = games_lost;
    }

    public int getGamesUnfinished() {
        return games_unfinished;
    }

    public void setGamesUnfinished(int games_unfinished) {
        this.games_unfinished = games_unfinished;
    }

    public int getCurrentStreak() {
        return current_streak;
    }

    public void setCurrentStreak(int current_streak) {
        this.current_streak = current_streak;
    }

    public int getMaxStreak() {
        return max_streak;
    }

    public void setMaxStreak(int max_streak) {
        this.max_streak = max_streak;
    }

    public int getPerfectPuzzles() {
        return perfect_puzzles;
    }

    public void setPerfectPuzzles(int perfect_puzzles) {
        this.perfect_puzzles = perfect_puzzles;
    }

    public int[] getMistakeHistogram() {
        return mistake_histogram;
    }

    public void setMistakeHistogram(int[] mistake_histogram) {
        if (mistake_histogram == null || mistake_histogram.length != 6) {
            throw new IllegalArgumentException("mistakeHistogram must have length 6");
        }
        this.mistake_histogram = mistake_histogram;
    }

    public double getWinRate() {
        return games_played == 0 ? 0.0 : (games_won * 100.0) / games_played;
    }

    public double getLossRate() {
        return games_played == 0 ? 0.0 : (games_lost * 100.0) / games_played;
    }

    @Override
    public String toString() {
        return "UserStats{" +
                "gamesPlayed=" + games_played +
                ", gamesWon=" + games_won +
                ", gamesLost=" + games_lost +
                ", gamesUnfinished=" + games_unfinished +
                ", currentStreak=" + current_streak +
                ", maxStreak=" + max_streak +
                ", perfectPuzzles=" + perfect_puzzles +
                ", mistakeHistogram=" + Arrays.toString(mistake_histogram) +
                '}';
    }
}

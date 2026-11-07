package common.model;

import com.google.gson.annotations.SerializedName;

import common.model.enums.playerOutcome;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class playerGameState {

    @SerializedName("accountId")
    private String account_id;
    @SerializedName("correctGroupsFound")
    private int correct_groups_found;
    @SerializedName("wrongProposals")
    private int wrong_proposals;
    @SerializedName("malformedProposals")
    private int malformed_proposals;
    @SerializedName("currentScore")
    private int current_score;
    private playerOutcome outcome;
    @SerializedName("finishedAtEpochMillis")
    private long finished_at_epoch_millis;
    @SerializedName("foundGroupKeys")
    private Set<String> found_group_keys;

    public playerGameState() {
        this.outcome = playerOutcome.PLAYING;
        this.found_group_keys = new HashSet<>();
    }

    public playerGameState(String account_id) {
        this();
        this.account_id = account_id;
    }

    public String getAccountId() {
        return account_id;
    }

    public void setAccountId(String account_id) {
        this.account_id = account_id;
    }

    public int getCorrectGroupsFound() {
        return correct_groups_found;
    }

    public void setCorrectGroupsFound(int correct_groups_found) {
        this.correct_groups_found = correct_groups_found;
    }

    public int getWrongProposals() {
        return wrong_proposals;
    }

    public void setWrongProposals(int wrong_proposals) {
        this.wrong_proposals = wrong_proposals;
    }

    public int getMalformedProposals() {
        return malformed_proposals;
    }

    public void setMalformedProposals(int malformed_proposals) {
        this.malformed_proposals = malformed_proposals;
    }

    public int getCurrentScore() {
        return current_score;
    }

    public void setCurrentScore(int current_score) {
        this.current_score = current_score;
    }

    public playerOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(playerOutcome outcome) {
        this.outcome = outcome;
    }

    public long getFinishedAtEpochMillis() {
        return finished_at_epoch_millis;
    }

    public void setFinishedAtEpochMillis(long finished_at_epoch_millis) {
        this.finished_at_epoch_millis = finished_at_epoch_millis;
    }

    public Set<String> getFoundGroupKeys() {
        return Collections.unmodifiableSet(found_group_keys);
    }

    public void setFoundGroupKeys(Set<String> found_group_keys) {
        this.found_group_keys = new HashSet<>(found_group_keys);
    }

    public void addFoundGroupKey(String key) {
        this.found_group_keys.add(key);
    }

    public boolean hasFoundGroupKey(String key) {
        return this.found_group_keys.contains(key);
    }

    public boolean isFinished() {
        return outcome != playerOutcome.PLAYING;
    }

    public boolean canSubmit() {
        return outcome == playerOutcome.PLAYING;
    }

    public void incrementCorrectGroups() {
        this.correct_groups_found++;
        recomputeScore();
    }

    public void incrementWrongProposals() {
        this.wrong_proposals++;
        recomputeScore();
    }

    public void recomputeScore() {
        int bonus;
        switch (correct_groups_found) {
            case 1 -> bonus = 6;
            case 2 -> bonus = 12;
            case 3 -> bonus = 18;
            default -> bonus = 0;
        }
        this.current_score = bonus - (4 * wrong_proposals);
    }

    public void markWon() {
        this.outcome = playerOutcome.WON;
        this.finished_at_epoch_millis = System.currentTimeMillis();
    }

    public void markLost() {
        this.outcome = playerOutcome.LOST;
        this.finished_at_epoch_millis = System.currentTimeMillis();
    }

    public void markTimeout() {
        this.outcome = playerOutcome.TIMEOUT;
        this.finished_at_epoch_millis = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return "PlayerGameState{" +
                "account_id='" + account_id + '\'' +
                ", correctGroupsFound=" + correct_groups_found +
                ", wrongProposals=" + wrong_proposals +
                ", malformedProposals=" + malformed_proposals +
                ", currentScore=" + current_score +
                ", outcome=" + outcome +
                ", finishedAtEpochMillis=" + finished_at_epoch_millis +
                ", foundGroupKeys=" + found_group_keys +
                '}';
    }
}

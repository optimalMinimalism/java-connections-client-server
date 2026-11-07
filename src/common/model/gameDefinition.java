package common.model;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import common.util.canonicalizer;

public class gameDefinition {

    @SerializedName("gameId")
    private int game_id;
    private List<groupDefinition> groups;

    public gameDefinition() {
        this.groups = new ArrayList<>();
    }

    public gameDefinition(int game_id, List<groupDefinition> groups) {
        this.game_id = game_id;
        this.groups = new ArrayList<>(groups);
    }

    public void validate() {
        if (groups == null || groups.size() != 4) {
            throw new IllegalArgumentException("Un puzzle deve contenere quattro gruppi");
        }

        Set<String> unique_words = new HashSet<>();
        for (groupDefinition group : groups) {
            if (group == null || group.getTheme() == null || group.getTheme().isBlank()
                    || group.getWords().size() != 4) {
                throw new IllegalArgumentException("Ogni gruppo deve avere un tema e quattro parole");
            }

            for (String word : group.getWords()) {
                if (word == null || word.isBlank() || !unique_words.add(canonicalizer.normalizeWord(word))) {
                    throw new IllegalArgumentException("Parola vuota o ripetuta nel puzzle");
                }
            }
        }
    }

    public int getGameId() {
        return game_id;
    }

    public void setGameId(int game_id) {
        this.game_id = game_id;
    }

    public List<groupDefinition> getGroups() {
        return Collections.unmodifiableList(groups);
    }

    public void setGroups(List<groupDefinition> groups) {
        this.groups = new ArrayList<>(groups);
    }

    public List<String> getAllWords() {
        List<String> all_words = new ArrayList<>();
        for (groupDefinition group : groups) {
            all_words.addAll(group.getWords());
        }

        return all_words;
    }

    public int getGroupCount() {
        return groups == null ? 0 : groups.size();
    }

    @Override
    public String toString() {
        return "GameDefinition{" +
                "gameId=" + game_id +
                ", groups=" + groups +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof gameDefinition that)) return false;
        return game_id == that.game_id && Objects.equals(groups, that.groups);
    }

    @Override
    public int hashCode() {
        return Objects.hash(game_id, groups);
    }
}

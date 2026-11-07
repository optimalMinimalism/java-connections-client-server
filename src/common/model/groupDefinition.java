package common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public class groupDefinition {

    private String theme;
    private List<String> words;

    public groupDefinition() {
        this.words = new ArrayList<>();
    }

    public groupDefinition(String theme, List<String> words) {
        this.theme = theme;
        this.words = new ArrayList<>(words);
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public List<String> getWords() {
        return Collections.unmodifiableList(words);
    }

    public void setWords(List<String> words) {
        this.words = new ArrayList<>(words);
    }

    public boolean containsWord(String word) {
        return words != null && words.contains(word);
    }

    @Override
    public String toString() {
        return "GroupDefinition{" +
                "theme='" + theme + '\'' +
                ", words=" + words +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof groupDefinition that)) return false;
        return Objects.equals(theme, that.theme) && Objects.equals(words, that.words);
    }

    @Override
    public int hashCode() {
        return Objects.hash(theme, words);
    }
}

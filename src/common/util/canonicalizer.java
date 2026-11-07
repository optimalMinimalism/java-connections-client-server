package common.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class canonicalizer {

    private canonicalizer() {
    }

    public static String canonicalizeWords(List<String> words) {
        List<String> normalized = new ArrayList<>();
        for (String word : words) {
            normalized.add(normalizeWord(word));
        }

        Collections.sort(normalized);
        return String.join("|", normalized);
    }

    // Il confronto non dipende dalla lingua impostata sul sistema.
    public static String normalizeWord(String word) {
        return word == null ? "" : word.trim().toUpperCase(Locale.ROOT);
    }
}

package server.core;

import common.model.gameDefinition;
import common.protocol.jsonCodec;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

// Lettura e indice protetti da state_lock.
public class puzzleRepository implements AutoCloseable {

    private JsonReader reader;
    private String file_path;
    private int current_puzzle_index;

    public void load(String file_path) throws IOException {
        this.file_path = file_path;
        reopen();
    }

    private void reopen() throws IOException {
        close();
        reader = new JsonReader(Files.newBufferedReader(Path.of(file_path), StandardCharsets.UTF_8));
        try {
            reader.beginArray();
            if (!reader.hasNext()) throw new IOException("Dataset vuoto");
            current_puzzle_index = 0;
        } catch (IOException | RuntimeException e) {
            close();
            throw e;
        }
    }

    public gameDefinition getNextPuzzle() {
        int saved_index = current_puzzle_index;
        try {
            if (reader == null) setCurrentPuzzleIndex(saved_index);
            if (!reader.hasNext()) {
                reopen(); // Fine dataset: si riparte dal primo puzzle.
                saved_index = 0;
            }
            gameDefinition puzzle = jsonCodec.gson().fromJson(reader, gameDefinition.class);
            if (puzzle == null) throw new IllegalArgumentException("Puzzle nullo");
            puzzle.validate();
            current_puzzle_index++;
            return puzzle;
        } catch (IOException | RuntimeException e) {
            // In caso di errore mantieni l'indice precedente.
            current_puzzle_index = saved_index;
            try { close(); } catch (IOException close_error) { e.addSuppressed(close_error); }
            throw new IllegalStateException("Puzzle non valido alla posizione " + saved_index, e);
        }
    }

    public int getCurrentPuzzleIndex() { return current_puzzle_index; }

    public void setCurrentPuzzleIndex(int index) {
        if (index < 0) throw new IllegalArgumentException("Indice puzzle negativo");
        int saved_index = current_puzzle_index;
        try {
            reopen();
            for (int i = 0; i < index; i++) {
                if (!reader.hasNext()) throw new IOException("Avanzamento dataset non valido");
                reader.skipValue();
                current_puzzle_index++;
            }
        } catch (IOException | RuntimeException e) {
            current_puzzle_index = saved_index;
            try { close(); } catch (IOException close_error) { e.addSuppressed(close_error); }
            throw new IllegalStateException("Ripristino del dataset fallito", e);
        }
    }

    public void close() throws IOException {
        JsonReader current_reader = reader;
        reader = null;
        if (current_reader != null) current_reader.close();
    }
}

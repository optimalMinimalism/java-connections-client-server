package common.protocol;

import com.google.gson.annotations.SerializedName;

public class notificationMessage {

    // Anche con Base64 e intestazione JSON il datagramma resta sotto 1200 byte.
    public static final int FRAGMENT_BYTES = 750;
    public static final int MAX_FRAGMENTS = 1024;
    private String type;
    @SerializedName("gameId")
    private int game_id;
    private String message;

    public notificationMessage() {
    }

    public notificationMessage(String type, int game_id, String message) {
        this.type = type;
        this.game_id = game_id;
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public int getGameId() {
        return game_id;
    }

    public String getMessage() {
        return message;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setGameId(int game_id) {
        this.game_id = game_id;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}

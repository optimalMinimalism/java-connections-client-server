package common.protocol;

import common.model.enums.errorCode;
import com.google.gson.JsonObject;

public class responseMessage {

    private String status;
    private String message;
    private String code;
    private JsonObject payload;

    public responseMessage() {
        this.payload = new JsonObject();
    }

    public static responseMessage ok(String message) {
        responseMessage response = new responseMessage();
        response.status = "ok";
        response.message = message;
        return response;
    }

    public static responseMessage error(errorCode code, String message) {
        responseMessage response = new responseMessage();
        response.status = "error";
        response.code = code.name();
        response.message = message;
        return response;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public String getCode() {
        return code;
    }

    public JsonObject getPayload() {
        return payload;
    }

    public void setPayload(JsonObject payload) {
        this.payload = payload;
    }

    public void addProperty(String key, String value) {
        payload.addProperty(key, value);
    }

    public void addProperty(String key, Number value) {
        payload.addProperty(key, value);
    }

    public void addProperty(String key, Boolean value) {
        payload.addProperty(key, value);
    }
}

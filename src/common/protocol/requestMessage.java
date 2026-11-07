package common.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

public class requestMessage {

    private String operation;
    private JsonObject payload;

    public requestMessage() {
        this.payload = new JsonObject();
    }

    public requestMessage(String operation) {
        this();
        this.operation = operation;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public JsonObject getPayload() {
        return payload;
    }

    public void setPayload(JsonObject payload) {
        this.payload = payload;
    }

    public String getString(String key) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) return null;
        if (!payload.get(key).isJsonPrimitive() || !payload.getAsJsonPrimitive(key).isString()) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }

        return payload.get(key).getAsString();
    }

    public Integer getInt(String key) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) return null;
        if (!payload.get(key).isJsonPrimitive() || !payload.getAsJsonPrimitive(key).isNumber()) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }

        try {
            return payload.get(key).getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("INVALID_REQUEST");
        }
    }

    public JsonArray getArray(String key) {
        if (!payload.has(key) || payload.get(key).isJsonNull()) return null;
        if (!payload.get(key).isJsonArray()) throw new IllegalArgumentException("INVALID_REQUEST");
        return payload.getAsJsonArray(key);
    }
}

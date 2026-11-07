package server.net;

import common.protocol.jsonCodec;
import common.protocol.requestMessage;
import common.protocol.responseMessage;
import com.google.gson.JsonObject;
import server.core.protocolRouter;
import server.session.playerSession;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class clientHandler implements Runnable {

    private static final int MAX_REQUEST_CHARS = 16_384;

    private final Socket socket;
    private final String session_id = java.util.UUID.randomUUID().toString();
    private final protocolRouter protocol_router;
    private final Map<String, playerSession> live_sessions;
    private final playerSession session;

    public clientHandler(
            Socket socket,
            protocolRouter protocol_router,
            Map<String, playerSession> live_sessions
    ) {
        this.socket = socket;
        this.protocol_router = protocol_router;
        this.live_sessions = live_sessions;
        this.session = new playerSession(socket.getInetAddress().getHostAddress());
    }

    @Override
    public void run() {
        try (
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)
                );
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)
                )
        ) {
            // Attese TCP e invio delle risposte non occupano il monitor del gioco.
            while (true) {
                String line;
                try {
                    line = readRequestLine(reader);
                } catch (IllegalArgumentException e) {
                    writeResponse(writer, responseMessage.error(common.model.enums.errorCode.INVALID_REQUEST,
                        "Request too long"));
                    break;
                }
                if (line == null) break;
                writeResponse(writer, handleIncomingLine(line));
            }
        } catch (IOException e) {
            // Connessione chiusa o errore di rete.
        } finally {
            cleanupSession();
            closeQuietly();
        }
    }

    private String readRequestLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') {
                if (line.length() > 0 && line.charAt(line.length() - 1) == '\r') {
                    line.setLength(line.length() - 1);
                }

                return line.toString();
            }
            if (line.length() >= MAX_REQUEST_CHARS) throw new IllegalArgumentException("Request too long");
            line.append((char) character);
        }
        if (line.length() != 0) throw new IOException("Richiesta TCP interrotta");
        return null;
    }

    private responseMessage handleIncomingLine(String line) {
        try {
            JsonObject raw = jsonCodec.parseObject(line);
            requestMessage request = new requestMessage();
            if (!raw.has("operation") || !raw.get("operation").isJsonPrimitive()
                    || !raw.getAsJsonPrimitive("operation").isString()) {
                return responseMessage.error(common.model.enums.errorCode.INVALID_REQUEST,
                    "Missing or invalid operation");
            }
            request.setOperation(raw.get("operation").getAsString());

            JsonObject payload = new JsonObject();
            for (String key : raw.keySet()) {
                if (!"operation".equals(key)) {
                    payload.add(key, raw.get(key));
                }
            }
            request.setPayload(payload);

            synchronized (protocol_router.getStateLock()) {
                responseMessage response = protocol_router.route(request, session);
                updateLiveSessionsMap();
                return response;
            }
        } catch (Exception e) {
            return responseMessage.error(common.model.enums.errorCode.INVALID_REQUEST, "Malformed JSON request");
        }
    }

    private void writeResponse(BufferedWriter writer, responseMessage response) throws IOException {
        writer.write(jsonCodec.toJson(response));
        writer.newLine();
        writer.flush();
    }

    private void updateLiveSessionsMap() {
        if (session.isLoggedIn() && session.getAccountId() != null) {
            live_sessions.put(session_id, session);
        } else {
            live_sessions.remove(session_id);
        }
    }

    private void cleanupSession() {
        synchronized (protocol_router.getStateLock()) {
            session.setConnected(false);
            session.clearLogin();
            live_sessions.remove(session_id);
        }
    }

    private void closeQuietly() {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }
}

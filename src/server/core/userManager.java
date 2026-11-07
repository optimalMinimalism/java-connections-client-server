package server.core;

import common.model.user;
import common.model.enums.errorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

// Accessi protetti da state_lock.
public class userManager {

    private final Map<String, user> users;

    public userManager() {
        this.users = new HashMap<>();
    }

    public userManager(Map<String, user> initial_users) {
        this();
        if (initial_users != null) {
            this.users.putAll(initial_users);
        }
    }

    public user register(String username, String password) {
        validateUsernameAndPassword(username, password);

        if (users.containsKey(username)) {
            throw new IllegalArgumentException(errorCode.USERNAME_ALREADY_EXISTS.name());
        }

        user account = new user(username, password);
        account.setAccountId(java.util.UUID.randomUUID().toString());
        users.put(username, account);
        return account;
    }

    public user authenticate(String username, String password) {
        validateUsernameAndPassword(username, password);

        user account = users.get(username);
        if (account == null || !account.passwordMatches(password)) {
            throw new IllegalArgumentException(errorCode.INVALID_CREDENTIALS.name());
        }

        return account;
    }

    public user updateCredentials(
            String old_username,
            String old_password,
            String new_username,
            String new_password
    ) {
        validateUsernameAndPassword(old_username, old_password);

        user existing = users.get(old_username);
        if (existing == null) {
            throw new IllegalArgumentException(errorCode.USER_NOT_FOUND.name());
        }

        if (!existing.passwordMatches(old_password)) {
            throw new IllegalArgumentException(errorCode.INVALID_CREDENTIALS.name());
        }

        if ((new_username != null && new_username.isBlank())
                || (new_password != null && new_password.isBlank())) {
            throw new IllegalArgumentException(errorCode.INVALID_REQUEST.name());
        }

        boolean change_username = new_username != null && !new_username.isBlank() && !new_username.equals(old_username);
        boolean change_password = new_password != null && !new_password.isBlank();

        if (!change_username && !change_password) {
            return existing;
        }

        if (change_username && users.containsKey(new_username)) {
            throw new IllegalArgumentException(errorCode.NEW_USERNAME_ALREADY_EXISTS.name());
        }

        if (change_username) {
            users.remove(old_username);
            existing.setUsername(new_username);
            users.put(new_username, existing);
        }

        if (change_password) {
            existing.setPassword(new_password);
        }

        return existing;
    }

    public user getUserByUsername(String username) {
        return users.get(username);
    }

    public user getUserById(String account_id) {
        if (account_id == null) return null;
        for (user account : users.values()) {
            if (account_id.equals(account.getAccountId())) {
                return account;
            }
        }

        return null;
    }

    public List<user> getAllUsers() {
        return new ArrayList<>(users.values());
    }

    // Copia superficiale: anche la serializzazione deve restare sotto state_lock.
    public Map<String, user> snapshotUsers() {
        return new HashMap<>(users);
    }

    private void validateUsernameAndPassword(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalArgumentException(errorCode.INVALID_REQUEST.name());
        }
    }
}

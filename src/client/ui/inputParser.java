package client.ui;

import java.io.Console;
import java.util.Arrays;
import java.util.Scanner;

public final class inputParser {

    private inputParser() {
    }

    public static String readPassword(Scanner scanner, String prompt) {
        Console console = System.console();
        if (console == null) {
            System.out.println("Hidden input unavailable. Run the client in a terminal to hide passwords.");
            System.out.print(prompt);
            return scanner.nextLine().trim();
        }
        char[] password = console.readPassword("%s", prompt);
        if (password == null) {
            throw new IllegalStateException("Password input closed.");
        }

        try {
            return new String(password).trim();
        } finally {
            Arrays.fill(password, '\0');
        }
    }

}

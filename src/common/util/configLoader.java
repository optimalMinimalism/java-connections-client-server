package common.util;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class configLoader {

    private final Properties properties;

    public configLoader(String path) throws IOException {
        this.properties = new Properties();
        try (InputStream in = new FileInputStream(path)) {
            this.properties.load(in);
        }
    }

    public String getString(String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing config key: " + key);
        }

        return value.trim();
    }

    public int getInt(String key) {
        return Integer.parseInt(getString(key));
    }

    public long getLong(String key) {
        return Long.parseLong(getString(key));
    }

}

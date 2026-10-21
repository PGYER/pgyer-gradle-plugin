package com.pgyer.upload.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Facts about this plugin build. */
public final class PluginInfo {

    private static final String VERSION = load();

    private PluginInfo() {}

    /** The plugin version, such as {@code 1.0.0}. */
    public static String version() {
        return VERSION;
    }

    private static String load() {
        try (InputStream in = PluginInfo.class.getResourceAsStream("/com/pgyer/upload/plugin.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String version = props.getProperty("version");
                if (version != null && !version.isBlank() && !version.contains("$")) {
                    return version.trim();
                }
            }
        } catch (IOException ignored) {
            // Fall through.
        }
        return "unknown";
    }
}

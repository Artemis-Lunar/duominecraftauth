package com.artemislunar.duoauth;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

public record PluginConfig(
        String apiHost,
        String integrationKey,
        String secretKey,
        int timeoutSeconds,
        boolean failOpen,
        Set<String> protectedPlayers,
        Path opsFile,
        Set<String> protectedOps,
        Map<String, String> bindings,
        String prefix,
        String deniedMessage,
        String timeoutMessage,
        String failedMessage,
        String errorMessage,
        String enrollMessage
) {

    private static final String CONFIG_NAME = "duominecraftauth.properties";

    public static PluginConfig load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);

        Path configPath = dataDirectory.resolve(CONFIG_NAME);
        if (Files.notExists(configPath)) {
            try (InputStream inputStream = PluginConfig.class.getClassLoader().getResourceAsStream(CONFIG_NAME)) {
                if (inputStream == null) {
                    throw new IOException("Default config resource " + CONFIG_NAME + " was not found");
                }
                try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
                    writer.write(new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }

        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }

        return new PluginConfig(
                require(properties, "duo.apiHost"),
                require(properties, "duo.integrationKey"),
                require(properties, "duo.secretKey"),
                Integer.parseInt(properties.getProperty("duo.timeoutSeconds", "65")),
                Boolean.parseBoolean(properties.getProperty("duo.failOpen", "false")),
                parseProtectedPlayers(properties.getProperty("security.protectedPlayers", "")),
                parseOptionalPath(dataDirectory, properties.getProperty("security.opsFile", "")),
                loadProtectedOps(dataDirectory, properties.getProperty("security.opsFile", "")),
                parseBindings(properties),
                properties.getProperty("messages.prefix", "[Duo]"),
                properties.getProperty("messages.denied", "You must approve the Duo push before joining."),
                properties.getProperty("messages.timeout", "Duo verification timed out. Please try again."),
                properties.getProperty("messages.failed", "Duo verification failed."),
                properties.getProperty("messages.error", "Duo is currently unavailable. Contact an admin."),
                properties.getProperty("messages.enroll", "Duo enrollment is required before this account can log in.")
        );
    }

    public boolean requiresDuo(String username) {
        String normalized = normalize(username);
        return protectedPlayers.contains(normalized) || protectedOps.contains(normalized);
    }

    public String resolveDuoUsername(String minecraftUsername) {
        return bindings.getOrDefault(normalize(minecraftUsername), normalize(minecraftUsername));
    }

    private static Set<String> parseProtectedPlayers(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }

        Set<String> values = new LinkedHashSet<>();
        for (String entry : raw.split(",")) {
            String normalized = normalize(entry);
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return Collections.unmodifiableSet(values);
    }

    private static Map<String, String> parseBindings(Properties properties) {
        Map<String, String> values = new HashMap<>();
        for (String key : properties.stringPropertyNames()) {
            if (!key.startsWith("bindings.")) {
                continue;
            }

            String minecraftUsername = normalize(key.substring("bindings.".length()));
            String duoUsername = properties.getProperty(key, "").trim();
            if (!minecraftUsername.isEmpty() && !duoUsername.isEmpty()) {
                values.put(minecraftUsername, duoUsername);
            }
        }
        return Collections.unmodifiableMap(values);
    }

    private static Path parseOptionalPath(Path dataDirectory, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path path = Path.of(raw.trim());
        if (path.isAbsolute()) {
            return path;
        }
        return dataDirectory.resolve(path).normalize();
    }

    private static Set<String> loadProtectedOps(Path dataDirectory, String rawPath) throws IOException {
        Path path = parseOptionalPath(dataDirectory, rawPath);
        return OpsFileLoader.loadProtectedOps(path);
    }

    private static String require(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required config key: " + key);
        }
        return value.trim();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}

package com.artemislunar.duoauth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class OpsFileLoader {

    private OpsFileLoader() {
    }

    public static Set<String> loadProtectedOps(Path opsFile) throws IOException {
        if (opsFile == null || Files.notExists(opsFile)) {
            return Collections.emptySet();
        }

        try (Reader reader = Files.newBufferedReader(opsFile, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            if (!root.isJsonArray()) {
                return Collections.emptySet();
            }

            JsonArray array = root.getAsJsonArray();
            Set<String> usernames = new LinkedHashSet<>();
            for (JsonElement entry : array) {
                if (!entry.isJsonObject()) {
                    continue;
                }

                JsonObject object = entry.getAsJsonObject();
                String name = getString(object, "name");
                if (!name.isBlank()) {
                    usernames.add(name.trim().toLowerCase(Locale.ROOT));
                }
            }
            return Collections.unmodifiableSet(usernames);
        }
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }
}

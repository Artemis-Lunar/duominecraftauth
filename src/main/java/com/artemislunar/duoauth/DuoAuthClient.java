package com.artemislunar.duoauth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class DuoAuthClient {

    private final PluginConfig config;
    private final HttpClient httpClient;

    public DuoAuthClient(PluginConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public DuoDecision verify(String duoUsername, String ipAddress) throws IOException, InterruptedException {
        PreauthResponse preauth = preauth(duoUsername, ipAddress);
        return switch (preauth.result()) {
            case "allow" -> new DuoDecision(true, "allowed");
            case "deny" -> new DuoDecision(false, fallback(preauth.statusMessage(), config.failedMessage()));
            case "enroll" -> new DuoDecision(false, fallback(preauth.statusMessage(), config.enrollMessage()));
            case "auth" -> verifyPush(duoUsername, ipAddress);
            default -> new DuoDecision(false, fallback(preauth.statusMessage(), config.failedMessage()));
        };
    }

    private DuoDecision verifyPush(String duoUsername, String ipAddress) throws IOException, InterruptedException {
        AuthStartResponse authStart = startPush(duoUsername, ipAddress);
        if (authStart.txid().isBlank()) {
            return new DuoDecision(false, config.failedMessage());
        }

        long deadline = System.currentTimeMillis() + (config.timeoutSeconds() * 1000L);
        while (System.currentTimeMillis() < deadline) {
            AuthStatusResponse status = fetchAuthStatus(authStart.txid());
            if ("allow".equalsIgnoreCase(status.result())) {
                return new DuoDecision(true, fallback(status.statusMessage(), "allowed"));
            }
            if ("deny".equalsIgnoreCase(status.result())) {
                String message = status.isTimeout() ? config.timeoutMessage() : fallback(status.statusMessage(), config.failedMessage());
                return new DuoDecision(false, message);
            }
            if (!"waiting".equalsIgnoreCase(status.result())) {
                return new DuoDecision(false, fallback(status.statusMessage(), config.failedMessage()));
            }
            sleepQuietly(1500L);
        }

        return new DuoDecision(false, config.timeoutMessage());
    }

    private PreauthResponse preauth(String duoUsername, String ipAddress) throws IOException, InterruptedException {
        Map<String, String> params = new TreeMap<>();
        params.put("username", duoUsername);
        if (!ipAddress.isBlank()) {
            params.put("ipaddr", ipAddress);
        }

        JsonObject response = sendSignedRequest("POST", "/auth/v2/preauth", params);
        return new PreauthResponse(
                getNestedString(response, "result"),
                getNestedString(response, "status_msg")
        );
    }

    private AuthStartResponse startPush(String duoUsername, String ipAddress) throws IOException, InterruptedException {
        Map<String, String> params = new TreeMap<>();
        params.put("username", duoUsername);
        params.put("factor", "push");
        params.put("device", "auto");
        params.put("async", "1");
        if (!ipAddress.isBlank()) {
            params.put("ipaddr", ipAddress);
        }

        JsonObject response = sendSignedRequest("POST", "/auth/v2/auth", params);
        return new AuthStartResponse(getNestedString(response, "txid"));
    }

    private AuthStatusResponse fetchAuthStatus(String txid) throws IOException, InterruptedException {
        Map<String, String> params = new TreeMap<>();
        params.put("txid", txid);

        JsonObject response = sendSignedRequest("GET", "/auth/v2/auth_status", params);
        return new AuthStatusResponse(
                getNestedString(response, "result"),
                getNestedString(response, "status"),
                getNestedString(response, "status_msg")
        );
    }

    private JsonObject sendSignedRequest(String method, String path, Map<String, String> params) throws IOException, InterruptedException {
        String paramString = buildParamString(params);
        String dateHeader = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC));
        String canonical = String.join("\n",
                dateHeader,
                method.toUpperCase(Locale.ROOT),
                config.apiHost().toLowerCase(Locale.ROOT),
                path,
                paramString
        );

        String signature = hmacSha1Hex(config.secretKey(), canonical);
        String basicAuth = Base64.getEncoder().encodeToString(
                (config.integrationKey() + ":" + signature).getBytes(StandardCharsets.UTF_8)
        );

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .timeout(Duration.ofSeconds(Math.max(config.timeoutSeconds(), 15)))
                .header("Date", dateHeader)
                .header("Authorization", "Basic " + basicAuth);

        if ("GET".equalsIgnoreCase(method)) {
            URI uri = URI.create("https://" + config.apiHost() + path + "?" + paramString);
            builder.uri(uri).GET();
        } else {
            URI uri = URI.create("https://" + config.apiHost() + path);
            builder.uri(uri)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(paramString));
        }

        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Duo API returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        String stat = getString(root, "stat");
        if (!"OK".equalsIgnoreCase(stat)) {
            throw new IOException("Duo API stat was not OK: " + response.body());
        }
        JsonElement responseElement = root.get("response");
        if (responseElement == null || !responseElement.isJsonObject()) {
            throw new IOException("Duo API response field was missing: " + response.body());
        }
        return responseElement.getAsJsonObject();
    }

    private static String getNestedString(JsonObject object, String key) {
        return getString(object, key);
    }

    private static String getString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    private static String fallback(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String buildParamString(Map<String, String> params) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(params.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));

        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < entries.size(); index++) {
            Map.Entry<String, String> entry = entries.get(index);
            if (index > 0) {
                builder.append('&');
            }
            builder.append(percentEncode(entry.getKey()));
            builder.append('=');
            builder.append(percentEncode(entry.getValue()));
        }
        return builder.toString();
    }

    private static String percentEncode(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            int unsigned = current & 0xFF;
            if (isUnreserved(unsigned)) {
                builder.append((char) unsigned);
            } else {
                builder.append('%');
                builder.append(Character.toUpperCase(Character.forDigit((unsigned >> 4) & 0xF, 16)));
                builder.append(Character.toUpperCase(Character.forDigit(unsigned & 0xF, 16)));
            }
        }
        return builder.toString();
    }

    private static boolean isUnreserved(int value) {
        return (value >= 'A' && value <= 'Z')
                || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9')
                || value == '-'
                || value == '.'
                || value == '_'
                || value == '~';
    }

    private static String hmacSha1Hex(String secret, String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] digest = mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte current : digest) {
                builder.append(String.format("%02x", current & 0xFF));
            }
            return builder.toString();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create Duo signature", exception);
        }
    }

    private static void sleepQuietly(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private record PreauthResponse(String result, String statusMessage) {
    }

    private record AuthStartResponse(String txid) {
    }

    private record AuthStatusResponse(String result, String status, String statusMessage) {
        private boolean isTimeout() {
            return "timeout".equalsIgnoreCase(status);
        }
    }
}

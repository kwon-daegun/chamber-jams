package com.chamberjams.backend.auth;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class TelegramInitDataValidator {

    private static final long MAX_AGE_SECONDS = 86_400;
    private static final long MAX_FUTURE_SKEW_SECONDS = 60;

    private static final Pattern SHA256_HEX =
        Pattern.compile("[0-9a-fA-F]{64}");

    private final String botToken;

    public TelegramInitDataValidator(
            @Value("${telegram.bot-token}") String botToken) {

        if (botToken == null || botToken.isBlank()) {
            throw new IllegalStateException(
                "Telegram bot token is not configured"
            );
        }

        this.botToken = botToken.trim();
    }

    /**
     * Validates Telegram Mini App initData and returns its verified fields.
     * The returned map does not contain the received hash.
     */
    public Map<String, String> validate(String rawInitData) {
        if (rawInitData == null || rawInitData.isBlank()) {
            throw new IllegalArgumentException(
                "Telegram init data is required"
            );
        }

        Map<String, String> fields = parseQueryString(rawInitData);

        String receivedHash = fields.remove("hash");

        if (receivedHash == null
                || !SHA256_HEX.matcher(receivedHash).matches()) {
            throw new IllegalArgumentException(
                "Invalid Telegram init data"
            );
        }

        String dataCheckString = fields.entrySet()
            .stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + "=" + entry.getValue())
            .collect(Collectors.joining("\n"));

        try {
            byte[] secretKey = hmacSha256(
                "WebAppData".getBytes(StandardCharsets.UTF_8),
                botToken.getBytes(StandardCharsets.UTF_8)                
            );

            byte[] calculatedHash = hmacSha256(
                secretKey,
                dataCheckString.getBytes(StandardCharsets.UTF_8)
            );

            String calculatedHex = HexFormat.of()
                .formatHex(calculatedHash);

            boolean hashMatches = MessageDigest.isEqual(
                calculatedHex.getBytes(StandardCharsets.US_ASCII),
                receivedHash.toLowerCase(Locale.ROOT)
                    .getBytes(StandardCharsets.US_ASCII)
            );

            if (!hashMatches) {
                throw new IllegalArgumentException(
                    "Invalid Telegram init data"
                );
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException(
                "Unable to validate Telegram init data",
                exception
            );
        }

        validateAuthDate(fields.get("auth_date"));

        if (!fields.containsKey("user")) {
            throw new IllegalArgumentException(
                "Telegram user information is missing"
            );
        }

        return Map.copyOf(fields);
    }

    private static Map<String, String> parseQueryString(String rawData) {
        Map<String, String> fields = new LinkedHashMap<>();

        for (String part : rawData.split("&")) {
            if (part.isEmpty()) {
                continue;
            }

            int separator = part.indexOf("=");

            if (separator < 1) {
                throw new IllegalArgumentException(
                    "Invalid Telegram init data"
                );
            }

            String key = URLDecoder.decode(
                part.substring(0, separator),
                StandardCharsets.UTF_8
            );

            String value = URLDecoder.decode(
                part.substring(separator + 1),
                StandardCharsets.UTF_8
            );

            if (fields.containsKey(key)) {
                throw new IllegalArgumentException(
                    "Invalid Telegram init data"
                );
            }

            fields.put(key, value);
        }

        return fields;
    }

    private static void validateAuthDate(String value) {
        if (value == null) {
            throw new IllegalArgumentException(
                "Telegram authentication date is missing"
            );
        }

        final long authTimestamp;

        try {
            authTimestamp = Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "Invalid Telegram authentication date"
            );
        }

        long now = Instant.now().getEpochSecond();

        if (authTimestamp > now + MAX_FUTURE_SKEW_SECONDS
                || now - authTimestamp > MAX_AGE_SECONDS) {
            throw new IllegalArgumentException(
                "Telegram authentication data has expired"
            );
        }
    }

    private static byte[] hmacSha256(
            byte[] key,
            byte[] message) throws GeneralSecurityException {

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));

        return mac.doFinal(message);
    }
}

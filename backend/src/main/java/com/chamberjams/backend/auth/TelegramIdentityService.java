
package com.chamberjams.backend.auth;

import java.util.Map;

import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.chamberjams.backend.auth.TelegramAuthController.TelegramProfile;

@Service
public class TelegramIdentityService {

    private final TelegramInitDataValidator validator;
    private final ObjectMapper objectMapper;

    public TelegramIdentityService(
            TelegramInitDataValidator validator,
            ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    public TelegramProfile validateProfile(String initData) {
        if (initData == null || initData.isBlank()) {
            throw new IllegalArgumentException(
                    "Telegram authentication is required.");
        }

        Map<String, String> fields = validator.validate(initData);
        String userJson = fields.get("user");

        if (userJson == null || userJson.isBlank()) {
            throw new IllegalArgumentException(
                    "Telegram user information is missing.");
        }

        try {
            JsonNode user = objectMapper.readTree(userJson);
            JsonNode id = user.get("id");
            String firstName = textOrNull(user, "first_name");

            if (id == null
                    || !id.isIntegralNumber()
                    || firstName == null
                    || firstName.isBlank()) {
                throw new IllegalArgumentException(
                        "Invalid Telegram profile.");
            }

            return new TelegramProfile(
                    id.asText(),
                    firstName,
                    textOrNull(user, "last_name"),
                    textOrNull(user, "username"),
                    textOrNull(user, "photo_url"),
                    textOrNull(user, "language_code")
            );

        } catch (JacksonException exception) {
            throw new IllegalArgumentException(
                    "Invalid Telegram profile data.",
                    exception);
        }
    }

    private static String textOrNull(JsonNode user, String field) {
        JsonNode value = user.get(field);

        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }

        return value.asText();
    }
}

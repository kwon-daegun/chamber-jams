package com.chamberjams.backend.auth;

import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chamberjams.backend.auth.TelegramInitDataValidator;

@RestController
@RequestMapping("/api/auth")
public class TelegramAuthController {

    private final TelegramInitDataValidator validator;
    private final ObjectMapper objectMapper;

    public TelegramAuthController(
            TelegramInitDataValidator validator,
            ObjectMapper objectMapper) {
        this.validator = validator;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/telegram/validate")
    public ResponseEntity<?> validateTelegramUser(
            @RequestBody(required = false) InitDataRequest request) {

        if (request == null
                || request.initData() == null
                || request.initData().isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Telegram initData is required"));
        }

        try {
            // Validate Telegram's signature and auth_date first.
            Map<String, String> fields =
                validator.validate(request.initData());

            // The user field is JSON contained in the signed data.
            JsonNode user = objectMapper.readTree(fields.get("user"));

            JsonNode id = user.get("id");
            String firstName = textOrNull(user, "first_name");

            if (id == null
                    || !id.isIntegralNumber()
                    || firstName == null
                    || firstName.isBlank()) {
                return unauthorized();
            }

            TelegramProfile profile = new TelegramProfile(
                id.asText(),
                firstName,
                textOrNull(user, "last_name"),
                textOrNull(user, "username"),
                textOrNull(user, "photo_url"),
                textOrNull(user, "language_code")
            );

            return ResponseEntity.ok(profile);

        } catch (IllegalArgumentException
                | JacksonException exception) {
            return unauthorized();
        }
    }

    private static String textOrNull(JsonNode user, String field) {
        JsonNode value = user.get(field);

        if (value == null || value.isNull() || !value.isValueNode()) {
            return null;
        }

        return value.asText();
    }

    private static ResponseEntity<Map<String, String>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(Map.of(
                "error",
                "Invalid or expired Telegram authentication data"
            ));
    }

    public record InitDataRequest(String initData) {}

    public record TelegramProfile(
        String telegramId,
        String firstName,
        String lastName,
        String username,
        String photoUrl,
        String languageCode
    ) {}
}

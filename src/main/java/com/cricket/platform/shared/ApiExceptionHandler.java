package com.cricket.platform.shared;

import com.cricket.platform.identity.LoginUser;
import com.cricket.platform.player.CreatePlayer;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(LoginUser.InvalidCredentialsException.class)
    ResponseEntity<ApiError> invalidCredentials(LoginUser.InvalidCredentialsException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError("INVALID_CREDENTIALS", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    ResponseEntity<ApiError> duplicate(DuplicateKeyException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("EMAIL_ALREADY_EXISTS", "An account with this email already exists.", Instant.now()));
    }

    @ExceptionHandler(CreatePlayer.PlayerProfileAlreadyExistsException.class)
    ResponseEntity<ApiError> playerProfileExists(CreatePlayer.PlayerProfileAlreadyExistsException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiError("PLAYER_PROFILE_ALREADY_EXISTS", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(CreatePlayer.PlayerProfileNotFoundException.class)
    ResponseEntity<ApiError> playerProfileNotFound(CreatePlayer.PlayerProfileNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("PLAYER_PROFILE_NOT_FOUND", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<ApiError> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest()
                .body(new ApiError("VALIDATION_ERROR", ex.getMessage(), Instant.now()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        Map<String, String> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return ResponseEntity.badRequest().body(
                new ApiError("VALIDATION_ERROR", "Please check the highlighted fields.", Instant.now(), fields));
    }

    public record ApiError(String code, String message, Instant timestamp, Map<String, String> fields) {
        public ApiError(String code, String message, Instant timestamp) {
            this(code, message, timestamp, Map.of());
        }
    }
}

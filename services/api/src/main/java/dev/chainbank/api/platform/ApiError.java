package dev.chainbank.api.platform;

/** Consistent error payload for every endpoint. Never includes stack traces or secrets. */
public record ApiError(int status, String code, String message, String correlationId) {
}

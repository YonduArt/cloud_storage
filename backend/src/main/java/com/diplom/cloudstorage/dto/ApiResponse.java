package com.diplom.cloudstorage.dto;

import java.time.Instant;

public record ApiResponse<T>(
        boolean success,
        T data,
        String message,
        Instant timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now());
    }

    public static <T> ApiResponse<T> ok(String message, T data) {
        return new ApiResponse<>(true, data, message, Instant.now());
    }

    public static ApiResponse<Void> fail(String message) {
        return new ApiResponse<>(false, null, message, Instant.now());
    }
}

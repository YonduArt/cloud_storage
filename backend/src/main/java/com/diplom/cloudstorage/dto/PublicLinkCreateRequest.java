package com.diplom.cloudstorage.dto;

public record PublicLinkCreateRequest(
        Integer expiresInDays,
        String password
) {
}

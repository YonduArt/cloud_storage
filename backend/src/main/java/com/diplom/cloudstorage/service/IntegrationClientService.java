package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.exception.ApiException;
import com.diplom.cloudstorage.integration.dto.IntegrationClientCreateRequest;
import com.diplom.cloudstorage.integration.dto.IntegrationClientCreateResponse;
import com.diplom.cloudstorage.integration.dto.IntegrationClientResponse;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.model.IntegrationClient;
import com.diplom.cloudstorage.repository.AppUserRepository;
import com.diplom.cloudstorage.repository.IntegrationClientRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IntegrationClientService {
    public static final Set<String> ALLOWED_SCOPES = Set.of("read", "write", "upload", "download", "search", "stats");
    public static final List<String> DEFAULT_SCOPES = List.of("read", "search", "stats", "upload", "download", "write");

    private final IntegrationClientRepository integrationClientRepository;
    private final AppUserRepository appUserRepository;

    public IntegrationClientService(IntegrationClientRepository integrationClientRepository,
                                    AppUserRepository appUserRepository) {
        this.integrationClientRepository = integrationClientRepository;
        this.appUserRepository = appUserRepository;
    }

    @Transactional
    public IntegrationClientCreateResponse createClient(IntegrationClientCreateRequest request) {
        AppUser owner = requireCurrentUser();
        String apiKey = generateApiKey();

        IntegrationClient client = new IntegrationClient();
        client.setOwner(owner);
        client.setName(request.name().trim());
        client.setApiKeyHash(hashApiKey(apiKey));
        client.setScopes(joinScopes(normalizeScopes(request.scopes())));
        client.setEnabled(true);

        IntegrationClient saved = integrationClientRepository.save(client);
        return new IntegrationClientCreateResponse(
                saved.getId(),
                saved.getName(),
                apiKey,
                splitScopes(saved.getScopes()),
                saved.isEnabled(),
                saved.getCreatedAt()
        );
    }

    @Transactional(readOnly = true)
    public List<IntegrationClientResponse> listCurrentUserClients() {
        AppUser owner = requireCurrentUser();
        return integrationClientRepository.findByOwnerIdOrderByCreatedAtDesc(owner.getId()).stream()
                .map(client -> new IntegrationClientResponse(
                        client.getId(),
                        client.getName(),
                        splitScopes(client.getScopes()),
                        client.isEnabled(),
                        client.getCreatedAt(),
                        client.getLastUsedAt()
                ))
                .toList();
    }

    @Transactional
    public void revokeClient(Long clientId) {
        AppUser owner = requireCurrentUser();
        IntegrationClient client = integrationClientRepository.findByIdAndOwnerId(clientId, owner.getId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Integration client not found"));
        client.setEnabled(false);
        integrationClientRepository.save(client);
    }

    @Transactional
    public IntegrationClient requireEnabledByApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing X-Api-Key");
        }
        String hash = hashApiKey(apiKey.trim());
        IntegrationClient client = integrationClientRepository.findByApiKeyHashAndEnabledTrue(hash)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid API key"));
        client.setLastUsedAt(Instant.now());
        return integrationClientRepository.save(client);
    }

    private String generateApiKey() {
        return "csk_" + UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private AppUser requireCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        String login = authentication.getName().trim().toLowerCase();
        return appUserRepository.findByUsernameIgnoreCaseOrEmailIgnoreCase(login, login)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    private String hashApiKey(String apiKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot hash API key");
        }
    }

    private List<String> normalizeScopes(List<String> requestedScopes) {
        if (requestedScopes == null || requestedScopes.isEmpty()) {
            return DEFAULT_SCOPES;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String scope : requestedScopes) {
            if (scope == null || scope.isBlank()) {
                continue;
            }
            String value = scope.trim().toLowerCase();
            if (!ALLOWED_SCOPES.contains(value)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported scope: " + value);
            }
            normalized.add(value);
        }
        if (normalized.isEmpty()) {
            return DEFAULT_SCOPES;
        }
        return List.copyOf(normalized);
    }

    public List<String> splitScopes(String scopesRaw) {
        if (scopesRaw == null || scopesRaw.isBlank()) {
            return DEFAULT_SCOPES;
        }
        return List.of(scopesRaw.split(",")).stream()
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(String::toLowerCase)
                .distinct()
                .toList();
    }

    private String joinScopes(List<String> scopes) {
        return scopes.stream().collect(Collectors.joining(","));
    }
}

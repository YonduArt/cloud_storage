package com.diplom.cloudstorage.repository;

import com.diplom.cloudstorage.model.IntegrationClient;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntegrationClientRepository extends JpaRepository<IntegrationClient, Long> {
    Optional<IntegrationClient> findByApiKeyHashAndEnabledTrue(String apiKeyHash);
    List<IntegrationClient> findByOwnerIdOrderByCreatedAtDesc(Long ownerId);
    Optional<IntegrationClient> findByIdAndOwnerId(Long id, Long ownerId);
}

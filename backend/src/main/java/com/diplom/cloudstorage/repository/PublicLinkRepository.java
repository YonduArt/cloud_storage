package com.diplom.cloudstorage.repository;

import com.diplom.cloudstorage.model.PublicLink;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PublicLinkRepository extends JpaRepository<PublicLink, Long> {
    Optional<PublicLink> findByToken(String token);
    Optional<PublicLink> findByIdAndFileOwnerId(Long id, Long ownerId);
    Optional<PublicLink> findByIdAndFolderOwnerId(Long id, Long ownerId);
    Optional<PublicLink> findByFileIdAndActiveTrue(Long fileId);
    Optional<PublicLink> findByFolderIdAndActiveTrue(Long folderId);
    List<PublicLink> findByFileOwnerIdOrFolderOwnerIdOrderByCreatedAtDesc(Long fileOwnerId, Long folderOwnerId);
    List<PublicLink> findByActiveTrueAndExpiresAtBefore(Instant now);
    void deleteByFileId(Long fileId);
}

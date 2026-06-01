package com.diplom.cloudstorage.repository;

import com.diplom.cloudstorage.model.StoredFile;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findByOwnerIdAndFolderIdAndDeletedAtIsNull(Long ownerId, Long folderId);
    List<StoredFile> findByOwnerIdAndFolderIsNullAndDeletedAtIsNull(Long ownerId);
    Optional<StoredFile> findByOwnerIdAndFolderIdAndNameIgnoreCaseAndDeletedAtIsNull(Long ownerId, Long folderId, String name);
    Optional<StoredFile> findByOwnerIdAndFolderIsNullAndNameIgnoreCaseAndDeletedAtIsNull(Long ownerId, String name);
    List<StoredFile> findByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long ownerId);
    List<StoredFile> findByDeletedAtIsNotNullAndPurgeAfterBefore(Instant now);
    List<StoredFile> findByOwnerIdAndDeletedAtIsNull(Long ownerId);
    List<StoredFile> findTop20ByOwnerIdAndDeletedAtIsNullOrderByUploadedAtDesc(Long ownerId);
    List<StoredFile> findTop20ByOwnerIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc(Long ownerId);
    List<StoredFile> findTop10ByOwnerIdAndDeletedAtIsNullOrderBySizeBytesDesc(Long ownerId);
    List<StoredFile> findByOwnerIdAndFileGroupAndDeletedAtIsNullOrderByUploadedAtDesc(Long ownerId, String fileGroup);
    List<StoredFile> findByOwnerIdAndFavoriteTrueAndDeletedAtIsNullOrderByUploadedAtDesc(Long ownerId);
    long countByOwnerIdAndDeletedAtIsNull(Long ownerId);
    long countByDeletedAtIsNull();

    @Query("select f from StoredFile f where f.owner.id = :ownerId and f.deletedAt is null and lower(f.name) like lower(concat('%', :query, '%'))")
    List<StoredFile> searchByName(@Param("ownerId") Long ownerId, @Param("query") String query);
}

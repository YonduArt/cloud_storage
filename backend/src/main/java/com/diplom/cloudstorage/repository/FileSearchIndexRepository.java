package com.diplom.cloudstorage.repository;

import com.diplom.cloudstorage.model.FileSearchIndex;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FileSearchIndexRepository extends JpaRepository<FileSearchIndex, Long> {
    Optional<FileSearchIndex> findByFileId(Long fileId);

    @Query("""
            select i from FileSearchIndex i
            join fetch i.file f
            where i.owner.id = ?1
              and i.status = 'READY'
              and f.deletedAt is null
            """)
    List<FileSearchIndex> findReadyForOwner(Long ownerId);
}

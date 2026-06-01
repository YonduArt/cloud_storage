package com.diplom.cloudstorage.repository;

import com.diplom.cloudstorage.model.FileEmbedding;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileEmbeddingRepository extends JpaRepository<FileEmbedding, Long> {
    void deleteByFileId(Long fileId);
    List<FileEmbedding> findByFileId(Long fileId);
    List<FileEmbedding> findByOwnerIdAndEmbeddingType(Long ownerId, String embeddingType);
}

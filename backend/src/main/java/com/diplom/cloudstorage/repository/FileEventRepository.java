package com.diplom.cloudstorage.repository;

import com.diplom.cloudstorage.model.FileEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FileEventRepository extends JpaRepository<FileEvent, Long> {
    List<FileEvent> findTop100ByOwnerIdOrderByCreatedAtDesc(Long ownerId);
}

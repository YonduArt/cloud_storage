package com.diplom.cloudstorage.repository;

import com.diplom.cloudstorage.model.Folder;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FolderRepository extends JpaRepository<Folder, Long> {
    List<Folder> findByOwnerIdAndParentId(Long ownerId, Long parentId);
    List<Folder> findByOwnerIdAndParentIsNull(Long ownerId);
    Optional<Folder> findByOwnerIdAndParentIdAndNameIgnoreCase(Long ownerId, Long parentId, String name);
    Optional<Folder> findByOwnerIdAndParentIsNullAndNameIgnoreCase(Long ownerId, String name);
    List<Folder> findByOwnerIdAndFavoriteTrueOrderByCreatedAtDesc(Long ownerId);

    @Query("select f from Folder f where f.owner.id = :ownerId and lower(f.name) like lower(concat('%', :query, '%'))")
    List<Folder> searchByName(@Param("ownerId") Long ownerId, @Param("query") String query);
}

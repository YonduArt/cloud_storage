package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.exception.ApiException;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class FileStorageService {

    private final Path storageRoot;

    public FileStorageService(@Value("${app.storage.root:storage}") String storageRoot) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.storageRoot);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot create storage root");
        }
    }

    public String save(Long userId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "File is empty");
        }

        String safeName = file.getOriginalFilename() == null ? "file.bin" : file.getOriginalFilename().replace("..", "");
        String storedFileName = UUID.randomUUID() + "_" + safeName;
        Path userDir = storageRoot.resolve(String.valueOf(userId));
        try {
            Files.createDirectories(userDir);
            Path target = userDir.resolve(storedFileName);
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot store file");
        }
    }

    public Resource loadAsResource(String absolutePath) {
        try {
            Path path = Paths.get(absolutePath).normalize();
            Resource resource = new UrlResource(path.toUri());
            if (!resource.exists()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "File not found in storage");
            }
            return resource;
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read file from storage");
        }
    }

    public String saveThumbnail(Long userId, byte[] bytes) {
        Path thumbnailDir = storageRoot.resolve(String.valueOf(userId)).resolve("thumbnails");
        String storedFileName = UUID.randomUUID() + "_thumb.jpg";
        try {
            Files.createDirectories(thumbnailDir);
            Path target = thumbnailDir.resolve(storedFileName);
            Files.copy(new ByteArrayInputStream(bytes), target, StandardCopyOption.REPLACE_EXISTING);
            return target.toString();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot store file thumbnail");
        }
    }

    public void delete(String absolutePath) {
        try {
            Files.deleteIfExists(Paths.get(absolutePath).normalize());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot delete file from storage");
        }
    }
}

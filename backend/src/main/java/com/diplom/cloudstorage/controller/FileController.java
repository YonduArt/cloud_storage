package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.BulkFileRequest;
import com.diplom.cloudstorage.dto.FileIndexStatusResponse;
import com.diplom.cloudstorage.dto.FileResponse;
import com.diplom.cloudstorage.dto.MoveRequest;
import com.diplom.cloudstorage.dto.PreviewResponse;
import com.diplom.cloudstorage.dto.RenameRequest;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.service.FileStorageService;
import com.diplom.cloudstorage.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/files")
@Tag(name = "Files", description = "File operations")
public class FileController {

    private final StorageService storageService;
    private final FileStorageService fileStorageService;

    public FileController(StorageService storageService, FileStorageService fileStorageService) {
        this.storageService = storageService;
        this.fileStorageService = fileStorageService;
    }

    @PostMapping("/upload")
    @Operation(summary = "Upload single file")
    public ResponseEntity<ApiResponse<FileResponse>> upload(@RequestParam(required = false) Long folderId,
                                                            @RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.upload(folderId, file)));
    }

    @PostMapping("/upload-folder")
    @Operation(summary = "Upload folder by files[] and relativePaths[]")
    public ResponseEntity<ApiResponse<List<FileResponse>>> uploadFolder(@RequestParam(required = false) Long folderId,
                                                                        @RequestParam("files") List<MultipartFile> files,
                                                                        @RequestParam("relativePaths") List<String> relativePaths) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.uploadFolder(folderId, files, relativePaths)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get file metadata")
    public ResponseEntity<ApiResponse<FileResponse>> getFile(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFileMetadata(id)));
    }

    @GetMapping("/{id}/index-status")
    @Operation(summary = "Get file indexing status")
    public ResponseEntity<ApiResponse<FileIndexStatusResponse>> indexStatus(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFileIndexStatus(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename file")
    public ResponseEntity<ApiResponse<FileResponse>> rename(@PathVariable Long id, @Valid @RequestBody RenameRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.renameFile(id, request)));
    }

    @PatchMapping("/{id}/move")
    @Operation(summary = "Move file to target folder")
    public ResponseEntity<ApiResponse<FileResponse>> move(@PathVariable Long id, @Valid @RequestBody MoveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.moveFile(id, request)));
    }

    @PatchMapping("/{id}/favorite")
    @Operation(summary = "Mark or unmark file as favorite")
    public ResponseEntity<ApiResponse<FileResponse>> favorite(@PathVariable Long id,
                                                              @RequestParam(defaultValue = "true") boolean favorite) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.setFileFavorite(id, favorite)));
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Download file")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        StoredFile file = storageService.getFileById(id);
        storageService.touchAccess(id);
        Resource resource = fileStorageService.loadAsResource(file.getStoredPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(resource);
    }

    @GetMapping("/{id}/preview")
    @Operation(summary = "Get file preview metadata")
    public ResponseEntity<ApiResponse<PreviewResponse>> preview(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFilePreview(id)));
    }

    @GetMapping("/{id}/preview/content")
    @Operation(summary = "Read file content inline for preview")
    public ResponseEntity<Resource> previewContent(@PathVariable Long id) {
        StoredFile file = storageService.getFileById(id);
        storageService.touchAccess(id);
        Resource resource = fileStorageService.loadAsResource(file.getStoredPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                        .filename(file.getName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(resource);
    }

    @GetMapping("/{id}/thumbnail")
    @Operation(summary = "Download file thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable Long id) {
        StoredFile file = storageService.getFileThumbnail(id);
        boolean generatedThumbnail = file.getThumbnailPath() != null && !file.getThumbnailPath().isBlank();
        Resource resource = fileStorageService.loadAsResource(generatedThumbnail ? file.getThumbnailPath() : file.getStoredPath());
        return ResponseEntity.ok()
                .contentType(generatedThumbnail ? MediaType.IMAGE_JPEG : MediaType.parseMediaType(file.getContentType()))
                .body(resource);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete file")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
        storageService.deleteFile(id);
        return ResponseEntity.ok(ApiResponse.ok("File deleted", null));
    }

    @PatchMapping("/{id}/trash")
    @Operation(summary = "Move file to trash")
    public ResponseEntity<ApiResponse<FileResponse>> moveToTrash(@PathVariable Long id,
                                                                 @RequestParam(defaultValue = "30") int keepDays) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.moveToTrash(id, keepDays)));
    }

    @PatchMapping("/{id}/restore")
    @Operation(summary = "Restore file from trash")
    public ResponseEntity<ApiResponse<FileResponse>> restore(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.restoreFromTrash(id)));
    }

    @PatchMapping("/trash/restore-batch")
    @Operation(summary = "Restore several files from trash")
    public ResponseEntity<ApiResponse<List<FileResponse>>> restoreBatch(@Valid @RequestBody BulkFileRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.restoreFilesFromTrash(request.ids())));
    }

    @PostMapping("/trash/delete-batch")
    @Operation(summary = "Delete several trash files permanently")
    public ResponseEntity<ApiResponse<Void>> deleteBatch(@Valid @RequestBody BulkFileRequest request) {
        storageService.deleteFiles(request.ids());
        return ResponseEntity.ok(ApiResponse.ok("Files deleted", null));
    }

    @GetMapping("/trash")
    @Operation(summary = "List files in trash")
    public ResponseEntity<ApiResponse<List<FileResponse>>> trash() {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getTrash()));
    }

    @GetMapping("/recent-uploaded")
    @Operation(summary = "List recently uploaded files")
    public ResponseEntity<ApiResponse<List<FileResponse>>> recentUploaded(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getRecentUploaded(limit)));
    }

    @GetMapping("/recent-opened")
    @Operation(summary = "List recently opened files")
    public ResponseEntity<ApiResponse<List<FileResponse>>> recentOpened(@RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getRecentOpened(limit)));
    }

    @GetMapping("/groups/{group}")
    @Operation(summary = "List files by file group")
    public ResponseEntity<ApiResponse<List<FileResponse>>> filesByGroup(@PathVariable String group) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFilesByGroup(group)));
    }
}

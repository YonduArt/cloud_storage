package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.FolderCreateRequest;
import com.diplom.cloudstorage.dto.FolderResponse;
import com.diplom.cloudstorage.dto.MoveRequest;
import com.diplom.cloudstorage.dto.RenameRequest;
import com.diplom.cloudstorage.dto.StorageViewResponse;
import com.diplom.cloudstorage.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ByteArrayResource;
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

@RestController
@RequestMapping("/api/folders")
@Tag(name = "Folders", description = "Folder operations")
public class FolderController {

    private final StorageService storageService;

    public FolderController(StorageService storageService) {
        this.storageService = storageService;
    }

    @PostMapping
    @Operation(summary = "Create empty folder")
    public ResponseEntity<ApiResponse<FolderResponse>> createFolder(@Valid @RequestBody FolderCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.createFolder(request)));
    }

    @GetMapping("/root")
    @Operation(summary = "Get virtual root folder")
    public ResponseEntity<ApiResponse<FolderResponse>> root() {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getRootFolder()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get folder by id")
    public ResponseEntity<ApiResponse<FolderResponse>> getFolder(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFolder(id)));
    }

    @GetMapping("/{id}/content")
    @Operation(summary = "Get folder content with sort")
    public ResponseEntity<ApiResponse<StorageViewResponse>> getContent(@PathVariable Long id,
                                                                       @RequestParam(defaultValue = "name") String sort,
                                                                       @RequestParam(defaultValue = "asc") String order) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFolderContent(id, sort, order)));
    }

    @GetMapping("/root/content")
    @Operation(summary = "Get root folder content with sort")
    public ResponseEntity<ApiResponse<StorageViewResponse>> getRootContent(@RequestParam(defaultValue = "name") String sort,
                                                                           @RequestParam(defaultValue = "asc") String order) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFolderContent(null, sort, order)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Rename folder")
    public ResponseEntity<ApiResponse<FolderResponse>> renameFolder(@PathVariable Long id,
                                                                    @Valid @RequestBody RenameRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.renameFolder(id, request)));
    }

    @PatchMapping("/{id}/move")
    @Operation(summary = "Move folder to target folder")
    public ResponseEntity<ApiResponse<FolderResponse>> moveFolder(@PathVariable Long id,
                                                                  @Valid @RequestBody MoveRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.moveFolder(id, request)));
    }

    @PatchMapping("/{id}/favorite")
    @Operation(summary = "Mark or unmark folder as favorite")
    public ResponseEntity<ApiResponse<FolderResponse>> favoriteFolder(@PathVariable Long id,
                                                                      @RequestParam(defaultValue = "true") boolean favorite) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.setFolderFavorite(id, favorite)));
    }

    @GetMapping("/favorites")
    @Operation(summary = "List favorite folders and files")
    public ResponseEntity<ApiResponse<StorageViewResponse>> favorites() {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFavorites()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete empty folder")
    public ResponseEntity<ApiResponse<Void>> deleteFolder(@PathVariable Long id) {
        storageService.deleteFolder(id);
        return ResponseEntity.ok(ApiResponse.ok("Folder deleted", null));
    }

    @GetMapping("/{id}/download")
    @Operation(summary = "Download folder as zip archive")
    public ResponseEntity<ByteArrayResource> downloadFolder(@PathVariable Long id) {
        byte[] zip = storageService.downloadFolderAsZip(id);
        ByteArrayResource resource = new ByteArrayResource(zip);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("folder-" + id + ".zip", StandardCharsets.UTF_8)
                        .build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(zip.length)
                .body(resource);
    }
}

package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.PublicLinkCreateRequest;
import com.diplom.cloudstorage.dto.PublicLinkResponse;
import com.diplom.cloudstorage.dto.PublicResourceResponse;
import com.diplom.cloudstorage.model.Folder;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.service.FileStorageService;
import com.diplom.cloudstorage.service.PublicLinkService;
import com.diplom.cloudstorage.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api")
@Tag(name = "Public Links", description = "Public sharing endpoints")
public class PublicLinkController {

    private final PublicLinkService publicLinkService;
    private final FileStorageService fileStorageService;
    private final StorageService storageService;

    public PublicLinkController(PublicLinkService publicLinkService, FileStorageService fileStorageService, StorageService storageService) {
        this.publicLinkService = publicLinkService;
        this.fileStorageService = fileStorageService;
        this.storageService = storageService;
    }

    @PostMapping("/public-links/files/{fileId}")
    @Operation(summary = "Generate public link for file")
    public ResponseEntity<ApiResponse<PublicLinkResponse>> create(@PathVariable Long fileId,
                                                                  @RequestBody(required = false) PublicLinkCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(publicLinkService.create(fileId, request)));
    }

    @PostMapping("/public-links/folders/{folderId}")
    @Operation(summary = "Generate public link for folder")
    public ResponseEntity<ApiResponse<PublicLinkResponse>> createFolder(@PathVariable Long folderId,
                                                                        @RequestBody(required = false) PublicLinkCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(publicLinkService.createFolderLink(folderId, request)));
    }

    @GetMapping("/public-links")
    @Operation(summary = "List current user's public links")
    public ResponseEntity<ApiResponse<List<PublicLinkResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(publicLinkService.listCurrentUserLinks()));
    }

    @DeleteMapping("/public-links/{id}")
    @Operation(summary = "Disable public link")
    public ResponseEntity<ApiResponse<Void>> disable(@PathVariable Long id) {
        publicLinkService.disable(id);
        return ResponseEntity.ok(ApiResponse.ok("Public link disabled", null));
    }

    @GetMapping("/public/{token}")
    @Operation(summary = "Get public file or folder metadata")
    public ResponseEntity<ApiResponse<PublicResourceResponse>> publicResource(@PathVariable String token,
                                                                              @RequestParam(required = false) String password) {
        return ResponseEntity.ok(ApiResponse.ok(publicLinkService.resolvePublicResource(token, password)));
    }

    @GetMapping("/public/{token}/folders/{folderId}")
    @Operation(summary = "Get public folder content")
    public ResponseEntity<ApiResponse<PublicResourceResponse>> publicFolderResource(@PathVariable String token,
                                                                                    @PathVariable Long folderId,
                                                                                    @RequestParam(required = false) String password) {
        return ResponseEntity.ok(ApiResponse.ok(publicLinkService.resolvePublicFolderResource(token, folderId, password)));
    }

    @GetMapping("/public/{token}/download")
    @Operation(summary = "Download file or root folder by public token")
    public ResponseEntity<Resource> downloadByPublicLink(@PathVariable String token,
                                                         @RequestParam(required = false) String password) {
        PublicResourceResponse resource = publicLinkService.resolvePublicResource(token, password);
        if ("folder".equals(resource.targetType())) {
            Folder folder = publicLinkService.resolvePublicFolder(token, password);
            return folderDownloadResponse(folder);
        }
        StoredFile file = publicLinkService.resolvePublicFile(token, password);
        return fileDownloadResponse(file);
    }

    @GetMapping("/public/{token}/folders/{folderId}/download")
    @Operation(summary = "Download public folder as zip archive")
    public ResponseEntity<Resource> downloadPublicFolder(@PathVariable String token,
                                                         @PathVariable Long folderId,
                                                         @RequestParam(required = false) String password) {
        Folder folder = publicLinkService.resolvePublicFolder(token, folderId, password);
        return folderDownloadResponse(folder);
    }

    @GetMapping("/public/{token}/files/{fileId}/download")
    @Operation(summary = "Download file from public folder")
    public ResponseEntity<Resource> downloadPublicFolderFile(@PathVariable String token,
                                                             @PathVariable Long fileId,
                                                             @RequestParam(required = false) String password) {
        StoredFile file = publicLinkService.resolvePublicFolderFile(token, fileId, password);
        return fileDownloadResponse(file);
    }

    @GetMapping("/public/{token}/files/{fileId}/thumbnail")
    @Operation(summary = "Get public file thumbnail")
    public ResponseEntity<Resource> publicFileThumbnail(@PathVariable String token,
                                                       @PathVariable Long fileId,
                                                       @RequestParam(required = false) String password) {
        StoredFile file = publicLinkService.resolvePublicThumbnailFile(token, fileId, password);
        Resource resource = fileStorageService.loadAsResource(file.getThumbnailPath());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(resource);
    }

    private ResponseEntity<Resource> fileDownloadResponse(StoredFile file) {
        Resource resource = fileStorageService.loadAsResource(file.getStoredPath());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.getName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .body(resource);
    }

    private ResponseEntity<Resource> folderDownloadResponse(Folder folder) {
        byte[] zip = storageService.downloadFolderAsZip(folder.getOwner().getId(), folder.getId());
        ByteArrayResource resource = new ByteArrayResource(zip);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(folder.getName() + ".zip", StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentLength(zip.length)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }
}

package com.diplom.cloudstorage.integration;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.FileResponse;
import com.diplom.cloudstorage.dto.FolderCreateRequest;
import com.diplom.cloudstorage.dto.FolderResponse;
import com.diplom.cloudstorage.dto.SearchItemResponse;
import com.diplom.cloudstorage.dto.StorageStatsResponse;
import com.diplom.cloudstorage.dto.StorageViewResponse;
import com.diplom.cloudstorage.exception.ApiException;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.security.IntegrationApiKeyFilter;
import com.diplom.cloudstorage.service.FileStorageService;
import com.diplom.cloudstorage.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/integration/v1")
public class IntegrationApiController {

    private final StorageService storageService;
    private final FileStorageService fileStorageService;

    public IntegrationApiController(StorageService storageService,
                                    FileStorageService fileStorageService) {
        this.storageService = storageService;
        this.fileStorageService = fileStorageService;
    }

    @GetMapping("/health")
    public ResponseEntity<ApiResponse<String>> health() {
        return ResponseEntity.ok(ApiResponse.ok("UP"));
    }

    @GetMapping("/storage/root-content")
    public ResponseEntity<ApiResponse<StorageViewResponse>> rootContent(@RequestParam(defaultValue = "name") String sort,
                                                                        @RequestParam(defaultValue = "asc") String order,
                                                                        HttpServletRequest request) {
        requireScope(request, "read");
        return ResponseEntity.ok(ApiResponse.ok(storageService.getFolderContent(null, sort, order)));
    }

    @PostMapping("/folders")
    public ResponseEntity<ApiResponse<FolderResponse>> createFolder(@RequestBody FolderCreateRequest folderRequest,
                                                                    HttpServletRequest request) {
        requireScope(request, "write");
        return ResponseEntity.ok(ApiResponse.ok(storageService.createFolder(folderRequest)));
    }

    @PostMapping("/files/upload")
    public ResponseEntity<ApiResponse<FileResponse>> upload(@RequestParam(required = false) Long folderId,
                                                            @RequestParam("file") MultipartFile file,
                                                            HttpServletRequest request) {
        requireScope(request, "upload");
        return ResponseEntity.ok(ApiResponse.ok(storageService.upload(folderId, file)));
    }

    @GetMapping("/files/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, HttpServletRequest request) {
        requireScope(request, "download");
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

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<SearchItemResponse>>> search(@RequestParam String query,
                                                                        HttpServletRequest request) {
        requireScope(request, "search");
        return ResponseEntity.ok(ApiResponse.ok(storageService.search(query)));
    }

    @GetMapping("/storage/stats")
    public ResponseEntity<ApiResponse<StorageStatsResponse>> stats(HttpServletRequest request) {
        requireScope(request, "stats");
        return ResponseEntity.ok(ApiResponse.ok(storageService.getStorageStats()));
    }

    @SuppressWarnings("unchecked")
    private void requireScope(HttpServletRequest request, String scope) {
        Object attr = request.getAttribute(IntegrationApiKeyFilter.REQ_ATTR_SCOPES);
        if (!(attr instanceof List<?> scopes)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Integration scopes are missing");
        }
        List<String> scopeValues = (List<String>) scopes;
        if (!scopeValues.contains(scope)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Scope '" + scope + "' is required");
        }
    }
}

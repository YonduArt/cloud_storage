package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.StorageStatsResponse;
import com.diplom.cloudstorage.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/storage")
@Tag(name = "Storage", description = "Storage quota and usage statistics")
public class StorageStatsController {

    private final StorageService storageService;

    public StorageStatsController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping("/stats")
    @Operation(summary = "Get current user's storage usage statistics")
    public ResponseEntity<ApiResponse<StorageStatsResponse>> stats() {
        return ResponseEntity.ok(ApiResponse.ok(storageService.getStorageStats()));
    }
}

package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.SearchItemResponse;
import com.diplom.cloudstorage.service.StorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/search")
@Tag(name = "Search", description = "Search across folders and files")
public class SearchController {

    private final StorageService storageService;

    public SearchController(StorageService storageService) {
        this.storageService = storageService;
    }

    @GetMapping
    @Operation(summary = "Search by query")
    public ResponseEntity<ApiResponse<List<SearchItemResponse>>> search(@RequestParam String query) {
        return ResponseEntity.ok(ApiResponse.ok(storageService.search(query)));
    }

    @PostMapping("/reindex")
    @Operation(summary = "Reindex current user's files")
    public ResponseEntity<ApiResponse<Integer>> reindex() {
        return ResponseEntity.ok(ApiResponse.ok(storageService.reindexCurrentUserFiles()));
    }
}

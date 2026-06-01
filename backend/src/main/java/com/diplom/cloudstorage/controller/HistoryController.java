package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.FileEventResponse;
import com.diplom.cloudstorage.service.FileEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/history")
@Tag(name = "History", description = "User file activity history")
public class HistoryController {

    private final FileEventService eventService;

    public HistoryController(FileEventService eventService) {
        this.eventService = eventService;
    }

    @GetMapping
    @Operation(summary = "List current user's activity history")
    public ResponseEntity<ApiResponse<List<FileEventResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(eventService.listCurrentUserEvents()));
    }
}

package com.diplom.cloudstorage.controller;

import com.diplom.cloudstorage.dto.AdminUserResponse;
import com.diplom.cloudstorage.dto.AdminUserUpdateRequest;
import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.dto.PerformanceReportResponse;
import com.diplom.cloudstorage.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin", description = "Administration endpoints")
public class AdminController {

    private final AdminService adminService;

    public AdminController(AdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping("/users")
    @Operation(summary = "List users with storage statistics")
    public ResponseEntity<ApiResponse<List<AdminUserResponse>>> users() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.listUsers()));
    }

    @PatchMapping("/users/{id}")
    @Operation(summary = "Update user enabled state or storage quota")
    public ResponseEntity<ApiResponse<AdminUserResponse>> updateUser(@PathVariable Long id,
                                                                     @RequestBody AdminUserUpdateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(adminService.updateUser(id, request)));
    }

    @GetMapping("/performance-report")
    @Operation(summary = "Get synthetic performance report for diploma charts/tables")
    public ResponseEntity<ApiResponse<PerformanceReportResponse>> performanceReport() {
        return ResponseEntity.ok(ApiResponse.ok(adminService.getPerformanceReport()));
    }
}

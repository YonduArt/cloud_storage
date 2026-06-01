package com.diplom.cloudstorage.integration;

import com.diplom.cloudstorage.dto.ApiResponse;
import com.diplom.cloudstorage.integration.dto.IntegrationClientCreateRequest;
import com.diplom.cloudstorage.integration.dto.IntegrationClientCreateResponse;
import com.diplom.cloudstorage.integration.dto.IntegrationClientResponse;
import com.diplom.cloudstorage.service.IntegrationClientService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integration-clients")
public class IntegrationClientController {

    private final IntegrationClientService integrationClientService;

    public IntegrationClientController(IntegrationClientService integrationClientService) {
        this.integrationClientService = integrationClientService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<IntegrationClientCreateResponse>> create(@Valid @RequestBody IntegrationClientCreateRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(integrationClientService.createClient(request)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<IntegrationClientResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(integrationClientService.listCurrentUserClients()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable Long id) {
        integrationClientService.revokeClient(id);
        return ResponseEntity.ok(ApiResponse.ok("Integration API key revoked", null));
    }
}

package com.diplom.cloudstorage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "app.storage.quota-bytes=12")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class StorageStatsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnStorageStatsAndBlockUploadOverQuota() throws Exception {
        String token = registerAndGetToken();

        uploadFile(token, "notes.txt", "hello");

        mockMvc.perform(get("/api/storage/stats")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quotaBytes").value(12))
                .andExpect(jsonPath("$.data.usedBytes").value(5))
                .andExpect(jsonPath("$.data.freeBytes").value(7))
                .andExpect(jsonPath("$.data.fileCount").value(1))
                .andExpect(jsonPath("$.data.groups[0].fileGroup").value("document"))
                .andExpect(jsonPath("$.data.groups[0].bytes").value(5))
                .andExpect(jsonPath("$.data.largestFiles[0].name").value("notes.txt"));

        MockMultipartFile tooLarge = new MockMultipartFile(
                "file",
                "big.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "too much data".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/files/upload")
                        .file(tooLarge)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("Storage quota exceeded"));
    }

    private String registerAndGetToken() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"storage_user_%s",
                                  "email":"storage-user-%s@example.com",
                                  "password":"123456",
                                  "displayName":"Storage User"
                                }
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private void uploadFile(String token, String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }
}

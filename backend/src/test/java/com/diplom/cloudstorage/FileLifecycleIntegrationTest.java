package com.diplom.cloudstorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.repository.StoredFileRepository;
import com.diplom.cloudstorage.service.StorageService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FileLifecycleIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageService storageService;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Test
    void shouldMoveFileToTrashRestoreAndDeleteIt() throws Exception {
        String token = registerAndGetToken("lifecycle");
        long fileId = uploadFile(token, "report.txt", "content");

        mockMvc.perform(patch("/api/files/{id}/trash", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("keepDays", "7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedAt").exists())
                .andExpect(jsonPath("$.data.purgeAfter").exists());

        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/files/trash")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("report.txt"));

        mockMvc.perform(patch("/api/files/{id}/restore", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());

        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("report.txt"));

        mockMvc.perform(delete("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldDenyAccessToAnotherUsersFile() throws Exception {
        String ownerToken = registerAndGetToken("owner");
        String anotherToken = registerAndGetToken("another");
        long fileId = uploadFile(ownerToken, "private.txt", "secret");

        mockMvc.perform(get("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(delete("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void shouldIsolateTrashBetweenUsers() throws Exception {
        String ownerToken = registerAndGetToken("trash_owner");
        String anotherToken = registerAndGetToken("trash_another");
        long fileId = uploadFile(ownerToken, "isolated.txt", "hidden");

        mockMvc.perform(patch("/api/files/{id}/trash", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .param("keepDays", "7"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/files/trash")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(patch("/api/files/{id}/restore", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(delete("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/files/trash")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("isolated.txt"));
    }

    @Test
    void shouldPurgeExpiredTrashFiles() throws Exception {
        String token = registerAndGetToken("expired_trash");
        long fileId = uploadFile(token, "expired.txt", "expired content");

        mockMvc.perform(patch("/api/files/{id}/trash", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("keepDays", "1"))
                .andExpect(status().isOk());

        StoredFile file = storedFileRepository.findById(fileId).orElseThrow();
        file.setPurgeAfter(Instant.now().minusSeconds(60));
        storedFileRepository.save(file);

        int purged = storageService.purgeExpiredTrash();

        assertThat(purged).isEqualTo(1);
        assertThat(storedFileRepository.findById(fileId)).isEmpty();
        mockMvc.perform(get("/api/files/{id}", fileId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isNotFound());
    }

    @Test
    void shouldRestoreAndDeleteTrashFilesInBulk() throws Exception {
        String token = registerAndGetToken("bulk_trash");
        long firstId = uploadFile(token, "first.txt", "first");
        long secondId = uploadFile(token, "second.txt", "second");
        long thirdId = uploadFile(token, "third.txt", "third");

        moveToTrash(token, firstId);
        moveToTrash(token, secondId);
        moveToTrash(token, thirdId);

        mockMvc.perform(patch("/api/files/trash/restore-batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ids": [%d, %d]
                                }
                                """.formatted(firstId, secondId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].deletedAt").doesNotExist())
                .andExpect(jsonPath("$.data[1].deletedAt").doesNotExist());

        mockMvc.perform(get("/api/files/trash")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(thirdId));

        mockMvc.perform(post("/api/files/trash/delete-batch")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "ids": [%d]
                                }
                                """.formatted(thirdId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/files/trash")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void shouldRenameRestoredFileWhenNameAlreadyExists() throws Exception {
        String token = registerAndGetToken("restore_conflict");
        long trashedId = uploadFile(token, "report.txt", "old content");
        moveToTrash(token, trashedId);
        uploadFile(token, "report.txt", "new content");

        mockMvc.perform(patch("/api/files/{id}/restore", trashedId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("report (restored).txt"))
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());
    }

    private String registerAndGetToken(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"%s_%s",
                                  "email":"%s-%s@example.com",
                                  "password":"123456",
                                  "displayName":"Test User"
                                }
                                """.formatted(prefix, suffix, prefix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private long uploadFile(String token, String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private void moveToTrash(String token, long fileId) throws Exception {
        mockMvc.perform(patch("/api/files/{id}/trash", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("keepDays", "7"))
                .andExpect(status().isOk());
    }
}

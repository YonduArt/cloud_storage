package com.diplom.cloudstorage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HistoryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldLogCurrentUserFileAndFolderEvents() throws Exception {
        String token = registerAndGetToken("history");
        long folderId = createFolder(token, "Docs");
        long fileId = uploadFile(token, "notes.txt", "hello");

        mockMvc.perform(patch("/api/files/{id}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "notes-renamed.txt"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/api/files/{id}/move", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetFolderId": %d
                                }
                                """.formatted(folderId)))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/history")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4))
                .andExpect(jsonPath("$.data[0].action").value("FILE_MOVED"))
                .andExpect(jsonPath("$.data[0].targetName").value("notes-renamed.txt"))
                .andExpect(jsonPath("$.data[1].action").value("FILE_RENAMED"))
                .andExpect(jsonPath("$.data[2].action").value("FILE_UPLOADED"))
                .andExpect(jsonPath("$.data[3].action").value("FOLDER_CREATED"));
    }

    @Test
    void shouldNotShowAnotherUsersHistory() throws Exception {
        String ownerToken = registerAndGetToken("history_owner");
        String anotherToken = registerAndGetToken("history_another");

        uploadFile(ownerToken, "private.txt", "secret");

        mockMvc.perform(get("/api/history")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
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
                                  "displayName":"History User"
                                }
                                """.formatted(prefix, suffix, prefix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private long createFolder(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/folders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "parentId": null
                                }
                                """.formatted(name)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
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
}

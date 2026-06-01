package com.diplom.cloudstorage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
class FolderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldRejectMovingFolderIntoItsChild() throws Exception {
        String token = registerAndGetToken();
        long parentId = createFolder(token, "parent", null);
        long childId = createFolder(token, "child", parentId);

        mockMvc.perform(patch("/api/folders/{id}/move", parentId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetFolderId": %d
                                }
                                """.formatted(childId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Folder cannot be moved into itself or its child folder"));
    }

    @Test
    void shouldUploadFolderAndPreserveRelativePathStructure() throws Exception {
        String token = registerAndGetToken();
        MockMultipartFile readme = new MockMultipartFile(
                "files",
                "readme.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "hello".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile logo = new MockMultipartFile(
                "files",
                "logo.png",
                MediaType.IMAGE_PNG_VALUE,
                "png".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/files/upload-folder")
                        .file(readme)
                        .file(logo)
                        .param("relativePaths", "Project/docs/readme.txt", "Project/images/logo.png")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("readme.txt"))
                .andExpect(jsonPath("$.data[1].name").value("logo.png"));

        long projectId = findFolderId(token, null, "Project");
        long docsId = findFolderId(token, projectId, "docs");
        long imagesId = findFolderId(token, projectId, "images");

        assertFolderContainsFile(token, docsId, "readme.txt");
        assertFolderContainsFile(token, imagesId, "logo.png");
    }

    @Test
    void shouldIsolateFoldersBetweenUsers() throws Exception {
        String ownerToken = registerAndGetToken();
        String anotherToken = registerAndGetToken();
        long folderId = createFolder(ownerToken, "private-folder", null);

        mockMvc.perform(get("/api/folders/{id}", folderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(delete("/api/folders/{id}", folderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void shouldRejectDeletingNonEmptyFolderAndDownloadFolderArchive() throws Exception {
        String token = registerAndGetToken();
        long folderId = createFolder(token, "docs", null);

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "readme.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "archive content".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .param("folderId", String.valueOf(folderId))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/folders/{id}", folderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Folder is not empty"));

        mockMvc.perform(get("/api/folders/{id}/download", folderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_OCTET_STREAM_VALUE))
                .andExpect(header().exists(HttpHeaders.CONTENT_DISPOSITION));
    }

    @Test
    void shouldMarkFilesAndFoldersAsFavorites() throws Exception {
        String token = registerAndGetToken();
        long folderId = createFolder(token, "favorite-folder", null);
        long fileId = uploadFile(token, folderId, "favorite.txt", "favorite content");

        mockMvc.perform(patch("/api/folders/{id}/favorite", folderId)
                        .param("favorite", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(true));

        mockMvc.perform(patch("/api/files/{id}/favorite", fileId)
                        .param("favorite", "true")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(true));

        mockMvc.perform(get("/api/folders/favorites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders[0].name").value("favorite-folder"))
                .andExpect(jsonPath("$.data.files[0].name").value("favorite.txt"));

        mockMvc.perform(patch("/api/files/{id}/favorite", fileId)
                        .param("favorite", "false")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.favorite").value(false));

        mockMvc.perform(get("/api/folders/favorites")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.files.length()").value(0));
    }

    private String registerAndGetToken() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"folder_user_%s",
                                  "email":"folder-user-%s@example.com",
                                  "password":"123456",
                                  "displayName":"Folder User"
                                }
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private long createFolder(String token, String name, Long parentId) throws Exception {
        String parentJson = parentId == null ? "null" : parentId.toString();
        MvcResult result = mockMvc.perform(post("/api/folders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "parentId": %s
                                }
                                """.formatted(name, parentJson)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private long uploadFile(String token, Long folderId, String filename, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                MediaType.TEXT_PLAIN_VALUE,
                content.getBytes(StandardCharsets.UTF_8)
        );

        var request = multipart("/api/files/upload")
                .file(file)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (folderId != null) {
            request.param("folderId", String.valueOf(folderId));
        }

        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private long findFolderId(String token, Long parentId, String name) throws Exception {
        String endpoint = parentId == null ? "/api/folders/root/content" : "/api/folders/" + parentId + "/content";
        MvcResult result = mockMvc.perform(get(endpoint)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode folders = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("folders");
        for (JsonNode folder : folders) {
            if (name.equals(folder.path("name").asText())) {
                return folder.path("id").asLong();
            }
        }
        throw new AssertionError("Folder not found: " + name);
    }

    private void assertFolderContainsFile(String token, long folderId, String fileName) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/folders/{id}/content", folderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode files = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data")
                .path("files");
        for (JsonNode file : files) {
            if (fileName.equals(file.path("name").asText())) {
                return;
            }
        }
        throw new AssertionError("File not found: " + fileName);
    }
}

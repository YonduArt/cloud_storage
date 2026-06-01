package com.diplom.cloudstorage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.diplom.cloudstorage.repository.PublicLinkRepository;
import com.diplom.cloudstorage.service.PublicLinkService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.zip.ZipInputStream;
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
class PublicLinkIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PublicLinkRepository publicLinkRepository;

    @Autowired
    private PublicLinkService publicLinkService;

    @Test
    void shouldCreateDownloadAndDisablePublicLink() throws Exception {
        String token = registerAndGetToken();
        long fileId = uploadFile(token);

        MvcResult linkResult = mockMvc.perform(post("/api/public-links/files/{fileId}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").exists())
                .andExpect(jsonPath("$.data.fileName").value("public.txt"))
                .andExpect(jsonPath("$.data.sizeBytes").value(14))
                .andExpect(jsonPath("$.data.token").exists())
                .andExpect(jsonPath("$.data.active").value(true))
                .andReturn();

        JsonNode link = objectMapper.readTree(linkResult.getResponse().getContentAsString()).path("data");
        long linkId = link.path("id").asLong();
        String publicToken = link.path("token").asText();

        mockMvc.perform(get("/api/public/{token}", publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetType").value("file"))
                .andExpect(jsonPath("$.data.name").value("public.txt"))
                .andExpect(jsonPath("$.data.files[0].name").value("public.txt"));

        mockMvc.perform(get("/api/public/{token}/download", publicToken))
                .andExpect(status().isOk())
                .andExpect(content().bytes("public content".getBytes(StandardCharsets.UTF_8)));

        mockMvc.perform(get("/api/public-links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fileName").value("public.txt"))
                .andExpect(jsonPath("$.data[0].active").value(true));

        mockMvc.perform(delete("/api/public-links/{id}", linkId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public-links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active").value(false));

        mockMvc.perform(get("/api/public/{token}", publicToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldCreateExpiringPublicLinkAndRejectExpiredToken() throws Exception {
        String token = registerAndGetToken("expiring_public");
        long fileId = uploadFile(token);

        MvcResult linkResult = mockMvc.perform(post("/api/public-links/files/{fileId}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expiresInDays": 1
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.expired").value(false))
                .andReturn();

        JsonNode link = objectMapper.readTree(linkResult.getResponse().getContentAsString()).path("data");
        String publicToken = link.path("token").asText();

        var entity = publicLinkRepository.findByToken(publicToken).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(60));
        publicLinkRepository.save(entity);

        mockMvc.perform(get("/api/public/{token}", publicToken))
                .andExpect(status().isForbidden());

        org.assertj.core.api.Assertions.assertThat(publicLinkService.disableExpiredLinks()).isGreaterThanOrEqualTo(1);

        mockMvc.perform(get("/api/public-links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].active").value(false))
                .andExpect(jsonPath("$.data[0].expired").value(true));
    }

    @Test
    void shouldProtectPublicLinkWithPassword() throws Exception {
        String token = registerAndGetToken("password_public");
        long fileId = uploadFile(token);

        MvcResult linkResult = mockMvc.perform(post("/api/public-links/files/{fileId}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "password": "secret"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasPassword").value(true))
                .andReturn();

        String publicToken = objectMapper.readTree(linkResult.getResponse().getContentAsString())
                .path("data")
                .path("token")
                .asText();

        mockMvc.perform(get("/api/public/{token}", publicToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/public/{token}", publicToken)
                        .param("password", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetType").value("file"))
                .andExpect(jsonPath("$.data.hasPassword").value(true))
                .andExpect(jsonPath("$.data.files[0].name").value("public.txt"));

        mockMvc.perform(get("/api/public/{token}/download", publicToken)
                        .param("password", "secret"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("public content".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void shouldIsolatePublicLinksBetweenUsers() throws Exception {
        String ownerToken = registerAndGetToken("owner_public");
        String anotherToken = registerAndGetToken("another_public");
        long ownerFileId = uploadFile(ownerToken);

        MvcResult linkResult = mockMvc.perform(post("/api/public-links/files/{fileId}", ownerFileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andReturn();

        long linkId = objectMapper.readTree(linkResult.getResponse().getContentAsString())
                .path("data")
                .path("id")
                .asLong();

        mockMvc.perform(post("/api/public-links/files/{fileId}", ownerFileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/public-links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(delete("/api/public-links/{id}", linkId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(get("/api/public-links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].active").value(true));
    }

    @Test
    void shouldReuseActivePublicLinkForSameFile() throws Exception {
        String token = registerAndGetToken("reuse_public");
        long fileId = uploadFile(token);

        MvcResult firstResult = mockMvc.perform(post("/api/public-links/files/{fileId}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult secondResult = mockMvc.perform(post("/api/public-links/files/{fileId}", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode first = objectMapper.readTree(firstResult.getResponse().getContentAsString()).path("data");
        JsonNode second = objectMapper.readTree(secondResult.getResponse().getContentAsString()).path("data");
        String firstToken = first.path("token").asText();

        mockMvc.perform(get("/api/public-links")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].token").value(firstToken));

        org.assertj.core.api.Assertions.assertThat(second.path("id").asLong()).isEqualTo(first.path("id").asLong());
        org.assertj.core.api.Assertions.assertThat(second.path("token").asText()).isEqualTo(firstToken);
    }

    @Test
    void shouldCreateAndOpenPublicFolderLink() throws Exception {
        String token = registerAndGetToken("public_folder");
        long folderId = createFolder(token, "Shared docs");
        long insideFileId = uploadFile(token, folderId, "inside.txt", "inside content");

        MvcResult linkResult = mockMvc.perform(post("/api/public-links/folders/{folderId}", folderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetType").value("folder"))
                .andExpect(jsonPath("$.data.folderName").value("Shared docs"))
                .andReturn();

        String publicToken = objectMapper.readTree(linkResult.getResponse().getContentAsString())
                .path("data")
                .path("token")
                .asText();

        mockMvc.perform(get("/api/public/{token}", publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.targetType").value("folder"))
                .andExpect(jsonPath("$.data.name").value("Shared docs"))
                .andExpect(jsonPath("$.data.files.length()").value(1))
                .andExpect(jsonPath("$.data.files[0].name").value("inside.txt"));

        mockMvc.perform(get("/api/public/{token}/files/{fileId}/download", publicToken, insideFileId))
                .andExpect(status().isOk())
                .andExpect(content().bytes("inside content".getBytes(StandardCharsets.UTF_8)));

        MvcResult zipResult = mockMvc.perform(get("/api/public/{token}/download", publicToken))
                .andExpect(status().isOk())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(zipContainsEntry(zipResult.getResponse().getContentAsByteArray(), "Shared docs/inside.txt")).isTrue();
    }

    @Test
    void shouldOpenNestedFolderInsidePublicFolderLink() throws Exception {
        String token = registerAndGetToken("public_nested_folder");
        long rootFolderId = createFolder(token, null, "Shared root");
        long nestedFolderId = createFolder(token, rootFolderId, "Nested docs");
        uploadFile(token, nestedFolderId, "nested.txt", "nested content");

        MvcResult linkResult = mockMvc.perform(post("/api/public-links/folders/{folderId}", rootFolderId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();

        String publicToken = objectMapper.readTree(linkResult.getResponse().getContentAsString())
                .path("data")
                .path("token")
                .asText();

        mockMvc.perform(get("/api/public/{token}", publicToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folders[0].id").value(nestedFolderId))
                .andExpect(jsonPath("$.data.folders[0].name").value("Nested docs"));

        mockMvc.perform(get("/api/public/{token}/folders/{folderId}", publicToken, nestedFolderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.folderId").value(nestedFolderId))
                .andExpect(jsonPath("$.data.files.length()").value(1))
                .andExpect(jsonPath("$.data.files[0].name").value("nested.txt"));

        MvcResult zipResult = mockMvc.perform(get("/api/public/{token}/folders/{folderId}/download", publicToken, nestedFolderId))
                .andExpect(status().isOk())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(zipContainsEntry(zipResult.getResponse().getContentAsByteArray(), "Nested docs/nested.txt")).isTrue();
    }

    private boolean zipContainsEntry(byte[] bytes, String entryName) throws Exception {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            var entry = zip.getNextEntry();
            while (entry != null) {
                if (entryName.equals(entry.getName())) {
                    return true;
                }
                entry = zip.getNextEntry();
            }
            return false;
        }
    }

    private String registerAndGetToken() throws Exception {
        return registerAndGetToken("public_link_user");
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
                                  "displayName":"Public Link User"
                                }
                                """.formatted(prefix, suffix, prefix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private long uploadFile(String token) throws Exception {
        return uploadFile(token, null, "public.txt", "public content");
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

    private long createFolder(String token, String name) throws Exception {
        return createFolder(token, null, name);
    }

    private long createFolder(String token, Long parentId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/folders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "parentId": %s
                                }
                                """.formatted(name, parentId == null ? "null" : parentId)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }
}

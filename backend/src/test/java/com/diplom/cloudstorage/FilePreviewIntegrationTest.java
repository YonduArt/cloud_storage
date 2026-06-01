package com.diplom.cloudstorage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class FilePreviewIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldReturnTextPreviewMetadataAndInlineContent() throws Exception {
        String token = registerAndGetToken();
        long fileId = uploadFile(token, "preview.txt", MediaType.TEXT_PLAIN_VALUE, "Hello preview".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/files/{id}/preview", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewKind").value("text"))
                .andExpect(jsonPath("$.data.textSnippet").value("Hello preview"))
                .andExpect(jsonPath("$.data.contentUrl").value("/api/files/" + fileId + "/preview/content"));

        mockMvc.perform(get("/api/files/{id}/preview/content", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.TEXT_PLAIN_VALUE))
                .andExpect(content().string("Hello preview"));
    }

    @Test
    void shouldResolvePdfAndBinaryPreviewKinds() throws Exception {
        String token = registerAndGetToken();
        long pdfId = uploadFile(token, "document.pdf", MediaType.APPLICATION_PDF_VALUE, "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        long binaryId = uploadFile(token, "archive.bin", MediaType.APPLICATION_OCTET_STREAM_VALUE, new byte[]{1, 2, 3, 4});

        mockMvc.perform(get("/api/files/{id}/preview", pdfId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewKind").value("pdf"))
                .andExpect(jsonPath("$.data.textSnippet").doesNotExist())
                .andExpect(jsonPath("$.data.downloadable").value(true));

        mockMvc.perform(get("/api/files/{id}/preview", binaryId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.previewKind").value("binary"))
                .andExpect(jsonPath("$.data.textSnippet").doesNotExist())
                .andExpect(jsonPath("$.data.downloadable").value(true));
    }

    @Test
    void shouldDenyPreviewForAnotherUsersFile() throws Exception {
        String ownerToken = registerAndGetToken();
        String anotherToken = registerAndGetToken();
        long fileId = uploadFile(ownerToken, "private.txt", MediaType.TEXT_PLAIN_VALUE, "secret".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(get("/api/files/{id}/preview", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false));
    }

    private String registerAndGetToken() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"preview_user_%s",
                                  "email":"preview-user-%s@example.com",
                                  "password":"123456",
                                  "displayName":"Preview User"
                                }
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private long uploadFile(String token, String filename, String contentType, byte[] bytes) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                contentType,
                bytes
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

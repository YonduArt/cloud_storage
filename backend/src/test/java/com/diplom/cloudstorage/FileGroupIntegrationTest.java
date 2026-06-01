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
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.imageio.ImageIO;
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
class FileGroupIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldClassifyUploadedFilesAndListThemByGroup() throws Exception {
        String token = registerAndGetToken();

        uploadFile(token, "mountains.jpg", MediaType.IMAGE_JPEG_VALUE, "jpg");
        uploadFile(token, "notes.txt", MediaType.TEXT_PLAIN_VALUE, "hello");

        mockMvc.perform(get("/api/files/groups/photo")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("mountains.jpg"))
                .andExpect(jsonPath("$.data[0].extension").value("jpg"))
                .andExpect(jsonPath("$.data[0].fileGroup").value("photo"))
                .andExpect(jsonPath("$.data[0].hasThumbnail").value(true));

        mockMvc.perform(get("/api/files/groups/document")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].name").value("notes.txt"))
                .andExpect(jsonPath("$.data[0].extension").value("txt"))
                .andExpect(jsonPath("$.data[0].fileGroup").value("document"));
    }

    @Test
    void shouldReturnGeneratedThumbnailForUploadedImage() throws Exception {
        String token = registerAndGetToken();
        long fileId = uploadImage(token, "photo.png");

        mockMvc.perform(get("/api/files/{id}/thumbnail", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_JPEG_VALUE));
    }

    @Test
    void shouldFallbackToOriginalImageWhenGeneratedThumbnailIsUnavailable() throws Exception {
        String token = registerAndGetToken();
        long fileId = uploadSvg(token);

        mockMvc.perform(get("/api/files/{id}/thumbnail", fileId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/svg+xml"))
                .andExpect(content().string("""
                        <svg xmlns="http://www.w3.org/2000/svg" width="24" height="18"><rect width="24" height="18" fill="green"/></svg>
                        """.trim()));
    }

    private String registerAndGetToken() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username":"file_group_user_%s",
                                  "email":"file-group-user-%s@example.com",
                                  "password":"123456",
                                  "displayName":"File Group User"
                                }
                                """.formatted(suffix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private void uploadFile(String token, String filename, String contentType, String content) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                contentType,
                filename.endsWith(".jpg") ? imageBytes("jpg") : content.getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    private long uploadImage(String token, String filename) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                filename,
                MediaType.IMAGE_PNG_VALUE,
                imageBytes("png")
        );

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hasThumbnail").value(true))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private long uploadSvg(String token) throws Exception {
        String svg = """
                <svg xmlns="http://www.w3.org/2000/svg" width="24" height="18"><rect width="24" height="18" fill="green"/></svg>
                """.trim();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "vector.svg",
                "image/svg+xml",
                svg.getBytes(StandardCharsets.UTF_8)
        );

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fileGroup").value("photo"))
                .andExpect(jsonPath("$.data.hasThumbnail").value(false))
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("id").asLong();
    }

    private byte[] imageBytes(String format) throws Exception {
        BufferedImage image = new BufferedImage(24, 18, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, (x + y) % 2 == 0 ? Color.GREEN.getRGB() : Color.BLUE.getRGB());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, format, out);
        return out.toByteArray();
    }
}

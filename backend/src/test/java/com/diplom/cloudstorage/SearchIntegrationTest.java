package com.diplom.cloudstorage;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.model.FileEmbedding;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.repository.AppUserRepository;
import com.diplom.cloudstorage.repository.FileEmbeddingRepository;
import com.diplom.cloudstorage.repository.StoredFileRepository;
import com.diplom.cloudstorage.service.AiWorkerClient;
import com.diplom.cloudstorage.service.VectorUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private AppUserRepository userRepository;

    @Autowired
    private StoredFileRepository fileRepository;

    @Autowired
    private FileEmbeddingRepository embeddingRepository;

    @MockBean
    private AiWorkerClient aiWorkerClient;

    @BeforeEach
    void setUpAiWorkerMock() {
        when(aiWorkerClient.analyzeText(anyString())).thenAnswer(invocation -> new AiWorkerClient.AnalysisResult(
                "Text document indexed locally",
                invocation.getArgument(0),
                null,
                List.of(1.0, 0.0),
                null,
                "fallback-java-384"
        ));
        when(aiWorkerClient.embedTextQueryResult(anyString()))
                .thenReturn(new AiWorkerClient.EmbeddingResult(List.of(1.0, 0.0), "fallback-java-384"));
        when(aiWorkerClient.embedImageQueryResult(anyString()))
                .thenReturn(new AiWorkerClient.EmbeddingResult(List.of(), "fallback-java-512"));
    }

    @Test
    void shouldSearchCurrentUserFilesAndFoldersByName() throws Exception {
        String token = registerAndGetToken("search");
        createFolder(token, "Project Alpha");
        uploadFile(token, "alpha-notes.txt", "alpha");
        uploadFile(token, "beta-notes.txt", "beta");

        mockMvc.perform(get("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("query", "alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[?(@.type == 'folder' && @.name == 'Project Alpha')]").exists())
                .andExpect(jsonPath("$.data[?(@.type == 'file' && @.name == 'alpha-notes.txt')]").exists());
    }

    @Test
    void shouldNotReturnAnotherUsersSearchResults() throws Exception {
        String ownerToken = registerAndGetToken("search_owner");
        String anotherToken = registerAndGetToken("search_another");
        uploadFile(ownerToken, "private-alpha.txt", "secret");

        mockMvc.perform(get("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + anotherToken)
                        .param("query", "private-alpha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void shouldSearchFileByIndexedTextContent() throws Exception {
        String token = registerAndGetToken("indexed_search");
        uploadFile(token, "notes.txt", "В этом документе написано про астрофизику и телескопы.");

        JsonNode result = waitForSearchResult(token, "астрофизику");

        org.assertj.core.api.Assertions.assertThat(result.path("data").size()).isGreaterThanOrEqualTo(1);
        org.assertj.core.api.Assertions.assertThat(result.path("data").toString()).contains("notes.txt");
    }

    @Test
    void shouldReturnOnlyStrongNonFallbackSemanticMatches() throws Exception {
        String prefix = "semantic_filter";
        String token = registerAndGetToken(prefix);
        AppUser owner = userRepository.findAll().stream()
                .filter(user -> user.getUsername().startsWith(prefix + "_"))
                .findFirst()
                .orElseThrow();
        StoredFile galaxyFile = saveIndexedFile(owner, "galaxy-notes.txt", "intfloat/multilingual-e5-small", List.of(1.0, 0.0));
        saveIndexedFile(owner, "legacy-index.txt", "fallback-hash-384", List.of(0.0, 1.0));

        when(aiWorkerClient.embedTextQueryResult("космос"))
                .thenReturn(new AiWorkerClient.EmbeddingResult(List.of(1.0, 0.0), "intfloat/multilingual-e5-small"));
        when(aiWorkerClient.embedTextQueryResult("рецепт борща"))
                .thenReturn(new AiWorkerClient.EmbeddingResult(List.of(0.0, 1.0), "intfloat/multilingual-e5-small"));

        MvcResult cosmosResponse = mockMvc.perform(get("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("query", "космос"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id == %d && @.matchType == 'SEMANTIC')]".formatted(galaxyFile.getId())).exists())
                .andReturn();
        JsonNode cosmosRoot = objectMapper.readTree(cosmosResponse.getResponse().getContentAsString());
        org.assertj.core.api.Assertions.assertThat(cosmosRoot.path("data").size()).isEqualTo(1);

        mockMvc.perform(get("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("query", "рецепт борща"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void shouldTranslateKnownRussianImageQueriesBeforeClipSearch() throws Exception {
        String prefix = "image_translate";
        String token = registerAndGetToken(prefix);
        AppUser owner = userRepository.findAll().stream()
                .filter(user -> user.getUsername().startsWith(prefix + "_"))
                .findFirst()
                .orElseThrow();
        StoredFile catFile = saveIndexedFile(owner, "cat-photo.jpg", "photo", "IMAGE", "ViT-B-32", List.of(1.0, 0.0));

        when(aiWorkerClient.embedImageQueryResult("a photo of a cat"))
                .thenReturn(new AiWorkerClient.EmbeddingResult(List.of(1.0, 0.0), "ViT-B-32"));

        mockMvc.perform(get("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("query", "кот"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(catFile.getId()))
                .andExpect(jsonPath("$.data[0].matchType").value("IMAGE"))
                .andExpect(jsonPath("$.data[0].contentType").value(MediaType.IMAGE_JPEG_VALUE))
                .andExpect(jsonPath("$.data[0].fileGroup").value("photo"));

        mockMvc.perform(get("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("query", "фото кота"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(catFile.getId()))
                .andExpect(jsonPath("$.data[0].matchType").value("IMAGE"))
                .andExpect(jsonPath("$.data[0].contentType").value(MediaType.IMAGE_JPEG_VALUE))
                .andExpect(jsonPath("$.data[0].fileGroup").value("photo"));

        mockMvc.perform(get("/api/search")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .param("query", "привет"))
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
                                  "displayName":"Search User"
                                }
                                """.formatted(prefix, suffix, prefix, suffix)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("token").asText();
    }

    private void createFolder(String token, String name) throws Exception {
        mockMvc.perform(post("/api/folders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "parentId": null
                                }
                                """.formatted(name)))
                .andExpect(status().isOk());
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

    private StoredFile saveIndexedFile(AppUser owner, String filename, String modelName, List<Double> vector) {
        return saveIndexedFile(owner, filename, "document", "TEXT", modelName, vector);
    }

    private StoredFile saveIndexedFile(AppUser owner, String filename, String fileGroup, String embeddingType, String modelName, List<Double> vector) {
        StoredFile file = new StoredFile();
        file.setOwner(owner);
        file.setName(filename);
        file.setStoredPath("test-storage/" + filename);
        file.setSizeBytes(128L);
        file.setContentType("photo".equals(fileGroup) ? MediaType.IMAGE_JPEG_VALUE : MediaType.TEXT_PLAIN_VALUE);
        file.setExtension("photo".equals(fileGroup) ? "jpg" : "txt");
        file.setFileGroup(fileGroup);
        file = fileRepository.save(file);

        FileEmbedding embedding = new FileEmbedding();
        embedding.setOwner(owner);
        embedding.setFile(file);
        embedding.setEmbeddingType(embeddingType);
        embedding.setModelName(modelName);
        embedding.setVector(VectorUtils.serialize(vector));
        embeddingRepository.save(embedding);
        return file;
    }

    private JsonNode waitForSearchResult(String token, String query) throws Exception {
        JsonNode root = objectMapper.createObjectNode();
        for (int i = 0; i < 20; i++) {
            MvcResult result = mockMvc.perform(get("/api/search")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                            .param("query", query))
                    .andExpect(status().isOk())
                    .andReturn();
            root = objectMapper.readTree(result.getResponse().getContentAsString());
            if (root.path("data").size() > 0) {
                return root;
            }
            Thread.sleep(150);
        }
        return root;
    }
}

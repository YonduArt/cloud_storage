package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.model.StoredFile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiWorkerClient {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final boolean enabled;

    public AiWorkerClient(ObjectMapper objectMapper,
                          @Value("${app.ai-worker.url:http://localhost:8090}") String baseUrl,
                          @Value("${app.ai-worker.enabled:true}") boolean enabled) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.enabled = enabled;
    }

    public AnalysisResult analyzeText(String text) {
        if (!enabled) {
            return AnalysisResult.text(text, fallbackVector(text, 384), "fallback-java-384");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/analyze/text"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(java.util.Map.of("text", text))))
                    .build();
            JsonNode root = send(request);
            return new AnalysisResult(
                    root.path("description").asText("Text document indexed locally"),
                    root.path("extractedText").asText(text),
                    null,
                    toVector(root.path("textEmbedding")),
                    null,
                    root.path("textModel").asText("local-text-model")
            );
        } catch (Exception e) {
            return AnalysisResult.text(text, fallbackVector(text, 384), "fallback-java-384");
        }
    }

    public AnalysisResult analyzeImage(StoredFile file) {
        if (!enabled) {
            return AnalysisResult.image("", fallbackVector(file.getName(), 512), "fallback-java-512");
        }
        try {
            String boundary = "----CloudStorageBoundary" + System.nanoTime();
            byte[] body = multipartBody(boundary, Path.of(file.getStoredPath()), file.getName(), file.getContentType());
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/analyze/image"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            JsonNode root = send(request);
            return new AnalysisResult(
                    root.path("description").asText("Image indexed locally"),
                    root.path("ocrText").asText(""),
                    toVector(root.path("imageEmbedding")),
                    toVector(root.path("textEmbedding")),
                    root.path("imageModel").asText("local-image-model"),
                    root.path("textModel").asText("")
            );
        } catch (Exception e) {
            return AnalysisResult.image("", fallbackVector(file.getName(), 512), "fallback-java-512");
        }
    }

    public List<Double> embedTextQuery(String query) {
        return embedTextQueryResult(query).embedding();
    }

    public EmbeddingResult embedTextQueryResult(String query) {
        if (!enabled) {
            return new EmbeddingResult(fallbackVector(query, 384), "fallback-java-384");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embed/query/text"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(java.util.Map.of("query", query))))
                    .build();
            JsonNode root = send(request);
            return new EmbeddingResult(
                    toVector(root.path("embedding")),
                    root.path("model").asText("local-text-model")
            );
        } catch (Exception e) {
            return new EmbeddingResult(fallbackVector(query, 384), "fallback-java-384");
        }
    }

    public List<Double> embedImageQuery(String query) {
        return embedImageQueryResult(query).embedding();
    }

    public EmbeddingResult embedImageQueryResult(String query) {
        if (!enabled) {
            return new EmbeddingResult(fallbackVector(query, 512), "fallback-java-512");
        }
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/embed/query/image"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(java.util.Map.of("query", query))))
                    .build();
            JsonNode root = send(request);
            return new EmbeddingResult(
                    toVector(root.path("embedding")),
                    root.path("model").asText("local-image-model")
            );
        } catch (Exception e) {
            return new EmbeddingResult(fallbackVector(query, 512), "fallback-java-512");
        }
    }

    private JsonNode send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("AI worker returned " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private byte[] multipartBody(String boundary, Path path, String fileName, String contentType) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName.replace("\"", "") + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(Files.readAllBytes(path));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private List<Double> toVector(JsonNode node) {
        List<Double> result = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        node.forEach(value -> result.add(value.asDouble()));
        return result;
    }

    private List<Double> fallbackVector(String text, int size) {
        double[] vector = new double[size];
        String[] tokens = (text == null || text.isBlank() ? "empty" : text.toLowerCase()).split("[^\\p{L}\\p{N}]+");
        for (String token : tokens) {
            if (token.isBlank()) {
                continue;
            }
            int hash = token.hashCode();
            int index = Math.floorMod(hash, size);
            vector[index] += 1.0;
        }
        double norm = 0.0;
        for (double value : vector) {
            norm += value * value;
        }
        norm = Math.sqrt(norm);
        List<Double> result = new ArrayList<>(size);
        for (double value : vector) {
            result.add(norm == 0 ? 0.0 : value / norm);
        }
        return result;
    }

    public record AnalysisResult(
            String description,
            String extractedText,
            List<Double> imageEmbedding,
            List<Double> textEmbedding,
            String imageModel,
            String textModel
    ) {
        static AnalysisResult text(String text, List<Double> embedding, String model) {
            return new AnalysisResult("Text document indexed locally", text, null, embedding, null, model);
        }

        static AnalysisResult image(String extractedText, List<Double> embedding, String model) {
            return new AnalysisResult("Image indexed locally", extractedText, embedding, null, model, null);
        }
    }

    public record EmbeddingResult(
            List<Double> embedding,
            String model
    ) {
    }
}

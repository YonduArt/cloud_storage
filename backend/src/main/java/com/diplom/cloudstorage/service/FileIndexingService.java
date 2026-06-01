package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.model.FileEmbedding;
import com.diplom.cloudstorage.model.FileSearchIndex;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.repository.FileEmbeddingRepository;
import com.diplom.cloudstorage.repository.FileSearchIndexRepository;
import com.diplom.cloudstorage.repository.StoredFileRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FileIndexingService {

    private static final int TEXT_LIMIT = 120_000;

    private final StoredFileRepository fileRepository;
    private final FileSearchIndexRepository indexRepository;
    private final FileEmbeddingRepository embeddingRepository;
    private final AiWorkerClient aiWorkerClient;

    public FileIndexingService(StoredFileRepository fileRepository,
                               FileSearchIndexRepository indexRepository,
                               FileEmbeddingRepository embeddingRepository,
                               AiWorkerClient aiWorkerClient) {
        this.fileRepository = fileRepository;
        this.indexRepository = indexRepository;
        this.embeddingRepository = embeddingRepository;
        this.aiWorkerClient = aiWorkerClient;
    }

    @Transactional
    public void indexFile(Long fileId) {
        StoredFile file = fileRepository.findById(fileId).orElse(null);
        if (file == null || file.getDeletedAt() != null) {
            return;
        }
        FileSearchIndex index = indexRepository.findByFileId(fileId).orElseGet(() -> createIndex(file));
        index.setStatus("INDEXING");
        index.setErrorMessage(null);
        indexRepository.save(index);
        embeddingRepository.deleteByFileId(fileId);

        try {
            if ("photo".equals(file.getFileGroup())) {
                indexImage(file, index);
            } else {
                indexTextLikeFile(file, index);
            }
            index.setStatus("READY");
            index.setIndexedAt(Instant.now());
        } catch (Exception e) {
            index.setStatus("FAILED");
            index.setErrorMessage(e.getMessage() == null ? "Indexing failed" : e.getMessage());
        }
        indexRepository.save(index);
    }

    private FileSearchIndex createIndex(StoredFile file) {
        FileSearchIndex index = new FileSearchIndex();
        index.setFile(file);
        index.setOwner(file.getOwner());
        index.setContentType(file.getFileGroup() == null ? "other" : file.getFileGroup());
        return index;
    }

    private void indexImage(StoredFile file, FileSearchIndex index) {
        AiWorkerClient.AnalysisResult result = aiWorkerClient.analyzeImage(file);
        index.setDescription(result.description());
        index.setExtractedText(limit(result.extractedText()));
        if (result.imageEmbedding() != null && !result.imageEmbedding().isEmpty()) {
            saveEmbedding(file, "IMAGE", result.imageModel(), result.imageEmbedding());
        }
        if (result.textEmbedding() != null && !result.textEmbedding().isEmpty()) {
            saveEmbedding(file, "TEXT", result.textModel(), result.textEmbedding());
        }
    }

    private void indexTextLikeFile(StoredFile file, FileSearchIndex index) {
        String extractedText = extractText(file);
        AiWorkerClient.AnalysisResult result = aiWorkerClient.analyzeText(extractedText);
        index.setDescription(result.description());
        index.setExtractedText(limit(result.extractedText()));
        if (result.textEmbedding() != null && !result.textEmbedding().isEmpty()) {
            saveEmbedding(file, "TEXT", result.textModel(), result.textEmbedding());
        }
    }

    private String extractText(StoredFile file) {
        String extension = file.getExtension() == null ? "" : file.getExtension().toLowerCase(Locale.ROOT);
        Path path = Path.of(file.getStoredPath());
        try {
            if ("pdf".equals(extension)) {
                try (var document = Loader.loadPDF(path.toFile())) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setEndPage(Math.min(20, document.getNumberOfPages()));
                    return limit(stripper.getText(document));
                }
            }
            if ("docx".equals(extension)) {
                try (var in = Files.newInputStream(path); var document = new XWPFDocument(in)) {
                    return limit(String.join("\n", document.getParagraphs().stream().map(p -> p.getText()).toList()));
                }
            }
            if (isPlainText(extension, file.getContentType())) {
                byte[] bytes = Files.readAllBytes(path);
                int limit = Math.min(bytes.length, TEXT_LIMIT);
                return new String(bytes, 0, limit, StandardCharsets.UTF_8);
            }
        } catch (IOException ignored) {
            return file.getName();
        }
        return file.getName() + " " + (file.getFileGroup() == null ? "" : file.getFileGroup());
    }

    private boolean isPlainText(String extension, String contentType) {
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        return type.startsWith("text/")
                || List.of("txt", "md", "csv", "json", "xml", "java", "js", "ts", "tsx", "jsx", "css", "html", "yml", "yaml", "log").contains(extension);
    }

    private void saveEmbedding(StoredFile file, String type, String model, List<Double> vector) {
        FileEmbedding embedding = new FileEmbedding();
        embedding.setFile(file);
        embedding.setOwner(file.getOwner());
        embedding.setEmbeddingType(type);
        embedding.setModelName(model == null || model.isBlank() ? "local-model" : model);
        embedding.setVector(VectorUtils.serialize(vector));
        embeddingRepository.save(embedding);
    }

    private String limit(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= TEXT_LIMIT ? value : value.substring(0, TEXT_LIMIT);
    }
}

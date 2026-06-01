package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.dto.FileIndexStatusResponse;
import com.diplom.cloudstorage.dto.FileIndexSummaryResponse;
import com.diplom.cloudstorage.dto.FileResponse;
import com.diplom.cloudstorage.dto.FolderCreateRequest;
import com.diplom.cloudstorage.dto.FolderResponse;
import com.diplom.cloudstorage.dto.MoveRequest;
import com.diplom.cloudstorage.dto.PreviewResponse;
import com.diplom.cloudstorage.dto.RenameRequest;
import com.diplom.cloudstorage.dto.SearchItemResponse;
import com.diplom.cloudstorage.dto.StorageStatsResponse;
import com.diplom.cloudstorage.dto.StorageViewResponse;
import com.diplom.cloudstorage.exception.ApiException;
import com.diplom.cloudstorage.mapper.FileMapper;
import com.diplom.cloudstorage.mapper.FolderMapper;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.model.Folder;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.repository.FolderRepository;
import com.diplom.cloudstorage.repository.FileEmbeddingRepository;
import com.diplom.cloudstorage.repository.FileSearchIndexRepository;
import com.diplom.cloudstorage.repository.PublicLinkRepository;
import com.diplom.cloudstorage.repository.StoredFileRepository;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);
    private static final double TEXT_SEMANTIC_THRESHOLD = 0.82;
    private static final double IMAGE_SEMANTIC_THRESHOLD = 0.245;
    private static final double IMAGE_TOP_MARGIN = 0.035;
    private static final Map<String, String> IMAGE_QUERY_TRANSLATIONS = imageQueryTranslations();
    private static final Map<String, String> IMAGE_QUERY_PROMPTS = imageQueryPrompts();
    private static final Set<String> IMAGE_QUERY_STOP_WORDS = imageQueryStopWords();

    private final FolderRepository folderRepository;
    private final StoredFileRepository fileRepository;
    private final FileSearchIndexRepository indexRepository;
    private final FileEmbeddingRepository embeddingRepository;
    private final PublicLinkRepository publicLinkRepository;
    private final UserService userService;
    private final FileStorageService fileStorageService;
    private final FileEventService eventService;
    private final ApplicationEventPublisher eventPublisher;
    private final AiWorkerClient aiWorkerClient;
    private final FolderMapper folderMapper;
    private final FileMapper fileMapper;
    private final long quotaBytes;

    public StorageService(FolderRepository folderRepository,
                          StoredFileRepository fileRepository,
                          FileSearchIndexRepository indexRepository,
                          FileEmbeddingRepository embeddingRepository,
                          PublicLinkRepository publicLinkRepository,
                          UserService userService,
                          FileStorageService fileStorageService,
                          FileEventService eventService,
                          ApplicationEventPublisher eventPublisher,
                          AiWorkerClient aiWorkerClient,
                          FolderMapper folderMapper,
                          FileMapper fileMapper,
                          @Value("${app.storage.quota-bytes:1073741824}") long quotaBytes) {
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        this.indexRepository = indexRepository;
        this.embeddingRepository = embeddingRepository;
        this.publicLinkRepository = publicLinkRepository;
        this.userService = userService;
        this.fileStorageService = fileStorageService;
        this.eventService = eventService;
        this.eventPublisher = eventPublisher;
        this.aiWorkerClient = aiWorkerClient;
        this.folderMapper = folderMapper;
        this.fileMapper = fileMapper;
        this.quotaBytes = quotaBytes;
    }

    private FileResponse toFileResponse(StoredFile file) {
        FileResponse response = fileMapper.toResponse(file);
        return new FileResponse(
                response.id(),
                response.name(),
                response.contentType(),
                response.extension(),
                response.fileGroup(),
                response.hasThumbnail(),
                response.sizeBytes(),
                response.uploadedAt(),
                response.folderId(),
                response.deletedAt(),
                response.purgeAfter(),
                response.lastAccessedAt(),
                response.favorite(),
                indexSummary(file)
        );
    }

    private FileIndexSummaryResponse indexSummary(StoredFile file) {
        var index = indexRepository.findByFileId(file.getId()).orElse(null);
        List<FileIndexSummaryResponse.EmbeddingInfo> embeddings = embeddingRepository.findByFileId(file.getId()).stream()
                .map(embedding -> new FileIndexSummaryResponse.EmbeddingInfo(
                        embedding.getEmbeddingType(),
                        embedding.getModelName(),
                        vectorDimensions(embedding.getVector()),
                        embedding.getCreatedAt()
                ))
                .toList();
        return new FileIndexSummaryResponse(
                index == null ? "PENDING" : index.getStatus(),
                index == null ? null : index.getCreatedAt(),
                index == null ? null : index.getIndexedAt(),
                index == null ? null : index.getErrorMessage(),
                embeddings
        );
    }

    private int vectorDimensions(String vector) {
        try {
            return VectorUtils.parse(vector).size();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    @Transactional(readOnly = true)
    public FolderResponse getRootFolder() {
        AppUser owner = userService.requireCurrentUser();
        return new FolderResponse(null, "root", null, false);
    }

    @Transactional(readOnly = true)
    public FolderResponse getFolder(Long id) {
        AppUser owner = userService.requireCurrentUser();
        return folderMapper.toResponse(requireFolder(id, owner.getId()));
    }

    @Transactional(readOnly = true)
    public StorageViewResponse getFolderContent(Long folderId, String sort, String order) {
        AppUser owner = userService.requireCurrentUser();
        Long userId = owner.getId();
        List<Folder> folders = folderId == null
                ? folderRepository.findByOwnerIdAndParentIsNull(userId)
                : folderRepository.findByOwnerIdAndParentId(userId, folderId);
        List<StoredFile> files = folderId == null
                ? fileRepository.findByOwnerIdAndFolderIsNullAndDeletedAtIsNull(userId)
                : fileRepository.findByOwnerIdAndFolderIdAndDeletedAtIsNull(userId, folderId);

        boolean asc = !"desc".equalsIgnoreCase(order);
        Comparator<Folder> folderComparator = switch (sort == null ? "name" : sort.toLowerCase()) {
            case "createdat", "created", "date" -> Comparator.comparing(Folder::getCreatedAt);
            default -> Comparator.comparing(Folder::getName, String.CASE_INSENSITIVE_ORDER);
        };
        Comparator<StoredFile> fileComparator = switch (sort == null ? "name" : sort.toLowerCase()) {
            case "size" -> Comparator.comparing(StoredFile::getSizeBytes);
            case "createdat", "created", "date" -> Comparator.comparing(StoredFile::getUploadedAt);
            default -> Comparator.comparing(StoredFile::getName, String.CASE_INSENSITIVE_ORDER);
        };
        if (!asc) {
            folderComparator = folderComparator.reversed();
            fileComparator = fileComparator.reversed();
        }
        folders.sort(folderComparator);
        files.sort(fileComparator);

        return new StorageViewResponse(
                folders.stream().map(folderMapper::toResponse).toList(),
                files.stream().map(this::toFileResponse).toList()
        );
    }

    @Transactional
    public FolderResponse createFolder(FolderCreateRequest request) {
        AppUser owner = userService.requireCurrentUser();
        Folder folder = new Folder();
        folder.setName(request.name().trim());
        folder.setOwner(owner);
        if (request.parentId() != null) {
            folder.setParent(requireFolder(request.parentId(), owner.getId()));
        }
        ensureFolderUnique(owner.getId(), request.parentId(), folder.getName());
        Folder saved = folderRepository.save(folder);
        eventService.log(owner, "FOLDER_CREATED", "folder", saved.getId(), saved.getName(), "Папка создана");
        return folderMapper.toResponse(saved);
    }

    @Transactional
    public FolderResponse renameFolder(Long folderId, RenameRequest request) {
        AppUser owner = userService.requireCurrentUser();
        Folder folder = requireFolder(folderId, owner.getId());
        String newName = request.name().trim();
        if (!folder.getName().equalsIgnoreCase(newName)) {
            ensureFolderUnique(owner.getId(), folder.getParent() == null ? null : folder.getParent().getId(), newName);
        }
        String oldName = folder.getName();
        folder.setName(newName);
        Folder saved = folderRepository.save(folder);
        eventService.log(owner, "FOLDER_RENAMED", "folder", saved.getId(), saved.getName(), oldName + " -> " + saved.getName());
        return folderMapper.toResponse(saved);
    }

    @Transactional
    public FolderResponse moveFolder(Long folderId, MoveRequest request) {
        AppUser owner = userService.requireCurrentUser();
        Folder folder = requireFolder(folderId, owner.getId());
        Folder target = request.targetFolderId() == null ? null : requireFolder(request.targetFolderId(), owner.getId());
        ensureFolderMoveTargetIsSafe(folder, target);
        ensureFolderUnique(owner.getId(), target == null ? null : target.getId(), folder.getName());
        folder.setParent(target);
        Folder saved = folderRepository.save(folder);
        eventService.log(owner, "FOLDER_MOVED", "folder", saved.getId(), saved.getName(), "Новая папка: " + targetName(target));
        return folderMapper.toResponse(saved);
    }

    @Transactional
    public void deleteFolder(Long folderId) {
        AppUser owner = userService.requireCurrentUser();
        Long userId = owner.getId();
        Folder folder = requireFolder(folderId, userId);
        if (!folderRepository.findByOwnerIdAndParentId(userId, folderId).isEmpty()
                || !fileRepository.findByOwnerIdAndFolderIdAndDeletedAtIsNull(userId, folderId).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Folder is not empty");
        }
        String folderName = folder.getName();
        folderRepository.delete(folder);
        eventService.log(owner, "FOLDER_DELETED", "folder", folderId, folderName, "Папка удалена");
    }

    @Transactional
    public FileResponse upload(Long folderId, MultipartFile multipartFile) {
        AppUser owner = userService.requireCurrentUser();
        Long userId = owner.getId();
        Folder folder = folderId == null ? null : requireFolder(folderId, userId);
        String name = multipartFile.getOriginalFilename() == null ? "file.bin" : multipartFile.getOriginalFilename();
        ensureQuotaAvailable(owner, multipartFile.getSize());
        return uploadToFolder(owner, folder, folderId, multipartFile, name);
    }

    private FileResponse uploadToFolder(AppUser owner, Folder folder, Long folderId, MultipartFile multipartFile, String name) {
        Long userId = owner.getId();
        ensureFileUnique(userId, folderId, name);
        String storedPath = fileStorageService.save(userId, multipartFile);

        StoredFile file = new StoredFile();
        file.setOwner(owner);
        file.setFolder(folder);
        file.setStoredPath(storedPath);
        file.setName(name);
        file.setContentType(multipartFile.getContentType() == null ? "application/octet-stream" : multipartFile.getContentType());
        file.setExtension(extractExtension(name));
        file.setFileGroup(resolveFileGroup(file.getExtension()));
        file.setThumbnailPath(createThumbnailPathIfSupported(userId, storedPath, file.getFileGroup()));
        file.setSizeBytes(multipartFile.getSize());
        owner.setUsedSpace(owner.getUsedSpace() + multipartFile.getSize());
        StoredFile saved = fileRepository.save(file);
        eventService.log(owner, "FILE_UPLOADED", "file", saved.getId(), saved.getName(), "Размер: " + saved.getSizeBytes() + " байт");
        eventPublisher.publishEvent(new FileIndexRequestedEvent(saved.getId()));
        return toFileResponse(saved);
    }

    @Transactional
    public List<FileResponse> uploadFolder(Long parentId, List<MultipartFile> files, List<String> relativePaths) {
        if (files == null || files.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No files to upload");
        }
        if (relativePaths == null || relativePaths.size() != files.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "relativePaths count must match files count");
        }
        AppUser owner = userService.requireCurrentUser();
        Folder baseFolder = parentId == null ? null : requireFolder(parentId, owner.getId());
        long totalUploadSize = files.stream().mapToLong(MultipartFile::getSize).sum();
        ensureQuotaAvailable(owner, totalUploadSize);
        List<FileResponse> uploaded = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            List<String> pathParts = parseRelativePath(relativePaths.get(i));
            String fileName = pathParts.get(pathParts.size() - 1);
            Folder targetFolder = ensureFolderPath(owner, baseFolder, pathParts.subList(0, pathParts.size() - 1));
            Long targetFolderId = targetFolder == null ? null : targetFolder.getId();
            uploaded.add(uploadToFolder(owner, targetFolder, targetFolderId, files.get(i), fileName));
        }
        return uploaded;
    }

    @Transactional(readOnly = true)
    public StoredFile getFileById(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        return requireActiveFile(fileId, owner.getId());
    }

    @Transactional(readOnly = true)
    public PreviewResponse getFilePreview(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        String previewKind = resolvePreviewKind(file);
        return new PreviewResponse(
                file.getId(),
                file.getName(),
                file.getContentType(),
                previewKind,
                file.getSizeBytes(),
                "text".equals(previewKind) ? readTextSnippet(file.getStoredPath()) : null,
                "/api/files/" + file.getId() + "/preview/content",
                true
        );
    }

    @Transactional(readOnly = true)
    public StoredFile getFileThumbnail(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        if ((file.getThumbnailPath() == null || file.getThumbnailPath().isBlank()) && !"photo".equals(file.getFileGroup())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Thumbnail not found");
        }
        return file;
    }

    @Transactional
    public FileResponse getFileMetadata(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        file.setLastAccessedAt(Instant.now());
        return toFileResponse(fileRepository.save(file));
    }

    @Transactional(readOnly = true)
    public FileIndexStatusResponse getFileIndexStatus(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        var index = indexRepository.findByFileId(file.getId()).orElse(null);
        List<FileIndexStatusResponse.EmbeddingInfo> embeddings = embeddingRepository.findByFileId(file.getId()).stream()
                .map(embedding -> new FileIndexStatusResponse.EmbeddingInfo(
                        embedding.getEmbeddingType(),
                        embedding.getModelName(),
                        vectorDimensions(embedding.getVector()),
                        embedding.getCreatedAt()
                ))
                .toList();
        return new FileIndexStatusResponse(
                file.getId(),
                index == null ? "PENDING" : index.getStatus(),
                index == null ? file.getFileGroup() : index.getContentType(),
                index == null ? null : index.getIndexedAt(),
                index == null ? null : index.getErrorMessage(),
                embeddings
        );
    }

    @Transactional
    public FileResponse renameFile(Long fileId, RenameRequest request) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        Long folderId = file.getFolder() == null ? null : file.getFolder().getId();
        String newName = request.name().trim();
        if (!file.getName().equalsIgnoreCase(newName)) {
            ensureFileUnique(owner.getId(), folderId, newName);
        }
        String oldName = file.getName();
        file.setName(newName);
        file.setExtension(extractExtension(newName));
        file.setFileGroup(resolveFileGroup(file.getExtension()));
        StoredFile saved = fileRepository.save(file);
        eventService.log(owner, "FILE_RENAMED", "file", saved.getId(), saved.getName(), oldName + " -> " + saved.getName());
        return toFileResponse(saved);
    }

    @Transactional
    public FileResponse moveFile(Long fileId, MoveRequest request) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        Folder target = request.targetFolderId() == null ? null : requireFolder(request.targetFolderId(), owner.getId());
        ensureFileUnique(owner.getId(), target == null ? null : target.getId(), file.getName());
        file.setFolder(target);
        StoredFile saved = fileRepository.save(file);
        eventService.log(owner, "FILE_MOVED", "file", saved.getId(), saved.getName(), "Новая папка: " + targetName(target));
        return toFileResponse(saved);
    }

    @Transactional
    public void deleteFile(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireAnyFile(fileId, owner.getId());
        String fileName = file.getName();
        deleteStoredFile(file);
        eventService.log(owner, "FILE_DELETED", "file", fileId, fileName, "Файл удален окончательно");
    }

    @Transactional
    public void deleteFiles(List<Long> fileIds) {
        AppUser owner = userService.requireCurrentUser();
        normalizeBulkIds(fileIds).forEach(fileId -> {
            StoredFile file = requireAnyFile(fileId, owner.getId());
            if (file.getDeletedAt() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "File is not in trash");
            }
            String fileName = file.getName();
            deleteStoredFile(file);
            eventService.log(owner, "FILE_DELETED", "file", fileId, fileName, "Файл удален из корзины");
        });
    }

    @Transactional
    public FileResponse moveToTrash(Long fileId, int keepDays) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        Instant now = Instant.now();
        int effectiveKeepDays = Math.max(1, keepDays);
        file.setDeletedAt(now);
        file.setPurgeAfter(now.plusSeconds(effectiveKeepDays * 24L * 60L * 60L));
        StoredFile saved = fileRepository.save(file);
        eventService.log(owner, "FILE_TRASHED", "file", saved.getId(), saved.getName(), "Срок хранения в корзине: " + effectiveKeepDays + " дн.");
        return toFileResponse(saved);
    }

    @Transactional
    public FileResponse restoreFromTrash(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireAnyFile(fileId, owner.getId());
        if (file.getDeletedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "File is not in trash");
        }
        Long folderId = file.getFolder() == null ? null : file.getFolder().getId();
        String oldName = file.getName();
        file.setName(resolveRestoredName(owner.getId(), folderId, file.getName()));
        file.setDeletedAt(null);
        file.setPurgeAfter(null);
        StoredFile saved = fileRepository.save(file);
        eventService.log(owner, "FILE_RESTORED", "file", saved.getId(), saved.getName(), oldName.equals(saved.getName()) ? "Файл восстановлен" : oldName + " -> " + saved.getName());
        return toFileResponse(saved);
    }

    @Transactional
    public List<FileResponse> restoreFilesFromTrash(List<Long> fileIds) {
        return normalizeBulkIds(fileIds).stream()
                .map(this::restoreFromTrash)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FileResponse> getTrash() {
        AppUser owner = userService.requireCurrentUser();
        return fileRepository.findByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(owner.getId())
                .stream()
                .map(this::toFileResponse)
                .toList();
    }

    @Transactional
    public int purgeExpiredTrash() {
        List<StoredFile> expiredFiles = fileRepository.findByDeletedAtIsNotNullAndPurgeAfterBefore(Instant.now());
        expiredFiles.forEach(this::deleteStoredFile);
        return expiredFiles.size();
    }

    @Transactional(readOnly = true)
    public List<FileResponse> getRecentUploaded(int limit) {
        AppUser owner = userService.requireCurrentUser();
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        return fileRepository.findTop20ByOwnerIdAndDeletedAtIsNullOrderByUploadedAtDesc(owner.getId())
                .stream()
                .limit(effectiveLimit)
                .map(this::toFileResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FileResponse> getRecentOpened(int limit) {
        AppUser owner = userService.requireCurrentUser();
        int effectiveLimit = Math.max(1, Math.min(limit, 100));
        return fileRepository.findTop20ByOwnerIdAndDeletedAtIsNullAndLastAccessedAtIsNotNullOrderByLastAccessedAtDesc(owner.getId())
                .stream()
                .limit(effectiveLimit)
                .map(this::toFileResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<FileResponse> getFilesByGroup(String group) {
        AppUser owner = userService.requireCurrentUser();
        String normalizedGroup = normalizeFileGroup(group);
        return fileRepository.findByOwnerIdAndFileGroupAndDeletedAtIsNullOrderByUploadedAtDesc(owner.getId(), normalizedGroup)
                .stream()
                .map(this::toFileResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StorageViewResponse getFavorites() {
        AppUser owner = userService.requireCurrentUser();
        List<FolderResponse> folders = folderRepository.findByOwnerIdAndFavoriteTrueOrderByCreatedAtDesc(owner.getId())
                .stream()
                .map(folderMapper::toResponse)
                .toList();
        List<FileResponse> files = fileRepository.findByOwnerIdAndFavoriteTrueAndDeletedAtIsNullOrderByUploadedAtDesc(owner.getId())
                .stream()
                .map(this::toFileResponse)
                .toList();
        return new StorageViewResponse(folders, files);
    }

    @Transactional
    public FileResponse setFileFavorite(Long fileId, boolean favorite) {
        StoredFile file = requireActiveFile(fileId, userService.requireCurrentUser().getId());
        file.setFavorite(favorite);
        fileRepository.save(file);
        eventService.log(file.getOwner(), favorite ? "FILE_FAVORITE_ADDED" : "FILE_FAVORITE_REMOVED", "file", file.getId(), file.getName(),
                favorite ? "Файл добавлен в избранное" : "Файл удален из избранного");
        return toFileResponse(file);
    }

    @Transactional
    public FolderResponse setFolderFavorite(Long folderId, boolean favorite) {
        AppUser owner = userService.requireCurrentUser();
        Folder folder = requireFolder(folderId, owner.getId());
        folder.setFavorite(favorite);
        folderRepository.save(folder);
        eventService.log(owner, favorite ? "FOLDER_FAVORITE_ADDED" : "FOLDER_FAVORITE_REMOVED", "folder", folder.getId(), folder.getName(),
                favorite ? "Папка добавлена в избранное" : "Папка удалена из избранного");
        return folderMapper.toResponse(folder);
    }

    @Transactional(readOnly = true)
    public StorageStatsResponse getStorageStats() {
        AppUser owner = userService.requireCurrentUser();
        List<StoredFile> activeFiles = fileRepository.findByOwnerIdAndDeletedAtIsNull(owner.getId());
        List<StoredFile> trashFiles = fileRepository.findByOwnerIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(owner.getId());
        long activeBytes = activeFiles.stream().mapToLong(StoredFile::getSizeBytes).sum();
        long trashBytes = trashFiles.stream().mapToLong(StoredFile::getSizeBytes).sum();
        long usedBytes = owner.getUsedSpace();
        long effectiveQuotaBytes = effectiveQuotaBytes(owner);
        long freeBytes = Math.max(0L, effectiveQuotaBytes - usedBytes);
        double usagePercent = effectiveQuotaBytes <= 0 ? 0.0 : Math.min(100.0, (usedBytes * 100.0) / effectiveQuotaBytes);

        List<StorageStatsResponse.GroupUsageResponse> groups = activeFiles.stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        file -> file.getFileGroup() == null ? "other" : file.getFileGroup(),
                        java.util.stream.Collectors.toList()
                ))
                .entrySet()
                .stream()
                .map(entry -> new StorageStatsResponse.GroupUsageResponse(
                        entry.getKey(),
                        entry.getValue().stream().mapToLong(StoredFile::getSizeBytes).sum(),
                        entry.getValue().size()
                ))
                .sorted(Comparator.comparing(StorageStatsResponse.GroupUsageResponse::bytes).reversed())
                .toList();

        List<FileResponse> largestFiles = fileRepository.findTop10ByOwnerIdAndDeletedAtIsNullOrderBySizeBytesDesc(owner.getId())
                .stream()
                .map(this::toFileResponse)
                .toList();

        return new StorageStatsResponse(
                effectiveQuotaBytes,
                usedBytes,
                freeBytes,
                usagePercent,
                activeBytes,
                trashBytes,
                activeFiles.size(),
                groups,
                largestFiles
        );
    }

    @Transactional
    public void touchAccess(Long fileId) {
        AppUser owner = userService.requireCurrentUser();
        StoredFile file = requireActiveFile(fileId, owner.getId());
        file.setLastAccessedAt(Instant.now());
        fileRepository.save(file);
    }

    @Transactional(readOnly = true)
    public List<SearchItemResponse> search(String query) {
        AppUser owner = userService.requireCurrentUser();
        Long userId = owner.getId();
        String q = query == null ? "" : query.trim();
        java.util.Map<String, SearchItemResponse> result = new java.util.LinkedHashMap<>();
        folderRepository.searchByName(userId, q).forEach(folder -> result.put(
                "folder-" + folder.getId(),
                new SearchItemResponse("folder", folder.getId(), folder.getName(),
                        folder.getParent() == null ? null : folder.getParent().getId(), null, folder.getCreatedAt(), "NAME", 1.0,
                        null, null, null, null)));
        fileRepository.searchByName(userId, q).forEach(file -> result.put(
                "file-" + file.getId(),
                searchItem(file, "NAME", 1.0)));
        String normalizedQuery = q.toLowerCase(Locale.ROOT);
        indexRepository.findReadyForOwner(userId).stream()
                .filter(index -> containsIgnoreCase(index.getExtractedText(), normalizedQuery)
                        || containsIgnoreCase(index.getDescription(), normalizedQuery))
                .forEach(index -> {
            StoredFile file = index.getFile();
            result.putIfAbsent("file-" + file.getId(), searchItem(file, "TEXT", 0.86));
        });
        addSemanticResults(owner.getId(), q, "TEXT", result);
        addSemanticResults(owner.getId(), q, "IMAGE", result);
        return result.values().stream()
                .sorted(Comparator.comparingInt((SearchItemResponse item) -> matchRank(item.matchType())).reversed()
                        .thenComparing(SearchItemResponse::score, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(SearchItemResponse::name, String.CASE_INSENSITIVE_ORDER))
                .limit(60)
                .toList();
    }

    @Transactional(readOnly = true)
    public int reindexCurrentUserFiles() {
        AppUser owner = userService.requireCurrentUser();
        List<StoredFile> files = fileRepository.findByOwnerIdAndDeletedAtIsNull(owner.getId());
        files.forEach(file -> eventPublisher.publishEvent(new FileIndexRequestedEvent(file.getId())));
        return files.size();
    }

    private boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private SearchItemResponse searchItem(StoredFile file, String matchType, Double score) {
        return new SearchItemResponse(
                "file",
                file.getId(),
                file.getName(),
                file.getFolder() == null ? null : file.getFolder().getId(),
                file.getSizeBytes(),
                file.getUploadedAt(),
                matchType,
                score,
                file.getContentType(),
                file.getExtension(),
                file.getFileGroup(),
                file.getThumbnailPath() != null && !file.getThumbnailPath().isBlank()
        );
    }

    private int matchRank(String matchType) {
        if ("NAME".equals(matchType)) {
            return 4;
        }
        if ("IMAGE".equals(matchType)) {
            return 3;
        }
        if ("TEXT".equals(matchType) || "OCR".equals(matchType)) {
            return 2;
        }
        if ("SEMANTIC".equals(matchType)) {
            return 1;
        }
        return 0;
    }

    private void addSemanticResults(Long ownerId, String query, String type, java.util.Map<String, SearchItemResponse> result) {
        if (query == null || query.isBlank()) {
            return;
        }
        String embeddingQuery = "IMAGE".equals(type) ? imageEmbeddingQuery(query) : query;
        if (embeddingQuery == null || embeddingQuery.isBlank()) {
            log.debug("Skip {} semantic search for query='{}': no compatible embedding query", type, query);
            return;
        }
        AiWorkerClient.EmbeddingResult queryEmbedding = "IMAGE".equals(type)
                ? aiWorkerClient.embedImageQueryResult(embeddingQuery)
                : aiWorkerClient.embedTextQueryResult(embeddingQuery);
        if (isFallbackModel(queryEmbedding.model()) || queryEmbedding.embedding().isEmpty()) {
            log.debug("Skip {} semantic search for query='{}': query model is {}", type, query, queryEmbedding.model());
            return;
        }
        double threshold = "IMAGE".equals(type) ? IMAGE_SEMANTIC_THRESHOLD : TEXT_SEMANTIC_THRESHOLD;
        List<SemanticScore> scores = embeddingRepository.findByOwnerIdAndEmbeddingType(ownerId, type).stream()
                .filter(embedding -> isCompatibleEmbedding(queryEmbedding, embedding))
                .map(embedding -> new SemanticScore(embedding, VectorUtils.cosine(queryEmbedding.embedding(), VectorUtils.parse(embedding.getVector()))))
                .peek(score -> log.debug("Search score type={} fileId={} model={} score={}",
                        type, score.embedding().getFile().getId(), score.embedding().getModelName(), score.value()))
                .sorted(Comparator.comparing(SemanticScore::value).reversed())
                .toList();
        double runnerUpScore = scores.size() > 1 ? scores.get(1).value() : 0.0;
        scores.stream()
                .filter(score -> score.value() >= threshold)
                .filter(score -> !"IMAGE".equals(type) || score.value() - runnerUpScore >= IMAGE_TOP_MARGIN)
                .limit(20)
                .forEach(score -> {
                    StoredFile file = score.embedding().getFile();
                    if (file.getDeletedAt() != null) {
                        return;
                    }
                    result.putIfAbsent("file-" + file.getId(), searchItem(file, type.equals("IMAGE") ? "IMAGE" : "SEMANTIC", score.value()));
                });
    }

    private boolean isCompatibleEmbedding(AiWorkerClient.EmbeddingResult queryEmbedding, com.diplom.cloudstorage.model.FileEmbedding embedding) {
        String fileModel = embedding.getModelName();
        if (isFallbackModel(fileModel)) {
            log.debug("Skip {} embedding for fileId={}: fallback model {}", embedding.getEmbeddingType(), embedding.getFile().getId(), fileModel);
            return false;
        }
        if (!Objects.equals(normalizeModelName(queryEmbedding.model()), normalizeModelName(fileModel))) {
            log.debug("Skip {} embedding for fileId={}: query model {} != file model {}",
                    embedding.getEmbeddingType(), embedding.getFile().getId(), queryEmbedding.model(), fileModel);
            return false;
        }
        List<Double> fileVector;
        try {
            fileVector = VectorUtils.parse(embedding.getVector());
        } catch (RuntimeException e) {
            log.debug("Skip {} embedding for fileId={}: cannot parse vector", embedding.getEmbeddingType(), embedding.getFile().getId());
            return false;
        }
        if (queryEmbedding.embedding().size() != fileVector.size()) {
            log.debug("Skip {} embedding for fileId={}: query dimension {} != file dimension {}",
                    embedding.getEmbeddingType(), embedding.getFile().getId(), queryEmbedding.embedding().size(), fileVector.size());
            return false;
        }
        return true;
    }

    private boolean isFallbackModel(String modelName) {
        String normalized = normalizeModelName(modelName);
        return normalized.isBlank()
                || normalized.startsWith("fallback")
                || normalized.contains("fallback-hash");
    }

    private String normalizeModelName(String modelName) {
        return modelName == null ? "" : modelName.trim().toLowerCase(Locale.ROOT);
    }

    private boolean hasLatinLetter(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if ((ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z')) {
                return true;
            }
        }
        return false;
    }

    private String imageEmbeddingQuery(String query) {
        if (hasLatinLetter(query)) {
            return imagePrompt(query);
        }
        String normalized = query.toLowerCase(Locale.ROOT).replace('ё', 'е').trim();
        if (normalized.isBlank()) {
            return "";
        }
        List<String> translated = new ArrayList<>();
        for (String token : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (IMAGE_QUERY_STOP_WORDS.contains(token)) {
                continue;
            }
            String english = IMAGE_QUERY_TRANSLATIONS.get(token);
            if (english == null) {
                return "";
            }
            translated.add(english);
        }
        if (translated.isEmpty()) {
            return "";
        }
        return imagePrompt(String.join(" ", translated));
    }

    private String imagePrompt(String query) {
        String normalized = query.toLowerCase(Locale.ROOT).trim().replaceAll("\\s+", " ");
        return IMAGE_QUERY_PROMPTS.getOrDefault(normalized, query);
    }

    private static Map<String, String> imageQueryTranslations() {
        Map<String, String> translations = new LinkedHashMap<>();
        translations.put("кот", "cat");
        translations.put("кота", "cat");
        translations.put("кошка", "cat");
        translations.put("кошки", "cat");
        translations.put("котик", "cat");
        translations.put("котенок", "kitten");
        translations.put("животное", "animal");
        translations.put("животные", "animals");
        translations.put("собака", "dog");
        translations.put("собаки", "dog");
        translations.put("пес", "dog");
        translations.put("щенок", "puppy");
        translations.put("птица", "bird");
        translations.put("птицы", "birds");
        translations.put("лес", "forest");
        translations.put("дерево", "tree");
        translations.put("деревья", "trees");
        translations.put("природа", "nature");
        translations.put("трава", "grass");
        translations.put("поле", "field");
        translations.put("гора", "mountain");
        translations.put("горы", "mountains");
        translations.put("пейзаж", "landscape");
        translations.put("река", "river");
        translations.put("озеро", "lake");
        translations.put("море", "sea");
        translations.put("океан", "ocean");
        translations.put("пляж", "beach");
        translations.put("небо", "sky");
        translations.put("облако", "cloud");
        translations.put("облака", "clouds");
        translations.put("снег", "snow");
        translations.put("зима", "winter");
        translations.put("машина", "car");
        translations.put("машину", "car");
        translations.put("машины", "car");
        translations.put("автомобиль", "car");
        translations.put("автомобили", "cars");
        translations.put("авто", "car");
        translations.put("транспорт", "vehicle");
        translations.put("мотоцикл", "motorcycle");
        translations.put("велосипед", "bicycle");
        translations.put("самолет", "airplane");
        translations.put("поезд", "train");
        translations.put("корабль", "ship");
        translations.put("лодка", "boat");
        translations.put("человек", "person");
        translations.put("мужчина", "man");
        translations.put("женщина", "woman");
        translations.put("портрет", "portrait");
        translations.put("ребенок", "child");
        translations.put("дети", "children");
        translations.put("люди", "people");
        translations.put("город", "city");
        translations.put("улица", "street");
        translations.put("дорога", "road");
        translations.put("дом", "house");
        translations.put("здание", "building");
        translations.put("офис", "office");
        translations.put("комната", "room");
        translations.put("кухня", "kitchen");
        translations.put("еда", "food");
        translations.put("блюдо", "food");
        translations.put("ресторан", "restaurant");
        translations.put("напиток", "drink");
        translations.put("кофе", "coffee");
        translations.put("чай", "tea");
        translations.put("торт", "cake");
        translations.put("пицца", "pizza");
        translations.put("салат", "salad");
        translations.put("цветок", "flower");
        translations.put("цветы", "flowers");
        translations.put("документ", "document");
        translations.put("скриншот", "screenshot");
        translations.put("экран", "screen");
        translations.put("таблица", "table");
        translations.put("текст", "text");
        translations.put("красный", "red");
        translations.put("красная", "red");
        translations.put("синий", "blue");
        translations.put("синяя", "blue");
        translations.put("зеленый", "green");
        translations.put("зеленая", "green");
        translations.put("белый", "white");
        translations.put("белая", "white");
        translations.put("черный", "black");
        translations.put("черная", "black");
        return Map.copyOf(translations);
    }

    private static Set<String> imageQueryStopWords() {
        return Set.of(
                "фото",
                "фотография",
                "фотку",
                "картинка",
                "картинку",
                "изображение",
                "снимок",
                "найди",
                "покажи",
                "на",
                "с",
                "со",
                "и"
        );
    }

    private static Map<String, String> imageQueryPrompts() {
        Map<String, String> prompts = new LinkedHashMap<>();
        prompts.put("cat", "a photo of a cat");
        prompts.put("kitten", "a photo of a kitten");
        prompts.put("animal", "a photo of an animal");
        prompts.put("animals", "a photo of animals");
        prompts.put("dog", "a photo of a dog");
        prompts.put("puppy", "a photo of a puppy");
        prompts.put("bird", "a photo of a bird");
        prompts.put("birds", "a photo of birds");
        prompts.put("forest", "a photo of a forest");
        prompts.put("tree", "a photo of a tree");
        prompts.put("trees", "a photo of trees");
        prompts.put("landscape", "a landscape photo");
        prompts.put("nature", "a nature photo");
        prompts.put("grass", "a photo of grass");
        prompts.put("field", "a photo of a field");
        prompts.put("mountain", "a photo of a mountain");
        prompts.put("mountains", "a photo of mountains");
        prompts.put("river", "a photo of a river");
        prompts.put("lake", "a photo of a lake");
        prompts.put("sea", "a photo of the sea");
        prompts.put("ocean", "a photo of the ocean");
        prompts.put("beach", "a photo of a beach");
        prompts.put("sky", "a photo of the sky");
        prompts.put("cloud", "a photo of a cloud");
        prompts.put("clouds", "a photo of clouds");
        prompts.put("snow", "a photo of snow");
        prompts.put("winter", "a winter photo");
        prompts.put("car", "a photo of a red car");
        prompts.put("cars", "a photo of cars");
        prompts.put("red car", "a photo of a red car");
        prompts.put("blue car", "a photo of a blue car");
        prompts.put("black car", "a photo of a black car");
        prompts.put("white car", "a photo of a white car");
        prompts.put("vehicle", "a photo of a vehicle");
        prompts.put("motorcycle", "a photo of a motorcycle");
        prompts.put("bicycle", "a photo of a bicycle");
        prompts.put("airplane", "a photo of an airplane");
        prompts.put("train", "a photo of a train");
        prompts.put("ship", "a photo of a ship");
        prompts.put("boat", "a photo of a boat");
        prompts.put("person", "a photo of a person");
        prompts.put("man", "a photo of a man");
        prompts.put("woman", "a photo of a woman");
        prompts.put("portrait", "a portrait photo");
        prompts.put("child", "a photo of a child");
        prompts.put("children", "a photo of children");
        prompts.put("people", "a photo of people");
        prompts.put("city", "a photo of a city");
        prompts.put("street", "a photo of a street");
        prompts.put("road", "a photo of a road");
        prompts.put("house", "a photo of a house");
        prompts.put("building", "a photo of a building");
        prompts.put("office", "a photo of an office");
        prompts.put("room", "a photo of a room");
        prompts.put("kitchen", "a photo of a kitchen");
        prompts.put("food", "a photo of food");
        prompts.put("restaurant", "a photo of a restaurant");
        prompts.put("drink", "a photo of a drink");
        prompts.put("coffee", "a photo of coffee");
        prompts.put("tea", "a photo of tea");
        prompts.put("cake", "a photo of a cake");
        prompts.put("pizza", "a photo of pizza");
        prompts.put("salad", "a photo of salad");
        prompts.put("flower", "a photo of a flower");
        prompts.put("flowers", "a photo of flowers");
        prompts.put("document", "a screenshot of a document");
        prompts.put("screenshot", "a screenshot");
        prompts.put("screen", "a screenshot of a screen");
        prompts.put("table", "a screenshot of a table");
        prompts.put("text", "a screenshot of text");
        return Map.copyOf(prompts);
    }

    private record SemanticScore(com.diplom.cloudstorage.model.FileEmbedding embedding, double value) {
    }

    @Transactional(readOnly = true)
    public byte[] downloadFolderAsZip(Long folderId) {
        AppUser owner = userService.requireCurrentUser();
        Folder root = requireFolder(folderId, owner.getId());
        return downloadFolderAsZip(root);
    }

    @Transactional(readOnly = true)
    public byte[] downloadFolderAsZip(Folder root) {
        Long ownerId = root.getOwner().getId();
        return downloadFolderAsZip(ownerId, root);
    }

    @Transactional(readOnly = true)
    public byte[] downloadFolderAsZip(Long ownerId, Long folderId) {
        Folder root = requireFolder(folderId, ownerId);
        return downloadFolderAsZip(ownerId, root);
    }

    private byte[] downloadFolderAsZip(Long ownerId, Folder root) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            writeFolderToZip(ownerId, root, root.getName(), zip);
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot create folder archive");
        }
    }

    private void writeFolderToZip(Long userId, Folder root, String prefix, ZipOutputStream zipOutputStream) throws IOException {
        List<StoredFile> files = fileRepository.findByOwnerIdAndFolderIdAndDeletedAtIsNull(userId, root.getId());
        for (StoredFile file : files) {
            ZipEntry entry = new ZipEntry(prefix + "/" + file.getName());
            zipOutputStream.putNextEntry(entry);
            Files.copy(Paths.get(file.getStoredPath()), zipOutputStream);
            zipOutputStream.closeEntry();
        }
        for (Folder child : folderRepository.findByOwnerIdAndParentId(userId, root.getId())) {
            writeFolderToZip(userId, child, prefix + "/" + child.getName(), zipOutputStream);
        }
    }

    private void ensureFolderMoveTargetIsSafe(Folder folder, Folder target) {
        Folder current = target;
        while (current != null) {
            if (Objects.equals(current.getId(), folder.getId())) {
                throw new ApiException(HttpStatus.CONFLICT, "Folder cannot be moved into itself or its child folder");
            }
            current = current.getParent();
        }
    }

    private Folder ensureFolderPath(AppUser owner, Folder baseFolder, List<String> folderNames) {
        Folder current = baseFolder;
        for (String folderName : folderNames) {
            Long parentId = current == null ? null : current.getId();
            current = findOrCreateFolder(owner, current, parentId, folderName);
        }
        return current;
    }

    private Folder findOrCreateFolder(AppUser owner, Folder parent, Long parentId, String name) {
        return (parentId == null
                ? folderRepository.findByOwnerIdAndParentIsNullAndNameIgnoreCase(owner.getId(), name)
                : folderRepository.findByOwnerIdAndParentIdAndNameIgnoreCase(owner.getId(), parentId, name))
                .orElseGet(() -> {
                    Folder folder = new Folder();
                    folder.setOwner(owner);
                    folder.setParent(parent);
                    folder.setName(name);
                    return folderRepository.save(folder);
                });
    }

    private List<String> parseRelativePath(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "relativePath must not be empty");
        }
        String normalized = relativePath.replace('\\', '/').trim();
        if (normalized.startsWith("/")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "relativePath must be relative");
        }
        String[] rawParts = normalized.split("/");
        List<String> parts = new ArrayList<>();
        for (String rawPart : rawParts) {
            String part = rawPart.trim();
            if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "relativePath contains invalid segment");
            }
            parts.add(part);
        }
        return parts;
    }

    private Folder requireFolder(Long folderId, Long userId) {
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Folder not found"));
        if (!folder.getOwner().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return folder;
    }

    private StoredFile requireAnyFile(Long fileId, Long userId) {
        StoredFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "File not found"));
        if (!file.getOwner().getId().equals(userId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Access denied");
        }
        return file;
    }

    private StoredFile requireActiveFile(Long fileId, Long userId) {
        StoredFile file = requireAnyFile(fileId, userId);
        if (file.getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File moved to trash");
        }
        return file;
    }

    private void ensureFolderUnique(Long userId, Long parentId, String name) {
        boolean exists = parentId == null
                ? folderRepository.findByOwnerIdAndParentIsNullAndNameIgnoreCase(userId, name).isPresent()
                : folderRepository.findByOwnerIdAndParentIdAndNameIgnoreCase(userId, parentId, name).isPresent();
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "Folder with this name already exists");
        }
    }

    private void ensureFileUnique(Long userId, Long folderId, String name) {
        boolean exists = fileNameExists(userId, folderId, name);
        if (exists) {
            throw new ApiException(HttpStatus.CONFLICT, "File with this name already exists");
        }
    }

    private void ensureQuotaAvailable(AppUser owner, long incomingBytes) {
        if (incomingBytes < 0) {
            incomingBytes = 0;
        }
        long effectiveQuotaBytes = effectiveQuotaBytes(owner);
        if (effectiveQuotaBytes > 0 && owner.getUsedSpace() + incomingBytes > effectiveQuotaBytes) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "Storage quota exceeded");
        }
    }

    private long effectiveQuotaBytes(AppUser owner) {
        return owner.getStorageQuotaBytes() == null ? quotaBytes : owner.getStorageQuotaBytes();
    }

    private List<Long> normalizeBulkIds(List<Long> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No files selected");
        }
        List<Long> normalized = fileIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (normalized.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No files selected");
        }
        return normalized;
    }

    private boolean fileNameExists(Long userId, Long folderId, String name) {
        return folderId == null
                ? fileRepository.findByOwnerIdAndFolderIsNullAndNameIgnoreCaseAndDeletedAtIsNull(userId, name).isPresent()
                : fileRepository.findByOwnerIdAndFolderIdAndNameIgnoreCaseAndDeletedAtIsNull(userId, folderId, name).isPresent();
    }

    private String targetName(Folder folder) {
        return folder == null ? "Файлы" : folder.getName();
    }

    private void deleteStoredFile(StoredFile file) {
        fileStorageService.delete(file.getStoredPath());
        if (file.getThumbnailPath() != null) {
            fileStorageService.delete(file.getThumbnailPath());
        }
        AppUser owner = file.getOwner();
        owner.setUsedSpace(Math.max(0L, owner.getUsedSpace() - file.getSizeBytes()));
        publicLinkRepository.deleteByFileId(file.getId());
        embeddingRepository.deleteByFileId(file.getId());
        indexRepository.findByFileId(file.getId()).ifPresent(indexRepository::delete);
        fileRepository.delete(file);
    }

    private String extractExtension(String name) {
        if (name == null) {
            return null;
        }
        int dot = name.lastIndexOf('.');
        if (dot < 1 || dot == name.length() - 1) {
            return null;
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String resolveFileGroup(String extension) {
        if (extension == null || extension.isBlank()) {
            return "other";
        }
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic" -> "photo";
            case "mp4", "mkv", "mov", "avi", "webm" -> "video";
            case "mp3", "wav", "flac", "aac", "ogg", "m4a" -> "audio";
            case "pdf" -> "pdf";
            case "doc", "docx", "txt", "rtf", "odt", "xls", "xlsx", "ppt", "pptx", "csv" -> "document";
            case "zip", "rar", "7z", "tar", "gz" -> "archive";
            default -> "other";
        };
    }

    private String normalizeFileGroup(String group) {
        String normalized = group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "photo", "video", "audio", "pdf", "document", "archive", "other" -> normalized;
            default -> throw new ApiException(HttpStatus.BAD_REQUEST, "Unsupported file group");
        };
    }

    private String resolvePreviewKind(StoredFile file) {
        String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
        String extension = file.getExtension() == null ? "" : file.getExtension().toLowerCase(Locale.ROOT);
        if (contentType.startsWith("image/") || "photo".equals(file.getFileGroup())) {
            return "image";
        }
        if ("application/pdf".equals(contentType) || "pdf".equals(extension)) {
            return "pdf";
        }
        if (contentType.startsWith("video/") || "video".equals(file.getFileGroup())) {
            return "video";
        }
        if (contentType.startsWith("audio/") || "audio".equals(file.getFileGroup())) {
            return "audio";
        }
        if (contentType.startsWith("text/")
                || List.of("txt", "csv", "json", "xml", "md", "log", "java", "js", "ts", "tsx", "jsx", "css", "html", "yml", "yaml").contains(extension)) {
            return "text";
        }
        return "binary";
    }

    private String readTextSnippet(String storedPath) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(storedPath));
            int limit = Math.min(bytes.length, 120_000);
            return new String(bytes, 0, limit, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Cannot read text preview");
        }
    }

    private String createThumbnailPathIfSupported(Long userId, String storedPath, String fileGroup) {
        if (!"photo".equals(fileGroup)) {
            return null;
        }
        try {
            BufferedImage source = ImageIO.read(Paths.get(storedPath).toFile());
            if (source == null) {
                return null;
            }
            int maxSize = 360;
            double scale = Math.min(1.0, (double) maxSize / Math.max(source.getWidth(), source.getHeight()));
            int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = thumbnail.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.drawImage(source, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(thumbnail, "jpg", out)) {
                return null;
            }
            return fileStorageService.saveThumbnail(userId, out.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    private String resolveRestoredName(Long userId, Long folderId, String originalName) {
        if (!fileNameExists(userId, folderId, originalName)) {
            return originalName;
        }
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext = dot > 0 ? originalName.substring(dot) : "";
        String candidate = base + " (restored)" + ext;
        int counter = 2;
        while (fileNameExists(userId, folderId, candidate)) {
            candidate = base + " (restored " + counter + ")" + ext;
            counter++;
            if (counter > 2000) {
                throw new ApiException(HttpStatus.CONFLICT, "Cannot restore file name");
            }
        }
        return candidate;
    }

}

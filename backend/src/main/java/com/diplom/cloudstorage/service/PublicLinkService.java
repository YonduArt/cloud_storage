package com.diplom.cloudstorage.service;

import com.diplom.cloudstorage.dto.PublicLinkCreateRequest;
import com.diplom.cloudstorage.dto.PublicLinkResponse;
import com.diplom.cloudstorage.dto.PublicResourceResponse;
import com.diplom.cloudstorage.exception.ApiException;
import com.diplom.cloudstorage.mapper.FileMapper;
import com.diplom.cloudstorage.mapper.FolderMapper;
import com.diplom.cloudstorage.model.AppUser;
import com.diplom.cloudstorage.model.Folder;
import com.diplom.cloudstorage.model.PublicLink;
import com.diplom.cloudstorage.model.StoredFile;
import com.diplom.cloudstorage.repository.FolderRepository;
import com.diplom.cloudstorage.repository.PublicLinkRepository;
import com.diplom.cloudstorage.repository.StoredFileRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicLinkService {

    private final PublicLinkRepository publicLinkRepository;
    private final FolderRepository folderRepository;
    private final StoredFileRepository fileRepository;
    private final StorageService storageService;
    private final UserService userService;
    private final FileEventService eventService;
    private final FolderMapper folderMapper;
    private final FileMapper fileMapper;
    private final PasswordEncoder passwordEncoder;
    private final String publicBaseUrl;

    public PublicLinkService(PublicLinkRepository publicLinkRepository,
                             FolderRepository folderRepository,
                             StoredFileRepository fileRepository,
                             StorageService storageService,
                             UserService userService,
                             FileEventService eventService,
                             FolderMapper folderMapper,
                             FileMapper fileMapper,
                             PasswordEncoder passwordEncoder,
                             @Value("${app.public-base-url:http://localhost:5173}") String publicBaseUrl) {
        this.publicLinkRepository = publicLinkRepository;
        this.folderRepository = folderRepository;
        this.fileRepository = fileRepository;
        this.storageService = storageService;
        this.userService = userService;
        this.eventService = eventService;
        this.folderMapper = folderMapper;
        this.fileMapper = fileMapper;
        this.passwordEncoder = passwordEncoder;
        this.publicBaseUrl = publicBaseUrl;
    }

    @Transactional
    public PublicLinkResponse create(Long fileId, PublicLinkCreateRequest request) {
        StoredFile file = storageService.getFileById(fileId);
        PublicLink existingLink = publicLinkRepository.findByFileIdAndActiveTrue(file.getId()).orElse(null);
        if (existingLink != null) {
            applyExpiration(existingLink, request);
            applyPassword(existingLink, request);
            return toResponse(publicLinkRepository.save(existingLink));
        }
        PublicLink link = new PublicLink();
        link.setFile(file);
        link.setToken(UUID.randomUUID().toString().replace("-", ""));
        link.setActive(true);
        applyExpiration(link, request);
        applyPassword(link, request);
        PublicLink saved = publicLinkRepository.save(link);
        eventService.log(file.getOwner(), "PUBLIC_LINK_CREATED", "file", file.getId(), file.getName(), "Публичная ссылка создана");
        return toResponse(saved);
    }

    @Transactional
    public PublicLinkResponse createFolderLink(Long folderId, PublicLinkCreateRequest request) {
        AppUser owner = userService.requireCurrentUser();
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Folder not found"));
        if (!folder.getOwner().getId().equals(owner.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Access denied");
        }
        PublicLink existingLink = publicLinkRepository.findByFolderIdAndActiveTrue(folder.getId()).orElse(null);
        if (existingLink != null) {
            applyExpiration(existingLink, request);
            applyPassword(existingLink, request);
            return toResponse(publicLinkRepository.save(existingLink));
        }
        PublicLink link = new PublicLink();
        link.setFolder(folder);
        link.setToken(UUID.randomUUID().toString().replace("-", ""));
        link.setActive(true);
        applyExpiration(link, request);
        applyPassword(link, request);
        PublicLink saved = publicLinkRepository.save(link);
        eventService.log(owner, "PUBLIC_LINK_CREATED", "folder", folder.getId(), folder.getName(), "Публичная ссылка на папку создана");
        return toResponse(saved);
    }

    @Transactional
    public void disable(Long id) {
        AppUser owner = userService.requireCurrentUser();
        PublicLink link = publicLinkRepository.findByIdAndFileOwnerId(id, owner.getId())
                .or(() -> publicLinkRepository.findByIdAndFolderOwnerId(id, owner.getId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Public link not found"));
        link.setActive(false);
        publicLinkRepository.save(link);
        eventService.log(owner, "PUBLIC_LINK_DISABLED", targetType(link), targetId(link), targetName(link), "Публичная ссылка отключена");
    }

    @Transactional(readOnly = true)
    public List<PublicLinkResponse> listCurrentUserLinks() {
        AppUser owner = userService.requireCurrentUser();
        return publicLinkRepository.findByFileOwnerIdOrFolderOwnerIdOrderByCreatedAtDesc(owner.getId(), owner.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public StoredFile resolvePublicFile(String token, String password) {
        PublicLink link = requireActiveLink(token, password);
        if (link.getFile() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Public link points to folder");
        }
        if (link.getFile().getDeletedAt() != null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File moved to trash");
        }
        return link.getFile();
    }

    @Transactional(readOnly = true)
    public StoredFile resolvePublicFolderFile(String token, Long fileId, String password) {
        PublicLink link = requireActiveLink(token, password);
        if (link.getFolder() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Public link points to file");
        }
        StoredFile file = fileRepository.findById(fileId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "File not found"));
        if (file.getDeletedAt() != null || !file.getOwner().getId().equals(link.getFolder().getOwner().getId())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "File not found");
        }
        if (!isInsideFolder(file.getFolder(), link.getFolder().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "File is outside public folder");
        }
        return file;
    }

    @Transactional(readOnly = true)
    public Folder resolvePublicFolder(String token, String password) {
        PublicLink link = requireActiveLink(token, password);
        if (link.getFolder() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Public link points to file");
        }
        Folder folder = link.getFolder();
        folder.getOwner().getId();
        return folder;
    }

    @Transactional(readOnly = true)
    public Folder resolvePublicFolder(String token, Long folderId, String password) {
        PublicLink link = requireActiveLink(token, password);
        if (link.getFolder() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Public link points to file");
        }
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Folder not found"));
        if (!folder.getOwner().getId().equals(link.getFolder().getOwner().getId()) || !isInsideFolder(folder, link.getFolder().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Folder is outside public folder");
        }
        folder.getOwner().getId();
        return folder;
    }

    @Transactional(readOnly = true)
    public PublicResourceResponse resolvePublicResource(String token, String password) {
        PublicLink link = requireActiveLink(token, password);
        if (link.getFile() != null) {
            StoredFile file = link.getFile();
            if (file.getDeletedAt() != null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "File moved to trash");
            }
            return new PublicResourceResponse(
                    link.getToken(),
                    "file",
                    file.getName(),
                    null,
                    null,
                    file.getContentType(),
                    file.getSizeBytes(),
                    link.getCreatedAt(),
                    hasPassword(link),
                    List.of(),
                    List.of(fileMapper.toResponse(file))
            );
        }
        Folder folder = link.getFolder();
        return resolvePublicFolderResource(link, folder);
    }

    @Transactional(readOnly = true)
    public PublicResourceResponse resolvePublicFolderResource(String token, Long folderId, String password) {
        PublicLink link = requireActiveLink(token, password);
        if (link.getFolder() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Public link points to file");
        }
        Folder folder = folderRepository.findById(folderId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Folder not found"));
        if (!folder.getOwner().getId().equals(link.getFolder().getOwner().getId()) || !isInsideFolder(folder, link.getFolder().getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Folder is outside public folder");
        }
        return resolvePublicFolderResource(link, folder);
    }

    private PublicResourceResponse resolvePublicFolderResource(PublicLink link, Folder folder) {
        Long ownerId = folder.getOwner().getId();
        return new PublicResourceResponse(
                link.getToken(),
                "folder",
                folder.getName(),
                folder.getId(),
                folder.getParent() == null ? null : folder.getParent().getId(),
                null,
                null,
                link.getCreatedAt(),
                hasPassword(link),
                folderRepository.findByOwnerIdAndParentId(ownerId, folder.getId()).stream().map(folderMapper::toResponse).toList(),
                fileRepository.findByOwnerIdAndFolderIdAndDeletedAtIsNull(ownerId, folder.getId()).stream().map(fileMapper::toResponse).toList()
        );
    }

    @Transactional(readOnly = true)
    public StoredFile resolvePublicThumbnailFile(String token, Long fileId, String password) {
        PublicLink link = requireActiveLink(token, password);
        StoredFile file;
        if (link.getFile() != null) {
            file = link.getFile();
            if (!file.getId().equals(fileId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "File is outside public link");
            }
        } else {
            file = resolvePublicFolderFile(token, fileId, password);
        }
        if (file.getDeletedAt() != null || file.getThumbnailPath() == null || file.getThumbnailPath().isBlank()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Thumbnail not found");
        }
        return file;
    }

    private PublicLink requireActiveLink(String token, String password) {
        PublicLink link = publicLinkRepository.findByToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Public link not found"));
        if (!link.isActive()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Public link disabled");
        }
        if (isExpired(link)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Public link expired");
        }
        if (hasPassword(link) && (password == null || password.isBlank() || !passwordEncoder.matches(password, link.getPasswordHash()))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Public link password required");
        }
        return link;
    }

    @Transactional
    public int disableExpiredLinks() {
        List<PublicLink> expiredLinks = publicLinkRepository.findByActiveTrueAndExpiresAtBefore(Instant.now());
        expiredLinks.forEach(link -> link.setActive(false));
        publicLinkRepository.saveAll(expiredLinks);
        return expiredLinks.size();
    }

    private void applyExpiration(PublicLink link, PublicLinkCreateRequest request) {
        Integer expiresInDays = request == null ? null : request.expiresInDays();
        if (expiresInDays == null || expiresInDays <= 0) {
            link.setExpiresAt(null);
            return;
        }
        link.setExpiresAt(Instant.now().plus(Math.min(expiresInDays, 365), ChronoUnit.DAYS));
    }

    private void applyPassword(PublicLink link, PublicLinkCreateRequest request) {
        if (request == null || request.password() == null) {
            return;
        }
        String password = request.password().trim();
        link.setPasswordHash(password.isBlank() ? null : passwordEncoder.encode(password));
    }

    private boolean isExpired(PublicLink link) {
        return link.getExpiresAt() != null && !link.getExpiresAt().isAfter(Instant.now());
    }

    private boolean hasPassword(PublicLink link) {
        return link.getPasswordHash() != null && !link.getPasswordHash().isBlank();
    }

    private boolean isInsideFolder(Folder folder, Long publicFolderId) {
        Folder current = folder;
        while (current != null) {
            if (current.getId().equals(publicFolderId)) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private PublicLinkResponse toResponse(PublicLink link) {
        return new PublicLinkResponse(
                link.getId(),
                link.getToken(),
                targetType(link),
                link.getFile() == null ? null : link.getFile().getId(),
                link.getFile() == null ? null : link.getFile().getName(),
                link.getFolder() == null ? null : link.getFolder().getId(),
                link.getFolder() == null ? null : link.getFolder().getName(),
                link.getFile() == null ? null : link.getFile().getContentType(),
                link.getFile() == null ? null : link.getFile().getExtension(),
                link.getFile() == null ? null : link.getFile().getFileGroup(),
                link.getFile() == null ? null : link.getFile().getThumbnailPath() != null && !link.getFile().getThumbnailPath().isBlank(),
                link.getFile() == null ? null : link.getFile().getSizeBytes(),
                link.isActive(),
                link.getCreatedAt(),
                link.getExpiresAt(),
                isExpired(link),
                hasPassword(link),
                publicBaseUrl + "/public/" + link.getToken()
        );
    }

    private String targetType(PublicLink link) {
        return link.getFolder() == null ? "file" : "folder";
    }

    private Long targetId(PublicLink link) {
        return link.getFile() == null ? link.getFolder().getId() : link.getFile().getId();
    }

    private String targetName(PublicLink link) {
        return link.getFile() == null ? link.getFolder().getName() : link.getFile().getName();
    }
}

import { useEffect, useMemo, useState } from "react";
import {
  createIntegrationClient,
  createFolder,
  createPublicFolderLink,
  createPublicLink,
  deleteFile,
  deleteFolder,
  deleteTrashFiles,
  disablePublicLink,
  downloadFile,
  getAdminUsers,
  getPerformanceReport,
  getFilesByGroup,
  getFilePreview,
  getFilePreviewContent,
  getFileIndexStatus,
  getFileThumbnail,
  getFavorites,
  getFolderContent,
  getHistory,
  getPublicLinks,
  publicFileThumbnailUrl,
  getRecentOpened,
  getRecentUploaded,
  getStorageStats,
  getTrashFiles,
  listIntegrationClients,
  moveFile,
  moveFileToTrash,
  moveFolder,
  reindexCurrentUserFiles,
  revokeIntegrationClient,
  renameFile,
  renameFolder,
  restoreFilesFromTrash,
  restoreFileFromTrash,
  search,
  setFileFavorite,
  setFolderFavorite,
  uploadFile,
  uploadFolder,
  updateAdminUser
} from "../api/client";
import type { AdminUser, FileEvent, FileIndexStatus, FileItem, FilePreview, FolderItem, IntegrationClient, PerformanceReport, PublicLink, StorageStats, StorageView, User } from "../types";

type ViewMode = "grid" | "list";
type ItemFilter = "all" | "folders" | "files";
type WorkspaceMode = "drive" | "recentOpened" | "favorites" | "photos" | "albums" | "shared" | "uploads" | "trash" | "history" | "storage" | "admin" | "api";
type DraggedItem = { type: "file" | "folder"; id: number };
type RenameDialogState =
  | { type: "file"; item: FileItem; value: string }
  | { type: "folder"; item: FolderItem; value: string };
type MoveDialogState =
  | { type: "file"; item: FileItem; targetFolderId: number | null }
  | { type: "folder"; item: FolderItem; targetFolderId: number | null };
type ShareDialogState =
  | { type: "file"; item: FileItem; expiresInDays: number | null; password?: string; link?: PublicLink | null }
  | { type: "folder"; item: FolderItem; expiresInDays: number | null; password?: string; link?: PublicLink | null };
type ContextMenuState =
  | { x: number; y: number; type: "file"; item: FileItem }
  | { x: number; y: number; type: "folder"; item: FolderItem };
type UploadQueueItemStatus = "pending" | "uploading" | "uploaded" | "error";
type UploadQueueItem = {
  id: string;
  file: File;
  progress: number;
  status: UploadQueueItemStatus;
  error?: string;
};
type LoadModelPoint = {
  users: number;
  rps: number;
  p95: number;
  errorRate: number;
};

const VIEW_KEY = "cloud_view_mode";
const DRAG_MIME = "application/x-cloud-storage-item";
const MAX_UPLOAD_QUEUE = 10;

const modeTitles: Record<WorkspaceMode, string> = {
  drive: "Файлы",
  recentOpened: "Последние",
  favorites: "Избранное",
  photos: "Фото",
  albums: "Альбомы",
  shared: "Общий доступ",
  uploads: "Загрузки",
  trash: "Корзина",
  history: "История",
  storage: "Управление местом",
  admin: "Панель администратора",
  api: "API"
};

function formatBytes(bytes: number): string {
  if (!bytes) return "0 B";
  const units = ["B", "KB", "MB", "GB", "TB"];
  let value = bytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value.toFixed(value >= 100 || unitIndex === 0 ? 0 : 1)} ${units[unitIndex]}`;
}

function formatDate(value?: string | null): string {
  if (!value) return "-";
  return new Date(value).toLocaleString("ru-RU", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit"
  });
}

function timeUntil(value?: string | null): string {
  if (!value) return "-";
  const diffMs = new Date(value).getTime() - Date.now();
  if (diffMs <= 0) return "срок истек";
  const hours = Math.ceil(diffMs / (1000 * 60 * 60));
  if (hours < 24) return `${hours} ч`;
  const days = Math.ceil(hours / 24);
  return `${days} дн.`;
}

function publicLinkState(link: PublicLink): string {
  if (!link.active) return "Отключена";
  if (link.expired) return "Срок истек";
  if (!link.expiresAt) return "Бессрочно";
  return `Действует еще ${timeUntil(link.expiresAt)}`;
}

function fileBadge(file: FileItem): string {
  if (file.fileGroup === "photo") return "IMG";
  if (file.fileGroup === "video") return "VID";
  if (file.fileGroup === "audio") return "AUD";
  if (file.fileGroup === "pdf") return "PDF";
  if (file.fileGroup === "document") return "DOC";
  if (file.fileGroup === "archive") return "ZIP";
  return (file.extension || "FILE").slice(0, 4).toUpperCase();
}

function eventTitle(action: string): string {
  const titles: Record<string, string> = {
    FILE_UPLOADED: "Файл загружен",
    FILE_RENAMED: "Файл переименован",
    FILE_MOVED: "Файл перемещен",
    FILE_TRASHED: "Файл перемещен в корзину",
    FILE_RESTORED: "Файл восстановлен",
    FILE_DELETED: "Файл удален",
    FOLDER_CREATED: "Папка создана",
    FOLDER_RENAMED: "Папка переименована",
    FOLDER_MOVED: "Папка перемещена",
    FOLDER_DELETED: "Папка удалена",
    PUBLIC_LINK_CREATED: "Ссылка создана",
    PUBLIC_LINK_DISABLED: "Ссылка отключена"
  };
  return titles[action] || action;
}

function groupTitle(group?: string | null): string {
  const titles: Record<string, string> = {
    photo: "Фото",
    video: "Видео",
    audio: "Аудио",
    pdf: "PDF",
    document: "Документы",
    archive: "Архивы",
    other: "Другое"
  };
  return titles[group || "other"] || "Другое";
}

function matchTitle(matchType?: string | null): string | null {
  if (matchType === "TEXT") return "По содержимому";
  if (matchType === "IMAGE") return "По изображению";
  if (matchType === "OCR") return "По тексту на изображении";
  if (matchType === "SEMANTIC") return "По смыслу";
  if (matchType === "NAME") return "По названию";
  return null;
}

function confidenceTitle(score?: number): string | null {
  if (score == null || Number.isNaN(score)) return null;
  const normalized = score <= 1 ? score * 100 : score;
  return `Уверенность ${Math.max(0, Math.min(100, normalized)).toFixed(0)}%`;
}

function indexStatusLabel(status?: string | null): string {
  if (status === "READY") return "Готово";
  if (status === "FAILED") return "Ошибка";
  if (status === "INDEXING") return "Индексируется";
  return "Ожидает индексации";
}

function indexDotClass(file: FileItem): string {
  const status = file.index?.status;
  if (status === "READY") return "ready";
  if (status === "FAILED") return "failed";
  return "pending";
}

function indexTooltip(file: FileItem): string {
  const index = file.index;
  if (!index) return "Индексация: ожидает";
  const lines = [`Индексация: ${indexStatusLabel(index.status)}`];
  if (index.createdAt) lines.push(`Начата: ${formatDate(index.createdAt)}`);
  if (index.indexedAt) lines.push(`Готова: ${formatDate(index.indexedAt)}`);
  if (index.errorMessage) lines.push(`Ошибка: ${index.errorMessage}`);
  if (index.embeddings.length) {
    lines.push("Индексы:");
    index.embeddings.forEach((embedding) => {
      const createdAt = embedding.createdAt ? `, ${formatDate(embedding.createdAt)}` : "";
      lines.push(`${embedding.type}: ${embedding.dimensions}d, ${embedding.modelName}${createdAt}`);
    });
  } else {
    lines.push("Индексы: пока нет");
  }
  return lines.join("\n");
}

function statusToIndex(status: FileIndexStatus): FileItem["index"] {
  return {
    status: status.status,
    createdAt: status.createdAt,
    indexedAt: status.indexedAt,
    errorMessage: status.errorMessage,
    embeddings: status.embeddings
  };
}

function ThumbnailArt({ file }: { file: FileItem }) {
  const [url, setUrl] = useState<string | null>(null);

  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;

    if (!file.hasThumbnail && file.fileGroup !== "photo") {
      setUrl(null);
      return;
    }

    getFileThumbnail(file.id)
      .then((blob) => {
        if (cancelled) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      .catch(() => setUrl(null));

    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [file.id, file.hasThumbnail]);

  if (url) {
    return (
      <div className="file-art thumbnail-art">
        <img src={url} alt={file.name} />
      </div>
    );
  }

  return (
    <div className={`file-art group-${file.fileGroup || "other"}`}>
      <span>{fileBadge(file)}</span>
    </div>
  );
}

function PreviewModal({
  file,
  onClose,
  onDownload
}: {
  file: FileItem;
  onClose: () => void;
  onDownload: (file: FileItem) => void;
}) {
  const [preview, setPreview] = useState<FilePreview | null>(null);
  const [contentUrl, setContentUrl] = useState<string | null>(null);
  const [error, setError] = useState("");

  useEffect(() => {
    let objectUrl: string | null = null;
    let cancelled = false;
    setPreview(null);
    setContentUrl(null);
    setError("");

    getFilePreview(file.id)
      .then(async (metadata) => {
        if (cancelled) return;
        setPreview(metadata);
        if (["image", "pdf", "video", "audio"].includes(metadata.previewKind)) {
          const blob = await getFilePreviewContent(file.id);
          if (cancelled) return;
          objectUrl = URL.createObjectURL(blob);
          setContentUrl(objectUrl);
        }
      })
      .catch((e) => setError((e as Error).message));

    return () => {
      cancelled = true;
      if (objectUrl) {
        URL.revokeObjectURL(objectUrl);
      }
    };
  }, [file.id]);

  return (
    <div className="preview-backdrop" role="dialog" aria-modal="true">
      <section className="preview-modal">
        <header className="preview-header">
          <div>
            <h2>{file.name}</h2>
            <p>{formatBytes(file.sizeBytes)} · {file.contentType}</p>
          </div>
          <div className="preview-actions">
            <button type="button" onClick={() => onDownload(file)}>Скачать</button>
            <button className="ghost-btn" type="button" onClick={onClose}>Закрыть</button>
          </div>
        </header>

        <div className="preview-body">
          {error && <p className="error-text">{error}</p>}
          {!error && !preview && <p className="loading-text">Готовим предпросмотр...</p>}
          {preview?.previewKind === "image" && contentUrl && <img className="preview-image" src={contentUrl} alt={file.name} />}
          {preview?.previewKind === "pdf" && contentUrl && <iframe className="preview-frame" src={contentUrl} title={file.name} />}
          {preview?.previewKind === "video" && contentUrl && <video className="preview-media" src={contentUrl} controls />}
          {preview?.previewKind === "audio" && contentUrl && <audio className="preview-audio" src={contentUrl} controls />}
          {preview?.previewKind === "text" && <pre className="preview-text">{preview.textSnippet || ""}</pre>}
          {preview?.previewKind === "binary" && (
            <div className="preview-fallback">
              <div className={`file-art group-${file.fileGroup || "other"}`}>
                <span>{fileBadge(file)}</span>
              </div>
              <h3>Предпросмотр для этого типа файла недоступен</h3>
              <p>Файл можно скачать и открыть в подходящем приложении.</p>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

export default function StorageDashboard({
  user,
  onLogout
}: {
  user: User;
  onLogout: () => void;
}) {
  const [folderId, setFolderId] = useState<number | null>(null);
  const [breadcrumbs, setBreadcrumbs] = useState<{ id: number | null; name: string }[]>([{ id: null, name: "Файлы" }]);
  const [data, setData] = useState<StorageView>({ folders: [], files: [] });
  const [sharedLinks, setSharedLinks] = useState<PublicLink[]>([]);
  const [historyEvents, setHistoryEvents] = useState<FileEvent[]>([]);
  const [storageStats, setStorageStats] = useState<StorageStats | null>(null);
  const [adminUsers, setAdminUsers] = useState<AdminUser[]>([]);
  const [performanceReport, setPerformanceReport] = useState<PerformanceReport | null>(null);
  const [integrationClients, setIntegrationClients] = useState<IntegrationClient[]>([]);
  const [loadModelPoints, setLoadModelPoints] = useState<LoadModelPoint[]>([]);
  const [loadMinUsers, setLoadMinUsers] = useState(20);
  const [loadMaxUsers, setLoadMaxUsers] = useState(420);
  const [loadStepUsers, setLoadStepUsers] = useState(20);
  const [integrationClientName, setIntegrationClientName] = useState("external-server");
  const [integrationScopes, setIntegrationScopes] = useState<Set<string>>(() => new Set(["read", "write", "upload", "download", "search", "stats"]));
  const [createdApiKey, setCreatedApiKey] = useState("");
  const [activeSharedFileIds, setActiveSharedFileIds] = useState<Set<number>>(() => new Set());
  const [activeSharedFolderIds, setActiveSharedFolderIds] = useState<Set<number>>(() => new Set());
  const [newFolderName, setNewFolderName] = useState("");
  const [searchQuery, setSearchQuery] = useState("");
  const [sort, setSort] = useState("name");
  const [order, setOrder] = useState("asc");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [loading, setLoading] = useState(false);
  const [sharedUrl, setSharedUrl] = useState("");
  const [workspaceMode, setWorkspaceMode] = useState<WorkspaceMode>("drive");
  const [viewMode, setViewMode] = useState<ViewMode>(() =>
    localStorage.getItem(VIEW_KEY) === "list" ? "list" : "grid"
  );
  const [itemFilter, setItemFilter] = useState<ItemFilter>("all");
  const [selectedItem, setSelectedItem] = useState<string>("");
  const [selectedTrashIds, setSelectedTrashIds] = useState<Set<number>>(() => new Set());
  const [dragActive, setDragActive] = useState(false);
  const [dropTargetFolderId, setDropTargetFolderId] = useState<number | null>(null);
  const [dropTargetKey, setDropTargetKey] = useState<string | null>(null);
  const [previewFile, setPreviewFile] = useState<FileItem | null>(null);
  const [isSearchResult, setIsSearchResult] = useState(false);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [renameDialog, setRenameDialog] = useState<RenameDialogState | null>(null);
  const [moveDialog, setMoveDialog] = useState<MoveDialogState | null>(null);
  const [shareDialog, setShareDialog] = useState<ShareDialogState | null>(null);
  const [uploadQueue, setUploadQueue] = useState<UploadQueueItem[]>([]);
  const [uploadWidgetOpen, setUploadWidgetOpen] = useState(false);
  const [uploadingAll, setUploadingAll] = useState(false);

  useEffect(() => {
    localStorage.setItem(VIEW_KEY, viewMode);
  }, [viewMode]);

  function showNotice(message: string) {
    setNotice(message);
    window.setTimeout(() => setNotice(""), 2600);
  }

  async function loadStorage(currentFolderId: number | null = folderId) {
    setLoading(true);
    setError("");
    try {
      const response = await getFolderContent(currentFolderId, sort, order);
      setData(response);
      await refreshActiveSharedFiles();
      setIsSearchResult(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function refreshActiveSharedFiles(): Promise<PublicLink[]> {
    const links = await getPublicLinks();
    setActiveSharedFileIds(new Set(links.filter((link) => link.active && !link.expired && link.fileId != null).map((link) => link.fileId as number)));
    setActiveSharedFolderIds(new Set(links.filter((link) => link.active && !link.expired && link.folderId != null).map((link) => link.folderId as number)));
    return links;
  }

  async function loadWorkspace(mode: WorkspaceMode) {
    setLoading(true);
    setError("");
    try {
      if (mode === "drive") {
        await loadStorage(folderId);
        return;
      }
      if (mode === "trash") {
        const files = await getTrashFiles();
        setData({ folders: [], files });
      } else if (mode === "favorites") {
        const favorites = await getFavorites();
        setData(favorites);
      } else if (mode === "photos") {
        const files = await getFilesByGroup("photo");
        setData({ folders: [], files });
      } else if (mode === "uploads") {
        const files = await getRecentUploaded(30);
        setData({ folders: [], files });
      } else if (mode === "recentOpened") {
        const files = await getRecentOpened(30);
        setData({ folders: [], files });
      } else if (mode === "shared") {
        const links = await refreshActiveSharedFiles();
        setSharedLinks(links);
        setData({ folders: [], files: [] });
      } else if (mode === "history") {
        const events = await getHistory();
        setHistoryEvents(events);
        setData({ folders: [], files: [] });
      } else if (mode === "storage") {
        const stats = await getStorageStats();
        setStorageStats(stats);
        setData({ folders: [], files: [] });
      } else if (mode === "admin") {
        const users = await getAdminUsers();
        const report = await getPerformanceReport();
        setAdminUsers(users);
        setPerformanceReport(report);
        setData({ folders: [], files: [] });
      } else if (mode === "api") {
        const clients = await listIntegrationClients();
        setIntegrationClients(clients);
        setData({ folders: [], files: [] });
      } else {
        setData({ folders: [], files: [] });
      }
      if (mode !== "shared") {
        setSharedLinks([]);
        await refreshActiveSharedFiles();
      }
      if (mode !== "history") {
        setHistoryEvents([]);
      }
      if (mode !== "storage") {
        setStorageStats(null);
      }
      if (mode !== "admin") {
        setAdminUsers([]);
        setPerformanceReport(null);
      }
      if (mode !== "api") {
        setIntegrationClients([]);
        setCreatedApiKey("");
      }
      setIsSearchResult(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    loadWorkspace(workspaceMode);
  }, [workspaceMode, folderId, sort, order]);

  useEffect(() => {
    if (workspaceMode !== "drive") {
      setItemFilter("all");
    }
    if (workspaceMode !== "trash") {
      setSelectedTrashIds(new Set());
    }
  }, [workspaceMode]);

  useEffect(() => {
    if (workspaceMode === "admin" && loadModelPoints.length === 0) {
      setLoadModelPoints(buildLoadModel(loadMinUsers, loadMaxUsers, loadStepUsers));
    }
  }, [workspaceMode]);

  const visibleFolders = useMemo(() => {
    if (workspaceMode !== "drive" && workspaceMode !== "favorites") return [];
    if (itemFilter === "files") return [];
    return data.folders;
  }, [data.folders, itemFilter, workspaceMode]);

  const visibleFiles = useMemo(() => {
    if (workspaceMode !== "drive") return data.files;
    if (itemFilter === "folders") return [];
    return data.files;
  }, [data.files, itemFilter, workspaceMode]);

  const indexingFileIds = useMemo(
    () => visibleFiles
      .filter((file) => file.index && (file.index.status === "PENDING" || file.index.status === "INDEXING"))
      .map((file) => file.id),
    [visibleFiles]
  );
  const indexingCount = indexingFileIds.length;

  useEffect(() => {
    if (indexingFileIds.length === 0) return;
    let cancelled = false;
    const refreshIndexStatuses = async () => {
      const statuses = await Promise.all(
        indexingFileIds.map((id) => getFileIndexStatus(id).catch(() => null))
      );
      if (cancelled) return;
      setData((current) => ({
        ...current,
        files: current.files.map((file) => {
          const status = statuses.find((item) => item?.fileId === file.id);
          return status ? { ...file, index: statusToIndex(status) } : file;
        })
      }));
    };
    refreshIndexStatuses();
    const timer = window.setInterval(refreshIndexStatuses, 3000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [indexingFileIds.join(",")]);

  const currentTotalSize = useMemo(
    () => visibleFiles.reduce((sum, file) => sum + file.sizeBytes, 0),
    [visibleFiles]
  );

  const selectedTrashCount = selectedTrashIds.size;
  const allTrashSelected = workspaceMode === "trash" && visibleFiles.length > 0 && visibleFiles.every((file) => selectedTrashIds.has(file.id));

  async function handleCreateFolder(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!newFolderName.trim()) return;
    try {
      await createFolder({ name: newFolderName.trim(), parentId: folderId });
      setNewFolderName("");
      await loadStorage(folderId);
      showNotice("Папка создана");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function enqueueFiles(files: File[]) {
    if (!files.length) return;
    setUploadQueue((current) => {
      const freeSlots = Math.max(0, MAX_UPLOAD_QUEUE - current.length);
      if (freeSlots <= 0) {
        showNotice(`Можно добавить максимум ${MAX_UPLOAD_QUEUE} файлов`);
        return current;
      }
      const accepted = files.slice(0, freeSlots).map((file, index) => ({
        id: `${Date.now()}-${index}-${Math.random().toString(16).slice(2)}`,
        file,
        progress: 0,
        status: "pending" as UploadQueueItemStatus
      }));
      if (accepted.length < files.length) {
        showNotice(`Добавлено ${accepted.length} из ${files.length}. Лимит: ${MAX_UPLOAD_QUEUE}`);
      } else {
        showNotice(`Файлов в очереди: ${current.length + accepted.length}`);
      }
      return [...current, ...accepted];
    });
    setUploadWidgetOpen(true);
  }

  function removeUploadQueueItem(itemId: string) {
    setUploadQueue((current) => current.filter((item) => item.id !== itemId));
  }

  async function sendAllUploads() {
    if (uploadingAll) return;
    const pendingItems = uploadQueue.filter((item) => item.status === "pending" || item.status === "error");
    if (!pendingItems.length) return;
    setError("");
    setUploadingAll(true);
    let successCount = 0;
    let failCount = 0;
    const uploaded: FileItem[] = [];

    for (const item of pendingItems) {
      setUploadQueue((current) => current.map((entry) => (
        entry.id === item.id
          ? { ...entry, status: "uploading", progress: Math.max(1, entry.progress), error: undefined }
          : entry
      )));
      try {
        const uploadedFile = await uploadFile(folderId, item.file, (percent) => {
          setUploadQueue((current) => current.map((entry) => (
            entry.id === item.id ? { ...entry, progress: percent } : entry
          )));
        });
        uploaded.push(uploadedFile);
        successCount += 1;
        setUploadQueue((current) => current.map((entry) => (
          entry.id === item.id ? { ...entry, status: "uploaded", progress: 100 } : entry
        )));
      } catch (e) {
        failCount += 1;
        setUploadQueue((current) => current.map((entry) => (
          entry.id === item.id
            ? { ...entry, status: "error", error: (e as Error).message }
            : entry
        )));
      }
    }

    await loadWorkspace(workspaceMode);
    setUploadingAll(false);

    const hasIndexing = uploaded.some((file) => file.index?.status === "PENDING" || file.index?.status === "INDEXING");
    if (successCount > 0 && failCount === 0) {
      showNotice(hasIndexing ? `Загружено ${successCount}. Индексация запущена` : `Загружено ${successCount}`);
      return;
    }
    if (successCount > 0 && failCount > 0) {
      showNotice(`Загружено ${successCount}, с ошибкой ${failCount}`);
      return;
    }
    showNotice(`Не удалось загрузить ${failCount} файлов`);
  }

  async function handleUpload(event: React.ChangeEvent<HTMLInputElement>) {
    const files = event.target.files ? Array.from(event.target.files) : [];
    enqueueFiles(files);
    event.target.value = "";
  }

  async function handleFolderUpload(event: React.ChangeEvent<HTMLInputElement>) {
    const files = event.target.files ? Array.from(event.target.files) : [];
    const relativePaths = files.map((file) => {
      const withPath = file as File & { webkitRelativePath?: string };
      return withPath.webkitRelativePath || file.name;
    });
    event.target.value = "";
    if (!files.length) return;
    setError("");
    setLoading(true);
    try {
      const uploaded = await uploadFolder(folderId, files, relativePaths);
      await loadWorkspace(workspaceMode);
      const hasIndexing = uploaded.some((file) => file.index?.status === "PENDING" || file.index?.status === "INDEXING");
      showNotice(hasIndexing ? `Папка загружена, файлов: ${files.length}. Индексация запущена` : `Папка загружена, файлов: ${files.length}`);
    } catch (e) {
      setError((e as Error).message);
      setLoading(false);
    }
  }

  function switchMode(mode: WorkspaceMode) {
    setWorkspaceMode(mode);
    setSelectedItem("");
    setSearchQuery("");
  }

  function openFolder(folder: FolderItem) {
    if (folder.id == null) return;
    if (workspaceMode !== "drive") {
      setWorkspaceMode("drive");
      setBreadcrumbs([{ id: null, name: "Файлы" }, { id: folder.id, name: folder.name }]);
    }
    setFolderId(folder.id);
    setSelectedItem(`folder-${folder.id}`);
    if (workspaceMode === "drive") {
      setBreadcrumbs((prev) => [...prev, { id: folder.id, name: folder.name }]);
    }
    setSearchQuery("");
  }

  function jumpToCrumb(index: number) {
    if (workspaceMode !== "drive") return;
    const selected = breadcrumbs[index];
    setBreadcrumbs((prev) => prev.slice(0, index + 1));
    setFolderId(selected.id);
    setSelectedItem("");
    setSearchQuery("");
  }

  function goBackFolder() {
    if (workspaceMode !== "drive" || breadcrumbs.length <= 1) return;
    const nextIndex = breadcrumbs.length - 2;
    const previous = breadcrumbs[nextIndex];
    setBreadcrumbs((prev) => prev.slice(0, nextIndex + 1));
    setFolderId(previous.id);
    setSelectedItem("");
    setSearchQuery("");
  }

  function startItemDrag(event: React.DragEvent<HTMLElement>, item: DraggedItem) {
    if (workspaceMode !== "drive") return;
    event.dataTransfer.effectAllowed = "move";
    event.dataTransfer.setData(DRAG_MIME, JSON.stringify(item));
    setDragActive(false);
  }

  async function moveDraggedItemToFolder(event: React.DragEvent<HTMLElement>, targetFolderId: number | null) {
    event.preventDefault();
    event.stopPropagation();
    setDropTargetFolderId(null);
    setDropTargetKey(null);
    const payload = event.dataTransfer.getData(DRAG_MIME);
    if (!payload || workspaceMode !== "drive") return;
    try {
      const item = JSON.parse(payload) as DraggedItem;
      if (item.type === "file") {
        await moveFile(item.id, { targetFolderId });
      } else {
        if (item.id === targetFolderId) return;
        await moveFolder(item.id, { targetFolderId });
      }
      await loadStorage(folderId);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function markMoveTarget(event: React.DragEvent<HTMLElement>, targetKey: string, folderTargetId?: number | null) {
    if (!event.dataTransfer.types.includes(DRAG_MIME) || workspaceMode !== "drive") return;
    event.preventDefault();
    event.stopPropagation();
    event.dataTransfer.dropEffect = "move";
    setDropTargetKey(targetKey);
    setDropTargetFolderId(folderTargetId === undefined ? null : folderTargetId);
  }

  async function handleDeleteFolder(targetId: number | null) {
    if (targetId == null) return;
    try {
      await deleteFolder(targetId);
      await loadStorage(folderId);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleDeleteFile(file: FileItem) {
    try {
      if (workspaceMode === "trash") {
        await deleteFile(file.id);
        setSelectedTrashIds((prev) => {
          const next = new Set(prev);
          next.delete(file.id);
          return next;
        });
      } else {
        await moveFileToTrash(file.id, 30);
      }
      await loadWorkspace(workspaceMode);
      showNotice(workspaceMode === "trash" ? "Файл удален окончательно" : "Файл перемещен в корзину");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleToggleFolderFavorite(folder: FolderItem) {
    if (folder.id == null) return;
    try {
      await setFolderFavorite(folder.id, !folder.favorite);
      await loadWorkspace(workspaceMode);
      showNotice(folder.favorite ? "Папка удалена из избранного" : "Папка добавлена в избранное");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleToggleFileFavorite(file: FileItem) {
    try {
      await setFileFavorite(file.id, !file.favorite);
      await loadWorkspace(workspaceMode);
      showNotice(file.favorite ? "Файл удален из избранного" : "Файл добавлен в избранное");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleToggleAdminUser(target: AdminUser) {
    try {
      const updated = await updateAdminUser(target.id, { enabled: !target.enabled });
      setAdminUsers((current) => current.map((item) => item.id === updated.id ? updated : item));
      showNotice(updated.enabled ? "Пользователь разблокирован" : "Пользователь заблокирован");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleQuotaChange(target: AdminUser, value: string) {
    const quotaGb = Number(value);
    if (!Number.isFinite(quotaGb) || quotaGb < 0) return;
    try {
      const updated = await updateAdminUser(target.id, { storageQuotaBytes: Math.round(quotaGb * 1024 * 1024 * 1024) });
      setAdminUsers((current) => current.map((item) => item.id === updated.id ? updated : item));
      showNotice("Квота обновлена");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleRestoreFile(file: FileItem) {
    try {
      await restoreFileFromTrash(file.id);
      setSelectedTrashIds((prev) => {
        const next = new Set(prev);
        next.delete(file.id);
        return next;
      });
      await loadWorkspace(workspaceMode);
      showNotice("Файл восстановлен");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function toggleTrashSelection(fileId: number) {
    setSelectedTrashIds((prev) => {
      const next = new Set(prev);
      if (next.has(fileId)) {
        next.delete(fileId);
      } else {
        next.add(fileId);
      }
      return next;
    });
  }

  function toggleAllTrashSelection() {
    if (allTrashSelected) {
      setSelectedTrashIds(new Set());
    } else {
      setSelectedTrashIds(new Set(visibleFiles.map((file) => file.id)));
    }
  }

  async function handleRestoreSelectedTrash() {
    if (selectedTrashIds.size === 0) return;
    try {
      await restoreFilesFromTrash(Array.from(selectedTrashIds));
      setSelectedTrashIds(new Set());
      await loadWorkspace("trash");
      showNotice("Выбранные файлы восстановлены");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleDeleteSelectedTrash() {
    if (selectedTrashIds.size === 0) return;
    try {
      await deleteTrashFiles(Array.from(selectedTrashIds));
      setSelectedTrashIds(new Set());
      await loadWorkspace("trash");
      showNotice("Выбранные файлы удалены");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleDownload(file: FileItem) {
    try {
      await downloadFile(file.id, file.name);
      if (workspaceMode === "recentOpened") {
        await loadWorkspace("recentOpened");
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function openRenameFolder(folder: FolderItem) {
    if (folder.id == null) return;
    setRenameDialog({ type: "folder", item: folder, value: folder.name });
  }

  function openRenameFile(file: FileItem) {
    setRenameDialog({ type: "file", item: file, value: file.name });
  }

  async function submitRename(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!renameDialog) return;
    const name = renameDialog.value.trim();
    if (!name || name === renameDialog.item.name) {
      setRenameDialog(null);
      return;
    }
    try {
      if (renameDialog.type === "folder") {
        if (renameDialog.item.id == null) return;
        await renameFolder(renameDialog.item.id, { name });
        await loadStorage(folderId);
        showNotice("Папка переименована");
      } else {
        await renameFile(renameDialog.item.id, { name });
        await loadWorkspace(workspaceMode);
        showNotice("Файл переименован");
      }
      setRenameDialog(null);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleShareFolder(folder: FolderItem) {
    if (folder.id == null) return;
    try {
      const links = await refreshActiveSharedFiles();
      const existing = links.find((link) => link.active && !link.expired && link.folderId === folder.id) || null;
      setShareDialog({
        type: "folder",
        item: folder,
        expiresInDays: null,
        link: existing
      });
      if (existing) {
        setSharedUrl(existing.publicUrl);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function submitShareDialog(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!shareDialog) return;
    try {
      const payload = {
        expiresInDays: shareDialog.expiresInDays,
        ...(shareDialog.password !== undefined ? { password: shareDialog.password } : {})
      };
      const link = shareDialog.type === "folder"
        ? await createPublicFolderLink(shareDialog.item.id as number, payload)
        : await createPublicLink(shareDialog.item.id, payload);
      setSharedUrl(link.publicUrl);
      if (shareDialog.type === "folder") {
        setActiveSharedFolderIds((prev) => new Set(prev).add(shareDialog.item.id as number));
      } else {
        setActiveSharedFileIds((prev) => new Set(prev).add(shareDialog.item.id));
      }
      setShareDialog({ ...shareDialog, link });
      if (workspaceMode === "shared") {
        await loadWorkspace("shared");
      }
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(link.publicUrl);
      }
      showNotice("Публичная ссылка скопирована");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function openMoveFile(file: FileItem) {
    setMoveDialog({ type: "file", item: file, targetFolderId: file.folderId });
  }

  function openMoveFolder(folder: FolderItem) {
    if (folder.id == null) return;
    setMoveDialog({ type: "folder", item: folder, targetFolderId: folder.parentId });
  }

  async function submitMove(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!moveDialog) return;
    try {
      if (moveDialog.type === "file") {
        await moveFile(moveDialog.item.id, { targetFolderId: moveDialog.targetFolderId });
      } else {
        if (moveDialog.item.id === moveDialog.targetFolderId) return;
        await moveFolder(moveDialog.item.id as number, { targetFolderId: moveDialog.targetFolderId });
      }
      setMoveDialog(null);
      await loadStorage(folderId);
      showNotice("Объект перемещен");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleShare(file: FileItem) {
    try {
      const links = await refreshActiveSharedFiles();
      const existing = links.find((link) => link.active && !link.expired && link.fileId === file.id) || null;
      setShareDialog({
        type: "file",
        item: file,
        expiresInDays: null,
        link: existing
      });
      if (existing) {
        setSharedUrl(existing.publicUrl);
      }
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleCopyPublicLink(url: string) {
    try {
      if (navigator.clipboard) {
        await navigator.clipboard.writeText(url);
      }
      setSharedUrl(url);
      showNotice("Ссылка скопирована");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleDisablePublicLink(link: PublicLink) {
    try {
      await disablePublicLink(link.id);
      setActiveSharedFileIds((prev) => {
        const next = new Set(prev);
        if (link.fileId != null) {
          next.delete(link.fileId);
        }
        return next;
      });
      setActiveSharedFolderIds((prev) => {
        const next = new Set(prev);
        if (link.folderId != null) {
          next.delete(link.folderId);
        }
        return next;
      });
      if (workspaceMode === "shared") {
        await loadWorkspace("shared");
      } else {
        await refreshActiveSharedFiles();
      }
      setShareDialog((current) => current?.link?.id === link.id ? { ...current, link: { ...link, active: false } } : current);
      showNotice("Публичная ссылка отключена");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function folderShareActionTitle(folder: FolderItem): string {
    return folder.id != null && activeSharedFolderIds.has(folder.id) ? "Совместный доступ" : "Поделиться";
  }

  function fileShareActionTitle(file: FileItem): string {
    return activeSharedFileIds.has(file.id) ? "Совместный доступ" : "Поделиться";
  }

  async function handleSearch() {
    if (!searchQuery.trim()) {
      await loadWorkspace(workspaceMode);
      return;
    }
    setLoading(true);
    setError("");
    try {
      const result = await search(searchQuery);
      setData({
        folders: result.filter((item) => item.type === "folder").map((f) => ({
          id: f.id,
          name: f.name,
          parentId: f.parentId,
          favorite: false
        })),
        files: result
          .filter((item) => item.type === "file")
          .map((f) => ({
            id: f.id,
            name: f.name,
            folderId: f.parentId,
            sizeBytes: f.sizeBytes || 0,
            contentType: f.contentType || "application/octet-stream",
            extension: f.extension,
            fileGroup: f.fileGroup,
            hasThumbnail: f.hasThumbnail,
            uploadedAt: f.createdAt,
            matchType: f.matchType,
            score: f.score,
            favorite: false
          }))
      });
      setWorkspaceMode("drive");
      setIsSearchResult(true);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }

  async function handleReindex() {
    setError("");
    try {
      const scheduled = await reindexCurrentUserFiles();
      showNotice(`Файлов отправлено на переиндексацию: ${scheduled}`);
      await loadWorkspace(workspaceMode);
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function toggleIntegrationScope(scope: string) {
    setIntegrationScopes((current) => {
      const next = new Set(current);
      if (next.has(scope)) {
        next.delete(scope);
      } else {
        next.add(scope);
      }
      return next;
    });
  }

  async function handleCreateIntegrationClient(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const name = integrationClientName.trim();
    if (!name) return;
    if (integrationScopes.size === 0) {
      setError("Выберите хотя бы один scope");
      return;
    }
    try {
      const created = await createIntegrationClient({ name, scopes: Array.from(integrationScopes) });
      setCreatedApiKey(created.apiKey);
      const clients = await listIntegrationClients();
      setIntegrationClients(clients);
      showNotice("Integration API key создан");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  async function handleRevokeIntegrationClient(clientId: number) {
    try {
      await revokeIntegrationClient(clientId);
      setIntegrationClients((current) => current.filter((client) => client.id !== clientId));
      showNotice("Ключ API отозван");
    } catch (e) {
      setError((e as Error).message);
    }
  }

  function clearSearch() {
    setSearchQuery("");
    loadWorkspace(workspaceMode);
  }

  function modelNoise(seed: number, amp: number): number {
    return Math.sin(seed * 1.73) * amp + Math.cos(seed * 0.61) * amp * 0.45;
  }

  function buildLoadModel(minUsers: number, maxUsers: number, stepUsers: number): LoadModelPoint[] {
    const points: LoadModelPoint[] = [];
    const min = Math.max(1, Math.min(minUsers, maxUsers));
    const max = Math.max(minUsers, maxUsers);
    const step = Math.max(1, stepUsers);
    const stableCapacity = 260;
    for (let users = min; users <= max; users += step) {
      const pressure = users / stableCapacity;
      const rpsBase = users * 2.75;
      const rpsPenalty = pressure <= 1 ? 1 : Math.max(0.45, 1 - (pressure - 1) * 0.38);
      const rps = Math.max(8, rpsBase * rpsPenalty + modelNoise(users, 8));

      const p95Base = 165 + users * 1.45;
      const p95Penalty = pressure <= 1 ? 0 : Math.pow(pressure - 1, 1.8) * 520;
      const p95 = Math.max(90, p95Base + p95Penalty + modelNoise(users, 22));

      const errorRate = Math.max(0.05, (pressure <= 1 ? 0.12 + pressure * 0.38 : 0.8 + Math.pow(pressure - 1, 1.55) * 7.2) + modelNoise(users, 0.22));
      points.push({
        users,
        rps: Number(rps.toFixed(2)),
        p95: Number(p95.toFixed(2)),
        errorRate: Number(Math.max(0.01, errorRate).toFixed(2))
      });
    }
    return points;
  }

  function runLoadModeling() {
    setLoadModelPoints(buildLoadModel(loadMinUsers, loadMaxUsers, loadStepUsers));
  }

  function slugifyChartTitle(title: string): string {
    return title.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/(^-|-$)/g, "");
  }

  function downloadSvgAsJpeg(svgId: string, filenameBase: string) {
    const svg = document.getElementById(svgId) as SVGSVGElement | null;
    if (!svg) {
      setError("Не удалось найти график для экспорта.");
      return;
    }
    const serializer = new XMLSerializer();
    const svgString = serializer.serializeToString(svg);
    const svgBlob = new Blob([svgString], { type: "image/svg+xml;charset=utf-8" });
    const url = URL.createObjectURL(svgBlob);
    const img = new Image();
    img.onload = () => {
      const viewBox = svg.viewBox.baseVal;
      const baseWidth = Math.max(1, Math.round(viewBox && viewBox.width ? viewBox.width : svg.clientWidth || 800));
      const baseHeight = Math.max(1, Math.round(viewBox && viewBox.height ? viewBox.height : svg.clientHeight || 400));
      const isSmallChart = baseWidth <= 600 || baseHeight <= 260;
      const scale = isSmallChart ? 3 : 2;
      const width = baseWidth * scale;
      const height = baseHeight * scale;
      const canvas = document.createElement("canvas");
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext("2d");
      if (!ctx) {
        URL.revokeObjectURL(url);
        setError("Не удалось создать canvas для экспорта графика.");
        return;
      }
      ctx.fillStyle = "#ffffff";
      ctx.fillRect(0, 0, width, height);
      ctx.drawImage(img, 0, 0, width, height);
      const jpegUrl = canvas.toDataURL("image/jpeg", 0.92);
      const anchor = document.createElement("a");
      anchor.href = jpegUrl;
      anchor.download = `${filenameBase}-${new Date().toISOString().slice(0, 10)}.jpg`;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      URL.revokeObjectURL(url);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      setError("Ошибка при генерации JPEG из графика.");
    };
    img.src = url;
  }

  function renderModelLineChart() {
    if (!loadModelPoints.length) return null;
    const width = 760;
    const height = 280;
    const padLeft = 54;
    const padRight = 24;
    const padTop = 32;
    const padBottom = 42;
    const plotWidth = width - padLeft - padRight;
    const plotHeight = height - padTop - padBottom;

    const usersMin = Math.min(...loadModelPoints.map((p) => p.users));
    const usersMax = Math.max(...loadModelPoints.map((p) => p.users));
    const p95Min = Math.min(...loadModelPoints.map((p) => p.p95));
    const p95Max = Math.max(...loadModelPoints.map((p) => p.p95));
    const rpsMin = Math.min(...loadModelPoints.map((p) => p.rps));
    const rpsMax = Math.max(...loadModelPoints.map((p) => p.rps));

    const toX = (users: number) => padLeft + ((users - usersMin) / Math.max(1, usersMax - usersMin)) * plotWidth;
    const toYLeft = (v: number) => padTop + (1 - (v - p95Min) / Math.max(1, p95Max - p95Min)) * plotHeight;
    const toYRight = (v: number) => padTop + (1 - (v - rpsMin) / Math.max(1, rpsMax - rpsMin)) * plotHeight;
    const leftTicks = [0, 0.25, 0.5, 0.75, 1].map((t) => Number((p95Min + (p95Max - p95Min) * t).toFixed(0)));
    const rightTicks = [0, 0.25, 0.5, 0.75, 1].map((t) => Number((rpsMin + (rpsMax - rpsMin) * t).toFixed(0)));

    const latencyPath = loadModelPoints.map((p) => `${toX(p.users)},${toYLeft(p.p95)}`).join(" ");
    const rpsPath = loadModelPoints.map((p) => `${toX(p.users)},${toYRight(p.rps)}`).join(" ");

    return (
      <article>
        <div className="chart-title-row">
          <h3>Пользователи vs P95 + RPS</h3>
          <button type="button" className="ghost-btn" onClick={() => downloadSvgAsJpeg("model-line-chart", "users-vs-p95-rps")}>Скачать JPEG</button>
        </div>
        <svg id="model-line-chart" viewBox={`0 0 ${width} ${height}`} className="model-chart" role="img" aria-label="Модель нагрузки: users, p95, rps">
          <line x1={padLeft} y1={padTop} x2={padLeft} y2={height - padBottom} stroke="#cfd6e1" />
          <line x1={padLeft} y1={height - padBottom} x2={width - padRight} y2={height - padBottom} stroke="#cfd6e1" />
          <line x1={width - padRight} y1={padTop} x2={width - padRight} y2={height - padBottom} stroke="#cfd6e1" />
          {leftTicks.map((tick, index) => {
            const y = toYLeft(tick);
            return (
              <g key={`left-tick-${index}`}>
                <line x1={padLeft - 4} y1={y} x2={padLeft} y2={y} stroke="#98a2b3" />
                <text x={padLeft - 8} y={y + 4} textAnchor="end" className="chart-axis-label">{tick}</text>
              </g>
            );
          })}
          {rightTicks.map((tick, index) => {
            const y = toYRight(tick);
            return (
              <g key={`right-tick-${index}`}>
                <line x1={width - padRight} y1={y} x2={width - padRight + 4} y2={y} stroke="#98a2b3" />
                <text x={width - padRight + 8} y={y + 4} textAnchor="start" className="chart-axis-label">{tick}</text>
              </g>
            );
          })}
          <polyline points={latencyPath} fill="none" stroke="#ef4444" strokeWidth="3" />
          <polyline points={rpsPath} fill="none" stroke="#f9a8d4" strokeWidth="3" />
          <line x1={padLeft + 8} y1={18} x2={padLeft + 32} y2={18} stroke="#ef4444" strokeWidth="3" />
          <text x={padLeft + 38} y={21} className="chart-point-label">P95 (красная линия)</text>
          <line x1={padLeft + 212} y1={18} x2={padLeft + 236} y2={18} stroke="#f9a8d4" strokeWidth="3" />
          <text x={padLeft + 242} y={21} className="chart-point-label">RPS (розовая линия)</text>
          {loadModelPoints.map((p) => (
            <g key={`m-${p.users}`}>
              <circle cx={toX(p.users)} cy={toYLeft(p.p95)} r="3.2" fill="#ef4444" />
              <circle cx={toX(p.users)} cy={toYRight(p.rps)} r="3.2" fill="#f9a8d4" />
              <text x={toX(p.users)} y={height - 18} textAnchor="middle" className="chart-axis-label">{p.users}</text>
            </g>
          ))}
          <text x={16} y={18} className="chart-point-label">P95, мс</text>
          <text x={width - 74} y={18} className="chart-point-label">RPS</text>
          <text x={width / 2} y={height - 4} textAnchor="middle" className="chart-axis-label">Пользователи</text>
        </svg>
      </article>
    );
  }

  function renderModelErrorChart() {
    if (!loadModelPoints.length) return null;
    const width = 760;
    const height = 260;
    const padLeft = 54;
    const padRight = 24;
    const padTop = 28;
    const padBottom = 42;
    const plotWidth = width - padLeft - padRight;
    const plotHeight = height - padTop - padBottom;

    const usersMin = Math.min(...loadModelPoints.map((p) => p.users));
    const usersMax = Math.max(...loadModelPoints.map((p) => p.users));
    const errMin = Math.min(...loadModelPoints.map((p) => p.errorRate));
    const errMax = Math.max(...loadModelPoints.map((p) => p.errorRate));

    const toX = (users: number) => padLeft + ((users - usersMin) / Math.max(1, usersMax - usersMin)) * plotWidth;
    const toY = (err: number) => padTop + (1 - (err - errMin) / Math.max(1, errMax - errMin)) * plotHeight;
    const errorPath = loadModelPoints.map((p) => `${toX(p.users)},${toY(p.errorRate)}`).join(" ");
    const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => Number((errMin + (errMax - errMin) * t).toFixed(2)));

    return (
      <article>
        <div className="chart-title-row">
          <h3>Пользователи vs Ошибки</h3>
          <button type="button" className="ghost-btn" onClick={() => downloadSvgAsJpeg("model-error-chart", "users-vs-error-rate")}>Скачать JPEG</button>
        </div>
        <svg id="model-error-chart" viewBox={`0 0 ${width} ${height}`} className="model-chart" role="img" aria-label="Пользователи против процента ошибок">
          <line x1={padLeft} y1={padTop} x2={padLeft} y2={height - padBottom} stroke="#cfd6e1" />
          <line x1={padLeft} y1={height - padBottom} x2={width - padRight} y2={height - padBottom} stroke="#cfd6e1" />
          {ticks.map((tick, index) => {
            const y = toY(tick);
            return (
              <g key={`err-tick-${index}`}>
                <line x1={padLeft - 4} y1={y} x2={padLeft} y2={y} stroke="#98a2b3" />
                <text x={padLeft - 8} y={y + 4} textAnchor="end" className="chart-axis-label">{tick}%</text>
              </g>
            );
          })}
          <polyline points={errorPath} fill="none" stroke="#dc2626" strokeWidth="3" />
          {loadModelPoints.map((p) => (
            <g key={`err-${p.users}`}>
              <circle cx={toX(p.users)} cy={toY(p.errorRate)} r="3.4" fill="#dc2626" />
              <text x={toX(p.users)} y={height - 18} textAnchor="middle" className="chart-axis-label">{p.users}</text>
              <text x={toX(p.users)} y={toY(p.errorRate) - 8} textAnchor="middle" className="chart-point-label">
                {p.errorRate}%
              </text>
            </g>
          ))}
          <text x={16} y={18} className="chart-point-label">Ошибки, %</text>
          <text x={width / 2} y={height - 4} textAnchor="middle" className="chart-axis-label">Пользователи</text>
        </svg>
      </article>
    );
  }

  function renderUsersMetricChart(
    title: string,
    unit: string,
    color: string,
    extract: (scenario: PerformanceReport["scenarios"][number]) => number
  ) {
    if (!performanceReport || performanceReport.scenarios.length === 0) return null;
    const scenarios = [...performanceReport.scenarios].sort((a, b) => a.users - b.users);
    const usersMin = Math.min(...scenarios.map((s) => s.users));
    const usersMax = Math.max(...scenarios.map((s) => s.users));
    const values = scenarios.map(extract);
    const valueMin = Math.min(...values);
    const valueMax = Math.max(...values);

    const width = 520;
    const height = 220;
    const padLeft = 52;
    const padRight = 16;
    const padTop = 28;
    const padBottom = 36;
    const plotWidth = width - padLeft - padRight;
    const plotHeight = height - padTop - padBottom;

    const toX = (users: number) => {
      if (usersMax === usersMin) return padLeft + plotWidth / 2;
      return padLeft + ((users - usersMin) / (usersMax - usersMin)) * plotWidth;
    };
    const toY = (value: number) => {
      if (valueMax === valueMin) return padTop + plotHeight / 2;
      return padTop + (1 - (value - valueMin) / (valueMax - valueMin)) * plotHeight;
    };
    const ticks = [0, 0.25, 0.5, 0.75, 1].map((t) => Number((valueMin + (valueMax - valueMin) * t).toFixed(2)));

    const points = scenarios.map((s) => `${toX(s.users)},${toY(extract(s))}`).join(" ");

    const chartId = `users-metric-${slugifyChartTitle(title)}`;
    return (
      <article>
        <div className="chart-title-row">
          <h3>{title}</h3>
          <button type="button" className="ghost-btn" onClick={() => downloadSvgAsJpeg(chartId, slugifyChartTitle(title))}>Скачать JPEG</button>
        </div>
        <svg id={chartId} viewBox={`0 0 ${width} ${height}`} className="metric-chart" role="img" aria-label={title}>
          <line x1={padLeft} y1={padTop} x2={padLeft} y2={height - padBottom} stroke="#cfd6e1" />
          <line x1={padLeft} y1={height - padBottom} x2={width - padRight} y2={height - padBottom} stroke="#cfd6e1" />
          {ticks.map((tick, index) => {
            const y = toY(tick);
            return (
              <g key={`${title}-tick-${index}`}>
                <line x1={padLeft - 4} y1={y} x2={padLeft} y2={y} stroke="#98a2b3" />
                <text x={padLeft - 8} y={y + 4} textAnchor="end" className="chart-axis-label">{tick}</text>
              </g>
            );
          })}
          <polyline points={points} fill="none" stroke={color} strokeWidth="3" />
          {scenarios.map((s) => (
            <g key={`${title}-${s.scenario}`}>
              <circle cx={toX(s.users)} cy={toY(extract(s))} r="4" fill={color} />
              <text x={toX(s.users)} y={height - 14} textAnchor="middle" className="chart-axis-label">{s.users}</text>
              <text x={toX(s.users)} y={toY(extract(s)) - 8} textAnchor="middle" className="chart-point-label">
                {extract(s)} {unit}
              </text>
            </g>
          ))}
          <text x={16} y={20} className="chart-point-label">{unit}</text>
          <text x={width / 2} y={height - 2} textAnchor="middle" className="chart-axis-label">Пользователи</text>
        </svg>
      </article>
    );
  }

  function renderTimelineBars(
    title: string,
    timeline: { label: string; value: number }[],
    unit: string,
    throughput = false
  ) {
    if (!timeline.length) return null;
    const max = Math.max(...timeline.map((item) => item.value), 1);
    const chartId = `timeline-${slugifyChartTitle(title)}`;
    const width = 520;
    const rowHeight = 30;
    const topPad = 14;
    const bottomPad = 14;
    const labelWidth = 52;
    const trackX = 76;
    const valueX = width - 8;
    const trackWidth = valueX - trackX - 120;
    const height = topPad + bottomPad + timeline.length * rowHeight;
    return (
      <article>
        <div className="chart-title-row">
          <h3>{title}</h3>
          <button type="button" className="ghost-btn" onClick={() => downloadSvgAsJpeg(chartId, slugifyChartTitle(title))}>Скачать JPEG</button>
        </div>
        <svg id={chartId} viewBox={`0 0 ${width} ${height}`} className="metric-chart" role="img" aria-label={title}>
          {timeline.map((point, index) => {
            const y = topPad + rowHeight * index + 9;
            const barWidth = Math.max(8, (Math.max(0, point.value) / max) * trackWidth);
            const barColor = throughput ? "url(#throughputGradient)" : "url(#timelineGradient)";
            return (
              <g key={`${title}-${point.label}`}>
                <text x={12} y={y + 6} className="chart-axis-label">{point.label}</text>
                <rect x={trackX} y={y - 2} width={trackWidth} height="12" rx="6" fill="#e5eaf1" />
                <rect x={trackX} y={y - 2} width={barWidth} height="12" rx="6" fill={barColor} />
                <text x={valueX} y={y + 6} textAnchor="end" className="chart-point-label">{point.value} {unit}</text>
              </g>
            );
          })}
          <defs>
            <linearGradient id="timelineGradient" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#f59e0b" />
              <stop offset="100%" stopColor="#ef4444" />
            </linearGradient>
            <linearGradient id="throughputGradient" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stopColor="#2563eb" />
              <stop offset="100%" stopColor="#059669" />
            </linearGradient>
          </defs>
        </svg>
      </article>
    );
  }

  function downloadTextFile(filename: string, content: string, mimeType: string) {
    const blob = new Blob([content], { type: mimeType });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    URL.revokeObjectURL(url);
  }

  function exportPerformanceReportJson() {
    if (!performanceReport) return;
    downloadTextFile(
      `performance-report-${new Date().toISOString().slice(0, 10)}.json`,
      JSON.stringify(performanceReport, null, 2),
      "application/json"
    );
  }

  function exportPerformanceReportCsv() {
    if (!performanceReport) return;
    const header = "scenario,users,rps,p50_ms,p95_ms,p99_ms,error_rate_percent";
    const rows = performanceReport.scenarios.map((s) => (
      `${s.scenario},${s.users},${s.rps},${s.p50Ms},${s.p95Ms},${s.p99Ms},${s.errorRatePercent}`
    ));
    downloadTextFile(
      `performance-scenarios-${new Date().toISOString().slice(0, 10)}.csv`,
      [header, ...rows].join("\n"),
      "text/csv;charset=utf-8"
    );
  }

  function exportPerformanceReportMarkdown() {
    if (!performanceReport) return;
    const lines: string[] = [];
    lines.push("# Отчет по работоспособности системы");
    lines.push(`- Дата: ${formatDate(performanceReport.generatedAt)}`);
    lines.push(`- Окно: ${performanceReport.testWindow}`);
    lines.push("");
    lines.push("## KPI");
    lines.push(`- Availability: ${performanceReport.quality.availabilityPercent}%`);
    lines.push(`- Search precision: ${performanceReport.quality.searchPrecisionPercent}%`);
    lines.push(`- Search recall: ${performanceReport.quality.searchRecallPercent}%`);
    lines.push(`- Max stable users: ${performanceReport.quality.maxConcurrentUsersStable}`);
    lines.push(`- Recovery after spike: ${performanceReport.quality.recoveryAfterSpikeSeconds} s`);
    lines.push("");
    lines.push("## Сценарии");
    lines.push("| Scenario | Users | RPS | P50 ms | P95 ms | P99 ms | Error % |");
    lines.push("|---|---:|---:|---:|---:|---:|---:|");
    performanceReport.scenarios.forEach((s) => {
      lines.push(`| ${s.scenario} | ${s.users} | ${s.rps} | ${s.p50Ms} | ${s.p95Ms} | ${s.p99Ms} | ${s.errorRatePercent} |`);
    });
    downloadTextFile(
      `performance-report-${new Date().toISOString().slice(0, 10)}.md`,
      lines.join("\n"),
      "text/markdown;charset=utf-8"
    );
  }

  function openContextMenu(event: React.MouseEvent<HTMLElement>, menu: Omit<ContextMenuState, "x" | "y">) {
    event.preventDefault();
    event.stopPropagation();
    setContextMenu({ ...menu, x: event.clientX, y: event.clientY } as ContextMenuState);
  }

  function closeContextMenu() {
    setContextMenu(null);
  }

  return (
    <section
      className="dashboard file-manager"
      onClick={closeContextMenu}
      onDragOver={(event) => {
        event.preventDefault();
        if (!event.dataTransfer.types.includes(DRAG_MIME)) {
          setDragActive(true);
        }
      }}
      onDragLeave={() => {
        setDragActive(false);
        setDropTargetFolderId(null);
        setDropTargetKey(null);
      }}
      onDrop={async (event) => {
        event.preventDefault();
        setDragActive(false);
        setDropTargetFolderId(null);
        setDropTargetKey(null);
        if (event.dataTransfer.getData(DRAG_MIME)) return;
        const files = Array.from(event.dataTransfer.files || []);
        enqueueFiles(files);
      }}
    >
      <aside className="drive-sidebar">
        <div className="drive-brand">
          <div>
            <h1>Диск</h1>
            <p>{formatBytes(user.usedSpace)} занято</p>
          </div>
          <button className="icon-btn" type="button" title="Выйти" onClick={onLogout}>×</button>
        </div>

        <label className="upload-btn upload-primary">
          Загрузить
          <input type="file" onChange={handleUpload} multiple />
        </label>

        <label className="upload-btn upload-secondary">
          Папку
          <input
            type="file"
            onChange={handleFolderUpload}
            multiple
            {...{ webkitdirectory: "", directory: "" }}
          />
        </label>

        <form onSubmit={handleCreateFolder} className="new-folder-form compact-create">
          <input
            placeholder="Новая папка"
            value={newFolderName}
            onChange={(e) => setNewFolderName(e.target.value)}
            disabled={workspaceMode !== "drive"}
          />
          <button type="submit" disabled={workspaceMode !== "drive"}>Создать</button>
        </form>

        <nav className="drive-nav" aria-label="Разделы хранилища">
          {(["recentOpened", "drive", "favorites", "photos", "albums", "shared", "uploads", "trash", "history", "storage", "api", ...(user.role === "ROLE_ADMIN" ? ["admin" as WorkspaceMode] : [])] as WorkspaceMode[]).map((mode) => (
            <button
              key={mode}
              type="button"
              className={`${workspaceMode === mode ? "active" : ""} ${mode === "drive" && dropTargetKey === "nav-drive" ? "drop-target" : ""}`}
              onClick={() => switchMode(mode)}
              onDragOver={(event) => {
                if (mode === "drive") {
                  markMoveTarget(event, "nav-drive", null);
                }
              }}
              onDragLeave={() => setDropTargetKey(null)}
              onDrop={(event) => {
                if (mode === "drive") {
                  moveDraggedItemToFolder(event, null);
                }
              }}
            >
              <span className={`nav-glyph nav-${mode}`} aria-hidden="true" />
              {modeTitles[mode]}
            </button>
          ))}
        </nav>

        <div className="account-card">
          <strong>{user.displayName}</strong>
          <span>{user.email}</span>
        </div>
      </aside>

      <main className={`drive-main ${dragActive ? "drop-ready" : ""}`}>
        <header className="drive-toolbar">
          <div>
            <div className="title-row">
              {workspaceMode === "drive" && breadcrumbs.length > 1 && (
                <button
                  className={`back-btn ${dropTargetKey === "back" ? "drop-target" : ""}`}
                  type="button"
                  onClick={goBackFolder}
                  title="На уровень назад"
                  onDragOver={(event) => markMoveTarget(event, "back", breadcrumbs[breadcrumbs.length - 2]?.id ?? null)}
                  onDragLeave={() => setDropTargetKey(null)}
                  onDrop={(event) => moveDraggedItemToFolder(event, breadcrumbs[breadcrumbs.length - 2]?.id ?? null)}
                >
                  в†ђ
                </button>
              )}
              <h2>{modeTitles[workspaceMode]}</h2>
            </div>
            <nav className="breadcrumbs">
              {workspaceMode === "drive" ? (
                breadcrumbs.map((crumb, index) => (
                  <button
                    key={`${crumb.id}-${index}`}
                    className={dropTargetKey === `crumb-${index}` ? "drop-target" : ""}
                    onClick={() => jumpToCrumb(index)}
                    onDragOver={(event) => markMoveTarget(event, `crumb-${index}`, crumb.id)}
                    onDragLeave={() => setDropTargetKey(null)}
                    onDrop={(event) => moveDraggedItemToFolder(event, crumb.id)}
                  >
                    {crumb.name}
                  </button>
                ))
              ) : (
                <button type="button">{modeTitles[workspaceMode]}</button>
              )}
            </nav>
          </div>

          <div className="toolbar-controls">
            <div className="search-box">
              <input
                placeholder="Поиск"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") {
                    e.preventDefault();
                    handleSearch();
                  }
                }}
              />
              <button type="button" onClick={handleSearch}>Найти</button>
              {!!searchQuery && <button className="ghost-btn" onClick={clearSearch} type="button">Сброс</button>}
            </div>

            <select value={sort} onChange={(e) => setSort(e.target.value)} disabled={workspaceMode !== "drive"}>
              <option value="name">По названию</option>
              <option value="date">По дате</option>
              <option value="size">По размеру</option>
            </select>
            <select value={order} onChange={(e) => setOrder(e.target.value)} disabled={workspaceMode !== "drive"}>
              <option value="asc">ASC</option>
              <option value="desc">DESC</option>
            </select>
            <div className="view-toggle" role="group" aria-label="Вид">
              <button type="button" className={viewMode === "grid" ? "active" : ""} onClick={() => setViewMode("grid")}>Сетка</button>
              <button type="button" className={viewMode === "list" ? "active" : ""} onClick={() => setViewMode("list")}>Список</button>
            </div>
          </div>
        </header>

        <div className="content-toolbar">
          <button type="button" className={itemFilter === "all" ? "chip active" : "chip"} onClick={() => setItemFilter("all")}>Все</button>
          <button type="button" className={itemFilter === "folders" ? "chip active" : "chip"} onClick={() => setItemFilter("folders")} disabled={workspaceMode !== "drive"}>Папки</button>
          <button type="button" className={itemFilter === "files" ? "chip active" : "chip"} onClick={() => setItemFilter("files")}>Файлы</button>
          <span className="summary-pill">Папок: {visibleFolders.length}</span>
          <span className="summary-pill">Файлов: {visibleFiles.length}</span>
          <span className="summary-pill">На экране: {formatBytes(currentTotalSize)}</span>
          {indexingCount > 0 && <span className="summary-pill indexing">Индексируется: {indexingCount}</span>}
          {isSearchResult && <span className="summary-pill warning">Результаты поиска</span>}
          <button type="button" className="chip" onClick={handleReindex}>Переиндексировать</button>
        </div>

        {workspaceMode === "trash" && visibleFiles.length > 0 && (
          <div className="trash-bulk-bar">
            <label>
              <input type="checkbox" checked={allTrashSelected} onChange={toggleAllTrashSelection} />
              <span>Выбрано: {selectedTrashCount}</span>
            </label>
            <button type="button" disabled={selectedTrashCount === 0} onClick={handleRestoreSelectedTrash}>Восстановить выбранные</button>
            <button type="button" disabled={selectedTrashCount === 0} onClick={handleDeleteSelectedTrash}>Удалить выбранные</button>
          </div>
        )}

        {error && <p className="error-text">{error}</p>}
        {notice && <p className="toast-message">{notice}</p>}
        {sharedUrl && (
          <p className="shared-link">
            Публичная ссылка скопирована: <a href={sharedUrl} target="_blank" rel="noreferrer">открыть</a>
          </p>
        )}
        {loading && <p className="loading-text">Загрузка...</p>}

        <div className={`file-grid ${viewMode === "list" ? "list-mode" : ""}`}>
          {workspaceMode === "shared" && sharedLinks.map((link) => (
            <article className={`file-tile shared-tile ${link.active && !link.expired ? "" : "muted"}`} key={link.id}>
              {link.targetType === "folder" ? (
                <div className="folder-art" aria-hidden="true" />
              ) : link.hasThumbnail && link.fileId != null ? (
                <img className="shared-thumb" src={publicFileThumbnailUrl(link.token, link.fileId)} alt={link.fileName || "Файл"} />
              ) : (
                <div className={`file-art group-${link.fileGroup || "other"}`}>
                  <span>{(link.extension || link.fileName?.split(".").pop() || "FILE").slice(0, 4).toUpperCase()}</span>
                </div>
              )}
              <div className="tile-meta">
                <h3>{link.targetType === "folder" ? link.folderName : link.fileName}</h3>
                <p>{link.targetType === "folder" ? "Папка" : formatBytes(link.sizeBytes || 0)} · {publicLinkState(link)}</p>
                <p>Создана: {formatDate(link.createdAt)}</p>
                <p>Истекает: {link.expiresAt ? formatDate(link.expiresAt) : "не ограничена"}</p>
                {link.hasPassword && <p>Защищена паролем</p>}
              </div>
              <div className="tile-actions">
                <button onClick={() => handleCopyPublicLink(link.publicUrl)}>Копировать</button>
                <a href={link.publicUrl} target="_blank" rel="noreferrer">Открыть</a>
                {link.active && !link.expired && <button onClick={() => handleDisablePublicLink(link)}>Отключить</button>}
              </div>
            </article>
          ))}

          {workspaceMode === "history" && historyEvents.map((event) => (
            <article className="file-tile history-tile" key={event.id}>
              <div className={`history-icon ${event.targetType === "folder" ? "folder-event" : ""}`} aria-hidden="true">
                <span>{event.targetType === "folder" ? "DIR" : "FILE"}</span>
              </div>
              <div className="tile-meta">
                <h3>{eventTitle(event.action)}</h3>
                <p>{event.targetName}</p>
                {event.details && <p>{event.details}</p>}
                <p>{formatDate(event.createdAt)}</p>
              </div>
            </article>
          ))}

          {workspaceMode === "storage" && storageStats && (
            <section className="storage-panel">
              <div className="storage-hero">
                <div>
                  <h3>{formatBytes(storageStats.usedBytes)} из {formatBytes(storageStats.quotaBytes)}</h3>
                  <p>Свободно {formatBytes(storageStats.freeBytes)} · файлов {storageStats.fileCount}</p>
                </div>
                <strong>{Math.round(storageStats.usagePercent)}%</strong>
              </div>
              <div className="storage-progress" aria-label="Заполненность хранилища">
                <span style={{ width: `${Math.min(100, storageStats.usagePercent)}%` }} />
              </div>

              <div className="storage-stats-grid">
                <article>
                  <span>Активные файлы</span>
                  <strong>{formatBytes(storageStats.activeBytes)}</strong>
                </article>
                <article>
                  <span>В корзине</span>
                  <strong>{formatBytes(storageStats.trashBytes)}</strong>
                </article>
                <article>
                  <span>Доступно</span>
                  <strong>{formatBytes(storageStats.freeBytes)}</strong>
                </article>
              </div>

              <div className="storage-columns">
                <div>
                  <h3>По типам</h3>
                  <div className="usage-list">
                    {storageStats.groups.map((group) => (
                      <div className="usage-row" key={group.fileGroup}>
                        <span>{groupTitle(group.fileGroup)}</span>
                        <strong>{formatBytes(group.bytes)}</strong>
                        <em>{group.count} шт.</em>
                      </div>
                    ))}
                    {storageStats.groups.length === 0 && <p className="loading-text">Файлов пока нет.</p>}
                  </div>
                </div>
                <div>
                  <h3>Крупные файлы</h3>
                  <div className="usage-list">
                    {storageStats.largestFiles.map((file) => (
                      <button className="large-file-row" type="button" key={file.id} onClick={() => setPreviewFile(file)}>
                        <span>{file.name}</span>
                        <strong>{formatBytes(file.sizeBytes)}</strong>
                      </button>
                    ))}
                    {storageStats.largestFiles.length === 0 && <p className="loading-text">Крупных файлов пока нет.</p>}
                  </div>
                </div>
              </div>
            </section>
          )}

          {workspaceMode === "admin" && (
            <section className="admin-panel">
              <div className="admin-header">
                <div>
                  <h3>Пользователи</h3>
                  <p>Аккаунты, занятое место, файлы и ограничения</p>
                </div>
                <strong>{adminUsers.length}</strong>
              </div>
              <div className="admin-table">
                <div className="admin-row admin-row-head">
                  <span>Пользователь</span>
                  <span>Место</span>
                  <span>Файлы</span>
                  <span>Квота, GB</span>
                  <span>Статус</span>
                  <span>Действие</span>
                </div>
                {adminUsers.map((adminUser) => (
                  <div className="admin-row" key={adminUser.id}>
                    <span>
                      <strong>{adminUser.displayName}</strong>
                      <em>{adminUser.username} · {adminUser.email}</em>
                    </span>
                    <span>{formatBytes(adminUser.usedSpace)}</span>
                    <span>{adminUser.fileCount}</span>
                    <span>
                      <input
                        type="number"
                        min="0"
                        step="0.25"
                        defaultValue={(adminUser.storageQuotaBytes / 1024 / 1024 / 1024).toFixed(2)}
                        onBlur={(event) => handleQuotaChange(adminUser, event.target.value)}
                      />
                    </span>
                    <span className={adminUser.enabled ? "admin-ok" : "admin-blocked"}>
                      {adminUser.enabled ? "Активен" : "Заблокирован"}
                    </span>
                    <span>
                      <button type="button" disabled={adminUser.id === user.id} onClick={() => handleToggleAdminUser(adminUser)}>
                        {adminUser.enabled ? "Заблокировать" : "Разблокировать"}
                      </button>
                    </span>
                  </div>
                ))}
                {adminUsers.length === 0 && <p className="loading-text">Пользователей пока нет.</p>}
              </div>

              {performanceReport && (
                <section className="perf-report">
                  <div className="admin-header">
                    <div>
                      <h3>Нагрузочные тесты</h3>
                      <p>Окно: {performanceReport.testWindow} · Обновлено: {formatDate(performanceReport.generatedAt)}</p>
                    </div>
                    <div className="share-link-actions">
                      <button type="button" onClick={exportPerformanceReportCsv}>Скачать CSV</button>
                      <button type="button" onClick={exportPerformanceReportJson}>Скачать JSON</button>
                      <button type="button" onClick={exportPerformanceReportMarkdown}>Скачать MD</button>
                    </div>
                  </div>

                  <div className="storage-stats-grid">
                    <article>
                      <span>Средний CPU</span>
                      <strong>{performanceReport.summary.avgCpuPercent}%</strong>
                    </article>
                    <article>
                      <span>Средняя RAM</span>
                      <strong>{performanceReport.summary.avgMemoryPercent}%</strong>
                    </article>
                    <article>
                      <span>Пиковый RPS</span>
                      <strong>{performanceReport.summary.peakRps}</strong>
                    </article>
                    <article>
                      <span>Доступность</span>
                      <strong>{performanceReport.quality.availabilityPercent}%</strong>
                    </article>
                    <article>
                      <span>Precision поиска</span>
                      <strong>{performanceReport.quality.searchPrecisionPercent}%</strong>
                    </article>
                    <article>
                      <span>Recall поиска</span>
                      <strong>{performanceReport.quality.searchRecallPercent}%</strong>
                    </article>
                  </div>

                  <div className="content-toolbar">
                    <span className={performanceReport.scenarios.find((s) => s.scenario === "Load")?.p95Ms && performanceReport.scenarios.find((s) => s.scenario === "Load")!.p95Ms < 500 ? "summary-pill indexing" : "summary-pill warning"}>
                      KPI p95 read &lt; 500ms
                    </span>
                    <span className={performanceReport.summary.maxErrorRatePercent < 5 ? "summary-pill indexing" : "summary-pill warning"}>
                      KPI error rate &lt; 5%
                    </span>
                    <span className={performanceReport.quality.recoveryAfterSpikeSeconds < 120 ? "summary-pill indexing" : "summary-pill warning"}>
                      KPI recovery &lt; 120s
                    </span>
                  </div>

                  <div className="admin-table">
                    <div className="admin-row admin-row-head">
                      <span>Сценарий</span>
                      <span>Пользователи</span>
                      <span>RPS</span>
                      <span>P95, ms</span>
                      <span>P99, ms</span>
                      <span>Errors, %</span>
                    </div>
                    {performanceReport.scenarios.map((scenario) => (
                      <div className="admin-row" key={scenario.scenario}>
                        <span><strong>{scenario.scenario}</strong></span>
                        <span>{scenario.users}</span>
                        <span>{scenario.rps}</span>
                        <span>{scenario.p95Ms}</span>
                        <span>{scenario.p99Ms}</span>
                        <span className={scenario.errorRatePercent >= 3 ? "admin-blocked" : "admin-ok"}>
                          {scenario.errorRatePercent}
                        </span>
                      </div>
                    ))}
                  </div>

                  <div className="perf-charts">
                    {renderTimelineBars("Динамика P95 задержки", performanceReport.latencyTimeline, "мс")}
                    {renderTimelineBars("Динамика пропускной способности", performanceReport.throughputTimeline, "rps", true)}
                    {renderTimelineBars("Динамика процента ошибок", performanceReport.errorTimeline, "%")}
                    {renderTimelineBars("Динамика CPU", performanceReport.cpuTimeline, "%")}
                    {renderTimelineBars("Динамика RAM", performanceReport.memoryTimeline, "%")}
                    {renderTimelineBars("Динамика доступности", performanceReport.availabilityTimeline, "%")}
                    {renderTimelineBars("Динамика precision поиска", performanceReport.searchPrecisionTimeline, "%")}
                    {renderTimelineBars("Динамика recall поиска", performanceReport.searchRecallTimeline, "%")}
                  </div>

                  <div className="perf-charts">
                    {renderUsersMetricChart("RPS от количества пользователей", "rps", "#2563eb", (s) => s.rps)}
                    {renderUsersMetricChart("P50 задержка от количества пользователей", "мс", "#f59e0b", (s) => s.p50Ms)}
                    {renderUsersMetricChart("P95 задержка от количества пользователей", "мс", "#ea580c", (s) => s.p95Ms)}
                    {renderUsersMetricChart("P99 задержка от количества пользователей", "мс", "#b91c1c", (s) => s.p99Ms)}
                    {renderUsersMetricChart("Процент ошибок от количества пользователей", "%", "#dc2626", (s) => s.errorRatePercent)}
                  </div>
                </section>
              )}

              <section className="perf-report">
                <div className="admin-header">
                  <div>
                    <h3>Моделирование нагрузки</h3>
                    <p>График зависимости метрик от количества пользователей</p>
                  </div>
                </div>
                <form className="model-controls" onSubmit={(event) => { event.preventDefault(); runLoadModeling(); }}>
                  <label>
                    Минимум пользователей
                    <input type="number" min="1" value={loadMinUsers} onChange={(event) => setLoadMinUsers(Number(event.target.value) || 1)} />
                  </label>
                  <label>
                    Максимум пользователей
                    <input type="number" min="1" value={loadMaxUsers} onChange={(event) => setLoadMaxUsers(Number(event.target.value) || 1)} />
                  </label>
                  <label>
                    Шаг
                    <input type="number" min="1" value={loadStepUsers} onChange={(event) => setLoadStepUsers(Number(event.target.value) || 1)} />
                  </label>
                  <button type="submit">Смоделировать</button>
                </form>
                {renderModelLineChart()}
                {renderModelErrorChart()}
                {loadModelPoints.length > 0 && (
                  <div className="admin-table">
                    <div className="admin-row admin-row-head">
                      <span>Пользователи</span>
                      <span>RPS</span>
                      <span>P95, ms</span>
                      <span>Errors, %</span>
                      <span>Оценка</span>
                      <span>Комментарий</span>
                    </div>
                    {loadModelPoints.map((point) => (
                      <div className="admin-row" key={`load-point-${point.users}`}>
                        <span><strong>{point.users}</strong></span>
                        <span>{point.rps}</span>
                        <span>{point.p95}</span>
                        <span>{point.errorRate}</span>
                        <span className={point.errorRate < 5 ? "admin-ok" : "admin-blocked"}>
                          {point.errorRate < 5 ? "OK" : "RISK"}
                        </span>
                        <span>{point.p95 < 500 ? "Быстрый отклик" : point.p95 < 1200 ? "Допустимо" : "Требует оптимизации"}</span>
                      </div>
                    ))}
                  </div>
                )}
              </section>
            </section>
          )}

          {workspaceMode === "api" && (
            <section className="admin-panel">
              <div className="admin-header">
                <div>
                  <h3>Integration API</h3>
                  <p>Создание и отзыв ключей для внешних серверов</p>
                </div>
              </div>
              <form className="new-folder-form" onSubmit={handleCreateIntegrationClient}>
                <label>
                  Имя ключа
                  <input value={integrationClientName} onChange={(event) => setIntegrationClientName(event.target.value)} />
                </label>
                <div className="content-toolbar">
                  {["read", "write", "upload", "download", "search", "stats"].map((scope) => (
                    <button
                      key={scope}
                      type="button"
                      className={integrationScopes.has(scope) ? "chip active" : "chip"}
                      onClick={() => toggleIntegrationScope(scope)}
                    >
                      {scope}
                    </button>
                  ))}
                </div>
                <button type="submit">Создать ключ API</button>
              </form>
              {createdApiKey && (
                <div className="share-link-box">
                  <span>Скопируйте ключ сейчас: повторно он не показывается</span>
                  <input readOnly value={createdApiKey} />
                  <div className="share-link-actions">
                    <button type="button" onClick={() => handleCopyPublicLink(createdApiKey)}>Копировать</button>
                  </div>
                </div>
              )}
              <div className="admin-table">
                <div className="admin-row admin-row-head">
                  <span>Ключ</span>
                  <span>Scopes</span>
                  <span>Создан</span>
                  <span>Последний вызов</span>
                  <span>Статус</span>
                  <span>Действие</span>
                </div>
                {integrationClients.map((client) => (
                  <div className="admin-row" key={client.id}>
                    <span>
                      <strong>{client.name}</strong>
                      <em>ID: {client.id}</em>
                    </span>
                    <span>{client.scopes.join(", ")}</span>
                    <span>{formatDate(client.createdAt)}</span>
                    <span>{formatDate(client.lastUsedAt)}</span>
                    <span className={client.enabled ? "admin-ok" : "admin-blocked"}>
                      {client.enabled ? "Активен" : "Отключен"}
                    </span>
                    <span>
                      {client.enabled && (
                        <button type="button" onClick={() => handleRevokeIntegrationClient(client.id)}>
                          Отозвать
                        </button>
                      )}
                    </span>
                  </div>
                ))}
                {integrationClients.length === 0 && <p className="loading-text">Ключей пока нет.</p>}
              </div>
            </section>
          )}

          {visibleFolders.map((folder) => (
            <article
              className={`file-tile folder-tile ${selectedItem === `folder-${folder.id}` ? "selected" : ""} ${dropTargetFolderId === folder.id ? "drop-target" : ""}`}
              key={`${folder.id}-${folder.name}`}
              onClick={() => openFolder(folder)}
              onContextMenu={(event) => openContextMenu(event, { type: "folder", item: folder })}
              draggable={workspaceMode === "drive"}
              onDragStart={(event) => folder.id != null && startItemDrag(event, { type: "folder", id: folder.id })}
              onDragEnd={() => {
                setDragActive(false);
                setDropTargetFolderId(null);
                setDropTargetKey(null);
              }}
              onDragOver={(event) => {
                if (!event.dataTransfer.types.includes(DRAG_MIME) || folder.id == null) return;
                event.preventDefault();
                event.stopPropagation();
                event.dataTransfer.dropEffect = "move";
                setDropTargetFolderId(folder.id);
                setDropTargetKey(`folder-${folder.id}`);
              }}
              onDragLeave={() => {
                setDropTargetFolderId(null);
                setDropTargetKey(null);
              }}
              onDrop={(event) => folder.id != null && moveDraggedItemToFolder(event, folder.id)}
            >
              <div className="folder-art" aria-hidden="true" />
              <div className="tile-meta">
                <h3>{folder.name}</h3>
                {folder.favorite && <span className="favorite-badge">★ Избранное</span>}
                {folder.id != null && activeSharedFolderIds.has(folder.id) && <span className="share-badge">Общий доступ</span>}
                <p>Папка</p>
              </div>
            </article>
          ))}

          {visibleFiles.map((file) => (
            <article
              className={`file-tile ${selectedItem === `file-${file.id}` ? "selected" : ""}`}
              key={file.id}
              onClick={() => setSelectedItem(`file-${file.id}`)}
              onDoubleClick={() => setPreviewFile(file)}
              onContextMenu={(event) => openContextMenu(event, { type: "file", item: file })}
              draggable={workspaceMode === "drive"}
              onDragStart={(event) => startItemDrag(event, { type: "file", id: file.id })}
              onDragEnd={() => {
                setDragActive(false);
                setDropTargetFolderId(null);
                setDropTargetKey(null);
              }}
            >
              <ThumbnailArt file={file} />
              {file.index && (
                <span
                  className={`index-dot ${indexDotClass(file)}`}
                  title={indexTooltip(file)}
                  aria-label={indexStatusLabel(file.index.status)}
                />
              )}
              <div className="tile-meta">
                <h3>{file.name}</h3>
                {file.favorite && <span className="favorite-badge">★ Избранное</span>}
                {activeSharedFileIds.has(file.id) && <span className="share-badge">Общий доступ</span>}
                {isSearchResult && matchTitle(file.matchType) && (
                  <span className="match-badge">
                    {matchTitle(file.matchType)}
                    {confidenceTitle(file.score) ? ` · ${confidenceTitle(file.score)}` : ""}
                  </span>
                )}
                <p>{formatBytes(file.sizeBytes)} · {formatDate(file.uploadedAt)}</p>
                {workspaceMode === "trash" && (
                  <label className="trash-select" onClick={(event) => event.stopPropagation()}>
                    <input
                      type="checkbox"
                      checked={selectedTrashIds.has(file.id)}
                      onChange={() => toggleTrashSelection(file.id)}
                    />
                    <span>Осталось: {timeUntil(file.purgeAfter)}</span>
                  </label>
                )}
                {workspaceMode === "trash" && <p>Удалится: {formatDate(file.purgeAfter)}</p>}
                {workspaceMode === "recentOpened" && <p>Открыт: {formatDate(file.lastAccessedAt)}</p>}
              </div>
            </article>
          ))}
        </div>

        {!loading && visibleFolders.length === 0 && visibleFiles.length === 0 && sharedLinks.length === 0 && historyEvents.length === 0 && !storageStats && adminUsers.length === 0 && (
          <div className="empty-state">
            <h3>{workspaceMode === "drive" ? "Здесь пока пусто" : "В разделе пока нет файлов"}</h3>
            <p>{workspaceMode === "drive" ? "Перетащи файлы в окно или создай первую папку." : "Данные появятся здесь после соответствующих действий."}</p>
          </div>
        )}
      </main>
      {contextMenu && (
        <div
          className="context-menu"
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onClick={(event) => event.stopPropagation()}
        >
          {contextMenu.type === "folder" ? (
            <>
              <button type="button" onClick={() => { openFolder(contextMenu.item); closeContextMenu(); }}>Открыть</button>
              <button type="button" onClick={() => { handleToggleFolderFavorite(contextMenu.item); closeContextMenu(); }}>{contextMenu.item.favorite ? "Убрать из избранного" : "В избранное"}</button>
              <button type="button" onClick={() => { handleShareFolder(contextMenu.item); closeContextMenu(); }}>{folderShareActionTitle(contextMenu.item)}</button>
              <button type="button" onClick={() => { openMoveFolder(contextMenu.item); closeContextMenu(); }}>Переместить</button>
              <button type="button" onClick={() => { openRenameFolder(contextMenu.item); closeContextMenu(); }}>Переименовать</button>
              <button type="button" onClick={() => { handleDeleteFolder(contextMenu.item.id); closeContextMenu(); }}>Удалить</button>
            </>
          ) : workspaceMode === "trash" ? (
            <>
              <button type="button" onClick={() => { handleRestoreFile(contextMenu.item); closeContextMenu(); }}>Восстановить</button>
              <button type="button" onClick={() => { handleDeleteFile(contextMenu.item); closeContextMenu(); }}>Удалить окончательно</button>
            </>
          ) : (
            <>
              <button type="button" onClick={() => { setPreviewFile(contextMenu.item); closeContextMenu(); }}>Открыть</button>
              <button type="button" onClick={() => { handleDownload(contextMenu.item); closeContextMenu(); }}>Скачать</button>
              <button type="button" onClick={() => { handleToggleFileFavorite(contextMenu.item); closeContextMenu(); }}>{contextMenu.item.favorite ? "Убрать из избранного" : "В избранное"}</button>
              <button type="button" onClick={() => { handleShare(contextMenu.item); closeContextMenu(); }}>{fileShareActionTitle(contextMenu.item)}</button>
              <button type="button" onClick={() => { openMoveFile(contextMenu.item); closeContextMenu(); }}>Переместить</button>
              <button type="button" onClick={() => { openRenameFile(contextMenu.item); closeContextMenu(); }}>Переименовать</button>
              <button type="button" onClick={() => { handleDeleteFile(contextMenu.item); closeContextMenu(); }}>В корзину</button>
            </>
          )}
        </div>
      )}
      {renameDialog && (
        <div className="dialog-backdrop" role="dialog" aria-modal="true">
          <form className="action-dialog" onSubmit={submitRename}>
            <header>
              <h3>{renameDialog.type === "folder" ? "Переименовать папку" : "Переименовать файл"}</h3>
              <button className="icon-btn" type="button" onClick={() => setRenameDialog(null)} title="Закрыть">×</button>
            </header>
            <label>
              Новое имя
              <input
                autoFocus
                value={renameDialog.value}
                onChange={(event) => setRenameDialog({ ...renameDialog, value: event.target.value })}
              />
            </label>
            <footer>
              <button className="ghost-btn" type="button" onClick={() => setRenameDialog(null)}>Отмена</button>
              <button type="submit">Сохранить</button>
            </footer>
          </form>
        </div>
      )}
      {moveDialog && (
        <div className="dialog-backdrop" role="dialog" aria-modal="true">
          <form className="action-dialog" onSubmit={submitMove}>
            <header>
              <h3>Переместить</h3>
              <button className="icon-btn" type="button" onClick={() => setMoveDialog(null)} title="Закрыть">×</button>
            </header>
            <p className="dialog-subtitle">{moveDialog.item.name}</p>
            <label>
              Куда переместить
              <select
                value={moveDialog.targetFolderId == null ? "" : String(moveDialog.targetFolderId)}
                onChange={(event) => setMoveDialog({
                  ...moveDialog,
                  targetFolderId: event.target.value ? Number(event.target.value) : null
                })}
              >
                <option value="">Файлы</option>
                {breadcrumbs
                  .filter((crumb) => crumb.id != null && !(moveDialog.type === "folder" && crumb.id === moveDialog.item.id))
                  .map((crumb) => (
                    <option key={`crumb-${crumb.id}`} value={String(crumb.id)}>{crumb.name}</option>
                  ))}
                {data.folders
                  .filter((folder) => folder.id != null && !(moveDialog.type === "folder" && folder.id === moveDialog.item.id))
                  .map((folder) => (
                    <option key={`folder-${folder.id}`} value={String(folder.id)}>{folder.name}</option>
                  ))}
              </select>
            </label>
            <footer>
              <button className="ghost-btn" type="button" onClick={() => setMoveDialog(null)}>Отмена</button>
              <button type="submit">Переместить</button>
            </footer>
          </form>
        </div>
      )}
      {shareDialog && (
        <div className="dialog-backdrop" role="dialog" aria-modal="true">
          <form className="action-dialog share-dialog" onSubmit={submitShareDialog}>
            <header>
              <h3>Совместный доступ</h3>
              <button className="icon-btn" type="button" onClick={() => setShareDialog(null)} title="Закрыть">×</button>
            </header>
            <p className="dialog-subtitle">
              {shareDialog.type === "folder" ? "Папка" : "Файл"}: {shareDialog.item.name}
            </p>
            <label>
              Срок действия
              <select
                value={shareDialog.expiresInDays == null ? "" : String(shareDialog.expiresInDays)}
                onChange={(event) => setShareDialog({
                  ...shareDialog,
                  expiresInDays: event.target.value ? Number(event.target.value) : null
                })}
              >
                <option value="">Бессрочно</option>
                <option value="1">1 день</option>
                <option value="7">7 дней</option>
              </select>
            </label>
            <label>
              Пароль
              <input
                type="password"
                placeholder={shareDialog.link?.hasPassword ? "Пароль задан, введите новый для замены" : "Без пароля"}
                value={shareDialog.password}
                onChange={(event) => setShareDialog({ ...shareDialog, password: event.target.value })}
              />
            </label>
            {shareDialog.link && (
              <div className="share-link-box">
                <span>{publicLinkState(shareDialog.link)}</span>
                {shareDialog.link.hasPassword && <span>Ссылка защищена паролем</span>}
                <input readOnly value={shareDialog.link.publicUrl} />
                <div className="share-link-actions">
                  <button type="button" onClick={() => handleCopyPublicLink(shareDialog.link?.publicUrl || "")}>Копировать</button>
                  <a href={shareDialog.link.publicUrl} target="_blank" rel="noreferrer">Открыть</a>
                  {shareDialog.link.active && <button type="button" onClick={() => shareDialog.link && handleDisablePublicLink(shareDialog.link)}>Отключить</button>}
                </div>
              </div>
            )}
            <footer>
              <button className="ghost-btn" type="button" onClick={() => setShareDialog(null)}>Закрыть</button>
              <button type="submit">{shareDialog.link ? "Обновить ссылку" : "Создать ссылку"}</button>
            </footer>
          </form>
        </div>
      )}
      {previewFile && (
        <PreviewModal
          file={previewFile}
          onClose={() => setPreviewFile(null)}
          onDownload={handleDownload}
        />
      )}
      {uploadQueue.length > 0 && (
        <section className={`upload-queue-widget ${uploadWidgetOpen ? "open" : "collapsed"}`} aria-live="polite">
          <header>
            <button type="button" className="upload-queue-toggle" onClick={() => setUploadWidgetOpen((current) => !current)}>
              Загрузки ({uploadQueue.length}/10)
            </button>
          </header>
          {uploadWidgetOpen && (
            <>
              <div className="upload-queue-list">
                {uploadQueue.map((item) => (
                  <article key={item.id} className={`upload-queue-item ${item.status}`}>
                    <div className="upload-queue-main">
                      <strong title={item.file.name}>{item.file.name}</strong>
                      <span>{formatBytes(item.file.size)}</span>
                    </div>
                    <div className="upload-queue-progress">
                      <div className="upload-queue-progress-bar">
                        <span style={{ width: `${item.progress}%` }} />
                      </div>
                      <small>{item.progress}%</small>
                    </div>
                    <div className="upload-queue-actions">
                      <em>
                        {item.status === "pending" && "Ожидает"}
                        {item.status === "uploading" && "Загрузка"}
                        {item.status === "uploaded" && "Загружен"}
                        {item.status === "error" && "Ошибка"}
                      </em>
                      <button
                        type="button"
                        className="ghost-btn"
                        disabled={item.status === "uploading" || uploadingAll}
                        onClick={() => removeUploadQueueItem(item.id)}
                      >
                        Удалить
                      </button>
                    </div>
                    {item.error && <p className="error-text">{item.error}</p>}
                  </article>
                ))}
              </div>
              <footer className="upload-queue-footer">
                <button
                  type="button"
                  className="ghost-btn"
                  disabled={uploadingAll}
                  onClick={() => setUploadQueue((current) => current.filter((item) => item.status === "uploading"))}
                >
                  Очистить завершенные
                </button>
                <button
                  type="button"
                  disabled={uploadingAll || !uploadQueue.some((item) => item.status === "pending" || item.status === "error")}
                  onClick={sendAllUploads}
                >
                  {uploadingAll ? "Отправка..." : "Отправить все"}
                </button>
              </footer>
            </>
          )}
        </section>
      )}
    </section>
  );
}

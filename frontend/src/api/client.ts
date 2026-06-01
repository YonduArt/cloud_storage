import axios from "axios";
import type { AdminUser, AuthTokenResponse, FileEvent, FileIndexStatus, FileItem, FilePreview, FolderItem, IntegrationClient, IntegrationClientCreateResponse, PerformanceReport, PublicLink, PublicResource, SearchItem, StorageStats, StorageView, User } from "../types";

const TOKEN_KEY = "cloud_token";

type ApiResponse<T> = {
  success: boolean;
  data: T;
  message: string | null;
  timestamp: string;
};

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || "/api"
});

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || "Запрос не выполнен";
    if (error?.response?.status === 401) {
      localStorage.removeItem(TOKEN_KEY);
    }
    return Promise.reject(new Error(message));
  }
);

const unwrap = <T,>(response: { data: ApiResponse<T> }): T => response.data.data;

export const authToken = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string): void => localStorage.setItem(TOKEN_KEY, token),
  clear: (): void => localStorage.removeItem(TOKEN_KEY)
};

export async function register(payload: {
  username: string;
  email: string;
  password: string;
  displayName: string;
}): Promise<AuthTokenResponse> {
  return unwrap(await http.post("/auth/register", payload));
}

export async function login(payload: { login: string; password: string }): Promise<AuthTokenResponse> {
  return unwrap(await http.post("/auth/login", payload));
}

export async function me(): Promise<User> {
  return unwrap(await http.get("/users/me"));
}

export async function getAdminUsers(): Promise<AdminUser[]> {
  return unwrap(await http.get("/admin/users"));
}

export async function updateAdminUser(userId: number, payload: { enabled?: boolean; storageQuotaBytes?: number }): Promise<AdminUser> {
  return unwrap(await http.patch(`/admin/users/${userId}`, payload));
}

export async function getPerformanceReport(): Promise<PerformanceReport> {
  return unwrap(await http.get("/admin/performance-report"));
}

export async function createIntegrationClient(payload: { name: string; scopes: string[] }): Promise<IntegrationClientCreateResponse> {
  return unwrap(await http.post("/integration-clients", payload));
}

export async function listIntegrationClients(): Promise<IntegrationClient[]> {
  return unwrap(await http.get("/integration-clients"));
}

export async function revokeIntegrationClient(clientId: number): Promise<void> {
  await unwrap(await http.delete(`/integration-clients/${clientId}`));
}

export async function getRoot(): Promise<FolderItem> {
  return unwrap(await http.get("/folders/root"));
}

export async function getFolderContent(folderId: number | null, sort = "name", order = "asc"): Promise<StorageView> {
  const endpoint = folderId == null ? "/folders/root/content" : `/folders/${folderId}/content`;
  return unwrap(await http.get(endpoint, { params: { sort, order } }));
}

export async function createFolder(payload: { name: string; parentId: number | null }): Promise<FolderItem> {
  return unwrap(await http.post("/folders", payload));
}

export async function renameFolder(folderId: number, payload: { name: string }): Promise<FolderItem> {
  return unwrap(await http.patch(`/folders/${folderId}`, payload));
}

export async function moveFolder(folderId: number, payload: { targetFolderId: number | null }): Promise<FolderItem> {
  return unwrap(await http.patch(`/folders/${folderId}/move`, payload));
}

export async function setFolderFavorite(folderId: number, favorite: boolean): Promise<FolderItem> {
  return unwrap(await http.patch(`/folders/${folderId}/favorite`, null, { params: { favorite } }));
}

export async function getFavorites(): Promise<StorageView> {
  return unwrap(await http.get("/folders/favorites"));
}

export async function deleteFolder(folderId: number): Promise<void> {
  await unwrap(await http.delete(`/folders/${folderId}`));
}

export async function uploadFile(
  folderId: number | null,
  file: File,
  onProgress?: (percent: number) => void
): Promise<FileItem> {
  const body = new FormData();
  body.append("file", file);
  return unwrap(await http.post("/files/upload", body, {
    params: { folderId },
    onUploadProgress: (event) => {
      if (!onProgress || !event.total) return;
      const percent = Math.min(100, Math.round((event.loaded / event.total) * 100));
      onProgress(percent);
    }
  }));
}

export async function uploadFolder(folderId: number | null, files: File[], relativePaths: string[]): Promise<FileItem[]> {
  const body = new FormData();
  files.forEach((file) => body.append("files", file));
  relativePaths.forEach((relativePath) => body.append("relativePaths", relativePath));
  return unwrap(await http.post("/files/upload-folder", body, { params: { folderId } }));
}

export async function getFile(fileId: number): Promise<FileItem> {
  return unwrap(await http.get(`/files/${fileId}`));
}

export async function getFileIndexStatus(fileId: number): Promise<FileIndexStatus> {
  return unwrap(await http.get(`/files/${fileId}/index-status`));
}

export async function renameFile(fileId: number, payload: { name: string }): Promise<FileItem> {
  return unwrap(await http.patch(`/files/${fileId}`, payload));
}

export async function moveFile(fileId: number, payload: { targetFolderId: number | null }): Promise<FileItem> {
  return unwrap(await http.patch(`/files/${fileId}/move`, payload));
}

export async function setFileFavorite(fileId: number, favorite: boolean): Promise<FileItem> {
  return unwrap(await http.patch(`/files/${fileId}/favorite`, null, { params: { favorite } }));
}

export async function deleteFile(fileId: number): Promise<void> {
  await unwrap(await http.delete(`/files/${fileId}`));
}

export async function moveFileToTrash(fileId: number, keepDays = 30): Promise<FileItem> {
  return unwrap(await http.patch(`/files/${fileId}/trash`, null, { params: { keepDays } }));
}

export async function restoreFileFromTrash(fileId: number): Promise<FileItem> {
  return unwrap(await http.patch(`/files/${fileId}/restore`));
}

export async function restoreFilesFromTrash(ids: number[]): Promise<FileItem[]> {
  return unwrap(await http.patch("/files/trash/restore-batch", { ids }));
}

export async function deleteTrashFiles(ids: number[]): Promise<void> {
  await unwrap(await http.post("/files/trash/delete-batch", { ids }));
}

export async function getTrashFiles(): Promise<FileItem[]> {
  return unwrap(await http.get("/files/trash"));
}

export async function getRecentUploaded(limit = 20): Promise<FileItem[]> {
  return unwrap(await http.get("/files/recent-uploaded", { params: { limit } }));
}

export async function getRecentOpened(limit = 20): Promise<FileItem[]> {
  return unwrap(await http.get("/files/recent-opened", { params: { limit } }));
}

export async function getFilesByGroup(group: string): Promise<FileItem[]> {
  return unwrap(await http.get(`/files/groups/${group}`));
}

export async function getFileThumbnail(fileId: number): Promise<Blob> {
  const response = await http.get(`/files/${fileId}/thumbnail`, { responseType: "blob" });
  return response.data;
}

export async function getFilePreview(fileId: number): Promise<FilePreview> {
  return unwrap(await http.get(`/files/${fileId}/preview`));
}

export async function getFilePreviewContent(fileId: number): Promise<Blob> {
  const response = await http.get(`/files/${fileId}/preview/content`, { responseType: "blob" });
  return response.data;
}

export function downloadFileUrl(fileId: number): string {
  const base = import.meta.env.VITE_API_BASE_URL || "/api";
  return `${base}/files/${fileId}/download`;
}

export async function downloadFile(fileId: number, filename: string): Promise<void> {
  const response = await http.get(`/files/${fileId}/download`, { responseType: "blob" });
  const url = window.URL.createObjectURL(new Blob([response.data]));
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename || "file";
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.URL.revokeObjectURL(url);
}

export async function createPublicLink(fileId: number, payload?: { expiresInDays: number | null; password?: string }): Promise<PublicLink> {
  return unwrap(await http.post(`/public-links/files/${fileId}`, payload || null));
}

export async function createPublicFolderLink(folderId: number, payload?: { expiresInDays: number | null; password?: string }): Promise<PublicLink> {
  return unwrap(await http.post(`/public-links/folders/${folderId}`, payload || null));
}

export async function getPublicLinks(): Promise<PublicLink[]> {
  return unwrap(await http.get("/public-links"));
}

export async function disablePublicLink(linkId: number): Promise<void> {
  await unwrap(await http.delete(`/public-links/${linkId}`));
}

export async function getPublicResource(token: string, password?: string): Promise<PublicResource> {
  return unwrap(await http.get(`/public/${token}`, { params: password ? { password } : undefined }));
}

export async function getPublicFolderResource(token: string, folderId: number, password?: string): Promise<PublicResource> {
  return unwrap(await http.get(`/public/${token}/folders/${folderId}`, { params: password ? { password } : undefined }));
}

function withPublicPassword(url: string, password?: string): string {
  if (!password) return url;
  const separator = url.includes("?") ? "&" : "?";
  return `${url}${separator}${new URLSearchParams({ password }).toString()}`;
}

export function publicFileDownloadUrl(token: string, fileId: number, password?: string): string {
  const base = import.meta.env.VITE_API_BASE_URL || "/api";
  return withPublicPassword(`${base}/public/${token}/files/${fileId}/download`, password);
}

export function publicRootFileDownloadUrl(token: string, password?: string): string {
  const base = import.meta.env.VITE_API_BASE_URL || "/api";
  return withPublicPassword(`${base}/public/${token}/download`, password);
}

export function publicFolderDownloadUrl(token: string, folderId: number, password?: string): string {
  const base = import.meta.env.VITE_API_BASE_URL || "/api";
  return withPublicPassword(`${base}/public/${token}/folders/${folderId}/download`, password);
}

export function publicFileThumbnailUrl(token: string, fileId: number, password?: string): string {
  const base = import.meta.env.VITE_API_BASE_URL || "/api";
  return withPublicPassword(`${base}/public/${token}/files/${fileId}/thumbnail`, password);
}

export async function getHistory(): Promise<FileEvent[]> {
  return unwrap(await http.get("/history"));
}

export async function getStorageStats(): Promise<StorageStats> {
  return unwrap(await http.get("/storage/stats"));
}

export async function search(query: string): Promise<SearchItem[]> {
  return unwrap(await http.get("/search", { params: { query } }));
}

export async function reindexCurrentUserFiles(): Promise<number> {
  return unwrap(await http.post("/search/reindex"));
}

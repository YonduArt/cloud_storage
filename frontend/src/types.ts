export interface User {
  id: number;
  username: string;
  email: string;
  displayName: string;
  usedSpace: number;
  storageQuotaBytes: number;
  enabled: boolean;
  role: string;
}

export interface AdminUser {
  id: number;
  username: string;
  email: string;
  displayName: string;
  usedSpace: number;
  storageQuotaBytes: number;
  fileCount: number;
  enabled: boolean;
  role: string;
  createdAt: string;
}

export interface PerformanceScenarioMetrics {
  scenario: string;
  users: number;
  rps: number;
  p50Ms: number;
  p95Ms: number;
  p99Ms: number;
  errorRatePercent: number;
}

export interface PerformanceTimePoint {
  label: string;
  value: number;
}

export interface PerformanceReport {
  generatedAt: string;
  testWindow: string;
  scenarios: PerformanceScenarioMetrics[];
  latencyTimeline: PerformanceTimePoint[];
  throughputTimeline: PerformanceTimePoint[];
  errorTimeline: PerformanceTimePoint[];
  cpuTimeline: PerformanceTimePoint[];
  memoryTimeline: PerformanceTimePoint[];
  availabilityTimeline: PerformanceTimePoint[];
  searchPrecisionTimeline: PerformanceTimePoint[];
  searchRecallTimeline: PerformanceTimePoint[];
  summary: {
    avgCpuPercent: number;
    avgMemoryPercent: number;
    maxErrorRatePercent: number;
    peakRps: number;
  };
  quality: {
    availabilityPercent: number;
    searchPrecisionPercent: number;
    searchRecallPercent: number;
    maxConcurrentUsersStable: number;
    recoveryAfterSpikeSeconds: number;
  };
}

export interface FolderItem {
  id: number | null;
  name: string;
  parentId: number | null;
  favorite: boolean;
}

export interface FileItem {
  id: number;
  name: string;
  contentType: string;
  extension?: string | null;
  fileGroup?: string | null;
  hasThumbnail?: boolean | null;
  sizeBytes: number;
  uploadedAt: string;
  folderId: number | null;
  deletedAt?: string | null;
  purgeAfter?: string | null;
  lastAccessedAt?: string | null;
  favorite: boolean;
  matchType?: "NAME" | "TEXT" | "IMAGE" | "OCR" | "SEMANTIC";
  score?: number;
  index?: FileIndexSummary | null;
}

export interface FileIndexEmbedding {
  type: string;
  modelName: string;
  dimensions: number;
  createdAt?: string | null;
}

export interface FileIndexSummary {
  status: "PENDING" | "INDEXING" | "READY" | "FAILED" | string;
  createdAt?: string | null;
  indexedAt?: string | null;
  errorMessage?: string | null;
  embeddings: FileIndexEmbedding[];
}

export interface FileIndexStatus extends FileIndexSummary {
  fileId: number;
  contentType: string;
}

export interface StorageView {
  folders: FolderItem[];
  files: FileItem[];
}

export interface PublicLink {
  id: number;
  token: string;
  targetType: "file" | "folder";
  fileId: number | null;
  fileName: string | null;
  folderId: number | null;
  folderName: string | null;
  contentType: string | null;
  extension: string | null;
  fileGroup: string | null;
  hasThumbnail: boolean | null;
  sizeBytes: number | null;
  active: boolean;
  createdAt: string;
  expiresAt: string | null;
  expired: boolean;
  hasPassword: boolean;
  publicUrl: string;
}

export interface PublicResource {
  token: string;
  targetType: "file" | "folder";
  name: string;
  folderId: number | null;
  parentFolderId: number | null;
  contentType: string | null;
  sizeBytes: number | null;
  createdAt: string;
  hasPassword: boolean;
  folders: FolderItem[];
  files: FileItem[];
}

export interface FileEvent {
  id: number;
  action: string;
  targetType: "file" | "folder" | string;
  targetId: number | null;
  targetName: string;
  details: string | null;
  createdAt: string;
}

export interface StorageGroupUsage {
  fileGroup: string;
  bytes: number;
  count: number;
}

export interface StorageStats {
  quotaBytes: number;
  usedBytes: number;
  freeBytes: number;
  usagePercent: number;
  activeBytes: number;
  trashBytes: number;
  fileCount: number;
  groups: StorageGroupUsage[];
  largestFiles: FileItem[];
}

export interface FilePreview {
  fileId: number;
  name: string;
  contentType: string;
  previewKind: "image" | "pdf" | "video" | "audio" | "text" | "binary";
  sizeBytes: number;
  textSnippet: string | null;
  contentUrl: string;
  downloadable: boolean;
}

export interface SearchItem {
  type: "file" | "folder";
  id: number;
  name: string;
  parentId: number | null;
  sizeBytes: number | null;
  createdAt: string;
  contentType?: string | null;
  extension?: string | null;
  fileGroup?: string | null;
  hasThumbnail?: boolean | null;
  matchType?: "NAME" | "TEXT" | "IMAGE" | "OCR" | "SEMANTIC";
  score?: number;
}

export interface AuthTokenResponse {
  token: string;
  tokenType: string;
  user: User;
}

export interface IntegrationClient {
  id: number;
  name: string;
  scopes: string[];
  enabled: boolean;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface IntegrationClientCreateResponse {
  id: number;
  name: string;
  apiKey: string;
  scopes: string[];
  enabled: boolean;
  createdAt: string;
}

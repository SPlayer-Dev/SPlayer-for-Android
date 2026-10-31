import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

export interface AndroidDownloadPickResult {
  cancelled: boolean;
  uri?: string;
  name?: string;
}

export interface AndroidDownloadResult {
  status: "success" | "skipped" | "failed";
  path?: string;
  fileName?: string;
}

export interface AndroidWriteFileResult {
  status: "success" | "failed";
  path?: string;
}

export interface AndroidDownloadDirectoryInfo {
  exists: boolean;
  canWrite: boolean;
  name: string;
}

export interface AndroidDownloadProgressEvent {
  bytesRead: number;
  contentLength: number;
  percent: number;
}

/** 已下载/本地音频元数据条目 */
export interface AndroidLocalAudioEntry {
  /** 用 SAF URI 哈希生成的稳定 ID（避免与在线歌曲冲突） */
  id: number;
  /** 歌曲名（优先元信息，回退到文件名解析） */
  name: string;
  /** 艺术家（字符串，前端会再格式化为 MetaData[]） */
  artists: string;
  /** 专辑名 */
  album: string;
  /** 时长（毫秒） */
  duration: number;
  /** 文件字节大小 */
  size: number;
  /** SAF URI（用作 path 字段，可被 ExoPlayer 解析） */
  path: string;
  /** 原始文件名 */
  fileName: string;
  /** 文件扩展名（不含点） */
  ext: string;
  /** 文件最后修改时间戳 */
  lastModified: number;
  /** 扫描来源目录，用于本地曲库分组 */
  sourceFolder?: string;
}

export interface AndroidLocalScanResult {
  songs: AndroidLocalAudioEntry[];
  /** 扫描失败的目录数（仅本地音乐扫描时返回） */
  failedDirectories?: number;
  filteredCount?: number;
  mediaPermissionDenied?: boolean;
}

export interface AndroidLocalMusicDirectoryRef {
  uri: string;
  name?: string;
}

export interface AndroidDownloadPlugin {
  pickDownloadDirectory(): Promise<AndroidDownloadPickResult>;
  downloadFile(options: {
    url: string;
    fileName: string;
    directoryUri: string;
    subPath?: string;
  }): Promise<AndroidDownloadResult>;
  writeTextFile(options: {
    fileName: string;
    content: string;
    directoryUri: string;
    subPath?: string;
  }): Promise<AndroidWriteFileResult>;
  getDownloadDirectoryInfo(options: {
    directoryUri: string;
  }): Promise<AndroidDownloadDirectoryInfo>;
  /** 列出下载目录里的歌曲（递归扫描） */
  listDownloadedSongs(options: { directoryUri: string }): Promise<AndroidLocalScanResult>;
  /** 选取本地音乐目录（SAF 树选择器，仅授予读权限） */
  pickLocalMusicDirectory(): Promise<AndroidDownloadPickResult>;
  checkMusicPermission(): Promise<{ granted: boolean }>;
  requestMusicPermission(): Promise<{ granted: boolean }>;
  /** 扫描本地音乐目录列表，返回所有歌曲 */
  scanLocalMusic(options: {
    directories: AndroidLocalMusicDirectoryRef[];
    includeDeviceAudio?: boolean;
    excludeWhatsApp?: boolean;
    excludedDirectories?: string[];
  }): Promise<AndroidLocalScanResult>;
  addListener(
    eventName: "downloadProgress",
    listenerFunc: (event: AndroidDownloadProgressEvent) => void,
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export const AndroidDownload = registerPlugin<AndroidDownloadPlugin>("AndroidDownload");

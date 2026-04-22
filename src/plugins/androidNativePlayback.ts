import { registerPlugin, type PluginListenerHandle } from "@capacitor/core";

export interface AndroidNativePlaybackState {
  src: string;
  songId?: number;
  paused: boolean;
  ready: boolean;
  playing: boolean;
  buffering: boolean;
  durationMs: number;
  positionMs: number;
  volume: number;
  playbackRate: number;
  errorCode: number;
}

export interface AndroidNativeLoadOptions {
  url: string;
  positionMs?: number;
  autoPlay?: boolean;
}

export interface AndroidNativeMetadataPayload {
  songId?: number;
  title: string;
  artist: string;
  album: string;
  coverUrl: string;
  durationMs: number;
  canLike?: boolean;
}

export interface AndroidNativeQueueContextPayload {
  liked: boolean;
  canSkipPrevious: boolean;
  personalFmMode: boolean;
  controllerEnabled: boolean;
  desktopLyricButtonEnabled: boolean;
  desktopLyricEnabled: boolean;
  repeatOne: boolean;
  nextTrack?: AndroidNativeMetadataPayload & {
    liked?: boolean;
    url?: string;
  };
}

export interface AndroidNativeNotificationPrefsPayload {
  controllerEnabled: boolean;
  desktopLyricButtonEnabled: boolean;
}

export interface AndroidNativeApiContextPayload {
  apiBaseUrl: string;
  cookie: string;
}

export interface AndroidNativePlaybackStateEvent {
  // 增加必要的成员定义，例如
  state: string;
}

export interface AndroidNativeProgressEvent {
  durationMs: number;
  positionMs: number;
}

export interface AndroidNativeEndedEvent {
  durationMs: number;
}

export interface AndroidNativeErrorEvent {
  errorCode: number;
  message?: string;
}

export interface AndroidNativeCustomActionEvent {
  action:
    | "next"
    | "previous"
    | "play"
    | "pause"
    | "favorite"
    | "desktopLyric"
    | "desktopLyricReady"
    | "collapse"
    | "autoNext";
  songId?: number;
  liked?: boolean;
  desktopLyricEnabled?: boolean;
  collapsed?: boolean;
  success?: boolean;
  message?: string;
}

export interface AndroidNativePermissionResult {
  granted: boolean;
}

export interface AndroidNativeFloatingLyricDataPayload {
  lrcData: string;
  yrcData: string;
}

export interface AndroidNativeFloatingLyricProgressPayload {
  timeMs: number;
  playing: boolean;
}

export interface AndroidNativeFloatingLyricSongInfoPayload {
  name: string;
  artist: string;
}

export interface AndroidNativeFloatingLyricConfigPayload {
  playedColor?: string;
  unplayedColor?: string;
  shadowColor?: string;
  backgroundMaskColor?: string;
  textBackgroundMask?: boolean;
  showTran?: boolean;
  showWordLyrics?: boolean;
  isDoubleLine?: boolean;
  animation?: boolean;
  fontSize?: number;
  fontWeight?: number;
  position?: "left" | "center" | "right" | "both";
  windowWidthPercent?: number;
  windowHeightDp?: number;
}

export interface AndroidNativePlaybackPlugin {
  load(options: AndroidNativeLoadOptions): Promise<AndroidNativePlaybackState>;
  play(): Promise<AndroidNativePlaybackState>;
  pause(): Promise<AndroidNativePlaybackState>;
  stop(): Promise<AndroidNativePlaybackState>;
  seek(options: { positionMs: number }): Promise<AndroidNativePlaybackState>;
  setVolume(options: { volume: number }): Promise<void>;
  setRate(options: { rate: number }): Promise<void>;
  updateMetadata(options: AndroidNativeMetadataPayload): Promise<void>;
  updateQueueContext(options: AndroidNativeQueueContextPayload): Promise<void>;
  updateNotificationPrefs(options: AndroidNativeNotificationPrefsPayload): Promise<void>;
  setAllowMixWithOthers(options: { allow: boolean }): Promise<void>;
  syncApiContext(options: AndroidNativeApiContextPayload): Promise<void>;
  syncRemoteState(options: {
    playing: boolean;
    positionMs: number;
    durationMs: number;
  }): Promise<void>;
  getState(): Promise<AndroidNativePlaybackState>;
  requestNotificationPermission(): Promise<AndroidNativePermissionResult>;
  showFloatingLyric(): Promise<void>;
  hideFloatingLyric(): Promise<void>;
  updateFloatingLyricData(options: AndroidNativeFloatingLyricDataPayload): Promise<void>;
  updateFloatingLyricProgress(options: AndroidNativeFloatingLyricProgressPayload): Promise<void>;
  updateFloatingLyricSongInfo(options: AndroidNativeFloatingLyricSongInfoPayload): Promise<void>;
  updateFloatingLyricConfig(options: AndroidNativeFloatingLyricConfigPayload): Promise<void>;
  checkOverlayPermission(): Promise<AndroidNativePermissionResult>;
  requestOverlayPermission(): Promise<AndroidNativePermissionResult>;
  addListener(
    eventName: "playbackStateChanged",
    listenerFunc: (event: AndroidNativePlaybackStateEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "progressChanged",
    listenerFunc: (event: AndroidNativeProgressEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "ended",
    listenerFunc: (event: AndroidNativeEndedEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "error",
    listenerFunc: (event: AndroidNativeErrorEvent) => void,
  ): Promise<PluginListenerHandle>;
  addListener(
    eventName: "customAction",
    listenerFunc: (event: AndroidNativeCustomActionEvent) => void,
  ): Promise<PluginListenerHandle>;
  removeAllListeners(): Promise<void>;
}

export const AndroidNativePlayback = registerPlugin<AndroidNativePlaybackPlugin>(
  "AndroidNativePlayback",
);

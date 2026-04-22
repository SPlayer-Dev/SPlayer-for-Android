import { isCapacitorAndroid } from "@/utils/env";
import type { PluginListenerHandle } from "@capacitor/core";
import {
  AUDIO_EVENTS,
  AudioErrorCode,
  BaseAudioPlayer,
} from "./BaseAudioPlayer";
import type { EngineCapabilities } from "./IPlaybackEngine";
import {
  AndroidNativePlayback,
  type AndroidNativePlaybackState,
} from "@/plugins/androidNativePlayback";

export class AndroidNativeAudioPlayer extends BaseAudioPlayer {
  public override readonly capabilities: EngineCapabilities = {
    supportsRate: true,
    supportsSinkId: false,
    supportsEqualizer: false,
    supportsSpectrum: false,
  };

  private sourceUrl = "";
  private durationSeconds = 0;
  private currentTimeSecondsValue = 0;
  private pausedValue = true;
  private ready = false;
  private playbackRate = 1;
  private errorCode = 0;
  private listenersBound = false;
  private listenerHandles: PluginListenerHandle[] = [];
  private pendingSeekSeconds: number | null = null;
  private pendingSeekDeadline = 0;
  private pendingSeekObservedAtTarget = false;
  private suppressPlaybackEventUntil = 0;

  /** Seek 锁，确保 seek 期间 currentTime 始终返回目标值 */
  private isSeekLocked = false;
  /** Seek 锁定的目标时间（秒） */
  private seekTargetSeconds = 0;

  protected override shouldUseAudioGraph(): boolean {
    return false;
  }

  protected override onGraphInitialized(): void {}

  protected override applyNativeVolume(
    targetValue: number,
    _duration: number,
    _curve: "linear" | "exponential" | "equalPower",
  ): void {
    if (!isCapacitorAndroid) return;
    void AndroidNativePlayback.setVolume({ volume: targetValue });
  }

  public override init(): void {
    super.init();
    if (!this.listenersBound && isCapacitorAndroid) {
      this.listenersBound = true;
      void this.bindNativeListeners();
    }
  }

  public override destroy(): void {
    super.destroy();
    this.listenersBound = false;
    this.ready = false;
    this.sourceUrl = "";
    this.durationSeconds = 0;
    this.currentTimeSecondsValue = 0;
    this.isSeekLocked = false;
    this.seekTargetSeconds = 0;
    this.pendingSeekSeconds = null;
    this.pendingSeekDeadline = 0;
    this.pendingSeekObservedAtTarget = false;
    this.suppressPlaybackEventUntil = 0;
    void this.releaseListeners();
  }

  public async load(url: string): Promise<void> {
    this.init();
    this.sourceUrl = url;
    this.ready = false;
    this.errorCode = 0;
    this.isSeekLocked = false;
    this.seekTargetSeconds = 0;
    this.currentTimeSecondsValue = 0;
    this.pendingSeekSeconds = null;
    this.pendingSeekDeadline = 0;
    this.pendingSeekObservedAtTarget = false;
    this.dispatch(AUDIO_EVENTS.LOAD_START);

    const state = await AndroidNativePlayback.load({
      url,
      autoPlay: false,
    });
    this.applyState(state);
    this.dispatch(AUDIO_EVENTS.CAN_PLAY);
  }

  protected async doPlay(): Promise<void> {
    this.applyState(await AndroidNativePlayback.play());
  }

  protected async doPause(): Promise<void> {
    this.applyState(await AndroidNativePlayback.pause());
  }

  /**
   * 跳转到指定时间
   * 已加载媒体用 seekTo()，保留 ExoPlayer 内部缓冲，避免重新 prepare
   * 导致签名 URL 二次请求失败或 Range 请求被 CDN 降级为 200 的问题。
   */
  public override async seek(time: number): Promise<void> {
    const safeTime = Math.max(0, time);
    const wasPaused = this.pausedValue;
    this.isSeekLocked = true;
    this.seekTargetSeconds = safeTime;
    this.currentTimeSecondsValue = safeTime;
    this.pendingSeekSeconds = safeTime;
    this.pendingSeekDeadline = Date.now() + 6000;
    this.pendingSeekObservedAtTarget = false;

    this.cancelPendingPause();
    this.dispatch(AUDIO_EVENTS.SEEKING);
    const state = await AndroidNativePlayback.seek({
      positionMs: Math.max(0, Math.round(safeTime * 1000)),
    });
    this.applyState(state);
    if (wasPaused && !this.pausedValue) {
      this.suppressPlaybackEventUntil = Date.now() + 1000;
      this.applyState(await AndroidNativePlayback.pause());
    }
    this.isSeekLocked = false;
    this.dispatch(AUDIO_EVENTS.SEEKED);
  }

  protected async doSeek(time: number): Promise<void> {
    this.dispatch(AUDIO_EVENTS.SEEKING);
    const safeTime = Math.max(0, time);
    this.currentTimeSecondsValue = safeTime;
    this.pendingSeekSeconds = safeTime;
    this.pendingSeekDeadline = Date.now() + 6000;
    this.pendingSeekObservedAtTarget = false;
    const state = await AndroidNativePlayback.seek({
      positionMs: Math.max(0, Math.round(safeTime * 1000)),
    });
    this.applyState(state);
    this.dispatch(AUDIO_EVENTS.SEEKED);
  }

  public override stop(): void {
    this.cancelPendingPause();
    this.isSeekLocked = false;
    this.seekTargetSeconds = 0;
    this.pendingSeekSeconds = null;
    this.pendingSeekDeadline = 0;
    this.pendingSeekObservedAtTarget = false;
    void AndroidNativePlayback.stop().then((state) => {
      this.applyState(state);
    });
  }

  public setRate(value: number): void {
    this.playbackRate = value;
    void AndroidNativePlayback.setRate({ rate: value });
  }

  public getRate(): number {
    return this.playbackRate;
  }

  protected async doSetSinkId(_deviceId: string): Promise<void> {
    return Promise.resolve();
  }

  public get src(): string {
    return this.sourceUrl;
  }

  public get duration(): number {
    return this.durationSeconds;
  }

  public get currentTime(): number {
    if (this.isSeekLocked) {
      return this.seekTargetSeconds;
    }
    return this.currentTimeSecondsValue;
  }

  public get paused(): boolean {
    return this.pausedValue;
  }

  public getErrorCode(): number {
    return this.errorCode;
  }

  private async bindNativeListeners() {
    this.listenerHandles.push(
      await AndroidNativePlayback.addListener("playbackStateChanged", (event) => {
        const previousReady = this.ready;
        const previousPaused = this.pausedValue;
        this.applyState(event);

        if (this.ready && !previousReady) {
          this.dispatch(AUDIO_EVENTS.CAN_PLAY);
        }

        if (
          previousPaused !== this.pausedValue &&
          Date.now() >= this.suppressPlaybackEventUntil
        ) {
          this.dispatch(this.pausedValue ? AUDIO_EVENTS.PAUSE : AUDIO_EVENTS.PLAY);
        }
      }),
    );

    this.listenerHandles.push(
      await AndroidNativePlayback.addListener("progressChanged", (event) => {
        this.durationSeconds = Math.max(0, event.durationMs) / 1000;
        const nextTime = Math.max(0, event.positionMs) / 1000;
        if (this.shouldIgnoreRegressiveSeekUpdate(nextTime)) {
          return;
        }
        this.currentTimeSecondsValue = nextTime;
        this.maybeCompletePendingSeek(nextTime);
        this.dispatch(AUDIO_EVENTS.TIME_UPDATE);
      }),
    );

    this.listenerHandles.push(
      await AndroidNativePlayback.addListener("ended", (event) => {
        this.currentTimeSecondsValue = Math.max(0, event.durationMs) / 1000;
        this.pausedValue = true;
        this.dispatch(AUDIO_EVENTS.TIME_UPDATE);
        this.dispatch(AUDIO_EVENTS.ENDED);
      }),
    );

    this.listenerHandles.push(
      await AndroidNativePlayback.addListener("error", (event) => {
        this.errorCode = event.errorCode || AudioErrorCode.NETWORK;
        this.dispatch(AUDIO_EVENTS.ERROR, {
          originalEvent: new Event("error"),
          errorCode: this.errorCode,
        });
      }),
    );
  }

  private async releaseListeners() {
    const handles = [...this.listenerHandles];
    this.listenerHandles = [];
    await Promise.allSettled(handles.map((handle) => handle.remove()));
  }

  private applyState(state: Partial<AndroidNativePlaybackState>) {
    if (typeof state.src === "string") {
      this.sourceUrl = state.src;
    }
    if (typeof state.durationMs === "number") {
      this.durationSeconds = Math.max(0, state.durationMs) / 1000;
    }
    if (typeof state.positionMs === "number") {
      const nextTime = Math.max(0, state.positionMs) / 1000;
      if (!this.shouldIgnoreRegressiveSeekUpdate(nextTime)) {
        this.currentTimeSecondsValue = nextTime;
      }
    }
    if (typeof state.paused === "boolean") {
      this.pausedValue = state.paused;
    } else if (typeof state.playing === "boolean") {
      this.pausedValue = !state.playing;
    }
    if (typeof state.ready === "boolean") {
      this.ready = state.ready;
    }
    if (typeof state.playbackRate === "number") {
      this.playbackRate = state.playbackRate;
    }
    if (typeof state.errorCode === "number") {
      this.errorCode = state.errorCode;
    }
    if (Date.now() >= this.suppressPlaybackEventUntil) {
      this.suppressPlaybackEventUntil = 0;
    }
  }

  private shouldIgnoreRegressiveSeekUpdate(nextTime: number) {
    if (this.pendingSeekSeconds === null) {
      return false;
    }

    if (Date.now() > this.pendingSeekDeadline) {
      this.isSeekLocked = false;
      this.pendingSeekSeconds = null;
      this.pendingSeekDeadline = 0;
      this.pendingSeekObservedAtTarget = false;
      return false;
    }

    return nextTime + 0.25 < this.pendingSeekSeconds;
  }

  private maybeCompletePendingSeek(nextTime: number) {
    if (this.pendingSeekSeconds === null) {
      return;
    }

    if (Math.abs(nextTime - this.pendingSeekSeconds) >= 1.5) {
      return;
    }

    if (!this.pendingSeekObservedAtTarget) {
      this.pendingSeekObservedAtTarget = true;
      return;
    }

    this.pendingSeekSeconds = null;
    this.pendingSeekDeadline = 0;
    this.pendingSeekObservedAtTarget = false;
  }
}

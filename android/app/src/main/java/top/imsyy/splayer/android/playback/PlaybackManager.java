package top.imsyy.splayer.android.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Base64;
import android.util.Log;
import android.view.KeyEvent;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import top.imsyy.splayer.android.MainActivity;
import top.imsyy.splayer.android.R;
import top.imsyy.splayer.android.cache.AudioCacheProvider;
import top.imsyy.splayer.android.cache.AudioPrefetchTtlIndex;

@UnstableApi
public final class PlaybackManager {
  private static final String TAG = "PlaybackManager";
  private static final String NOTIFICATION_ICON_FONT_ASSET = "iconfont_notification.ttf";
  private static final String ICON_GLYPH_LYRIC = "\ue600";
  private static final String ICON_GLYPH_FAVORITE_FILLED = "\ue601";
  private static final String ICON_GLYPH_FAVORITE_OUTLINE = "\ue60a";
  private static final String ICON_GLYPH_PREVIOUS = "\ue63c";
  private static final String ICON_GLYPH_PLAY = "\ue63d";
  private static final String ICON_GLYPH_NEXT = "\ue63e";
  private static final String ICON_GLYPH_PAUSE = "\ue65f";
  private static final int FAVORITE_REQUEST_MAX_ATTEMPTS = 3;
  private static final long FAVORITE_REQUEST_RETRY_DELAY_MS = 350L;
  private static final long SEEK_STATE_GRACE_MS = 4000L;
  private static final long SEEK_POSITION_TOLERANCE_MS = 1500L;
  private static final int CONTENT_MIME_CACHE_MAX_SIZE = 1024;
  private static volatile PlaybackManager instance;

  private final Context appContext;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
  /** 仅服务于 favorite 等非播放路径；URL 解析走 urlResolver 内部 2 线程池避免被 favorite 阻塞。 */
  private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
  /**
   * URL 解析请求 token：每次 NEXT/PREV/ENDED 触发的解析自增 token，回调时若 token 已过期（用户连点
   * 又起了一次解析）直接丢弃结果，防止旧请求覆盖播放器状态。
   */
  private final java.util.concurrent.atomic.AtomicLong resolveTokenCounter =
      new java.util.concurrent.atomic.AtomicLong();
  private final AtomicLong artworkTokenCounter = new AtomicLong();

  private ExoPlayer player;
  /** 暴露给 MediaSession 的包装 Player：覆写 availableCommands，让系统媒体面板始终展示上一/下一首。 */
  private Player sessionPlayer;
  private MediaSession mediaSession;
  private volatile PlaybackService service;
  /** PlaybackService 是否已通过 onCreate→attachService 启动并保持运行；用于 ensureServiceRunning 节流。 */
  private volatile boolean serviceStarted;
  private AndroidNativePlaybackPlugin plugin;
  private Bitmap coverBitmap;
  /** 封面 JPEG 编码缓存，仅在 coverBitmap 变化时重压。 */
  private byte[] coverArtworkBytes;
  private Typeface notificationIconTypeface;

  /** volatile：promotion runnable 不持 this monitor 就能读到最新值（修复 #7 避免主线程锁竞争 → ANR 抖动） */
  private volatile String currentSource = "";
  private String apiBaseUrl = "";
  private String cookie = "";
  private String songLevel = "exhigh";
  private boolean disableAiAudio = false;

  /**
   * 当前 promotion 任务 —— load() 时排 10s timer，到期仍是这首歌则把它从 prefetch 升级为
   * promoted（正式缓存）并启动完整下载（为 automix 做准备）。<br>
   * 切歌 / stop / cleanup 时通过 {@link #cancelPromotion} 取消。
   *
   * <p>volatile：Runnable 末尾在主线程内清 null，而 cancelPromotion 经 synchronized 方法可能从
   * 其他线程进入读，需跨线程可见性保证。
   */
  @Nullable private volatile Runnable pendingPromotionRunnable;
  /** promotion 触发阈值：用户持续播放 10 秒 → 视为「真的喜欢」。 */
  private static final long PROMOTE_AFTER_MS = 10_000L;
  /** Java 端 URL 解析器：WebView 冻结时仍可自治取地址。 */
  private final PlaybackUrlResolver urlResolver = new PlaybackUrlResolver();
  private TrackMetadata currentMetadata = new TrackMetadata();
  private static final int NATIVE_ERROR_RECOVERY_MAX_ATTEMPTS = 2;
  private long nativeRecoverySongId = 0L;
  private int nativeRecoveryAttempts = 0;
  /**
   * Java 端自治播放队列（滑动窗口）。
   *
   * <p>JS 推 ±N 首窗口；ENDED / NEXT / PREVIOUS 均由 Java 自治。详见 .scratch/native-queue-design.md。
   */
  private final PlaybackQueue playbackQueue = new PlaybackQueue();
  /** 即将耗尽窗口（剩 ≤ 此数）时 emit requestUrls 让 JS 补；2 给 JS 留一定缓冲。 */
  private static final int WINDOW_REFILL_THRESHOLD = 2;

  private boolean controllerEnabled = true;
  private boolean desktopLyricButtonEnabled = false;
  private boolean desktopLyricEnabled = false;
  /** 允许与其他应用同时播放（关闭时才请求音频焦点，抢占其他应用） */
  private boolean allowMixWithOthers = true;
  private boolean canSkipPrevious = true;
  private boolean personalFmMode = false;
  private boolean liked = false;
  private boolean collapsed = false;
  /**
   * ENDED 拦截标记：当窗口耗尽且 hasNextOutsideWindow=true 时，emit requestUrls 后置位。
   * 下一次 updateQueueContext 收到新窗口后立即 advance + playFromQueue 续播，弥补
   * "JS 不会主动 play"的链路缺口。
   */
  private boolean pendingResumeAfterRefill = false;
  private boolean favoriteRequestInFlight = false;
  private long pendingSeekPositionMs = C.TIME_UNSET;
  private long pendingSeekDeadlineMs = 0L;
  private long lastKnownPositionMs = 0L;
  /** 已用 ExoPlayer 真实 duration 校准过的 source URL。 */
  private String durationCalibratedForSource = "";
  /** 悬浮歌词服务实例（运行时绑定） */
  private FloatingLyricService floatingLyricService;
  /** 远程状态同步：JS 端 AudioElementPlayer 驱动播放时，通知栏状态由此控制 */
  private boolean remoteMode = false;
  private boolean remoteIsPlaying = false;
  private long remotePositionMs = 0L;
  private long remoteDurationMs = 0L;
  /** Remote mode 下保持 CPU 唤醒，防止后台音频中断 */
  private PowerManager.WakeLock remoteWakeLock;
  /**
   * 音频频谱分析器：实现 Media3 AudioProcessor，挂在 ExoPlayer 解码链上做无权限 FFT 分析。
   *
   * 设计成单实例随 PlaybackManager 生命周期：当前是单 ExoPlayer 架构，FFT 处理器随之单实例；
   * 未来 Automix 复活引入双 ExoPlayer 时，需重构为 currentProcessor / nextProcessor 双实例，
   * listener 跟随 currentPlayer 切换（参考 memory: Android Automix 复活约束）。
   */
  private final FftAudioProcessor fftAudioProcessor = new FftAudioProcessor();
  /** 频谱是否已启用（JS 端通过 enableVisualizer 控制 listener 设置） */
  private boolean visualizerRequested = false;
  private final SessionCommand nextSessionCommand =
      new SessionCommand(PlaybackConstants.ACTION_NEXT, Bundle.EMPTY);
  private final SessionCommand previousSessionCommand =
      new SessionCommand(PlaybackConstants.ACTION_PREVIOUS, Bundle.EMPTY);
  private final SessionCommand favoriteSessionCommand =
      new SessionCommand(PlaybackConstants.ACTION_FAVORITE, Bundle.EMPTY);
  private final SessionCommand desktopLyricSessionCommand =
      new SessionCommand(PlaybackConstants.ACTION_DESKTOP_LYRIC, Bundle.EMPTY);
  private final MediaSession.Callback mediaSessionCallback =
      new MediaSession.Callback() {
        @Override
        public MediaSession.ConnectionResult onConnect(
            MediaSession session, MediaSession.ControllerInfo controller) {
          return new MediaSession.ConnectionResult.AcceptedResultBuilder(session)
              .setAvailableSessionCommands(buildAvailableSessionCommands())
              .setAvailablePlayerCommands(buildAvailablePlayerCommands())
              .setCustomLayout(buildCustomLayout())
              .setMediaButtonPreferences(buildMediaButtonPreferences())
              .setSessionActivity(buildContentIntent())
              .build();
        }

        @Override
        public int onPlayerCommandRequest(
            MediaSession session, MediaSession.ControllerInfo controller, int playerCommand) {
          switch (playerCommand) {
            case Player.COMMAND_SEEK_TO_NEXT:
            case Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM:
              // 转发到 JS；返回 SKIPPED 避免控制器缓存 NOT_SUPPORTED 状态误判后续点击。
              handleSessionAction(PlaybackConstants.ACTION_NEXT);
              return SessionResult.RESULT_INFO_SKIPPED;
            case Player.COMMAND_SEEK_TO_PREVIOUS:
            case Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
              // 与 NEXT 对齐：无条件转发到 JS，由 JS 自行判断边界（personalFM/列表首尾等）。
              // 不在 native 端做条件检查 + 返回 NOT_SUPPORTED——某些 Media3 控制器（系统媒体面板/
              // 锁屏）会缓存 NOT_SUPPORTED 状态，导致后续 setAvailableCommands 重新声明可用后
              // 按钮点击仍不发命令，直到控制器重连（切歌单等场景）才解锁。
              handleSessionAction(PlaybackConstants.ACTION_PREVIOUS);
              return SessionResult.RESULT_INFO_SKIPPED;
            default:
              return MediaSession.Callback.super.onPlayerCommandRequest(
                  session, controller, playerCommand);
          }
        }

        @Override
        public ListenableFuture<SessionResult> onCustomCommand(
            MediaSession session,
            MediaSession.ControllerInfo controller,
            SessionCommand customCommand,
            Bundle args) {
          return Futures.immediateFuture(handleCustomCommand(customCommand));
        }

        @Override
        public boolean onMediaButtonEvent(
            MediaSession session, MediaSession.ControllerInfo controller, Intent intent) {
          KeyEvent keyEvent = extractKeyEvent(intent);
          if (keyEvent == null || keyEvent.getAction() != KeyEvent.ACTION_DOWN) {
            return false;
          }

          switch (keyEvent.getKeyCode()) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
              return handleSessionAction(PlaybackConstants.ACTION_NEXT);
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
              return handleSessionAction(PlaybackConstants.ACTION_PREVIOUS);
            case KeyEvent.KEYCODE_MEDIA_PLAY:
              play();
              return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
              pause();
              return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
              handleNotificationAction(PlaybackConstants.ACTION_TOGGLE_PLAYBACK);
              return true;
            default:
              return false;
          }
        }
      };

  private final Runnable progressRunnable =
      new Runnable() {
        @Override
        public void run() {
          // 仅在真正播放时 emit；callback 链常驻，BUFFERING→READY 后下一拍立即续传
          if (player != null && player.isPlaying()) {
            emitProgressChanged();
          }
          if (player != null && player.getCurrentMediaItem() != null) {
            mainHandler.postDelayed(this, 250L);
          }
        }
      };

  private PlaybackManager(Context context) {
    this.appContext = context.getApplicationContext();
    AudioCacheProvider.setDiagnosticListener(
        (tag, message) -> mainHandler.post(() -> emitDiagnosticLog(tag, message)));
  }

  public static PlaybackManager getInstance(Context context) {
    if (instance == null) {
      synchronized (PlaybackManager.class) {
        if (instance == null) {
          instance = new PlaybackManager(context);
        }
      }
    }
    return instance;
  }

  public synchronized void attachService(PlaybackService playbackService) {
    service = playbackService;
    serviceStarted = true;
    ensureInitialized();
    updateNotification();
  }

  public synchronized void detachService(PlaybackService playbackService) {
    if (service == playbackService) {
      service = null;
      serviceStarted = false;
    }
  }

  public synchronized void attachPlugin(AndroidNativePlaybackPlugin playbackPlugin) {
    plugin = playbackPlugin;
    emitPlaybackState(true);
  }

  public synchronized void detachPlugin(AndroidNativePlaybackPlugin playbackPlugin) {
    if (plugin == playbackPlugin) {
      plugin = null;
      // plugin 销毁后 emit 没接收方，清 listener 让 FftAudioProcessor 跳过 FFT 计算节电
      visualizerRequested = false;
      fftAudioProcessor.setListener(null);
    }
  }

  public synchronized MediaSession getSession() {
    ensureInitialized();
    return mediaSession;
  }

  public synchronized JSObject load(String url, long positionMs, boolean autoPlay) {
    Log.d(TAG, "load: url=" + url + " positionMs=" + positionMs + " autoPlay=" + autoPlay);
    ensureInitialized();
    ensureServiceRunning();

    // ExoPlayer 直接驱动播放，退出 remote mode（JS 驱动模式）
    remoteMode = false;
    currentSource = url == null ? "" : url;
    currentMetadata.url = currentSource;

    // 取消上一首未到期的 10s promotion timer：用户已切歌，旧任务无意义
    cancelPromotion();

    // 实际播放：给该 cacheKey 续期 TTL + 排 10s timer 决定是否升级正式缓存
    if (!currentSource.isEmpty()) {
      try {
        android.net.Uri parsed = android.net.Uri.parse(currentSource);
        String scheme = parsed.getScheme();
        if (scheme != null && (scheme.equals("http") || scheme.equals("https"))) {
          final String cacheKey = AudioCacheProvider.resolveCacheKey(parsed);
          final String sourceSnapshot = currentSource;
          AudioPrefetchTtlIndex ttlIndex = AudioPrefetchTtlIndex.getInstance(appContext);
          ttlIndex.markAccess(cacheKey);
          // 已 promoted 的歌不再排 timer（已是正式缓存，预载也已经触发过）
          if (!ttlIndex.isPromoted(cacheKey)) {
            schedulePromotion(cacheKey, sourceSnapshot);
          }
        }
      } catch (Throwable e) {
        // mark 失败不影响播放
      }
    }
    clearPendingSeek();
    lastKnownPositionMs = 0L;
    // 失效在路上的 async resolve 回调；清旧 pending 避免补窗误消费
    resolveTokenCounter.incrementAndGet();
    pendingResumeAfterRefill = false;

    // 参考 SPlayer-ROM-Compat：先 setMediaItem + prepare，再 seekTo，最后 playWhenReady
    // 不用 setMediaItem(item, startPositionMs) ——某些 ROM/容器格式下 startPositionMs
    // 在 IDLE→prepare 转换中可能被丢弃，导致实际从 0 开始播放（UI 显示 seek 位置但音频从头放）
    player.setMediaItem(buildMediaItem(currentSource));
    player.prepare();
    durationCalibratedForSource = "";
    if (positionMs > 0) {
      player.seekTo(positionMs);
      beginPendingSeek(positionMs);
    }
    player.setPlayWhenReady(autoPlay);
    updateNotification();
    emitPlaybackState(true);
    return buildState();
  }

  public synchronized JSObject play() {
    ensureInitialized();
    ensureServiceRunning();
    player.play();
    updateNotification();
    emitPlaybackState(true);
    return buildState();
  }

  public synchronized JSObject pause() {
    ensureInitialized();
    player.pause();
    updateNotification();
    emitPlaybackState(true);
    return buildState();
  }

  public synchronized JSObject stop() {
    ensureInitialized();
    // 软停止：不清 MediaItem / 通知栏，留给下一次 load() 无缝替换，避免切歌瞬间闪烁。
    // 进程级清理走 cleanup() / onDestroy。
    player.pause();
    player.seekTo(0L);
    clearPendingSeek();
    lastKnownPositionMs = 0L;
    stopProgressUpdates();
    emitPlaybackState(true);
    return buildState();
  }

  /**
   * 排定 promotion timer：10s 后若仍是这首歌且 ExoPlayer 处于播放态，
   * 则把 cacheKey 升级为正式缓存 + 启动完整下载（为 automix 铺路）。
   *
   * <p>切歌 / stop / cleanup 时通过 {@link #cancelPromotion} 取消未到期的 timer。
   */
  private void schedulePromotion(@NonNull String cacheKey, @NonNull String sourceSnapshot) {
    cancelPromotion(); // 防御性：理论上 load 已调过，二次保险
    final String urlSnapshot = sourceSnapshot;
    final Runnable[] holder = new Runnable[1];
    Runnable r = () -> {
      // 主线程执行：currentSource 是 volatile 可直接读；player.isPlaying() / getPlayWhenReady() 必须在 applicationLooper（即主线程）调用。
      // 修复 #7：不再持 this monitor，避免与 Capacitor 工作线程上的 synchronized load/play/pause 竞争 → 减少 ANR 抖动。
      // 这里读 currentSource 是「快照对比」语义，跟 load() 写入的最终一致性可接受：若新一首 load 已经写入 currentSource，本 Runnable 会自然短路退出。
      final String curSource = currentSource;
      if (!urlSnapshot.equals(curSource)) {
        if (pendingPromotionRunnable == holder[0]) pendingPromotionRunnable = null;
        return;
      }
      if (!player.isPlaying() && !player.getPlayWhenReady()) {
        if (pendingPromotionRunnable == holder[0]) pendingPromotionRunnable = null;
        return;
      }
      // compare-and-clear：避免覆盖竞态间已经被 cancel/重排的新 Runnable 引用。
      // pendingPromotionRunnable 是 volatile，主线程内的写入对其他线程立即可见。
      if (pendingPromotionRunnable == holder[0]) pendingPromotionRunnable = null;
      // 不在此处立即写 promoted 标记：若网络中断，automix 可能读到截断文件。
      // 改由 AudioCacheProvider.prefetchUrlFull 在 CacheWriter 真正写完整后调 promote()。
      // 本处仅触发下载，AudioCacheProvider 内部 inFlight + isPromoted 短路保证幂等。
      AudioCacheProvider.prefetchUrlFull(appContext, urlSnapshot);
      Log.d(TAG, "promotion scheduled (download starts): " + cacheKey);
    };
    holder[0] = r;
    pendingPromotionRunnable = r;
    mainHandler.postDelayed(r, PROMOTE_AFTER_MS);
  }

  /** 取消未到期的 promotion timer。幂等。调用方需持 PlaybackManager 锁（load/cleanup 已 synchronized）。 */
  private void cancelPromotion() {
    Runnable r = pendingPromotionRunnable;
    if (r != null) {
      mainHandler.removeCallbacks(r);
      pendingPromotionRunnable = null;
    }
  }

  /** 用户确认退出 App：清播放、停前台服务、停桌面歌词服务 */
  public synchronized void shutdownAll() {
    try {
      cleanup();
    } catch (Exception e) {
      Log.w(TAG, "shutdownAll cleanup failed", e);
    }
    try {
      Intent floating = new Intent(appContext, FloatingLyricService.class);
      appContext.stopService(floating);
      floatingLyricService = null;
    } catch (Exception e) {
      Log.w(TAG, "shutdownAll stop floating lyric failed", e);
    }
    try {
      Intent playback = new Intent(appContext, PlaybackService.class);
      appContext.stopService(playback);
      serviceStarted = false;
    } catch (Exception e) {
      Log.w(TAG, "shutdownAll stop playback service failed", e);
    }
  }

  /** 硬清理：清播放列表 / 登入登出等场景，清空 MediaItem、通知栏、queuedNext 及快路径锁。 */
  public synchronized JSObject cleanup() {
    ensureInitialized();
    player.pause();
    player.seekTo(0L);
    player.stop();
    player.clearMediaItems();
    currentSource = "";
    cancelPromotion();
    clearPendingSeek();
    lastKnownPositionMs = 0L;
    playbackQueue.replace(null, -1, PlaybackQueue.RepeatMode.OFF, false, false, false);
    durationCalibratedForSource = "";
    // 清 token / pending，避免旧回调搅动新状态
    resolveTokenCounter.incrementAndGet();
    pendingResumeAfterRefill = false;
    stopProgressUpdates();
    clearNotification();
    emitPlaybackState(true);
    return buildState();
  }

  public synchronized JSObject seek(long positionMs) {
    ensureInitialized();
    long safePositionMs = Math.max(0L, positionMs);
    beginPendingSeek(safePositionMs);
    remoteMode = false;

    if (player.getCurrentMediaItem() == null
        || currentSource == null
        || currentSource.isEmpty()
        || player.getPlaybackState() == Player.STATE_IDLE) {
      boolean wasPlaying = player.getPlayWhenReady();
      player.setMediaItem(buildMediaItem(currentSource), safePositionMs);
      player.prepare();
      player.setPlayWhenReady(wasPlaying);
    } else {
      player.seekTo(safePositionMs);
    }

    updateNotification();
    emitPlaybackState(true);
    return buildState();
  }

  public synchronized void setVolume(float volume) {
    ensureInitialized();
    player.setVolume(Math.max(0f, Math.min(1f, volume)));
    emitPlaybackState(false);
    updateNotification();
  }

  public synchronized void setRate(float rate) {
    ensureInitialized();
    player.setPlaybackSpeed(Math.max(0.25f, Math.min(3f, rate)));
    emitPlaybackState(false);
    updateNotification();
  }

  public synchronized void updateMetadata(TrackMetadata metadata) {
    currentMetadata = metadata == null ? new TrackMetadata() : metadata;
    loadCoverBitmapAsync(currentMetadata.coverUrl);
    // 把最新元信息推回 ExoPlayer 当前 MediaItem，让锁屏 / 系统媒体面板同步刷新
    refreshCurrentMediaItemMetadata();
    updateMediaSessionButtons();
    updateNotification();
    emitPlaybackState(true);
  }

  /**
   * 接收 JS 推送的队列窗口。每次切歌 / 列表变化 / repeat/shuffle 变化都会调一次。
   *
   * @param windowTracks 当前 ±N 首（已按 shuffle 排序）
   * @param hasPreviousOutsideWindow / hasNextOutsideWindow 提示 Java：边缘时需 emit requestUrls
   */
  public synchronized void updateQueueContext(
      boolean likedState,
      boolean canSkipPreviousState,
      boolean personalFmModeState,
      boolean controllerEnabledState,
      boolean desktopLyricButtonEnabledState,
      boolean desktopLyricEnabledState,
      @Nullable List<PlaybackQueue.Track> windowTracks,
      int windowCurrentIndex,
      String repeatMode,
      boolean hasPreviousOutsideWindow,
      boolean hasNextOutsideWindow,
      boolean windowRefilled,
      boolean windowResetFromWrap) {
    liked = likedState;
    currentMetadata.liked = likedState;
    canSkipPrevious = canSkipPreviousState;
    personalFmMode = personalFmModeState;
    controllerEnabled = controllerEnabledState;
    desktopLyricButtonEnabled = desktopLyricButtonEnabledState;
    desktopLyricEnabled = desktopLyricEnabledState;

    playbackQueue.replace(
        windowTracks,
        windowCurrentIndex,
        PlaybackQueue.RepeatMode.fromString(repeatMode),
        hasPreviousOutsideWindow,
        hasNextOutsideWindow,
        personalFmModeState);

    // 立即预解析前方未解析项，避免后台 ENDED 时才发现无 URL。
    prefetchUpcomingUrls();
    requestUrlsIfWindowExhausted();
    updateMediaSessionButtons();
    updateNotification();
    emitPlaybackState(false);

    // ENDED 续播链路：上次 ENDED 因窗口耗尽 emit 了 requestUrls，现在新窗口已到位，
    // Java 必须主动驱动下一首；否则 ExoPlayer 停在 ENDED，用户感知"卡死"。
    //
    // 仅当 JS 端通过 refreshAndroidQueueWindow 路径推送（windowRefilled=true）才视为有效补窗响应。
    // 其他无关路径（liked 切换、桌面歌词开关、本地元数据加载等）也会调 syncAndroidPlaybackContext，
    // 若不门控会导致那些 sync 误触发续播，播错歌或播未完全 resolve 的曲目。
    if (pendingResumeAfterRefill && windowRefilled) {
      pendingResumeAfterRefill = false;
      // 修复 #3：续播取曲方式按 wrap 与否分流：
      // - wrap=true（末尾 ALL wrap）：JS 已把 playIndex 重置到 0，windowCurrentIndex 指向 track 0，
      //   直接 current() 播；advanceRaw 会跳到 track 1，丢掉 track 0。
      // - wrap=false（窗口右滑）：JS 推送时 windowCurrentIndex 仍指向刚结束的曲目，
      //   必须 advanceRaw(false) 推进到下一首；用 current() 会重播刚结束的歌。
      PlaybackQueue.Track resume =
          windowResetFromWrap ? playbackQueue.current() : playbackQueue.advanceRaw(false);
      if (resume != null) {
        resolveAndPlayAsync(resume, "auto", true, 5);
      } else {
        emitCustomAction("next", null, null, null, null, true, null);
      }
    }
  }

  public synchronized void updateNotificationPrefs(
      boolean controllerEnabledState, boolean desktopLyricButtonEnabledState) {
    controllerEnabled = controllerEnabledState;
    desktopLyricButtonEnabled = desktopLyricButtonEnabledState;
    updateMediaSessionButtons();
    updateNotification();
  }

  /**
   * 设置是否允许与其他应用同时播放。
   * true：不请求音频焦点，允许与其他应用混音；
   * false：由 ExoPlayer 独占音频焦点，开始播放会暂停其他应用。
   */
  public synchronized void setAllowMixWithOthers(boolean allow) {
    allowMixWithOthers = allow;
    if (player != null) {
      player.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
              .build(),
          !allowMixWithOthers);
    }
  }

  public synchronized void syncApiContext(
      String baseUrl, String cookieValue, String level, boolean disableAiAudioState) {
    apiBaseUrl = baseUrl == null ? "" : baseUrl.trim();
    cookie = cookieValue == null ? "" : cookieValue.trim();
    disableAiAudio = disableAiAudioState;
    if (level != null && !level.isEmpty()) {
      songLevel = level;
    }
    urlResolver.updateContext(apiBaseUrl, cookie, songLevel, disableAiAudio);
    prefetchUpcomingUrls();
  }

  /**
   * 远程状态同步：JS 端 AudioElementPlayer 驱动播放时，
   * 由 JS 主动推送播放状态，通知栏据此显示。
   */
  public synchronized void syncRemoteState(boolean playing, long positionMs, long durationMs) {
    ensureInitialized();
    ensureServiceRunning();
    remoteMode = true;
    remoteIsPlaying = playing;
    remotePositionMs = Math.max(0L, positionMs);
    remoteDurationMs = Math.max(0L, durationMs);
    updateRemoteWakeLock();
    updateNotification();
  }

  /** 通知栏应该显示"正在播放"还是"已暂停" */
  private boolean isEffectivelyPlaying() {
    if (remoteMode) return remoteIsPlaying;
    return player != null && player.isPlaying();
  }

  /** 通知栏应该显示"正在缓冲"吗 */
  private boolean isEffectivelyBuffering() {
    if (remoteMode) return false;
    return player != null && player.getPlaybackState() == Player.STATE_BUFFERING;
  }

  /** 在 remote mode 播放时持有 WakeLock，暂停/停止时释放 */
  private void updateRemoteWakeLock() {
    if (remoteMode && remoteIsPlaying) {
      if (remoteWakeLock == null) {
        PowerManager pm = (PowerManager) appContext.getSystemService(Context.POWER_SERVICE);
        remoteWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SPlayer::RemoteAudio");
        remoteWakeLock.setReferenceCounted(false);
      }
      if (!remoteWakeLock.isHeld()) {
        remoteWakeLock.acquire(4 * 60 * 60 * 1000L); // 最长 4 小时
      }
    } else {
      if (remoteWakeLock != null && remoteWakeLock.isHeld()) {
        remoteWakeLock.release();
      }
    }
  }

  public synchronized JSObject buildState() {
    ensureInitialized();

    JSObject state = new JSObject();
    state.put("src", currentSource);
    state.put("songId", currentMetadata.songId);
    state.put("paused", !player.getPlayWhenReady() || !player.isPlaying());
    state.put("ready", player.getPlaybackState() == Player.STATE_READY);
    state.put("playing", player.isPlaying());
    state.put("buffering", player.getPlaybackState() == Player.STATE_BUFFERING);
    state.put("durationMs", getDurationMs());
    state.put("positionMs", getPositionMs());
    state.put("volume", player.getVolume());
    state.put("playbackRate", player.getPlaybackParameters().speed);
    state.put("errorCode", 0);
    return state;
  }

  public synchronized void handleNotificationAction(String action) {
    if (action == null) {
      return;
    }

    switch (action) {
      case PlaybackConstants.ACTION_TOGGLE_PLAYBACK:
        if (remoteMode) {
          // remote 模式下转发给 JS 处理
          emitCustomAction(
              remoteIsPlaying ? "pause" : "play",
              null, null, null, null, true, null);
        } else {
          if (player == null) {
            return;
          }
          if (player.isPlaying()) {
            pause();
          } else {
            play();
          }
        }
        break;
      case PlaybackConstants.ACTION_NEXT:
        // 全自治：advanceRaw 取下一首（允许 url==null），resolveAndPlayAsync 补 URL。
        if (!personalFmMode) {
          PlaybackQueue.Track next = playbackQueue.advanceRaw(false);
          if (next != null) {
            resolveAndPlayAsync(next, "next", true, 5);
            break;
          }
          // 窗口右缘耗尽：JS 端补窗口后续播。覆盖两种场景：
          // 1. 还有后续歌（hasNextOutsideWindow=true）：JS 推下一页窗口
          // 2. 已是末尾 + ALL 模式（hasPreviousOutsideWindow=true）：JS 负责 wrap 到 0
          if (shouldRequestUrlsForwardWrap()) {
            pendingResumeAfterRefill = true;
            emitRequestUrls();
            break;
          }
        }
        // personalFM / 队列空 / 窗口全耗尽：回退 JS
        emitCustomAction("next", null, null, null, null, true, null);
        break;
      case PlaybackConstants.ACTION_PREVIOUS:
        // 同 ACTION_NEXT 设计：Java 自解 URL，不依赖 WebView。
        if (!personalFmMode) {
          PlaybackQueue.Track prev = playbackQueue.backRaw();
          if (prev != null) {
            resolveAndPlayAsync(prev, "previous", false, 3);
            break;
          }
        }
        emitCustomAction("previous", null, null, null, null, true, null);
        break;
      case PlaybackConstants.ACTION_FAVORITE:
        toggleFavoriteAsync();
        break;
      case PlaybackConstants.ACTION_DESKTOP_LYRIC:
        desktopLyricEnabled = !desktopLyricEnabled;
        // 直接控制悬浮歌词服务
        if (desktopLyricEnabled) {
          showFloatingLyric();
        } else {
          hideFloatingLyric();
        }
        updateMediaSessionButtons();
        updateNotification();
        emitCustomAction("desktopLyric", null, null, desktopLyricEnabled, null, true, null);
        break;
      case PlaybackConstants.ACTION_COLLAPSE:
        collapsed = !collapsed;
        updateNotification();
        emitCustomAction("collapse", null, null, null, collapsed, true, null);
        break;
      default:
        break;
    }
  }

  private boolean handleSessionAction(String action) {
    if (action == null) {
      return false;
    }

    switch (action) {
      case PlaybackConstants.ACTION_NEXT:
        handleNotificationAction(action);
        return true;
      case PlaybackConstants.ACTION_PREVIOUS:
        // 不返 NOT_SUPPORTED：控制器会缓存该状态导致后续点击哑火。
        handleNotificationAction(action);
        return true;
      case PlaybackConstants.ACTION_FAVORITE:
        if (!currentMetadata.canLike) {
          return false;
        }
        handleNotificationAction(action);
        return true;
      case PlaybackConstants.ACTION_DESKTOP_LYRIC:
        if (!desktopLyricButtonEnabled) {
          return false;
        }
        handleNotificationAction(action);
        return true;
      default:
        return false;
    }
  }

  private SessionResult handleCustomCommand(SessionCommand customCommand) {
    if (customCommand == null || customCommand.customAction == null) {
      return new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED);
    }

    return handleSessionAction(customCommand.customAction)
        ? new SessionResult(SessionResult.RESULT_SUCCESS)
        : new SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED);
  }

  private synchronized void ensureInitialized() {
    if (player != null && mediaSession != null) {
      return;
    }

    createNotificationChannel();

    // 自定义 RenderersFactory：override buildAudioSink 注入 FftAudioProcessor。
    // 数组形式（虽然现在只有一个 processor）是为后续 Automix 复活时可加 GainAudioProcessor
    // 实现淡入淡出预留扩展位。
    DefaultRenderersFactory renderersFactory =
        new DefaultRenderersFactory(appContext) {
          @Override
          protected AudioSink buildAudioSink(
              Context context,
              boolean enableFloatOutput,
              boolean enableAudioTrackPlaybackParams) {
            return new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(new AudioProcessor[] {fftAudioProcessor})
                .build();
          }
        };

    // 远端音频走 SimpleCache：第二次播放同一首歌直接读 cacheDir/exo/，无网络请求；
    // 本地 file:// / content:// 走默认 DataSource。CacheDataSource 仅对 http(s) 起作用。
    androidx.media3.datasource.DataSource.Factory cachedFactory =
        AudioCacheProvider.buildCachedDataSourceFactory(appContext);
    DefaultMediaSourceFactory mediaSourceFactory =
        new DefaultMediaSourceFactory(
                cachedFactory,
                new DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true)
                    .setConstantBitrateSeekingAlwaysEnabled(true));

    player = new ExoPlayer.Builder(appContext, renderersFactory)
        .setMediaSourceFactory(mediaSourceFactory)
        .build();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
        !allowMixWithOthers);
    player.setHandleAudioBecomingNoisy(true);
    // 后台播放关键：WAKE_MODE_NETWORK 同时锁 CPU + WiFi，防止 Doze 节流网络 prepare 与音频帧解码
    // 一旦应用进入 Doze，没有 wakelock 的 ExoPlayer 会 prepare 失败或播放卡顿，导致后台 ENDED 后切下一首没声音
    player.setWakeMode(C.WAKE_MODE_NETWORK);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED) {
              stopProgressUpdates();
              if (handleAutoAdvanceOnEnded()) {
                return;
              }
              emitEnded();
            } else if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
              startProgressUpdates();
              if (playbackState == Player.STATE_READY) {
                calibrateDurationFromPlayer();
              }
            }

            updateNotification();
            emitPlaybackState(true);
          }

          @Override
          public void onIsPlayingChanged(boolean isPlaying) {
            // 不在 false 分支 stopProgressUpdates：BUFFERING/seek/stall 也是 false，停链路会卡切歌
            if (isPlaying) {
              startProgressUpdates();
            }
            updateNotification();
            emitPlaybackState(true);
          }

          @Override
          public void onPositionDiscontinuity(
              Player.PositionInfo oldPosition,
              Player.PositionInfo newPosition,
              int reason) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK
                || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION
                || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
              // authoritative：seek/外部拖拽。TS 端需据此强制刷新估算基准。
              emitProgressChanged(Math.max(0L, newPosition.positionMs), true, true);
              updateNotification();
              emitPlaybackState(true);
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            if (recoverCurrentTrackAfterError(error)) {
              return;
            }
            emitError(error.errorCode, error.getMessage());
            updateNotification();
          }
        });

    sessionPlayer =
        new ForwardingPlayer(player) {
          @Override
          public Player.Commands getAvailableCommands() {
            // 强声明 PREV/NEXT 可用，避免 ExoPlayer 队列只有 1 项时按钮被置灰。
            return new Player.Commands.Builder()
                .addAll(super.getAvailableCommands())
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build();
          }

          @Override
          public boolean isCommandAvailable(int command) {
            return getAvailableCommands().contains(command);
          }

          // 系统媒体面板/锁屏的 PREV/NEXT 点击会直接调这几个方法，底层 1 项队列会全部 no-op；
          // 在这里劫持后转发到 JS 是唯一能确保按钮可点的位置。
          // 防呆：native 内部勿调 sessionPlayer.seekTo{Next,Previous}*，会被劫持成 emit 而不推进队列；
          // 需主动切曲请用 startTrackFromState() / handleSessionAction()。
          @Override
          public void seekToPrevious() {
            handleSessionAction(PlaybackConstants.ACTION_PREVIOUS);
          }

          @Override
          public void seekToPreviousMediaItem() {
            handleSessionAction(PlaybackConstants.ACTION_PREVIOUS);
          }

          @Override
          public void seekToNext() {
            handleSessionAction(PlaybackConstants.ACTION_NEXT);
          }

          @Override
          public void seekToNextMediaItem() {
            handleSessionAction(PlaybackConstants.ACTION_NEXT);
          }
        };

    mediaSession =
        new MediaSession.Builder(appContext, sessionPlayer)
            .setSessionActivity(buildContentIntent())
            .setCallback(mediaSessionCallback)
            .setCustomLayout(buildCustomLayout())
            .setMediaButtonPreferences(buildMediaButtonPreferences())
            .setPeriodicPositionUpdateEnabled(true)
            .build();
    updateMediaSessionButtons();
    coverBitmap = BitmapFactory.decodeResource(appContext.getResources(), R.mipmap.ic_launcher);
    coverArtworkBytes = encodeArtworkBytes(coverBitmap);
  }

  /** 用 ExoPlayer 真实 duration 校准 metadata。允许同一 source 多次再校准（buffering 早期值可能偏差） */
  private void calibrateDurationFromPlayer() {
    if (player == null) {
      return;
    }
    if (currentSource == null || currentSource.isEmpty()) {
      return;
    }
    long realDurationMs = player.getDuration();
    if (realDurationMs == C.TIME_UNSET || realDurationMs <= 0L) {
      return;
    }
    // 同一 source 已经做过初次校准时，仅在新值与已校准值差异 > 1s 时再次校准；
    // 这样既避免每次 progress tick 都触发刷新（噪声），又允许 DASH/CBR 等场景在 buffering
    // 早期取到偏差大的初值后被后续更准确的值替换。
    final long stabilityThresholdMs = 1000L;
    if (currentSource.equals(durationCalibratedForSource)
        && Math.abs(realDurationMs - currentMetadata.durationMs) <= stabilityThresholdMs) {
      return;
    }
    durationCalibratedForSource = currentSource;
    // 500ms 不敏感窗口：避免 ExoPlayer duration 在 buffering 后期微小抖动（±100~200ms）
    // 反复触发 refreshCurrentMediaItemMetadata → emit trackChanged，污染 JS 进度同步。
    if (Math.abs(realDurationMs - currentMetadata.durationMs) <= 500L) {
      return;
    }
    currentMetadata.durationMs = realDurationMs;
    refreshCurrentMediaItemMetadata();
  }

  /** Bitmap → JPEG@90 byte[]，用作 MediaMetadata.artworkData。 */
  @Nullable
  private byte[] encodeArtworkBytes(@Nullable Bitmap bitmap) {
    if (bitmap == null) {
      return null;
    }
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      if (bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)) {
        return baos.toByteArray();
      }
    } catch (Exception e) {
      Log.w(TAG, "encodeArtworkBytes failed", e);
    }
    return null;
  }

  /**
   * 启动 PlaybackService 前台服务。
   *
   * 节流：已 attach（onCreate 已跑过）直接返回，避免每次 load/play/syncRemoteState
   * 都重复触发 startForegroundService —— 这会让系统 Binder 走一遭、FGS 通知重建，
   * 在频繁 seek/切歌时表现为通知抖动 + 多次 "Background started FGS" 日志。
   */
  private void ensureServiceRunning() {
    if (serviceStarted && service != null) {
      return;
    }
    Intent intent = new Intent(appContext, PlaybackService.class);
    ContextCompat.startForegroundService(appContext, intent);
  }

  private MediaItem buildMediaItem(String url) {
    MediaItem.Builder builder = new MediaItem.Builder();
    if (url != null && !url.isEmpty()) {
      Uri uri = Uri.parse(url);
      builder.setUri(uri);
      if ("content".equals(uri.getScheme())) {
        builder.setMimeType(resolveContentMimeType(uri));
      }
    }
    builder.setMediaMetadata(buildMediaMetadata());
    return builder.build();
  }

  /** content:// MIME 缓存：避免 buildMediaItem 在主线程对未冷启的 ContentProvider 做同步 IPC。 */
  private final Map<String, String> contentMimeCache =
      Collections.synchronizedMap(
          new LinkedHashMap<String, String>(CONTENT_MIME_CACHE_MAX_SIZE, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
              return size() > CONTENT_MIME_CACHE_MAX_SIZE;
            }
          });

  @Nullable
  private String resolveContentMimeType(Uri uri) {
    String key = uri.toString();
    String cached = contentMimeCache.get(key);
    if (cached != null) {
      // 空字符串 sentinel：曾经查到过 null（未知 MIME），不再重复查
      return cached.isEmpty() ? null : cached;
    }
    try {
      ContentResolver resolver = appContext.getContentResolver();
      String mime = resolver.getType(uri);
      contentMimeCache.put(key, mime == null ? "" : mime);
      return mime;
    } catch (Exception error) {
      Log.w(TAG, "resolveContentMimeType failed", error);
      contentMimeCache.put(key, "");
      return null;
    }
  }

  private MediaMetadata buildMediaMetadata() {
    MediaMetadata.Builder builder = new MediaMetadata.Builder();

    if (currentMetadata.title != null) {
      builder.setTitle(currentMetadata.title);
    }
    if (currentMetadata.artist != null) {
      builder.setArtist(currentMetadata.artist);
    }
    if (currentMetadata.album != null) {
      builder.setAlbumTitle(currentMetadata.album);
    }
    // 锁屏 / 通知栏 / 进度总长以 ExoPlayer 实测 ms 为权威，避免上游元数据 dt 偏差造成进度条与实际播放错位。
    long resolvedDurationMs = currentMetadata.durationMs;
    if (player != null) {
      long realDurationMs = player.getDuration();
      if (realDurationMs != C.TIME_UNSET && realDurationMs > 0L) {
        resolvedDurationMs = realDurationMs;
      }
    }
    if (resolvedDurationMs > 0L) {
      builder.setDurationMs(resolvedDurationMs);
    }
    if (currentMetadata.coverUrl != null
        && !currentMetadata.coverUrl.isEmpty()
        && !currentMetadata.coverUrl.startsWith("blob:")) {
      try {
        builder.setArtworkUri(Uri.parse(currentMetadata.coverUrl));
      } catch (Exception ignored) {
      }
    }
    // artworkData 覆盖 blob:/网络不可达场景，蓝牙 AVRCP / Auto 必需这个才有封面。
    if (coverArtworkBytes != null) {
      builder.setArtworkData(coverArtworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER);
    }

    return builder.build();
  }

  /** 不重 prepare、不打断播放地把 metadata 同步到当前 MediaItem。 */
  private void refreshCurrentMediaItemMetadata() {
    if (player == null) {
      return;
    }
    MediaItem current = player.getCurrentMediaItem();
    if (current == null) {
      return;
    }
    try {
      MediaItem updated = current.buildUpon().setMediaMetadata(buildMediaMetadata()).build();
      int index = player.getCurrentMediaItemIndex();
      if (index >= 0 && index < player.getMediaItemCount()) {
        player.replaceMediaItem(index, updated);
      }
    } catch (Exception e) {
      Log.w(TAG, "refreshCurrentMediaItemMetadata failed", e);
    }
  }

  private boolean handleAutoAdvanceOnEnded() {
    if (personalFmMode) {
      // personalFM：JS 算法决定下一首，原生层不能自治
      return false;
    }
    // ENDED 路径响应 repeatOne（advanceRaw(true) 单曲循环时返回当前 track）。
    // 不再因 url==null 而 return null —— Java 自解 URL，让 resolveAndPlayAsync 接管。
    PlaybackQueue.Track next = playbackQueue.advanceRaw(true);
    if (next == null) {
      // 真正窗口耗尽（不在 ALL 模式 / 没有更多歌曲）
      if (shouldRequestUrlsForwardWrap()) {
        // 拦截 ENDED + 置位续播标记：updateQueueContext 收到新窗口后将主动 advance+play
        pendingResumeAfterRefill = true;
        emitRequestUrls();
        return true;
      }
      return false;
    }
    resolveAndPlayAsync(next, "auto", true, 5);
    return true;
  }

  /**
   * 前进方向窗口耗尽时是否需要让 JS 端补窗口：
   *
   * <ul>
   *   <li>hasNextOutsideWindow=true：还有后续歌曲（常规补窗）
   *   <li>ALL 模式 + hasPreviousOutsideWindow=true：已是末尾，JS 负责 wrap 到 0
   * </ul>
   *
   * <p>该函数不要与 {@link #requestUrlsIfWindowExhausted()} 混淆后者是「快到边」预警，
   * 本函数是「确实边上跳不动」必须补。
   */
  private boolean shouldRequestUrlsForwardWrap() {
    if (playbackQueue.hasNextOutsideWindow()) return true;
    return playbackQueue.getRepeatMode() == PlaybackQueue.RepeatMode.ALL
        && playbackQueue.hasPreviousOutsideWindow();
  }

  /**
   * Java URL 自治核心：url==null 则后台解析后开播，失败则跳过继续。
   *
   * @param forward true=失败时 advanceRaw 跳过坏首；false=停止（previous）
   * @param remainAttempts 连续失败限额，超过后回落 JS
   */
  private void resolveAndPlayAsync(
      @Nullable PlaybackQueue.Track next,
      String source,
      boolean forward,
      int remainAttempts) {
    if (next == null) {
      if (forward && shouldRequestUrlsForwardWrap()) {
        // 前进方向窗口耗尽：置位续播标记，让 JS 推新窗口后自动续播
        pendingResumeAfterRefill = true;
        emitRequestUrls();
      } else if (!forward) {
        // 后退方向窗口耗尽：回落 JS prev（不应触发 requestUrls，那是右缘语义）
        emitCustomAction("previous", null, null, null, null, true, null);
      } else if ("auto".equals(source)) {
        emitCustomAction("next", null, null, null, null, true, null);
      }
      return;
    }
    if (next.playable()) {
      playFromQueue(next, source);
      prefetchUpcomingUrls();
      return;
    }
    if (remainAttempts <= 0) {
      // 连续失败（账号 / 解锁 / 区域屏蔽）
      if (forward) {
        if (shouldRequestUrlsForwardWrap()) {
          pendingResumeAfterRefill = true;
          emitRequestUrls();
        } else {
          emitCustomAction("next", null, null, null, null, true, null);
        }
      } else {
        emitCustomAction("previous", null, null, null, null, true, null);
      }
      return;
    }
    final long songId = next.songId;
    final PlaybackQueue.Track target = next;
    // 自增 token：用户连点 NEXT 时旧 token 失效，回调直接丢弃，防止旧 URL 覆盖新播放
    final long myToken = resolveTokenCounter.incrementAndGet();
    urlResolver.submitResolve(
        songId,
        url ->
            mainHandler.post(
                () -> {
                  if (myToken != resolveTokenCounter.get()) {
                    // 过期回调：用户已发起新一轮 NEXT/PREV，丢弃本次结果
                    return;
                  }
                  if (url != null) {
                    target.url = url;
                    playbackQueue.updateTrackUrl(songId, url);
                    playFromQueue(target, source);
                    prefetchUpcomingUrls();
                  } else if (forward) {
                    Log.w(TAG, "resolveAndPlayAsync: songId=" + songId + " failed, skip forward");
                    PlaybackQueue.Track again = playbackQueue.advanceRaw(false);
                    resolveAndPlayAsync(again, source, true, remainAttempts - 1);
                  } else {
                    // PREVIOUS 失败：再 backRaw 跳过坏首继续后退；前缘耗尽时回落 JS prev
                    Log.w(TAG, "resolveAndPlayAsync: songId=" + songId + " failed, skip backward");
                    PlaybackQueue.Track again = playbackQueue.backRaw();
                    resolveAndPlayAsync(again, source, false, remainAttempts - 1);
                  }
                }));
  }

  /**
   * 预解析窗口前方 3 首，让 ENDED 时直接 playable；同时对已解析的下一首 1-2 个触发音频字节预载，
   * 锁屏 / WebView 冻结时仍能秒响。
   *
   * <ul>
   *   <li>未 resolved 的 URL：urlResolver.prefetchAsync 后台解析，回调里再触发音频字节预载
   *   <li>已 resolved 的 URL：直接 AudioCacheProvider.prefetchUrl 让 SimpleCache warm 512KB
   * </ul>
   *
   * <p>所有 prefetch 都是 fire-and-forget；inFlight / cachedBytes 短路保证幂等，多次调用不浪费带宽。
   */
  private void prefetchUpcomingUrls() {
    // 1) 已 resolved 的下一首 1-2 个：立刻让 SimpleCache 预载前 512KB
    //    这里是锁屏后切歌秒响的关键路径——不依赖 JS / WebView 调度。
    List<String> resolvedUrls = playbackQueue.peekUpcomingResolvedUrls(2);
    for (String url : resolvedUrls) {
      AudioCacheProvider.prefetchUrl(appContext, url);
    }

    // 2) 未 resolved 的 1-3 个：UrlResolver 后台解析 URL，
    //    解析完成回调里再补一次音频字节预载（确保 ENDED 时新解析出的 URL 也被 warm）
    List<PlaybackQueue.Track> upcoming = playbackQueue.peekUpcomingUnresolved(3);
    for (PlaybackQueue.Track t : upcoming) {
      final long songId = t.songId;
      urlResolver.prefetchAsync(
          t,
          playbackQueue,
          () -> {
            // onResolved 在 resolver 池线程；URL 已通过 updateTrackUrl 写回 queue，可直接查
            String resolvedUrl = playbackQueue.findUrlBySongId(songId);
            if (resolvedUrl != null && !resolvedUrl.isEmpty()) {
              AudioCacheProvider.prefetchUrl(appContext, resolvedUrl);
            }
          });
    }
  }

  /**
   * 从队列指定 Track 切歌并 emit trackChanged。
   *
   * @param source "auto" (ENDED) / "next" / "previous"
   */
  private void playFromQueue(PlaybackQueue.Track track, String source) {
    if (track == null || !track.playable()) {
      return;
    }
    // 切歌即失效旧 async 回调 + 清 pending，防止旧 URL 覆盖 / 补窗误消费
    resolveTokenCounter.incrementAndGet();
    pendingResumeAfterRefill = false;
    nativeRecoverySongId = 0L;
    nativeRecoveryAttempts = 0;
    TrackMetadata metadata = trackToMetadata(track);
    startTrackFromState(track.url, metadata, track.liked, true);

    // 通知 JS：native 已切到这首歌，请同步 statusStore.playIndex
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    if (currentPlugin != null) {
      JSObject payload = new JSObject();
      payload.put("action", "trackChanged");
      payload.put("success", true);
      payload.put("songId", track.songId);
      payload.put("playListIndex", track.playListIndex);
      payload.put("source", source);
      payload.put("liked", track.liked);
      currentPlugin.emitEvent("customAction", payload, true);
    }

    // 窗口边缘提前补 URL
    requestUrlsIfWindowExhausted();
  }

  private boolean recoverCurrentTrackAfterError(PlaybackException error) {
    long songId = currentMetadata.songId;
    if (songId <= 0 || !currentMetadata.canLike) return false;
    if (currentSource == null || currentSource.isEmpty()) return false;
    if (!isRecoverablePlaybackError(error)) return false;
    if (nativeRecoverySongId != songId) {
      nativeRecoverySongId = songId;
      nativeRecoveryAttempts = 0;
    }
    if (nativeRecoveryAttempts >= NATIVE_ERROR_RECOVERY_MAX_ATTEMPTS) return false;
    nativeRecoveryAttempts++;
    long positionMs = Math.max(0L, getPositionMs());
    TrackMetadata metadataSnapshot = currentMetadata.copy();
    boolean likedSnapshot = liked;
    Log.w(TAG, "native recover playback error code=" + error.errorCode + " songId=" + songId);
    final long myToken = resolveTokenCounter.incrementAndGet();
    urlResolver.clear(songId);
    urlResolver.submitResolve(
        songId,
        url ->
            mainHandler.post(
                () -> {
                  if (player == null) return;
                  if (myToken != resolveTokenCounter.get()) return;
                  if (url == null || url.isEmpty()) {
                    emitError(error.errorCode, error.getMessage());
                    updateNotification();
                    return;
                  }
                  metadataSnapshot.url = url;
                  currentSource = url;
                  currentMetadata = metadataSnapshot.copy();
                  playbackQueue.updateTrackUrl(songId, url);
                  liked = likedSnapshot;
                  clearPendingSeek();
                  durationCalibratedForSource = "";
                  player.setMediaItem(buildMediaItem(url));
                  player.prepare();
                  if (positionMs > 0) {
                    player.seekTo(positionMs);
                    beginPendingSeek(positionMs);
                  }
                  player.play();
                  updateNotification();
                  emitPlaybackState(true);
                  emitProgressChanged();
                  nativeRecoverySongId = 0L;
                  nativeRecoveryAttempts = 0;
                  prefetchUpcomingUrls();
                }));
    return true;
  }

  private boolean isRecoverablePlaybackError(PlaybackException error) {
    int code = error.errorCode;
    return code == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        || code == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        || code == PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE
        || code == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        || code == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
        || code == PlaybackException.ERROR_CODE_IO_NO_PERMISSION
        || code == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED
        || code == PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE;
  }

  /** 转换 PlaybackQueue.Track → TrackMetadata（复用现有切歌路径） */
  private TrackMetadata trackToMetadata(PlaybackQueue.Track track) {
    TrackMetadata m = new TrackMetadata();
    m.songId = track.songId;
    m.durationMs = track.durationMs;
    m.canLike = track.canLike;
    m.liked = track.liked;
    m.title = track.title;
    m.artist = track.artist;
    m.album = track.album;
    m.coverUrl = track.coverUrl;
    m.url = track.url == null ? "" : track.url;
    return m;
  }

  /** 仅在窗口外仍有歌时主动通知 JS 推新窗口（窗口外耗尽时无意义）。 */
  private void requestUrlsIfWindowExhausted() {
    if (playbackQueue.playableTracksAhead() <= WINDOW_REFILL_THRESHOLD
        && playbackQueue.hasNextOutsideWindow()) {
      emitRequestUrls();
    }
  }

  private void emitRequestUrls() {
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    if (currentPlugin == null) return;
    JSObject payload = new JSObject();
    payload.put("action", "requestUrls");
    payload.put("success", true);
    currentPlugin.emitEvent("customAction", payload, true);
  }

  private void emitDiagnosticLog(String tag, String message) {
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    if (currentPlugin == null) return;
    JSObject payload = new JSObject();
    payload.put("tag", tag);
    payload.put("message", message);
    currentPlugin.emitEvent("diagnosticLog", payload, false);
  }

  private void startTrackFromState(
      String source, TrackMetadata metadata, boolean likedState, boolean emitProgressImmediately) {
    if (player == null) {
      return;
    }

    currentSource = source == null ? "" : source;
    currentMetadata = metadata == null ? new TrackMetadata() : metadata.copy();
    liked = likedState;
    clearPendingSeek();
    lastKnownPositionMs = 0L;
    durationCalibratedForSource = "";
    player.setMediaItem(buildMediaItem(currentSource));
    player.prepare();
    player.seekTo(0L);
    player.play();
    loadCoverBitmapAsync(currentMetadata.coverUrl);
    updateMediaSessionButtons();
    updateNotification();
    // 注意：先 emitPlaybackState 再 emitProgressChanged。
    // gapless 切歌时 TS 引擎要先从 playbackStateChanged.src 检测到换轨并重置内部 _currentTime/lastTimeSyncAt，
    // 否则 progressChanged 触发的 timeupdate 会用旧基准估算出错误位置（进度条直接跳到末尾）。
    emitPlaybackState(true);
    if (emitProgressImmediately) {
      emitProgressChanged();
    }
  }

  private SessionCommands buildAvailableSessionCommands() {
    // PREV 不随 personalFM 屏蔽：FM 下 prev 交由 JS 走 initPersonalFM，语义一致。
    SessionCommands.Builder builder =
        new SessionCommands.Builder().add(nextSessionCommand).add(previousSessionCommand);

    if (currentMetadata.canLike) {
      builder.add(favoriteSessionCommand);
    }
    if (desktopLyricButtonEnabled) {
      builder.add(desktopLyricSessionCommand);
    }

    return builder.build();
  }

  private Player.Commands buildAvailablePlayerCommands() {
    Player.Commands.Builder builder =
        new Player.Commands.Builder().addAll(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS);
    builder.add(Player.COMMAND_SEEK_TO_NEXT);
    builder.add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM);
    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS);
    builder.add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM);

    return builder.build();
  }

  private List<CommandButton> buildCustomLayout() {
    return buildMediaButtonPreferences();
  }

  private List<CommandButton> buildMediaButtonPreferences() {
    List<CommandButton> buttons = new ArrayList<>();
    if (!controllerEnabled) {
      return buttons;
    }

    if (currentMetadata.canLike) {
      buttons.add(
          new CommandButton.Builder(
                  liked ? CommandButton.ICON_HEART_FILLED : CommandButton.ICON_HEART_UNFILLED)
              .setSessionCommand(favoriteSessionCommand)
              .setDisplayName(appContext.getString(R.string.playback_notification_favorite))
              .setSlots(CommandButton.SLOT_BACK_SECONDARY, CommandButton.SLOT_OVERFLOW)
              .build());
    }

    buttons.add(
        new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
            .setSessionCommand(previousSessionCommand)
            .setDisplayName(appContext.getString(R.string.playback_notification_previous))
            .setSlots(CommandButton.SLOT_BACK)
            .build());

    buttons.add(
        new CommandButton.Builder(CommandButton.ICON_NEXT)
            .setSessionCommand(nextSessionCommand)
            .setDisplayName(appContext.getString(R.string.playback_notification_next))
            .setSlots(CommandButton.SLOT_FORWARD)
            .build());

    if (desktopLyricButtonEnabled) {
      buttons.add(
          new CommandButton.Builder(CommandButton.ICON_SUBTITLES)
              .setSessionCommand(desktopLyricSessionCommand)
              .setDisplayName(appContext.getString(R.string.playback_notification_desktop_lyric))
              .setSlots(CommandButton.SLOT_FORWARD_SECONDARY, CommandButton.SLOT_OVERFLOW)
              .build());
    }

    return buttons;
  }

  private void updateMediaSessionButtons() {
    if (mediaSession == null) {
      return;
    }

    List<CommandButton> customLayout = buildCustomLayout();
    List<CommandButton> mediaButtonPreferences = buildMediaButtonPreferences();
    SessionCommands sessionCommands = buildAvailableSessionCommands();

    mediaSession.setCustomLayout(customLayout);
    mediaSession.setMediaButtonPreferences(mediaButtonPreferences);
    for (MediaSession.ControllerInfo controller : mediaSession.getConnectedControllers()) {
      mediaSession.setAvailableCommands(
          controller, sessionCommands, buildAvailablePlayerCommands());
    }
  }

  private void updateNotification() {
    if (service == null || player == null) {
      return;
    }

    if (!remoteMode && player.getCurrentMediaItem() == null && currentSource.isEmpty()) {
      clearNotification();
      return;
    }
    if (remoteMode && currentMetadata.title.isEmpty() && currentSource.isEmpty()) {
      clearNotification();
      return;
    }

    Notification notification = buildNotification();
    // 仅在「用户主动暂停」撤前台。ENDED / IDLE / BUFFERING 是过渡态，提前撤前台
    // 会被 Android 12+ 拒 (ForegroundServiceStartNotAllowedException)，导致 NEXT 哑火。
    boolean userPaused = !remoteMode
        && player != null
        && player.getPlaybackState() == Player.STATE_READY
        && !player.getPlayWhenReady();
    boolean remotePaused = remoteMode && !remoteIsPlaying;
    boolean shouldStayForeground = !userPaused && !remotePaused;
    try {
      if (shouldStayForeground) {
        service.startForeground(PlaybackConstants.NOTIFICATION_ID, notification);
      } else {
        service.stopForeground(false);
        NotificationManagerCompat.from(appContext)
            .notify(PlaybackConstants.NOTIFICATION_ID, notification);
      }
    } catch (SecurityException error) {
      Log.w(TAG, "Failed to show playback notification", error);
    } catch (IllegalStateException error) {
      // Android 12+ ForegroundServiceStartNotAllowedException 继承 IllegalStateException，
      // 在 Doze 边缘 / 系统极限场景偶发。仅记录，让通知降级为普通通知，避免崩溃。
      if (!isForegroundServiceStartNotAllowed(error)) {
        throw error;
      }
      Log.w(TAG, "Failed to enter foreground service from background", error);
      NotificationManagerCompat.from(appContext)
          .notify(PlaybackConstants.NOTIFICATION_ID, notification);
    }
  }

  private boolean isForegroundServiceStartNotAllowed(IllegalStateException error) {
    String className = error.getClass().getName();
    String message = error.getMessage();
    return className.contains("ForegroundServiceStartNotAllowedException")
        || (message != null && message.contains("ForegroundService"));
  }

  private Notification buildNotification() {
    NotificationCompat.Builder builder =
        new NotificationCompat.Builder(appContext, PlaybackConstants.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(buildContentIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isEffectivelyPlaying() || isEffectivelyBuffering())
            .setContentTitle(safeText(currentMetadata.title, appContext.getString(R.string.app_name)))
            .setContentText(safeText(currentMetadata.artist, ""))
            .setLargeIcon(coverBitmap);

    if (!controllerEnabled) {
      return builder.build();
    }

    int actionCount = 0;
    int playPauseActionIndex = 0;
    int nextActionIndex = 0;
    int previousActionIndex = 0;

    if (currentMetadata.canLike) {
      builder.addAction(buildNotificationAction(
          liked ? ICON_GLYPH_FAVORITE_FILLED : ICON_GLYPH_FAVORITE_OUTLINE,
          liked ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off,
          appContext.getString(R.string.playback_notification_favorite),
          PlaybackConstants.ACTION_FAVORITE));
      actionCount++;
    }

    previousActionIndex = actionCount;
    builder.addAction(buildNotificationAction(
        ICON_GLYPH_PREVIOUS,
        android.R.drawable.ic_media_previous,
        appContext.getString(R.string.playback_notification_previous),
        PlaybackConstants.ACTION_PREVIOUS));
    actionCount++;

    playPauseActionIndex = actionCount;
    boolean effectivelyPlaying = isEffectivelyPlaying();
    builder.addAction(buildNotificationAction(
        effectivelyPlaying ? ICON_GLYPH_PAUSE : ICON_GLYPH_PLAY,
        effectivelyPlaying
            ? android.R.drawable.ic_media_pause
            : android.R.drawable.ic_media_play,
        appContext.getString(R.string.playback_notification_play_pause),
        PlaybackConstants.ACTION_TOGGLE_PLAYBACK));
    actionCount++;

    nextActionIndex = actionCount;
    builder.addAction(buildNotificationAction(
        ICON_GLYPH_NEXT,
        android.R.drawable.ic_media_next,
        appContext.getString(R.string.playback_notification_next),
        PlaybackConstants.ACTION_NEXT));
    actionCount++;

    if (desktopLyricButtonEnabled) {
      builder.addAction(buildNotificationAction(
          ICON_GLYPH_LYRIC,
          desktopLyricEnabled
              ? android.R.drawable.presence_audio_online
              : android.R.drawable.presence_audio_busy,
          appContext.getString(R.string.playback_notification_desktop_lyric),
          PlaybackConstants.ACTION_DESKTOP_LYRIC));
      actionCount++;
    }

    int[] compactActionIndices =
        new int[] {previousActionIndex, playPauseActionIndex, nextActionIndex};

    builder.setStyle(
        new androidx.media.app.NotificationCompat.MediaStyle()
            .setShowActionsInCompactView(compactActionIndices)
            .setMediaSession(
                android.support.v4.media.session.MediaSessionCompat.Token.fromToken(
                    mediaSession.getPlatformToken())));

    return builder.build();
  }

  private NotificationCompat.Action buildNotificationAction(
      @Nullable String glyph, int fallbackIconResId, CharSequence title, String action) {
    PendingIntent pendingIntent = buildActionPendingIntent(action);
    Bitmap iconBitmap = glyph == null ? null : renderNotificationGlyph(glyph);
    if (iconBitmap != null) {
      return new NotificationCompat.Action.Builder(
              IconCompat.createWithBitmap(iconBitmap), title, pendingIntent)
          .build();
    }

    return new NotificationCompat.Action.Builder(fallbackIconResId, title, pendingIntent).build();
  }

  @Nullable
  private Bitmap renderNotificationGlyph(String glyph) {
    Typeface typeface = getNotificationIconTypeface();
    if (typeface == null) {
      return null;
    }

    float density = appContext.getResources().getDisplayMetrics().density;
    int bitmapSize = Math.max(48, Math.round(24f * density));
    float textSize = bitmapSize * 0.78f;

    Bitmap bitmap = Bitmap.createBitmap(bitmapSize, bitmapSize, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    paint.setColor(Color.WHITE);
    paint.setTypeface(typeface);
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(textSize);

    Paint.FontMetrics fontMetrics = paint.getFontMetrics();
    float baseline = (bitmapSize - fontMetrics.ascent - fontMetrics.descent) / 2f;
    canvas.drawText(glyph, bitmapSize / 2f, baseline, paint);
    return bitmap;
  }

  @Nullable
  private synchronized Typeface getNotificationIconTypeface() {
    if (notificationIconTypeface != null) {
      return notificationIconTypeface;
    }

    try {
      notificationIconTypeface =
          Typeface.createFromAsset(appContext.getAssets(), NOTIFICATION_ICON_FONT_ASSET);
      return notificationIconTypeface;
    } catch (Exception error) {
      Log.w(TAG, "Failed to load notification icon font", error);
      return null;
    }
  }

  private PendingIntent buildContentIntent() {
    Intent intent = new Intent(appContext, MainActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
    return PendingIntent.getActivity(
        appContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
  }

  private PendingIntent buildActionPendingIntent(String action) {
    Intent intent = new Intent(appContext, PlaybackActionReceiver.class);
    intent.setAction(action);
    return PendingIntent.getBroadcast(
        appContext,
        action.hashCode(),
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT | immutableFlag());
  }

  private int immutableFlag() {
    return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0;
  }

  @Nullable
  private KeyEvent extractKeyEvent(Intent intent) {
    if (intent == null) {
      return null;
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent.class);
    }

    Object value = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
    return value instanceof KeyEvent ? (KeyEvent) value : null;
  }

  private synchronized void clearNotification() {
    stopProgressUpdates();

    if (service != null) {
      try {
        service.stopForeground(true);
      } catch (Exception error) {
        Log.w(TAG, "Failed to stop foreground playback service", error);
      }
    }

    NotificationManagerCompat.from(appContext).cancel(PlaybackConstants.NOTIFICATION_ID);
  }

  private void createNotificationChannel() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
      return;
    }

    NotificationChannel channel =
        new NotificationChannel(
            PlaybackConstants.CHANNEL_ID,
            appContext.getString(R.string.playback_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW);
    channel.setDescription(appContext.getString(R.string.playback_notification_channel_description));

    NotificationManager notificationManager =
        ContextCompat.getSystemService(appContext, NotificationManager.class);
    if (notificationManager != null) {
      notificationManager.createNotificationChannel(channel);
    }
  }

  private void startProgressUpdates() {
    mainHandler.removeCallbacks(progressRunnable);
    mainHandler.post(progressRunnable);
  }

  private void stopProgressUpdates() {
    mainHandler.removeCallbacks(progressRunnable);
  }

  private void emitPlaybackState(boolean retain) {
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    if (currentPlugin != null) {
      currentPlugin.emitEvent("playbackStateChanged", buildState(), retain);
    }
  }

  private void emitProgressChanged() {
    emitProgressChanged(getPositionMs());
  }

  private void emitProgressChanged(long positionMs) {
    emitProgressChanged(positionMs, true, false);
  }

  /** authoritative=true 表示权威 seek，TS 端需据此刷新估算基准；false 是周期轮询。 */
  private void emitProgressChanged(
      long positionMs, boolean acknowledgePendingSeek, boolean authoritative) {
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    long safePositionMs = Math.max(0L, positionMs);
    if (acknowledgePendingSeek) {
      rememberReportedPosition(safePositionMs);
    } else {
      lastKnownPositionMs = safePositionMs;
    }
    if (currentPlugin == null) {
      return;
    }

    JSObject payload = new JSObject();
    payload.put("durationMs", getDurationMs());
    payload.put("positionMs", safePositionMs);
    if (authoritative) {
      payload.put("authoritative", true);
    }
    currentPlugin.emitEvent("progressChanged", payload, true);
  }

  private void emitEnded() {
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    if (currentPlugin == null) {
      return;
    }

    JSObject payload = new JSObject();
    payload.put("durationMs", getDurationMs());
    currentPlugin.emitEvent("ended", payload, true);
  }

  private void emitError(int errorCode, @Nullable String message) {
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    if (currentPlugin == null) {
      return;
    }

    JSObject payload = new JSObject();
    payload.put("errorCode", errorCode);
    if (message != null) {
      payload.put("message", message);
    }
    currentPlugin.emitEvent("error", payload, true);
  }

  private void emitCustomAction(
      String action,
      @Nullable Long songId,
      @Nullable Boolean likedState,
      @Nullable Boolean desktopLyricEnabledState,
      @Nullable Boolean collapsedState,
      boolean success,
      @Nullable String message) {
    AndroidNativePlaybackPlugin currentPlugin = plugin;
    if (currentPlugin == null) {
      return;
    }

    JSObject payload = new JSObject();
    payload.put("action", action);
    payload.put("success", success);
    if (songId != null) {
      payload.put("songId", songId);
    }
    if (likedState != null) {
      payload.put("liked", likedState);
    }
    if (desktopLyricEnabledState != null) {
      payload.put("desktopLyricEnabled", desktopLyricEnabledState);
    }
    if (collapsedState != null) {
      payload.put("collapsed", collapsedState);
    }
    if (message != null) {
      payload.put("message", message);
    }
    currentPlugin.emitEvent("customAction", payload, true);
  }

  // ========== 频谱可视化（基于 Media3 AudioProcessor 内嵌 FFT，无需任何权限）==========

  /**
   * JS 端开启/关闭频谱。
   *
   * 实现细节：FftAudioProcessor 一直挂在 ExoPlayer 解码链上，仅当 listener != null 时
   * 才执行实际的 FFT 计算（最佳节电）。
   */
  public synchronized boolean enableVisualizer(boolean enable) {
    visualizerRequested = enable;
    updateFftListenerAttachment();
    return true;
  }

  /** 根据频谱请求状态挂载或卸载 FFT 数据回调。 */
  private void updateFftListenerAttachment() {
    if (visualizerRequested) {
      fftAudioProcessor.setListener(this::onFftData);
    } else {
      fftAudioProcessor.setListener(null);
    }
  }

  /**
   * FFT 回调（音频线程）：只写 snapshot 并请求主线程 emit，杜绝音频线程跑 base64 / bridge。
   * coalescing：CAS 保证 mainHandler 队列同时最多 1 个 task，多余帧只覆盖 snapshot。
   */
  private void onFftData(int[] fftBins, float lowFreq) {
    if (!visualizerRequested) return;
    int len = fftBins.length;
    synchronized (visualizerSnapshotLock) {
      if (visualizerByteBuf == null || visualizerByteBuf.length != len) {
        visualizerByteBuf = new byte[len];
      }
      for (int i = 0; i < len; i++) {
        visualizerByteBuf[i] = (byte) fftBins[i];
      }
      pendingLowFreq = lowFreq;
      hasPendingVisualizerData = true;
    }
    if (visualizerEmitScheduled.compareAndSet(false, true)) {
      mainHandler.post(visualizerEmitTask);
    }
  }

  // 频谱共享缓冲与同步原语
  private final Object visualizerSnapshotLock = new Object();

  private byte[] visualizerByteBuf;
  private float pendingLowFreq;
  private boolean hasPendingVisualizerData = false;

  private final java.util.concurrent.atomic.AtomicBoolean visualizerEmitScheduled =
      new java.util.concurrent.atomic.AtomicBoolean(false);

  /** 主线程 emit：先释放 schedule 让新帧能再 post，clone snapshot 后 base64 推送。 */
  private final Runnable visualizerEmitTask =
      new Runnable() {
        @Override
        public void run() {
          visualizerEmitScheduled.set(false);
          AndroidNativePlaybackPlugin currentPlugin = plugin;
          if (currentPlugin == null) return;

          byte[] snapshot;
          float lowFreq;
          synchronized (visualizerSnapshotLock) {
            if (!hasPendingVisualizerData || visualizerByteBuf == null) return;
            snapshot = visualizerByteBuf.clone();
            lowFreq = pendingLowFreq;
            hasPendingVisualizerData = false;
          }

          String b64 = android.util.Base64.encodeToString(snapshot, android.util.Base64.NO_WRAP);
          JSObject payload = new JSObject();
          payload.put("fftB64", b64);
          payload.put("lowFreq", lowFreq);
          currentPlugin.emitEvent("visualizerData", payload, false);
        }
      };

  private void loadCoverBitmapAsync(String coverUrl) {
    final long artworkToken = artworkTokenCounter.incrementAndGet();
    if (coverUrl == null || coverUrl.isEmpty() || coverUrl.startsWith("blob:")) {
      coverBitmap = BitmapFactory.decodeResource(appContext.getResources(), R.mipmap.ic_launcher);
      coverArtworkBytes = encodeArtworkBytes(coverBitmap);
      updateNotification();
      return;
    }

    artworkExecutor.execute(
        () -> {
          Bitmap bitmap = null;
          InputStream inputStream = null;
          HttpURLConnection connection = null;

          try {
            if (coverUrl.startsWith("data:")) {
              // 仅支持 data:image/*;base64,xxx 形式；非 base64 / 非 image 的 data URL 直接忽略
              int commaIdx = coverUrl.indexOf(',');
              int base64MarkerIdx = coverUrl.indexOf(";base64");
              if (commaIdx > 0
                  && base64MarkerIdx > 0
                  && base64MarkerIdx < commaIdx
                  && coverUrl.startsWith("data:image/")) {
                String base64Data = coverUrl.substring(commaIdx + 1);
                try {
                  byte[] decoded = Base64.decode(base64Data, Base64.DEFAULT);
                  bitmap = BitmapFactory.decodeByteArray(decoded, 0, decoded.length);
                } catch (IllegalArgumentException ignored) {
                  // 非法 base64：忽略
                }
              }
            } else if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
              connection = (HttpURLConnection) new URL(coverUrl).openConnection();
              connection.setConnectTimeout(8000);
              connection.setReadTimeout(8000);
              connection.setDoInput(true);
              connection.connect();
              inputStream = connection.getInputStream();
              bitmap = BitmapFactory.decodeStream(inputStream);
            } else if (coverUrl.startsWith("content://")) {
              ContentResolver resolver = appContext.getContentResolver();
              inputStream = resolver.openInputStream(Uri.parse(coverUrl));
              if (inputStream != null) {
                bitmap = BitmapFactory.decodeStream(inputStream);
              }
            } else if (coverUrl.startsWith("file://")) {
              bitmap = BitmapFactory.decodeFile(Uri.parse(coverUrl).getPath());
            }
          } catch (Exception error) {
            Log.w(TAG, "Failed to load cover art", error);
          } finally {
            try {
              if (inputStream != null) {
                inputStream.close();
              }
            } catch (Exception ignored) {
            }
            if (connection != null) {
              connection.disconnect();
            }
          }

          final Bitmap resolvedBitmap =
              bitmap != null
                  ? bitmap
                  : BitmapFactory.decodeResource(appContext.getResources(), R.mipmap.ic_launcher);

          // JPEG 编码在后台线程完成，避免主线程卡帧。
          final byte[] encodedBytes = encodeArtworkBytes(resolvedBitmap);
          mainHandler.post(
              () -> {
                if (artworkToken != artworkTokenCounter.get()) {
                  emitDiagnosticLog("DIAG-Artwork", "stale cover ignored");
                  return;
                }
                coverBitmap = resolvedBitmap;
                coverArtworkBytes = encodedBytes;
                refreshCurrentMediaItemMetadata();
                updateNotification();
              });
        });
  }

  private void toggleFavoriteAsync() {
    if (!currentMetadata.canLike || currentMetadata.songId <= 0) {
      emitCustomAction("favorite", currentMetadata.songId, liked, null, null, false, "favorite_unavailable");
      return;
    }

    if (apiBaseUrl.isEmpty() || cookie.isEmpty()) {
      emitCustomAction("favorite", currentMetadata.songId, liked, null, null, false, "login_required");
      return;
    }

    final boolean targetLike = !liked;
    final long songId = currentMetadata.songId;
    synchronized (this) {
      if (favoriteRequestInFlight) {
        emitCustomAction("favorite", songId, liked, null, null, false, "favorite_busy");
        return;
      }
      favoriteRequestInFlight = true;
    }

    networkExecutor.execute(
        () -> {
          FavoriteRequestResult requestResult = performFavoriteRequest(songId, targetLike);
          mainHandler.post(
              () -> {
                favoriteRequestInFlight = false;
                if (requestResult.success) {
                  liked = targetLike;
                  currentMetadata.liked = targetLike;
                  updateMediaSessionButtons();
                  updateNotification();
                }

                emitCustomAction(
                    "favorite",
                    songId,
                    requestResult.success ? targetLike : liked,
                    null,
                    null,
                    requestResult.success,
                    requestResult.message);
              });
        });
  }

  private FavoriteRequestResult performFavoriteRequest(long songId, boolean targetLike) {
    String likeEndpoint = apiBaseUrl.endsWith("/like") ? apiBaseUrl : apiBaseUrl + "/like";

    for (int attempt = 1; attempt <= FAVORITE_REQUEST_MAX_ATTEMPTS; attempt++) {
      HttpURLConnection connection = null;
      try {
        String encodedCookie = URLEncoder.encode(cookie, StandardCharsets.UTF_8.name());
        String separator = likeEndpoint.contains("?") ? "&" : "?";
        String urlString =
            likeEndpoint
                + separator
                + "id="
                + songId
                + "&like="
                + targetLike
                + "&timestamp="
                + System.currentTimeMillis()
                + "&cookie="
                + encodedCookie;

        connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Cookie", cookie);
        connection.connect();

        int httpCode = connection.getResponseCode();
        InputStream inputStream = httpCode >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = readStream(inputStream);
        int businessCode = parseBusinessCode(response);

        if (httpCode == 200 && businessCode == 200) {
          return FavoriteRequestResult.success();
        }

        if (businessCode == 301 || businessCode == 401 || response.contains("需要登录")) {
          return FavoriteRequestResult.failure("login_required");
        }

        if (attempt >= FAVORITE_REQUEST_MAX_ATTEMPTS || !shouldRetryFavoriteRequest(httpCode, businessCode)) {
          Log.w(
              TAG,
              "Favorite request failed, httpCode="
                  + httpCode
                  + ", businessCode="
                  + businessCode
                  + ", response="
                  + response);
          return FavoriteRequestResult.failure("favorite_failed");
        }
      } catch (Exception error) {
        Log.w(TAG, "Failed to toggle song favorite, attempt=" + attempt, error);
        if (attempt >= FAVORITE_REQUEST_MAX_ATTEMPTS) {
          return FavoriteRequestResult.failure("favorite_failed");
        }
      } finally {
        if (connection != null) {
          connection.disconnect();
        }
      }

      try {
        Thread.sleep(FAVORITE_REQUEST_RETRY_DELAY_MS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        return FavoriteRequestResult.failure("favorite_failed");
      }
    }

    return FavoriteRequestResult.failure("favorite_failed");
  }

  private int parseBusinessCode(String response) {
    if (response == null || response.isEmpty()) {
      return -1;
    }

    try {
      return new JSONObject(response).optInt("code", -1);
    } catch (Exception error) {
      Log.w(TAG, "Failed to parse favorite response", error);
      return -1;
    }
  }

  private boolean shouldRetryFavoriteRequest(int httpCode, int businessCode) {
    if (httpCode >= 500) {
      return true;
    }
    return httpCode == 0 || httpCode == 408 || httpCode == 429 || businessCode == -1;
  }

  private String readStream(@Nullable InputStream inputStream) throws Exception {
    if (inputStream == null) {
      return "";
    }

    BufferedReader reader =
        new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    StringBuilder builder = new StringBuilder();
    String line;

    while ((line = reader.readLine()) != null) {
      builder.append(line);
    }

    reader.close();
    return builder.toString();
  }

  private long getDurationMs() {
    if (player == null) {
      return currentMetadata.durationMs;
    }
    long duration = player.getDuration();
    if (duration > 0) {
      return duration;
    }
    return Math.max(0L, currentMetadata.durationMs);
  }

  private long getPositionMs() {
    if (player == null) {
      return Math.max(0L, lastKnownPositionMs);
    }

    long playerPositionMs = Math.max(0L, player.getCurrentPosition());
    if (pendingSeekPositionMs != C.TIME_UNSET) {
      long now = System.currentTimeMillis();
      if (now > pendingSeekDeadlineMs) {
        clearPendingSeek();
      } else if (playerPositionMs + 250L < pendingSeekPositionMs) {
        return Math.max(0L, lastKnownPositionMs);
      }
      // 位置已到达 seek 目标，更新 lastKnownPositionMs 但不清除 pendingSeek
      // 只让 deadline 自然过期，防止后续异步回调携带旧位置覆盖
    }

    lastKnownPositionMs = playerPositionMs;
    return playerPositionMs;
  }

  private void beginPendingSeek(long positionMs) {
    pendingSeekPositionMs = Math.max(0L, positionMs);
    pendingSeekDeadlineMs = System.currentTimeMillis() + SEEK_STATE_GRACE_MS;
    lastKnownPositionMs = pendingSeekPositionMs;
  }

  private void clearPendingSeek() {
    pendingSeekPositionMs = C.TIME_UNSET;
    pendingSeekDeadlineMs = 0L;
  }

  private void rememberReportedPosition(long positionMs) {
    long safePositionMs = Math.max(0L, positionMs);

    if (pendingSeekPositionMs != C.TIME_UNSET) {
      long now = System.currentTimeMillis();
      if (now > pendingSeekDeadlineMs) {
        clearPendingSeek();
      } else if (safePositionMs + 250L < pendingSeekPositionMs) {
        return;
      }
    }

    lastKnownPositionMs = safePositionMs;
  }

  private String formatTime(long timeMs) {
    long totalSeconds = Math.max(0L, timeMs / 1000L);
    long minutes = totalSeconds / 60L;
    long seconds = totalSeconds % 60L;
    return String.format("%d:%02d", minutes, seconds);
  }

  private String safeText(@Nullable String value, String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value;
  }

  // ========== 悬浮歌词 API ==========

  // 缓冲区：服务未就绪时暂存数据，就绪后回放
  private String bufferedLrcJson = null, bufferedYrcJson = null;
  private String bufferedSongName = null, bufferedArtist = null;
  private long bufferedTimeMs = 0;
  private boolean bufferedPlaying = false;
  private JSONObject bufferedLyricConfig = null;

  /** 开启悬浮歌词服务 */
  public synchronized void showFloatingLyric() {
    Intent intent = new Intent(appContext, FloatingLyricService.class);
    appContext.startService(intent);
  }

  /** 关闭悬浮歌词服务 */
  public synchronized void hideFloatingLyric() {
    floatingLyricService = null;
    bufferedLrcJson = null;
    bufferedYrcJson = null;
    Intent intent = new Intent(appContext, FloatingLyricService.class);
    appContext.stopService(intent);
  }

  /** 服务启动时注册——立即回放缓冲数据 */
  public synchronized void attachFloatingLyricService(FloatingLyricService service) {
    floatingLyricService = service;
    // 先应用配置再推数据
    if (bufferedLyricConfig != null) {
      service.applyConfig(bufferedLyricConfig);
    }
    // 回放缓冲数据
    if (bufferedLrcJson != null || bufferedYrcJson != null) {
      service.pushLyrics(bufferedLrcJson, bufferedYrcJson);
    }
    if (bufferedSongName != null) {
      service.pushSongInfo(bufferedSongName, bufferedArtist);
    }
    service.pushProgress(bufferedTimeMs, bufferedPlaying);
  }

  public synchronized void detachFloatingLyricService(FloatingLyricService service) {
    if (floatingLyricService == service) floatingLyricService = null;
  }

  /** 推送歌词——有服务直推，没服务先缓冲 */
  public synchronized void updateFloatingLyricData(String lrcJson, String yrcJson) {
    bufferedLrcJson = lrcJson;
    bufferedYrcJson = yrcJson;
    if (floatingLyricService != null) floatingLyricService.pushLyrics(lrcJson, yrcJson);
  }

  /** 推送进度 */
  public synchronized void updateFloatingLyricProgress(long timeMs, boolean playing) {
    bufferedTimeMs = timeMs;
    bufferedPlaying = playing;
    if (floatingLyricService != null) floatingLyricService.pushProgress(timeMs, playing);
  }

  /** 推送歌曲信息 */
  public synchronized void updateFloatingLyricSongInfo(String name, String artist) {
    bufferedSongName = name;
    bufferedArtist = artist;
    if (floatingLyricService != null) floatingLyricService.pushSongInfo(name, artist);
  }

  /** 推送桌面歌词配置（颜色、字号、字重、遮罩等） */
  public synchronized void updateFloatingLyricConfig(JSONObject config) {
    bufferedLyricConfig = config;
    if (floatingLyricService != null) floatingLyricService.applyConfig(config);
  }

  public synchronized boolean isFloatingLyricRunning() {
    return floatingLyricService != null;
  }

  /** 悬浮歌词被用户在窗口内关闭 */
  public void emitDesktopLyricClosed() {
    desktopLyricEnabled = false;
    updateMediaSessionButtons();
    updateNotification();
    emitCustomAction("desktopLyric", null, null, false, null, true, null);
  }

  /** 设置悬浮歌词锁定 */
  public synchronized void setFloatingLyricLocked(boolean locked) {
    if (floatingLyricService != null) floatingLyricService.setLocked(locked);
  }

  static final class TrackMetadata {
    // long：上游 songId 可能超 int32 上限，溢出会导致 liked/favorite/prefetch 全错
    long songId;
    long durationMs;
    boolean canLike;
    boolean liked;
    String title = "";
    String artist = "";
    String album = "";
    String coverUrl = "";
    String url = "";

    TrackMetadata copy() {
      TrackMetadata copy = new TrackMetadata();
      copy.songId = songId;
      copy.durationMs = durationMs;
      copy.canLike = canLike;
      copy.liked = liked;
      copy.title = title;
      copy.artist = artist;
      copy.album = album;
      copy.coverUrl = coverUrl;
      copy.url = url;
      return copy;
    }
  }

  private static final class FavoriteRequestResult {
    final boolean success;
    final String message;

    private FavoriteRequestResult(boolean success, @Nullable String message) {
      this.success = success;
      this.message = message;
    }

    static FavoriteRequestResult success() {
      return new FavoriteRequestResult(true, null);
    }

    static FavoriteRequestResult failure(String message) {
      return new FavoriteRequestResult(false, message);
    }
  }
}

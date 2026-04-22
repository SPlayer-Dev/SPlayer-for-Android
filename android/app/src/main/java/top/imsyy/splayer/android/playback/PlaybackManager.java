package top.imsyy.splayer.android.playback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.KeyEvent;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.session.CommandButton;
import androidx.media3.session.MediaSession;
import androidx.media3.session.SessionCommand;
import androidx.media3.session.SessionCommands;
import androidx.media3.session.SessionResult;
import com.getcapacitor.JSObject;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import top.imsyy.splayer.android.MainActivity;
import top.imsyy.splayer.android.R;

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
  private static volatile PlaybackManager instance;

  private final Context appContext;
  private final Handler mainHandler = new Handler(Looper.getMainLooper());
  private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
  private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

  private ExoPlayer player;
  private MediaSession mediaSession;
  private PlaybackService service;
  private AndroidNativePlaybackPlugin plugin;
  private Bitmap coverBitmap;
  private Typeface notificationIconTypeface;

  private String currentSource = "";
  private String apiBaseUrl = "";
  private String cookie = "";
  private TrackMetadata currentMetadata = new TrackMetadata();
  private TrackMetadata queuedNextMetadata = null;
  private String queuedNextSource = "";

  private boolean controllerEnabled = true;
  private boolean desktopLyricButtonEnabled = false;
  private boolean desktopLyricEnabled = false;
  /** 允许与其他应用同时播放（关闭时才请求音频焦点，抢占其他应用） */
  private boolean allowMixWithOthers = true;
  private boolean canSkipPrevious = true;
  private boolean personalFmMode = false;
  private boolean repeatOneEnabled = false;
  private boolean liked = false;
  private boolean collapsed = false;
  private boolean favoriteRequestInFlight = false;
  private long pendingSeekPositionMs = C.TIME_UNSET;
  private long pendingSeekDeadlineMs = 0L;
  private long lastKnownPositionMs = 0L;
  /** 悬浮歌词服务实例（运行时绑定） */
  private FloatingLyricService floatingLyricService;
  /** 远程状态同步：JS 端 AudioElementPlayer 驱动播放时，通知栏状态由此控制 */
  private boolean remoteMode = false;
  private boolean remoteIsPlaying = false;
  private long remotePositionMs = 0L;
  private long remoteDurationMs = 0L;
  /** Remote mode 下保持 CPU 唤醒，防止后台音频中断 */
  private PowerManager.WakeLock remoteWakeLock;
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
              .setAvailablePlayerCommands(MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS)
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
              return handleSessionAction(PlaybackConstants.ACTION_NEXT)
                  ? SessionResult.RESULT_SUCCESS
                  : SessionResult.RESULT_ERROR_NOT_SUPPORTED;
            case Player.COMMAND_SEEK_TO_PREVIOUS:
            case Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM:
              return handleSessionAction(PlaybackConstants.ACTION_PREVIOUS)
                  ? SessionResult.RESULT_SUCCESS
                  : SessionResult.RESULT_ERROR_NOT_SUPPORTED;
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
          emitProgressChanged();

          if (player != null && player.getCurrentMediaItem() != null) {
            mainHandler.postDelayed(this, 1000L);
          }
        }
      };

  private PlaybackManager(Context context) {
    this.appContext = context.getApplicationContext();
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
    ensureInitialized();
    updateNotification();
  }

  public synchronized void detachService(PlaybackService playbackService) {
    if (service == playbackService) {
      service = null;
    }
  }

  public synchronized void attachPlugin(AndroidNativePlaybackPlugin playbackPlugin) {
    plugin = playbackPlugin;
    emitPlaybackState(true);
  }

  public synchronized void detachPlugin(AndroidNativePlaybackPlugin playbackPlugin) {
    if (plugin == playbackPlugin) {
      plugin = null;
    }
  }

  public synchronized MediaSession getSession() {
    ensureInitialized();
    return mediaSession;
  }

  public synchronized JSObject load(String url, long positionMs, boolean autoPlay) {
    ensureInitialized();
    ensureServiceRunning();

    // ExoPlayer 直接驱动播放，退出 remote mode（JS 驱动模式）
    remoteMode = false;
    currentSource = url == null ? "" : url;
    currentMetadata.url = currentSource;
    clearPendingSeek();
    lastKnownPositionMs = 0L;

    if (positionMs > 0) {
      // 在 prepare 之前设置起始位置，ExoPlayer 会在准备阶段
      // 通过 HTTP Range 请求正确位置的数据，而非先准备再 seekTo
      player.setMediaItem(buildMediaItem(currentSource), positionMs);
      beginPendingSeek(positionMs);
    } else {
      player.setMediaItem(buildMediaItem(currentSource));
    }
    player.prepare();

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
    player.pause();
    player.seekTo(0L);
    player.stop();
    player.clearMediaItems();
    currentSource = "";
    clearPendingSeek();
    lastKnownPositionMs = 0L;
    queuedNextSource = "";
    queuedNextMetadata = null;
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
    currentMetadata.url = currentSource;
    if (player != null) {
      MediaItem currentItem = player.getCurrentMediaItem();
      if (currentItem != null) {
        player.replaceMediaItem(
            player.getCurrentMediaItemIndex(),
            currentItem.buildUpon().setMediaMetadata(buildMediaMetadata()).build());
      }
    }
    if (mediaSession != null) {
      mediaSession.setSessionActivity(buildContentIntent());
    }
    loadCoverBitmapAsync(currentMetadata.coverUrl);
    updateMediaSessionButtons();
    updateNotification();
    emitPlaybackState(true);
  }

  public synchronized void updateQueueContext(
      boolean likedState,
      boolean canSkipPreviousState,
      boolean personalFmModeState,
      boolean controllerEnabledState,
      boolean desktopLyricButtonEnabledState,
      boolean desktopLyricEnabledState,
      boolean repeatOneState,
      @Nullable TrackMetadata nextTrack) {
    liked = likedState;
    currentMetadata.liked = likedState;
    canSkipPrevious = canSkipPreviousState;
    personalFmMode = personalFmModeState;
    controllerEnabled = controllerEnabledState;
    desktopLyricButtonEnabled = desktopLyricButtonEnabledState;
    desktopLyricEnabled = desktopLyricEnabledState;
    repeatOneEnabled = repeatOneState;
    queuedNextMetadata = nextTrack == null ? null : nextTrack.copy();
    queuedNextSource =
        queuedNextMetadata == null || queuedNextMetadata.url == null ? "" : queuedNextMetadata.url;
    updateMediaSessionButtons();
    updateNotification();
    emitPlaybackState(false);
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
    if (allowMixWithOthers == allow) return;
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

  public synchronized void syncApiContext(String baseUrl, String cookieValue) {
    apiBaseUrl = baseUrl == null ? "" : baseUrl.trim();
    cookie = cookieValue == null ? "" : cookieValue.trim();
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
        emitCustomAction("next", null, null, null, null, true, null);
        break;
      case PlaybackConstants.ACTION_PREVIOUS:
        if (canSkipPrevious && !personalFmMode) {
          emitCustomAction("previous", null, null, null, null, true, null);
        }
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
        if (!canSkipPrevious || personalFmMode) {
          return false;
        }
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

    player = new ExoPlayer.Builder(appContext)
        .setMediaSourceFactory(
            new DefaultMediaSourceFactory(
                appContext,
                new DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true)
                    .setConstantBitrateSeekingAlwaysEnabled(true)))
        .build();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build(),
        !allowMixWithOthers);
    player.setHandleAudioBecomingNoisy(true);
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
            }

            updateNotification();
            emitPlaybackState(true);
          }

          @Override
          public void onIsPlayingChanged(boolean isPlaying) {
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
              emitProgressChanged(Math.max(0L, newPosition.positionMs));
              updateNotification();
              emitPlaybackState(true);
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            emitError(error.errorCode, error.getMessage());
            updateNotification();
          }
        });

    mediaSession =
        new MediaSession.Builder(appContext, player)
            .setSessionActivity(buildContentIntent())
            .setCallback(mediaSessionCallback)
            .setCustomLayout(buildCustomLayout())
            .setMediaButtonPreferences(buildMediaButtonPreferences())
            .setPeriodicPositionUpdateEnabled(true)
            .build();
    updateMediaSessionButtons();
    coverBitmap = BitmapFactory.decodeResource(appContext.getResources(), R.mipmap.ic_launcher);
  }

  private void ensureServiceRunning() {
    Intent intent = new Intent(appContext, PlaybackService.class);
    ContextCompat.startForegroundService(appContext, intent);
  }

  private MediaItem buildMediaItem(String url) {
    MediaItem.Builder builder = new MediaItem.Builder();
    if (url != null && !url.isEmpty()) {
      builder.setUri(Uri.parse(url));
    }
    builder.setMediaMetadata(buildMediaMetadata());
    return builder.build();
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
    if (currentMetadata.durationMs > 0) {
      builder.setDurationMs(currentMetadata.durationMs);
    }
    if (currentMetadata.coverUrl != null
        && !currentMetadata.coverUrl.isEmpty()
        && !currentMetadata.coverUrl.startsWith("blob:")) {
      try {
        builder.setArtworkUri(Uri.parse(currentMetadata.coverUrl));
      } catch (Exception ignored) {
      }
    }

    return builder.build();
  }

  private boolean handleAutoAdvanceOnEnded() {
    if (repeatOneEnabled && currentSource != null && !currentSource.isEmpty()) {
      startTrackFromState(currentSource, currentMetadata, liked, false);
      return true;
    }

    if (queuedNextSource == null || queuedNextSource.isEmpty() || queuedNextMetadata == null) {
      return false;
    }

    TrackMetadata nextMetadata = queuedNextMetadata;
    boolean nextLiked = nextMetadata.liked;
    startTrackFromState(queuedNextSource, nextMetadata, nextLiked, true);
    queuedNextMetadata = null;
    queuedNextSource = "";
    emitCustomAction("autoNext", nextMetadata.songId, nextLiked, null, null, true, null);
    return true;
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
    player.setMediaItem(buildMediaItem(currentSource));
    player.prepare();
    player.seekTo(0L);
    player.play();
    loadCoverBitmapAsync(currentMetadata.coverUrl);
    updateMediaSessionButtons();
    updateNotification();
    if (emitProgressImmediately) {
      emitProgressChanged();
    }
    emitPlaybackState(true);
  }

  private SessionCommands buildAvailableSessionCommands() {
    SessionCommands.Builder builder = new SessionCommands.Builder().add(nextSessionCommand);

    if (canSkipPrevious && !personalFmMode) {
      builder.add(previousSessionCommand);
    }
    if (currentMetadata.canLike) {
      builder.add(favoriteSessionCommand);
    }
    if (desktopLyricButtonEnabled) {
      builder.add(desktopLyricSessionCommand);
    }

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

    if (canSkipPrevious && !personalFmMode) {
      buttons.add(
          new CommandButton.Builder(CommandButton.ICON_PREVIOUS)
              .setSessionCommand(previousSessionCommand)
              .setDisplayName(appContext.getString(R.string.playback_notification_previous))
              .setSlots(CommandButton.SLOT_BACK)
              .build());
    }

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
          controller, sessionCommands, MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS);
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
    try {
      if (isEffectivelyPlaying() || isEffectivelyBuffering()) {
        service.startForeground(PlaybackConstants.NOTIFICATION_ID, notification);
      } else {
        service.stopForeground(false);
        NotificationManagerCompat.from(appContext)
            .notify(PlaybackConstants.NOTIFICATION_ID, notification);
      }
    } catch (SecurityException error) {
      Log.w(TAG, "Failed to show playback notification", error);
    }
  }

  private Notification buildNotification() {
    long durationMs = getDurationMs();
    long positionMs = getPositionMs();
    String artistText = safeText(currentMetadata.artist, "");
    String progressText = durationMs > 0 ? formatTime(positionMs) + " / " + formatTime(durationMs) : "";
    String contentText = artistText;
    if (!progressText.isEmpty()) {
      contentText = artistText.isEmpty() ? progressText : artistText + "  " + progressText;
    }

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
            .setContentText(contentText)
            .setLargeIcon(coverBitmap);

    if (durationMs > 0) {
      builder.setProgress((int) durationMs, (int) Math.min(positionMs, durationMs), false);
      builder.setSubText(progressText);
    } else {
      builder.setProgress(0, 0, false);
      builder.setSubText(null);
    }

    if (!controllerEnabled) {
      return builder.build();
    }

    int actionCount = 0;
    int playPauseActionIndex = 0;
    int nextActionIndex = 0;
    int previousActionIndex = -1;

    if (currentMetadata.canLike) {
      builder.addAction(buildNotificationAction(
          liked ? ICON_GLYPH_FAVORITE_FILLED : ICON_GLYPH_FAVORITE_OUTLINE,
          liked ? android.R.drawable.btn_star_big_on : android.R.drawable.btn_star_big_off,
          appContext.getString(R.string.playback_notification_favorite),
          PlaybackConstants.ACTION_FAVORITE));
      actionCount++;
    }

    if (canSkipPrevious && !personalFmMode) {
      previousActionIndex = actionCount;
      builder.addAction(buildNotificationAction(
          ICON_GLYPH_PREVIOUS,
          android.R.drawable.ic_media_previous,
          appContext.getString(R.string.playback_notification_previous),
          PlaybackConstants.ACTION_PREVIOUS));
      actionCount++;
    }

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
        previousActionIndex >= 0
            ? new int[] {previousActionIndex, playPauseActionIndex, nextActionIndex}
            : new int[] {playPauseActionIndex, nextActionIndex};

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
    emitProgressChanged(positionMs, true);
  }

  private void emitProgressChanged(long positionMs, boolean acknowledgePendingSeek) {
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
      @Nullable Integer songId,
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

  private void loadCoverBitmapAsync(String coverUrl) {
    if (coverUrl == null || coverUrl.isEmpty() || coverUrl.startsWith("blob:")) {
      coverBitmap = BitmapFactory.decodeResource(appContext.getResources(), R.mipmap.ic_launcher);
      updateNotification();
      return;
    }

    artworkExecutor.execute(
        () -> {
          Bitmap bitmap = null;
          InputStream inputStream = null;
          HttpURLConnection connection = null;

          try {
            if (coverUrl.startsWith("http://") || coverUrl.startsWith("https://")) {
              connection = (HttpURLConnection) new URL(coverUrl).openConnection();
              connection.setConnectTimeout(8000);
              connection.setReadTimeout(8000);
              connection.setDoInput(true);
              connection.connect();
              inputStream = connection.getInputStream();
              bitmap = BitmapFactory.decodeStream(inputStream);
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

          mainHandler.post(
              () -> {
                coverBitmap = resolvedBitmap;
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
    final int songId = currentMetadata.songId;
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

  private FavoriteRequestResult performFavoriteRequest(int songId, boolean targetLike) {
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
      } else if (Math.abs(playerPositionMs - pendingSeekPositionMs) <= SEEK_POSITION_TOLERANCE_MS
          || playerPositionMs >= pendingSeekPositionMs - 250L) {
        clearPendingSeek();
      }
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
    lastKnownPositionMs = safePositionMs;
    if (pendingSeekPositionMs == C.TIME_UNSET) {
      return;
    }

    if (Math.abs(safePositionMs - pendingSeekPositionMs) <= SEEK_POSITION_TOLERANCE_MS
        || safePositionMs >= pendingSeekPositionMs - 250L) {
      clearPendingSeek();
    }
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
    int songId;
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

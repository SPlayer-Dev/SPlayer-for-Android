package top.imsyy.splayer.android.cache;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.datasource.cache.CacheDataSink;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.CacheSpan;
import androidx.media3.datasource.cache.CacheWriter;
import androidx.media3.datasource.cache.ContentMetadata;
import androidx.media3.datasource.cache.NoOpCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ExoPlayer SimpleCache 单例提供者。
 *
 * <p>关键设计：
 *
 * <ul>
 *   <li>使用 {@link NoOpCacheEvictor}：把驱逐权交给 {@link CacheStorage} 全局 LRU，避免双重逻辑。
 *   <li>cacheKey：从 URL 路径尾段提取音频文件名作为指纹，去掉 expire 签名等动态参数；
 *       同一首歌不同时间签出的 URL 仍可命中同一份缓存。
 *   <li>{@link DataSource.Factory} 链：HTTP upstream → CacheDataSource。
 * </ul>
 */
public final class AudioCacheProvider {

  /**
   * 音频文件名匹配：路径末段是 “xxxxx.{ext}” 形式时提取作为 cacheKey 主体。<br>
   * 不仅适用 NCM；任何 CDN 返回“动态路径 + 音频后缀”均可命中。使用 (?i) 允许大写后缀。
   */
  private static final Pattern AUDIO_FILE_PATTERN =
      Pattern.compile("/([A-Za-z0-9_-]+\\.(?i:mp3|flac|m4a|ogg|wav|aac|opus))");

  /**
   * 低磁盘阈值：设备剩余不足 1GB 时禁止音频缓存<strong>写入</strong>（prefetch + 播放期 sink）。<br>
   * 读取不受影响：已缓存的歌仍可本地命中播放。
   */
  public static final long LOW_DISK_THRESHOLD_BYTES = 1024L * 1024L * 1024L;

  /**
   * 设备剩余空间是否低于 {@link #LOW_DISK_THRESHOLD_BYTES}。<br>
   * StatFs 读取失败 (-1) 时返 false（保守：读不出不随意禁写）。
   */
  public static boolean isLowDiskSpace(@NonNull Context appContext) {
    long free = CacheStorage.getInstance(appContext).getDeviceFreeBytes();
    if (free < 0) return false;
    return free < LOW_DISK_THRESHOLD_BYTES;
  }

  @SuppressWarnings("StaticFieldLeak")
  private static volatile SimpleCache simpleCache;

  private static final Object lock = new Object();
  @Nullable private static volatile DiagnosticListener diagnosticListener;

  private AudioCacheProvider() {}

  public interface DiagnosticListener {
    void onDiagnosticLog(@NonNull String tag, @NonNull String message);
  }

  public static void setDiagnosticListener(@Nullable DiagnosticListener listener) {
    diagnosticListener = listener;
  }

  private static void emitDiagnosticLog(@NonNull String tag, @NonNull String message) {
    Log.d(TAG, tag + " " + message);
    DiagnosticListener listener = diagnosticListener;
    if (listener != null) {
      listener.onDiagnosticLog(tag, message);
    }
  }

  /** 懒初始化 SimpleCache 单例（同进程多次构造同目录会抛 IllegalStateException）。 */
  @NonNull
  public static SimpleCache getOrCreate(@NonNull Context appContext) {
    SimpleCache cache = simpleCache;
    if (cache != null) return cache;
    synchronized (lock) {
      if (simpleCache != null) return simpleCache;
      CacheStorage storage = CacheStorage.getInstance(appContext);
      simpleCache =
          new SimpleCache(
              storage.getAudioCacheDir(),
              new NoOpCacheEvictor(),
              new StandaloneDatabaseProvider(appContext));
      return simpleCache;
    }
  }

  /**
   * 偷看已初始化的 SimpleCache 实例；未初始化返 null，不触发初始化。<br>
   * 供 {@link CacheStorage#getTypeBytes} 在 SimpleCache 已就绪时走 O(1) 读取，否则回退扫盘。
   */
  @Nullable
  public static SimpleCache peekSimpleCache() {
    return simpleCache;
  }

  /** 释放（应用退出 / 测试场景）；正常运行不调用，单例随进程生命周期。 */
  public static void release() {
    synchronized (lock) {
      if (simpleCache != null) {
        simpleCache.release();
        simpleCache = null;
      }
    }
  }

  /**
   * 构造带缓存的 DataSource.Factory。
   *
   * <p>关键点 —— 必须显式设 {@link CacheDataSink}，否则 ExoPlayer 默认<strong>只读不写</strong>，
   * 已 prefetch 的字节会命中，但 prefetch 范围之外（如 512 KB 之后 / seek 跳过的段落）走 HTTP
   * upstream 拉取后不会落盘，导致"前半部分有、后半部分没"的缓存空洞。
   *
   * <p>{@link CacheDataSink#DEFAULT_FRAGMENT_SIZE} 是 5 MB；多数 NCM 歌曲单首 3-10 MB，
   * 显式设 {@link Long#MAX_VALUE} 强制单文件 chunk，避免 seek 时碎片化 + 减少 SimpleCache 元数据开销。
   *
   * <p>flags：
   * <ul>
   *   <li>{@link CacheDataSource#FLAG_IGNORE_CACHE_ON_ERROR}：上游失败时仍可读已缓存部分
   *   <li>{@link CacheDataSource#FLAG_BLOCK_ON_CACHE}：写入完成前阻塞读取，保证完整性
   * </ul>
   */
  @NonNull
  public static DataSource.Factory buildCachedDataSourceFactory(@NonNull Context appContext) {
    SimpleCache cache = getOrCreate(appContext);

    DataSource.Factory httpFactory =
        new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000);

    // http(s) 走 CacheDataSource；本地 file:// / content:// 直接 DefaultDataSource，
    // 避免本地文件被复制一份到 cacheDir/exo/。
    DataSource.Factory httpCacheFactory = buildHttpCacheFactory(appContext, httpFactory, cache, false);
    DataSource.Factory localFactory = new DefaultDataSource.Factory(appContext);

    return () -> new SchemeRoutingDataSource(httpCacheFactory, localFactory);
  }

  /**
   * 构造仅用于 http(s) 的 CacheDataSource 工厂；prefetch 路径直接使用此工厂，
   * 以便 {@code (CacheDataSource) ds} 强转保持有效。
   */
  @NonNull
  private static DataSource.Factory buildHttpCacheFactory(
      @NonNull Context appContext,
      @NonNull DataSource.Factory httpFactory,
      @NonNull SimpleCache cache,
      boolean blockOnCache) {
    CacheDataSink.Factory cacheSinkFactory =
        new CacheDataSink.Factory().setCache(cache).setFragmentSize(Long.MAX_VALUE);
    return () -> {
      CacheDataSource.Factory cf =
          new CacheDataSource.Factory()
              .setCache(cache)
              .setUpstreamDataSourceFactory(httpFactory)
              .setCacheKeyFactory(spec -> resolveCacheKey(spec.uri))
              .setFlags(
                  blockOnCache
                      ? CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR
                          | CacheDataSource.FLAG_BLOCK_ON_CACHE
                      : CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
      if (!isLowDiskSpace(appContext)) {
        cf.setCacheWriteDataSinkFactory(cacheSinkFactory);
      } else {
        Log.w(TAG, "low disk: audio cache write disabled (read-only)");
      }
      return cf.createDataSource();
    };
  }

  /**
   * 内部使用：prefetch / CacheWriter 路径专用的 CacheDataSource 工厂。
   * 始终返回 CacheDataSource 实例（http 上游 + cache 落盘），不做 scheme 路由。
   */
  @NonNull
  static DataSource.Factory buildHttpCacheFactoryForPrefetch(@NonNull Context appContext) {
    SimpleCache cache = getOrCreate(appContext);
    DataSource.Factory httpFactory =
        new DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000);
    return buildHttpCacheFactory(appContext, httpFactory, cache, true);
  }

  /**
   * 根据首次 open 的 DataSpec scheme 选择 http cache 或本地直读。<br>
   * 同一实例的多次 open 不应跨 scheme（ExoPlayer 不会复用 DataSource 跨 MediaItem），
   * 这里仍按每次 open 重新选择以稳健处理边界情况。
   */
  private static final class SchemeRoutingDataSource implements DataSource {
    private final DataSource.Factory httpCacheFactory;
    private final DataSource.Factory localFactory;
    private final java.util.List<TransferListener> pendingListeners = new java.util.ArrayList<>();
    private final Object routeLock = new Object();
    @Nullable private volatile DataSource current;

    SchemeRoutingDataSource(
        @NonNull DataSource.Factory httpCacheFactory, @NonNull DataSource.Factory localFactory) {
      this.httpCacheFactory = httpCacheFactory;
      this.localFactory = localFactory;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
      DataSource ds;
      synchronized (routeLock) {
        ds = current;
        if (ds == null) {
          pendingListeners.add(transferListener);
        }
      }
      if (ds != null) ds.addTransferListener(transferListener);
    }

    @Override
    public long open(@NonNull DataSpec dataSpec) throws java.io.IOException {
      DataSource previous;
      synchronized (routeLock) {
        previous = current;
        current = null;
      }
      if (previous != null) {
        try {
          previous.close();
        } catch (java.io.IOException ignored) {
        }
      }
      String scheme = dataSpec.uri.getScheme();
      boolean isHttp = "http".equals(scheme) || "https".equals(scheme);
      DataSource ds = isHttp ? httpCacheFactory.createDataSource() : localFactory.createDataSource();
      synchronized (routeLock) {
        for (TransferListener listener : pendingListeners) {
          ds.addTransferListener(listener);
        }
        pendingListeners.clear();
        current = ds;
      }
      long startedAt = android.os.SystemClock.elapsedRealtime();
      try {
        return ds.open(dataSpec);
      } finally {
        long costMs = android.os.SystemClock.elapsedRealtime() - startedAt;
        if (isHttp && costMs > 1000L) {
          emitDiagnosticLog("DIAG-AudioOpen", "slow open " + costMs + "ms key=" + resolveCacheKey(dataSpec.uri));
        }
      }
    }

    @Override
    public int read(@NonNull byte[] buffer, int offset, int length) throws java.io.IOException {
      DataSource ds = current;
      if (ds == null) return C.RESULT_END_OF_INPUT;
      return ds.read(buffer, offset, length);
    }

    @Nullable
    @Override
    public Uri getUri() {
      DataSource ds = current;
      return ds == null ? null : ds.getUri();
    }

    @Override
    public void close() throws java.io.IOException {
      DataSource ds;
      synchronized (routeLock) {
        ds = current;
        current = null;
      }
      if (ds != null) {
        ds.close();
      }
    }
  }

  /** 已知的临时签名 / 过期参数：仅这些会被剔除以保证同曲目缓存命中。其余 query 参与 key 防碰撞。 */
  private static final java.util.Set<String> EPHEMERAL_QUERY_KEYS =
      new java.util.HashSet<>(
          java.util.Arrays.asList(
              "expire",
              "expires",
              "signature",
              "sign",
              "token",
              "auth_key",
              "_t",
              "timestamp",
              "ts"));

  /**
   * 从 URL 提取稳定 cacheKey。
   *
   * <p>API命中 {@link #NCM_FILE_PATTERN} 时走 {@code ncm:<id>}，最稳定。
   * <p>其他场景（Subsonic / 自部署流媒体 / 签名 URL）兜底用 {@code host + path + 排序后非临时 query}
   * 的 md5：仅剔除已知临时签名参数，保留区分歌曲的业务参数（如 ?id=xxx）防止 A/B 共用 key 播错歌。
   */
  @NonNull
  public static String resolveCacheKey(@NonNull Uri uri) {
    String scheme = uri.getScheme();
    if (scheme != null && scheme.startsWith("file")) {
      // 本地文件：直接用 path（不会进缓存，但 cacheKey 仍要求非空）
      return "local:" + uri.getPath();
    }
    String path = uri.getPath();
    if (path == null) path = "";
    Matcher m = AUDIO_FILE_PATTERN.matcher(path);
    if (m.find()) {
      // 前缀 aud: 表示“从 URL 提取的音频文件名”；早期版本使用 ncm:，重命名后一次性 cache miss，可接受。
      return "aud:" + m.group(1).toLowerCase(java.util.Locale.ROOT);
    }
    // 通用回退：host + path + 排序后的稳定 query（剔除签名/过期参数）
    StringBuilder sb = new StringBuilder();
    String host = uri.getHost();
    if (host != null) sb.append(host);
    sb.append(path);
    java.util.Set<String> qnames;
    try {
      qnames = uri.getQueryParameterNames();
    } catch (UnsupportedOperationException e) {
      qnames = java.util.Collections.emptySet();
    }
    if (!qnames.isEmpty()) {
      java.util.List<String> sortedKeys = new java.util.ArrayList<>(qnames);
      java.util.Collections.sort(sortedKeys);
      sb.append('?');
      boolean first = true;
      for (String qk : sortedKeys) {
        if (EPHEMERAL_QUERY_KEYS.contains(qk.toLowerCase(java.util.Locale.ROOT))) continue;
        String qv = uri.getQueryParameter(qk);
        if (qv == null) qv = "";
        if (!first) sb.append('&');
        sb.append(qk).append('=').append(qv);
        first = false;
      }
    }
    return CacheStorage.keyFromUrl(sb.toString());
  }

  // ========== prefetch ==========

  private static final String TAG = "AudioCachePrefetch";
  /** 默认预下载字节数：2 MB，避免无损 / Hi-Res 在开头数秒跨过 512 KB 缓存边界后阻塞。 */
  public static final long DEFAULT_PREFETCH_BYTES = 2L * 1024L * 1024L;
  /** 短预载单线程：供“下一首前段音频”使用，低占用高响应。 */
  private static volatile ExecutorService prefetchExecutor;
  /**
   * 全量下载独立单线程：全量一首 5-10MB 需背景跑，不能占着短预载走道。<br>
   * 拆走后：load 下一首 → prefetchExecutor 立即跑短预载；全量送到 fullDownloadExecutor。
   */
  private static volatile ExecutorService fullDownloadExecutor;
  /** 全量下载任务取消标志；cancelAllPrefetch / clearAll 需要能同步中断。 */
  @Nullable private static volatile AtomicBoolean currentFullCancelFlag;
  /** 已在排队 / 进行中的 cacheKey 集合，幂等去重。 */
  private static final Set<String> inFlight = new HashSet<>();
  /**
   * 每个 cacheKey 独立写锁：保证同 cacheKey 的 CacheWriter 不会并发执行（短预载 + 预载任务跨 executor 时
   * 都从位置 0 写同一组 span，并发会触发 SimpleCache 的 holeSpan 锁竞争 + CacheException）。<br>
   * 短任务持锁 ~1s 完成；预载任务后到达直接接力——FLAG_BLOCK_ON_CACHE 会跳过已缓存字节只下剩余部分。<br>
   * 用 ConcurrentHashMap 保证 computeIfAbsent 的原子性。
   */
  private static final Map<String, Object> cacheKeyWriterLocks = new ConcurrentHashMap<>();
  /** 当前 prefetch 任务的 cancel 标志：切歌时取消上一首的 prefetch，给新任务让带宽。 */
  @Nullable private static volatile AtomicBoolean currentCancelFlag;

  @NonNull
  private static ExecutorService getExecutor() {
    if (prefetchExecutor == null) {
      synchronized (AudioCacheProvider.class) {
        if (prefetchExecutor == null) {
          prefetchExecutor =
              Executors.newSingleThreadExecutor(
                  r -> {
                    Thread t = new Thread(r, "audio-cache-prefetch");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                  });
        }
      }
    }
    return prefetchExecutor;
  }

  /** 预载 独立 executor：与短预载不抢资源。 */
  @NonNull
  private static ExecutorService getFullDownloadExecutor() {
    if (fullDownloadExecutor == null) {
      synchronized (AudioCacheProvider.class) {
        if (fullDownloadExecutor == null) {
          fullDownloadExecutor =
              Executors.newSingleThreadExecutor(
                  r -> {
                    Thread t = new Thread(r, "audio-cache-full-download");
                    t.setDaemon(true);
                    // 更低优先级：预载 为后台作业，不应抢占短预载 / 当前播放。
                    t.setPriority(Thread.NORM_PRIORITY - 2);
                    return t;
                  });
        }
      }
    }
    return fullDownloadExecutor;
  }

  /**
   * 主动把 url 前 {@link #DEFAULT_PREFETCH_BYTES} 字节写入 SimpleCache。
   *
   * <p>用法：切歌成功后立即调用 {@code prefetchUrl(下一首 url)}。下次 ExoPlayer setMediaItem
   * 这条 url 时，CacheDataSource 立刻命中本地，跳过 OPEN→网络握手 100-500ms。
   *
   * <p>幂等：同 cacheKey 已在队列或已 ready 时直接 no-op。
   *
   * @param appContext app context
   * @param url 要预 的 http(s) url；非 http 直接忽略
   */
  public static void prefetchUrl(@NonNull Context appContext, @Nullable String url) {
    prefetchUrlWithLength(appContext, url, DEFAULT_PREFETCH_BYTES, /* cancelPrev= */ true);
  }

  /**
   * 把 url 整首音频拉完写入 SimpleCache（length=EOF）。<br>
   * 用法：当用户播放某首歌 >10s 时，调用此方法把整首存档为"正式缓存"，
   * 为后续 automix（需要音频完整字节做 BPM / energy 分析）和离线播放铺路。
   *
   * <p>与短预载共用同一单线程池，按调用顺序排队；不取消已有任务（不抢带宽），
   * 排到自己时若用户已经切歌，TTL 索引也会让本任务的字节仍然有效（promoted=true）。
   */
  public static void prefetchUrlFull(@NonNull Context appContext, @Nullable String url) {
    // 幂等短路：已 promoted 跳过。预载 的推进与最终 promote 标记都在 prefetchUrlWithLength 内负责。
    if (url == null || url.isEmpty()) return;
    Uri uri = Uri.parse(url);
    // scheme 校验与短预载入口保持一致：非 http(s) 直接拒绝，避免误算 cacheKey 与日志噪声。
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return;
    String cacheKey = resolveCacheKey(uri);
    if (AudioPrefetchTtlIndex.getInstance(appContext).isPromoted(cacheKey)) return;
    // cancelPrev=false：完整 不应抢占已经在跑的短预载
    prefetchUrlWithLength(appContext, url, /* length= */ Long.MAX_VALUE, /* cancelPrev= */ false);
  }

  /** 公共实现：length 控制 字节数；cancelPrev 控制是否取消上一首未完成的任务。 */
  private static void prefetchUrlWithLength(
      @NonNull Context appContext, @Nullable String url, long length, boolean cancelPrev) {
    if (url == null || url.isEmpty()) return;
    // 低磁盘：不启动任何 prefetch（短预载 / 预载 都被拦）。在 PlaybackManager.schedulePromotion 调用前路拦截。
    if (isLowDiskSpace(appContext)) {
      Log.d(TAG, "low disk: prefetch skipped for " + url);
      return;
    }
    Uri uri = Uri.parse(url);
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return;

    final String cacheKey = resolveCacheKey(uri);
    final String inFlightKey = cacheKey + "|" + length; // 同 key 但不同 length 的请求不互斥
    synchronized (inFlight) {
      if (inFlight.contains(inFlightKey)) {
        return; // 已在排队 / 进行中
      }
      inFlight.add(inFlightKey);
    }

    final SimpleCache cache = getOrCreate(appContext);
    final AudioPrefetchTtlIndex ttlIndex = AudioPrefetchTtlIndex.getInstance(appContext);
    // 已完整命中（cachedBytes ≥ length 阈值）则跳过 IO；但仍刷新 TTL 索引让缓存"续期"
    long probeLength = length == Long.MAX_VALUE ? DEFAULT_PREFETCH_BYTES : length;
    long cachedBytes = cache.getCachedBytes(cacheKey, 0, probeLength);
    // 预载 场景：还需要确认是否真的下完了（用 contentLength 判断更准但成本高，这里宽松判定）
    if (length != Long.MAX_VALUE && cachedBytes >= length) {
      ttlIndex.markAccess(cacheKey);
      synchronized (inFlight) {
        inFlight.remove(inFlightKey);
      }
      return;
    }

    // 取消上一首的 prefetch：给当前新任务腾带宽（仅短预载场景）
    AtomicBoolean cancelFlag = new AtomicBoolean(false);
    final boolean isFull = length == Long.MAX_VALUE;
    if (cancelPrev) {
      AtomicBoolean prevCancel = currentCancelFlag;
      if (prevCancel != null) prevCancel.set(true);
      currentCancelFlag = cancelFlag;
    }
    if (isFull) {
      // 预载 不抢带宽但要可被 cancelAll 中断；覆盖上次未完成的预载任务取消标。
      AtomicBoolean prevFull = currentFullCancelFlag;
      if (prevFull != null) prevFull.set(true);
      currentFullCancelFlag = cancelFlag;
    }
    final AtomicBoolean cancelFlagFinal = cancelFlag;
    final long lengthFinal = length;

    // 路由：预载走后台 executor，短预载走响应 executor，互不阻塞。
    ExecutorService chosen = isFull ? getFullDownloadExecutor() : getExecutor();
    chosen.execute(() -> {
      // 取每个 cacheKey 自己的 monitor：跨 executor 时同 cacheKey 串行，避免并发 CacheWriter 写同一组 span
      final Object writerLock = cacheKeyWriterLocks.computeIfAbsent(cacheKey, k -> new Object());
      long lockWaitStartedAt = android.os.SystemClock.elapsedRealtime();
      try {
        synchronized (writerLock) {
          long lockWaitMs = android.os.SystemClock.elapsedRealtime() - lockWaitStartedAt;
          if (lockWaitMs > 500L) {
            emitDiagnosticLog("DIAG-CacheWriter", "lock wait " + lockWaitMs + "ms key=" + cacheKey);
          }
          DataSource.Factory factory = buildHttpCacheFactoryForPrefetch(appContext);
          DataSource ds = factory.createDataSource();
          DataSpec.Builder specBuilder =
              new DataSpec.Builder().setUri(uri).setKey(cacheKey).setPosition(0);
          if (lengthFinal != Long.MAX_VALUE) {
            specBuilder.setLength(lengthFinal);
          }
          DataSpec spec = specBuilder.build();
          CacheWriter writer =
              new CacheWriter(
                  (CacheDataSource) ds,
                  spec,
                  /* temporaryBuffer= */ null,
                  (requestLength, bytesCached, newBytesCached) -> {
                    // 取消信号：抛 InterruptedException 让 CacheWriter 退出
                    if (cancelFlagFinal.get()) Thread.currentThread().interrupt();
                  });
          writer.cache();
          // 预载 （length=MAX_VALUE）成功返回才标 promoted；CacheWriter 中途抛异常会走到 catch，不会 promote。
          // 这样 getPromotedAudioFile 看到 isPromoted=true 时，可认为文件完整；automix 不会读到截断字节。
          //
          // 修复 #1：promote 前比对实际缓存字节数 vs upstream Content-Length（CacheDataSource 在 open 时
          // 已写入 ContentMetadata）。chunked encoding 服务端提前 EOF 等场景 writer.cache() 仍正常返回，
          // 但 cachedBytes < contentLength → 不 promote，避免截断文件被当作完整 automix 源。
          if (lengthFinal == Long.MAX_VALUE) {
            long expected = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey));
            long actual = cache.getCachedBytes(cacheKey, 0, Long.MAX_VALUE);
            if (expected > 0 && actual < expected) {
              Log.w(
                  TAG,
                  "promote skipped (truncated): "
                      + cacheKey
                      + " cached="
                      + actual
                      + " expected="
                      + expected);
            } else {
              ttlIndex.promote(cacheKey);
            }
          } else {
            ttlIndex.markAccess(cacheKey);
          }
          String lengthLabel = lengthFinal == Long.MAX_VALUE ? "FULL" : lengthFinal + " bytes";
          Log.d(TAG, "prefetch done: " + cacheKey + " (" + lengthLabel + ")");
        }
      } catch (Throwable e) {
        // 网络失败 / 取消都走这里；不致命，下次播放走正常流程
        Log.d(TAG, "prefetch aborted: " + cacheKey + " - " + e.getMessage());
      } finally {
        synchronized (inFlight) {
          inFlight.remove(inFlightKey);
        }
      }
    });
  }

  /** 取消所有正在排队的 prefetch（应用关闭 / 清缓存场景）；同时中断预载 。 */
  public static void cancelAllPrefetch() {
    AtomicBoolean flag = currentCancelFlag;
    if (flag != null) flag.set(true);
    AtomicBoolean fullFlag = currentFullCancelFlag;
    if (fullFlag != null) fullFlag.set(true);
    synchronized (inFlight) {
      inFlight.clear();
    }
  }

  /**
   * 安全清空 audio 缓存：走 {@link SimpleCache#removeResource} 让 SimpleCache 同步内部 ContentIndex，
   * 而非 deleteRecursive 物理删目录（后者会导致活跃 SimpleCache 实例索引/磁盘不一致，后续播放抛 IOException）。
   *
   * <p>同时清 promoted 索引，避免 isPromoted 命中已删 key。
   */
  public static void clearAll(@NonNull Context appContext) {
    cancelAllPrefetch();
    SimpleCache cache = simpleCache;
    if (cache != null) {
      // 取 keys 副本：cache.getKeys() 返回内部视图，迭代中 removeResource 会 ConcurrentModification
      Set<String> keys = new HashSet<>(cache.getKeys());
      for (String key : keys) {
        try {
          cache.removeResource(key);
        } catch (Throwable e) {
          Log.w(TAG, "removeResource failed: " + key, e);
        }
      }
    }
    AudioPrefetchTtlIndex.getInstance(appContext).clearAllPromoted();
  }

  /**
   * 让 audio 缓存收缩到 {@code maxAudioBytes}：按 mtime 升序删 key，promoted 排到最后保护。
   *
   * <p>由 CacheStorage.enforceLimit 在非 audio 删完仍超配额时调用，让 audio 真正参与全局 LRU。
   *
   * @return 释放的字节数；调用方可记日志 / 汇报
   */
  public static long enforceLimitTo(@NonNull Context appContext, long maxAudioBytes) {
    SimpleCache cache = simpleCache;
    if (cache == null) return 0L;
    long total = cache.getCacheSpace();
    if (total <= maxAudioBytes) return 0L;

    AudioPrefetchTtlIndex idx = AudioPrefetchTtlIndex.getInstance(appContext);
    Set<String> keys;
    try {
      keys = new HashSet<>(cache.getKeys());
    } catch (Throwable e) {
      return 0L;
    }

    // 收集 (key, lastMtime, totalBytes, isPromoted)
    final class KeyMeta {
      final String key;
      final long mtime;
      final long bytes;
      final boolean promoted;

      KeyMeta(String k, long m, long b, boolean p) {
        key = k;
        mtime = m;
        bytes = b;
        promoted = p;
      }
    }
    java.util.List<KeyMeta> metas = new java.util.ArrayList<>();
    for (String key : keys) {
      NavigableSet<CacheSpan> spans;
      try {
        spans = cache.getCachedSpans(key);
      } catch (Throwable e) {
        continue;
      }
      if (spans == null || spans.isEmpty()) continue;
      long maxMtime = 0L, bytes = 0L;
      for (CacheSpan s : spans) {
        if (s.file != null) maxMtime = Math.max(maxMtime, s.file.lastModified());
        bytes += s.length;
      }
      metas.add(new KeyMeta(key, maxMtime, bytes, idx.isPromoted(key)));
    }
    // 排序：promoted 排最后（不轻易删）；同组按 mtime 升序
    metas.sort(
        (a, b) -> {
          if (a.promoted != b.promoted) return a.promoted ? 1 : -1;
          return Long.compare(a.mtime, b.mtime);
        });

    long current = total;
    long target = (long) (maxAudioBytes * 0.8);
    long freed = 0L;
    for (KeyMeta m : metas) {
      if (current <= target) break;
      try {
        cache.removeResource(m.key);
        // 同时清 TTL / promoted 索引（防孤儿条目）
        if (m.promoted) idx.unmarkPromoted(m.key);
        current -= m.bytes;
        freed += m.bytes;
      } catch (Throwable e) {
        Log.w(TAG, "evict failed: " + m.key, e);
      }
    }
    Log.i(TAG, "audio enforceLimit: 释放 " + freed + " 字节，回到 " + current + "/" + maxAudioBytes);
    return freed;
  }

  // ========== automix 支持 ==========

  /**
   * 给 automix 等需要直接读完整音频字节的场景：返回 SimpleCache 中某 url 对应的本地完整文件。
   *
   * <p>判定条件（必须全部满足）：
   * <ol>
   *   <li>该 url 已 promoted（用户播放 > 10s，并触发过 prefetchUrlFull）
   *   <li>SimpleCache 中存在 cacheKey 且字节连续（{@link CacheSpan#isCached}）
   *   <li>从 offset=0 开始（不是 seek 后的中段缓存）
   *   <li>只有一个 span（fragmentSize=MAX 保证）
   * </ol>
   *
   * <p>返回的 {@link File} 是真实物理文件路径，调用方可直接 fopen 做：
   * <ul>
   *   <li>BPM 检测（Aubio / Essentia）
   *   <li>波形分析 / 振幅包络
   *   <li>能量段落分割（用于自动 crossfade in/out 点选择）
   *   <li>淡入淡出 / EQ 等离线音频处理
   * </ul>
   *
   * <p>文件名是 SimpleCache 内部编码（{@code <cacheKey>.<index>.<id>.v3.exo}），<br>
   * 后缀 .exo 不是音频格式提示——文件内容是原始网络字节（mp3/flac/m4a 取决于 url），<br>
   * 用 ffmpeg / MediaExtractor 等需要自动识别格式或显式指定。
   *
   * @param appContext app context
   * @param url 原始 http(s) url（与播放时一致即可，签名 / expire 参数可不同）
   * @return 完整本地文件 File；未 promoted / 未下完 / SimpleCache 未就绪均返 null
   */
  @Nullable
  public static File getPromotedAudioFile(@NonNull Context appContext, @Nullable String url) {
    if (url == null || url.isEmpty()) return null;
    Uri uri;
    try {
      uri = Uri.parse(url);
    } catch (Throwable e) {
      return null;
    }
    String scheme = uri.getScheme();
    if (scheme == null || (!scheme.equals("http") && !scheme.equals("https"))) return null;

    String cacheKey = resolveCacheKey(uri);
    if (!AudioPrefetchTtlIndex.getInstance(appContext).isPromoted(cacheKey)) return null;

    SimpleCache cache = simpleCache;
    if (cache == null) return null;

    NavigableSet<CacheSpan> spans;
    try {
      spans = cache.getCachedSpans(cacheKey);
    } catch (Throwable e) {
      return null;
    }
    if (spans == null || spans.isEmpty()) return null;

    // fragmentSize=MAX 保证单 span；若意外有多 span 说明文件被切片，无法整段使用
    if (spans.size() > 1) return null;
    CacheSpan span = spans.first();
    if (span == null || span.position != 0L || !span.isCached || span.file == null) return null;
    if (!span.file.isFile()) return null;
    return span.file;
  }

  /**
   * 给 automix 探测：某 url 对应的本地缓存音频是否已就绪（promoted + 单 span 完整）。<br>
   * 等价于 {@link #getPromotedAudioFile} != null，但不分配 File 对象。
   */
  public static boolean isPromotedAudioReady(@NonNull Context appContext, @Nullable String url) {
    return getPromotedAudioFile(appContext, url) != null;
  }
}

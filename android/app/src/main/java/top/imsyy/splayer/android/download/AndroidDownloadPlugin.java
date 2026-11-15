package top.imsyy.splayer.android.download;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import androidx.activity.result.ActivityResult;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(
    name = "AndroidDownload",
    permissions = {
      @Permission(
          alias = "music",
          strings = {Manifest.permission.READ_MEDIA_AUDIO}),
      @Permission(
          alias = "legacyMusic",
          strings = {Manifest.permission.READ_EXTERNAL_STORAGE})
    })
public class AndroidDownloadPlugin extends Plugin {
  private static final int READ_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION
          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;
  private static final int WRITE_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION
          | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

  private final ExecutorService executor = Executors.newFixedThreadPool(2);
  private final ConcurrentHashMap<Long, PluginCall> activeDownloads = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> coverWriteLocks = new ConcurrentHashMap<>();

  @Override
  protected void handleOnDestroy() {
    executor.shutdownNow();
  }

  @PluginMethod
  public void pickDownloadDirectory(PluginCall call) {
    if (getActivity() == null) {
      call.reject("Activity unavailable");
      return;
    }

    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(WRITE_FLAGS);
    startActivityForResult(call, intent, "onPickDownloadDirectoryResult");
  }

  @ActivityCallback
  private void onPickDownloadDirectoryResult(@Nullable PluginCall call, ActivityResult result) {
    if (call == null) return;

    Intent data = result.getData();
    Uri uri = data == null ? null : data.getData();
    if (uri == null) {
      JSObject cancelled = new JSObject();
      cancelled.put("cancelled", true);
      call.resolve(cancelled);
      return;
    }

    try {
      int flags =
          data.getFlags()
              & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
      if (flags == 0)
        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
      getContext().getContentResolver().takePersistableUriPermission(uri, flags);
    } catch (SecurityException error) {
      call.reject("DOWNLOAD_DIRECTORY_PERMISSION_FAILED", error);
      return;
    }

    DocumentFile directory = DocumentFile.fromTreeUri(getContext(), uri);
    JSObject response = new JSObject();
    response.put("cancelled", false);
    response.put("uri", uri.toString());
    response.put(
        "name",
        directory != null && directory.getName() != null ? directory.getName() : "Download");
    call.resolve(response);
  }

  @PluginMethod
  public void downloadFile(PluginCall call) {
    String fileUrl = call.getString("url", "");
    String fileName = call.getString("fileName", "");
    String directoryUri = call.getString("directoryUri", "");
    String subPath = call.getString("subPath", "");

    if (fileUrl.isEmpty() || fileName.isEmpty() || directoryUri.isEmpty()) {
      call.reject("url, fileName, and directoryUri are required");
      return;
    }

    executor.execute(
        () -> {
          try {
            Uri dirUri = Uri.parse(directoryUri);
            DocumentFile directory = DocumentFile.fromTreeUri(getContext(), dirUri);
            if (directory == null || !directory.exists() || !directory.canWrite()) {
              call.reject("DOWNLOAD_DIRECTORY_NOT_WRITABLE");
              return;
            }

            // 处理子目录
            DocumentFile targetDir = directory;
            if (!subPath.isEmpty()) {
              String[] parts = subPath.split("/");
              for (String part : parts) {
                if (part.isEmpty()) continue;
                DocumentFile existing = findChild(targetDir, part);
                if (existing != null && existing.isDirectory()) {
                  targetDir = existing;
                } else {
                  targetDir = targetDir.createDirectory(part);
                  if (targetDir == null) {
                    call.reject("FAILED_TO_CREATE_SUBDIRECTORY: " + part);
                    return;
                  }
                }
              }
            }

            // 检查文件是否已存在
            String extension = getFileExtension(fileName);
            String baseName = getBaseName(fileName);
            DocumentFile existingFile = findChild(targetDir, fileName);
            if (existingFile != null && existingFile.exists()) {
              JSObject skipResult = new JSObject();
              skipResult.put("status", "skipped");
              skipResult.put("path", existingFile.getUri().toString());
              call.resolve(skipResult);
              return;
            }

            // 创建目标文件
            String mimeType = guessMimeType(extension);
            DocumentFile targetFile = targetDir.createFile(mimeType, fileName);
            if (targetFile == null) {
              call.reject("FAILED_TO_CREATE_FILE");
              return;
            }

            // 下载文件
            HttpURLConnection connection = null;
            InputStream input = null;
            OutputStream output = null;
            try {
              URL url = new URL(fileUrl);
              connection = (HttpURLConnection) url.openConnection();
              connection.setConnectTimeout(30000);
              connection.setReadTimeout(60000);
              connection.setRequestProperty("User-Agent", "SPlayer-for-Android");
              connection.connect();

              int responseCode = connection.getResponseCode();
              if (responseCode != HttpURLConnection.HTTP_OK
                  && responseCode != HttpURLConnection.HTTP_PARTIAL) {
                targetFile.delete();
                call.reject("HTTP_ERROR_" + responseCode);
                return;
              }

              int contentLength = connection.getContentLength();
              input = connection.getInputStream();
              output = getContext().getContentResolver().openOutputStream(targetFile.getUri());

              if (output == null) {
                targetFile.delete();
                call.reject("FAILED_TO_OPEN_OUTPUT_STREAM");
                return;
              }

              byte[] buffer = new byte[8192];
              int bytesRead;
              long totalRead = 0;
              int lastReportedPercent = -1;

              while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                if (contentLength > 0) {
                  int percent = (int) ((totalRead * 100) / contentLength);
                  if (percent != lastReportedPercent && percent % 5 == 0) {
                    lastReportedPercent = percent;
                    JSObject progress = new JSObject();
                    progress.put("bytesRead", totalRead);
                    progress.put("contentLength", contentLength);
                    progress.put("percent", percent / 100.0);
                    notifyListeners("downloadProgress", progress);
                  }
                }
              }

              output.flush();

              JSObject result = new JSObject();
              result.put("status", "success");
              result.put("path", targetFile.getUri().toString());
              result.put("fileName", fileName);
              call.resolve(result);

            } finally {
              if (input != null)
                try {
                  input.close();
                } catch (IOException ignored) {
                }
              if (output != null)
                try {
                  output.close();
                } catch (IOException ignored) {
                }
              if (connection != null) connection.disconnect();
            }
          } catch (Exception error) {
            call.reject("DOWNLOAD_FAILED", error);
          }
        });
  }

  @PluginMethod
  public void writeTextFile(PluginCall call) {
    String fileName = call.getString("fileName", "");
    String content = call.getString("content", "");
    String directoryUri = call.getString("directoryUri", "");
    String subPath = call.getString("subPath", "");

    if (fileName.isEmpty() || directoryUri.isEmpty()) {
      call.reject("fileName and directoryUri are required");
      return;
    }

    executor.execute(
        () -> {
          try {
            Uri dirUri = Uri.parse(directoryUri);
            DocumentFile directory = DocumentFile.fromTreeUri(getContext(), dirUri);
            if (directory == null || !directory.exists() || !directory.canWrite()) {
              call.reject("DOWNLOAD_DIRECTORY_NOT_WRITABLE");
              return;
            }

            DocumentFile targetDir = directory;
            if (!subPath.isEmpty()) {
              String[] parts = subPath.split("/");
              for (String part : parts) {
                if (part.isEmpty()) continue;
                DocumentFile existing = findChild(targetDir, part);
                if (existing != null && existing.isDirectory()) {
                  targetDir = existing;
                } else {
                  targetDir = targetDir.createDirectory(part);
                  if (targetDir == null) {
                    call.reject("FAILED_TO_CREATE_SUBDIRECTORY");
                    return;
                  }
                }
              }
            }

            String extension = getFileExtension(fileName);
            String mimeType = guessMimeType(extension);

            // 删除已存在的文件
            DocumentFile existingFile = findChild(targetDir, fileName);
            if (existingFile != null) existingFile.delete();

            DocumentFile targetFile = targetDir.createFile(mimeType, fileName);
            if (targetFile == null) {
              call.reject("FAILED_TO_CREATE_FILE");
              return;
            }

            OutputStream output = null;
            try {
              output = getContext().getContentResolver().openOutputStream(targetFile.getUri());
              if (output == null) {
                call.reject("FAILED_TO_OPEN_OUTPUT_STREAM");
                return;
              }
              output.write(content.getBytes(StandardCharsets.UTF_8));
              output.flush();

              JSObject result = new JSObject();
              result.put("status", "success");
              result.put("path", targetFile.getUri().toString());
              call.resolve(result);
            } finally {
              if (output != null)
                try {
                  output.close();
                } catch (IOException ignored) {
                }
            }
          } catch (Exception error) {
            call.reject("WRITE_FILE_FAILED", error);
          }
        });
  }

  @PluginMethod
  public void getDownloadDirectoryInfo(PluginCall call) {
    String directoryUri = call.getString("directoryUri", "");
    if (directoryUri.isEmpty()) {
      JSObject result = new JSObject();
      result.put("exists", false);
      call.resolve(result);
      return;
    }

    try {
      Uri dirUri = Uri.parse(directoryUri);
      DocumentFile directory = DocumentFile.fromTreeUri(getContext(), dirUri);
      JSObject result = new JSObject();
      result.put("exists", directory != null && directory.exists());
      result.put("canWrite", directory != null && directory.canWrite());
      result.put(
          "name", directory != null && directory.getName() != null ? directory.getName() : "");
      call.resolve(result);
    } catch (Exception error) {
      JSObject result = new JSObject();
      result.put("exists", false);
      call.resolve(result);
    }
  }

  /** 列出下载目录下的全部音乐文件（递归扫描），并提取基础元数据。 用于"下载完成"页面渲染下载历史。 */
  @PluginMethod
  public void listDownloadedSongs(PluginCall call) {
    String directoryUri = call.getString("directoryUri", "");
    if (directoryUri.isEmpty()) {
      JSObject empty = new JSObject();
      empty.put("songs", new JSArray());
      call.resolve(empty);
      return;
    }

    executor.execute(
        () -> {
          try {
            Uri dirUri = Uri.parse(directoryUri);
            DocumentFile directory = DocumentFile.fromTreeUri(getContext(), dirUri);
            if (directory == null || !directory.exists() || !directory.canRead()) {
              JSObject empty = new JSObject();
              empty.put("songs", new JSArray());
              call.resolve(empty);
              return;
            }

            JSArray songs = new JSArray();
            scanAudioFiles(directory, songs);

            JSObject response = new JSObject();
            response.put("songs", songs);
            call.resolve(response);
          } catch (SecurityException error) {
            call.reject("DOWNLOAD_DIRECTORY_PERMISSION_EXPIRED", error);
          } catch (Exception error) {
            call.reject("LIST_DOWNLOADED_SONGS_FAILED", error);
          }
        });
  }

  /** 递归扫描目录中的音乐文件，并提取元数据 */
  private void scanAudioFiles(DocumentFile directory, JSArray output) {
    DocumentFile[] children;
    try {
      children = directory.listFiles();
    } catch (SecurityException ignored) {
      return;
    }

    for (DocumentFile child : children) {
      if (child == null) continue;
      if (child.isDirectory()) {
        scanAudioFiles(child, output);
        continue;
      }
      if (!child.isFile()) continue;

      String name = child.getName();
      if (name == null || !isAudioFile(name)) continue;

      JSObject song = buildSongMetadata(child, name);
      if (song != null) output.put(song);
    }
  }

  /** 从 DocumentFile 提取歌曲元数据（艺术家、专辑、时长等） */
  @Nullable
  private JSObject buildSongMetadata(DocumentFile file, String fileName) {
    return buildSongMetadata(file.getUri(), fileName, file.length(), file.lastModified());
  }

  @Nullable
  private JSObject buildSongMetadata(Uri audioUri, String fileName, long size, long lastModified) {
    String uri = audioUri.toString();

    // 默认从文件名解析（"Artist - Title.ext" 或 "Title.ext"）
    String baseName = fileName;
    int dotIdx = baseName.lastIndexOf('.');
    String extension = dotIdx > 0 ? baseName.substring(dotIdx + 1).toLowerCase() : "";
    if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);

    String fallbackTitle = baseName;
    String fallbackArtist = "未知歌手";
    int sepIdx = baseName.indexOf(" - ");
    if (sepIdx > 0) {
      fallbackArtist = baseName.substring(0, sepIdx).trim();
      fallbackTitle = baseName.substring(sepIdx + 3).trim();
      if (fallbackTitle.isEmpty()) fallbackTitle = baseName;
    }

    String title = fallbackTitle;
    String artist = fallbackArtist;
    String album = "未知专辑";
    long duration = 0L;
    long bitrate = 0L;
    String cover = "";

    // 尝试用 MediaMetadataRetriever 读取标签信息
    MediaMetadataRetriever retriever = null;
    try {
      retriever = new MediaMetadataRetriever();
      retriever.setDataSource(getContext(), audioUri);

      String mTitle = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
      String mArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
      String mAlbum = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM);
      String mDuration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
      String mBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);

      if (mTitle != null && !mTitle.trim().isEmpty()) title = mTitle.trim();
      if (mArtist != null && !mArtist.trim().isEmpty()) artist = mArtist.trim();
      if (mAlbum != null && !mAlbum.trim().isEmpty()) album = mAlbum.trim();
      if (mDuration != null) {
        try {
          duration = Long.parseLong(mDuration);
        } catch (NumberFormatException ignored) {
        }
      }
      if (mBitrate != null) {
        try {
          bitrate = Long.parseLong(mBitrate);
        } catch (NumberFormatException ignored) {
        }
      }

      // 提取嵌入封面，压缩后写入 cacheDir/local-covers/<idHash>.jpg 并返回 file:// URI。
      // 用 file:// 而非 base64 data URL：避免上千首歌每首 ~30KB 字符串经 Capacitor 桥响应造成 IPC 肨胀 / OOM。
      byte[] embeddedPicture = retriever.getEmbeddedPicture();
      if (embeddedPicture != null) {
        cover = writeEmbeddedCoverToCache(uri, embeddedPicture);
      }
    } catch (Exception ignored) {
      // 文件不可解析，使用文件名兜底
    } finally {
      if (retriever != null) {
        try {
          retriever.release();
        } catch (Exception ignored) {
        }
      }
    }

    // ID 用 URI 哈希生成（确保稳定 + 不与在线歌曲 ID 冲突）
    long id = Math.abs((long) uri.hashCode()) + 1_000_000_000L;

    JSObject song = new JSObject();
    song.put("id", id);
    song.put("name", title);
    song.put("artists", artist);
    song.put("album", album);
    song.put("duration", duration);
    song.put("size", size);
    song.put("quality", bitrate);
    song.put("path", uri);
    song.put("fileName", fileName);
    song.put("ext", extension);
    song.put("lastModified", lastModified);
    song.put("cover", cover);
    return song;
  }

  /**
   * 嵌入封面落盘：按源 URI hash 成名，写入 {@code cacheDir/local-covers/}。返回 file:// URI。 已存在则复用。压缩到 1024px 内
   * JPEG quality 80。完全失败返回 ""。
   */
  private String writeEmbeddedCoverToCache(String sourceUri, byte[] pictureBytes) {
    Context ctx = getContext();
    if (ctx == null) return "";
    Bitmap original = null;
    Bitmap scaled = null;
    try {
      File coverDir = new File(ctx.getCacheDir(), "local-covers");
      if (!coverDir.exists() && !coverDir.mkdirs()) {
        return "";
      }
      String idHash = sha256Hex(sourceUri);
      if (idHash.isEmpty()) return "";
      File coverFile = new File(coverDir, idHash + ".jpg");
      Object writeLock = coverWriteLocks.computeIfAbsent(idHash, key -> new Object());
      try {
        synchronized (writeLock) {
          if (coverFile.isFile() && coverFile.length() > 0) {
            return "file://" + coverFile.getAbsolutePath();
          }
          BitmapFactory.Options boundsOptions = new BitmapFactory.Options();
          boundsOptions.inJustDecodeBounds = true;
          BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.length, boundsOptions);
          int maxSize = 1024;
          BitmapFactory.Options decodeOptions = new BitmapFactory.Options();
          decodeOptions.inSampleSize = calculateInSampleSize(boundsOptions, maxSize);
          original =
              BitmapFactory.decodeByteArray(pictureBytes, 0, pictureBytes.length, decodeOptions);
          if (original == null) return "";
          int w = original.getWidth();
          int h = original.getHeight();
          float scale = Math.min((float) maxSize / w, (float) maxSize / h);
          scaled = original;
          if (scale < 1.0f) {
            scaled =
                Bitmap.createScaledBitmap(
                    original, Math.round(w * scale), Math.round(h * scale), true);
          }
          try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos);
            byte[] jpegBytes = baos.toByteArray();
            // 原子写：tmp → rename，避免进程被杀产生半截断文件
            File tmp = new File(coverDir, idHash + ".jpg.tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
              fos.write(jpegBytes);
              fos.getFD().sync();
            } catch (IOException e) {
              // noinspection ResultOfMethodCallIgnored
              tmp.delete();
              return "";
            }
            if (coverFile.exists()) {
              // noinspection ResultOfMethodCallIgnored
              coverFile.delete();
            }
            if (!tmp.renameTo(coverFile)) {
              // noinspection ResultOfMethodCallIgnored
              tmp.delete();
              return "";
            }
          }
        }
      } finally {
        coverWriteLocks.remove(idHash, writeLock);
      }
      return "file://" + coverFile.getAbsolutePath();
    } catch (Throwable ignored) {
      return "";
    } finally {
      if (scaled != null && scaled != original) {
        scaled.recycle();
      }
      if (original != null) {
        original.recycle();
      }
    }
  }

  private String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        builder.append(String.format("%02x", b));
      }
      return builder.toString();
    } catch (Exception ignored) {
      return "";
    }
  }

  private int calculateInSampleSize(BitmapFactory.Options options, int maxSize) {
    int height = options.outHeight;
    int width = options.outWidth;
    if (height <= 0 || width <= 0) return maxSize;
    int inSampleSize = 1;
    while (height / inSampleSize > maxSize || width / inSampleSize > maxSize) {
      inSampleSize *= 2;
    }
    return inSampleSize;
  }

  private boolean isAudioFile(String name) {
    String lower = name.toLowerCase();
    return lower.endsWith(".mp3")
        || lower.endsWith(".flac")
        || lower.endsWith(".wav")
        || lower.endsWith(".ogg")
        || lower.endsWith(".m4a")
        || lower.endsWith(".aac")
        || lower.endsWith(".ape")
        || lower.endsWith(".wma")
        || lower.endsWith(".opus");
  }

  /** SAF 选取本地音乐目录（与下载目录使用相同 ACTION_OPEN_DOCUMENT_TREE） */
  @PluginMethod
  public void pickLocalMusicDirectory(PluginCall call) {
    if (getActivity() == null) {
      call.reject("Activity unavailable");
      return;
    }

    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(READ_FLAGS);
    startActivityForResult(call, intent, "onPickLocalMusicDirectoryResult");
  }

  @ActivityCallback
  private void onPickLocalMusicDirectoryResult(@Nullable PluginCall call, ActivityResult result) {
    if (call == null) return;

    Intent data = result.getData();
    Uri uri = data == null ? null : data.getData();
    if (uri == null) {
      JSObject cancelled = new JSObject();
      cancelled.put("cancelled", true);
      call.resolve(cancelled);
      return;
    }

    try {
      int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
      if (flags == 0) flags = Intent.FLAG_GRANT_READ_URI_PERMISSION;
      getContext().getContentResolver().takePersistableUriPermission(uri, flags);
    } catch (SecurityException error) {
      call.reject("LOCAL_MUSIC_DIRECTORY_PERMISSION_FAILED", error);
      return;
    }

    DocumentFile directory = DocumentFile.fromTreeUri(getContext(), uri);
    JSObject response = new JSObject();
    response.put("cancelled", false);
    response.put("uri", uri.toString());
    response.put(
        "name",
        directory != null && directory.getName() != null ? directory.getName() : "Local Music");
    call.resolve(response);
  }

  private boolean hasMusicPermission() {
    return getPermissionState(musicPermissionAlias()) == PermissionState.GRANTED;
  }

  private String musicPermissionAlias() {
    return Build.VERSION.SDK_INT >= 33 ? "music" : "legacyMusic";
  }

  @PluginMethod
  public void checkMusicPermission(PluginCall call) {
    JSObject result = new JSObject();
    result.put("granted", hasMusicPermission());
    call.resolve(result);
  }

  @PluginMethod
  public void requestMusicPermission(PluginCall call) {
    if (hasMusicPermission()) {
      checkMusicPermission(call);
      return;
    }
    requestPermissionForAlias(musicPermissionAlias(), call, "musicPermissionResult");
  }

  @PermissionCallback
  private void musicPermissionResult(PluginCall call) {
    checkMusicPermission(call);
  }

  /** 合并授权目录与系统音频库，后台扫描不弹出权限请求。 */
  @PluginMethod
  public void scanLocalMusic(PluginCall call) {
    JSArray directories = call.getArray("directories", new JSArray());
    JSArray excludedDirectories = call.getArray("excludedDirectories", new JSArray());
    boolean includeDeviceAudio = call.getBoolean("includeDeviceAudio", false);
    boolean excludeWhatsApp = call.getBoolean("excludeWhatsApp", true);
    executor.execute(
        () -> {
          try {
            boolean mediaPermissionDenied = includeDeviceAudio && !hasMusicPermission();
            LocalMusicScanner scanner =
                new LocalMusicScanner(
                    getContext(),
                    new LocalMusicScanner.MetadataReader() {
                      @Override
                      public JSObject read(Uri uri, String name, long size, long modified) {
                        return buildSongMetadata(uri, name, size, modified);
                      }

                      @Override
                      public boolean isAudio(String name) {
                        return isAudioFile(name);
                      }
                    },
                    excludeWhatsApp,
                    excludedDirectories);
            JSObject response =
                scanner.scan(directories, includeDeviceAudio && !mediaPermissionDenied);
            if (includeDeviceAudio && !mediaPermissionDenied && !hasMusicPermission()) {
              call.reject("LOCAL_MUSIC_PERMISSION_REVOKED");
              return;
            }
            response.put("mediaPermissionDenied", mediaPermissionDenied);
            call.resolve(response);
          } catch (Exception error) {
            call.reject("LOCAL_MUSIC_SCAN_FAILED", error);
          }
        });
  }

  @Nullable
  private DocumentFile findChild(DocumentFile parent, String name) {
    for (DocumentFile child : parent.listFiles()) {
      if (name.equals(child.getName())) return child;
    }
    return null;
  }

  private String getFileExtension(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(dot + 1).toLowerCase() : "";
  }

  private String getBaseName(String fileName) {
    int dot = fileName.lastIndexOf('.');
    return dot > 0 ? fileName.substring(0, dot) : fileName;
  }

  private String guessMimeType(String extension) {
    switch (extension) {
      case "mp3":
        return "audio/mpeg";
      case "flac":
        return "audio/flac";
      case "wav":
        return "audio/wav";
      case "ogg":
        return "audio/ogg";
      case "m4a":
        return "audio/mp4";
      case "aac":
        return "audio/aac";
      case "lrc":
        return "text/plain";
      case "ttml":
        return "application/xml";
      case "yrc":
        return "text/plain";
      case "ass":
        return "text/plain";
      case "json":
        return "application/json";
      default:
        return "application/octet-stream";
    }
  }
}

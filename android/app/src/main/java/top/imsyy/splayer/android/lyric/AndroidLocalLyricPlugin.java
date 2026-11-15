package top.imsyy.splayer.android.lyric;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import androidx.activity.result.ActivityResult;
import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import org.json.JSONException;
import org.json.JSONObject;

@CapacitorPlugin(name = "AndroidLocalLyric")
public class AndroidLocalLyricPlugin extends Plugin {
  private static final Pattern FILENAME_ID_PATTERN = Pattern.compile("(\\d+)");
  private static final String[] LYRIC_EXTENSIONS = {".ttml", ".yrc", ".lrc"};
  private static final String[] SIDECAR_EXTENSIONS = {".ttml", ".yrc", ".lrc"};
  private static final int READ_FLAGS =
      Intent.FLAG_GRANT_READ_URI_PERMISSION
          | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
          | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  @Override
  protected void handleOnDestroy() {
    executor.shutdownNow();
  }

  @PluginMethod
  public void pickLyricDirectory(PluginCall call) {
    if (getActivity() == null) {
      call.reject("Activity unavailable");
      return;
    }

    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(READ_FLAGS);
    startActivityForResult(call, intent, "onPickLyricDirectoryResult");
  }

  @ActivityCallback
  private void onPickLyricDirectoryResult(@Nullable PluginCall call, ActivityResult result) {
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
      call.reject("LYRIC_DIRECTORY_PERMISSION_FAILED", error);
      return;
    }

    DocumentFile directory = DocumentFile.fromTreeUri(getContext(), uri);
    JSObject response = new JSObject();
    response.put("cancelled", false);
    response.put("uri", uri.toString());
    response.put("name", safeName(directory, uri));
    call.resolve(response);
  }

  @PluginMethod
  public void scanLyricDirectories(PluginCall call) {
    JSArray directories = call.getArray("directories");
    if (directories == null) {
      call.reject("directories is required");
      return;
    }

    executor.execute(
        () -> {
          ScanAccumulator accumulator = new ScanAccumulator();
          for (int i = 0; i < directories.length(); i++) {
            try {
              JSONObject item = directories.getJSONObject(i);
              String uriText = item.optString("uri", "");
              if (uriText.isEmpty()) {
                accumulator.addFailure("", "", "EMPTY_DIRECTORY_URI", "");
                continue;
              }

              Uri uri = Uri.parse(uriText);
              DirectoryInfo directoryInfo = new DirectoryInfo(uriText, item.optString("name", ""));
              DocumentFile directory = DocumentFile.fromTreeUri(getContext(), uri);
              if (directory == null || !directory.exists() || !directory.canRead()) {
                accumulator.addFailure(
                    uriText, directoryInfo.name, "DIRECTORY_UNREADABLE", uriText);
                continue;
              }

              scanDirectory(directory, directoryInfo, accumulator);
            } catch (JSONException error) {
              accumulator.addFailure("", "", "INVALID_DIRECTORY_PAYLOAD", "");
            } catch (SecurityException error) {
              accumulator.addFailure("", "", "DIRECTORY_PERMISSION_EXPIRED", "");
            }
          }
          call.resolve(accumulator.toJSObject());
        });
  }

  @PluginMethod
  public void readLyricFile(PluginCall call) {
    String uriText = call.getString("uri", "");
    if (uriText.isEmpty()) {
      call.reject("uri is required");
      return;
    }

    executor.execute(
        () -> {
          try {
            JSObject response = new JSObject();
            response.put("content", readText(Uri.parse(uriText)));
            call.resolve(response);
          } catch (SecurityException error) {
            call.reject("LYRIC_FILE_PERMISSION_EXPIRED", error);
          } catch (Exception error) {
            call.reject("LYRIC_FILE_READ_FAILED", error);
          }
        });
  }

  @PluginMethod
  public void findSidecarLyric(PluginCall call) {
    String audioPath = call.getString("audioPath", "");
    if (audioPath.isEmpty()) {
      call.reject("audioPath is required");
      return;
    }

    executor.execute(
        () -> {
          try {
            Uri audioUri = Uri.parse(audioPath);
            if ("content".equalsIgnoreCase(audioUri.getScheme())) {
              JSObject contentResult = findContentSidecarLyric(audioUri);
              if (contentResult != null) {
                call.resolve(contentResult);
                return;
              }
            }

            JSObject fileResult = findFileSidecarLyric(audioPath);
            if (fileResult != null) {
              call.resolve(fileResult);
              return;
            }

            JSObject empty = new JSObject();
            empty.put("content", "");
            call.resolve(empty);
          } catch (Exception error) {
            JSObject empty = new JSObject();
            empty.put("content", "");
            call.resolve(empty);
          }
        });
  }

  @Nullable
  private JSObject findContentSidecarLyric(Uri audioUri) {
    MediaStoreAudioInfo mediaInfo = queryMediaStoreAudioInfo(audioUri);
    String baseName =
        mediaInfo != null && !mediaInfo.displayName.isEmpty()
            ? stripExtension(mediaInfo.displayName)
            : getAudioBaseName(audioUri);
    if (baseName.isEmpty()) return null;

    JSObject direct = findSidecarFromTree(audioUri, audioUri, baseName);
    if (direct != null) return direct;

    try {
      for (UriPermission permission :
          getContext().getContentResolver().getPersistedUriPermissions()) {
        if (!permission.isReadPermission()) continue;
        Uri treeUri = permission.getUri();
        JSObject result = findSidecarFromTree(audioUri, treeUri, baseName);
        if (result == null && mediaInfo != null) {
          result = findSidecarFromMediaStoreInfo(treeUri, mediaInfo, baseName);
        }
        if (result != null) return result;
      }
    } catch (Exception ignored) {
      return null;
    }

    return null;
  }

  @Nullable
  private MediaStoreAudioInfo queryMediaStoreAudioInfo(Uri audioUri) {
    boolean isMediaStore = "media".equals(audioUri.getAuthority());
    String[] projection =
        isMediaStore
            ? new String[] {
              MediaStore.MediaColumns.DISPLAY_NAME,
              MediaStore.MediaColumns.RELATIVE_PATH,
              MediaStore.MediaColumns.VOLUME_NAME
            }
            : new String[] {
              MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.RELATIVE_PATH
            };
    try (Cursor cursor =
        getContext().getContentResolver().query(audioUri, projection, null, null, null)) {
      if (cursor == null || !cursor.moveToFirst()) return null;
      String displayName = getCursorString(cursor, MediaStore.MediaColumns.DISPLAY_NAME);
      String relativePath = getCursorString(cursor, MediaStore.MediaColumns.RELATIVE_PATH);
      if (displayName.isEmpty() && relativePath.isEmpty()) return null;
      String volumeName =
          isMediaStore ? getCursorString(cursor, MediaStore.MediaColumns.VOLUME_NAME) : "";
      return new MediaStoreAudioInfo(displayName, relativePath, volumeName);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String getCursorString(Cursor cursor, String columnName) {
    int index = cursor.getColumnIndex(columnName);
    if (index < 0) return "";
    String value = cursor.getString(index);
    return value == null ? "" : value.trim();
  }

  @Nullable
  private JSObject findSidecarFromTree(Uri audioUri, Uri treeUri, String baseName) {
    try {
      if (audioUri.getAuthority() == null
          || !audioUri.getAuthority().equals(treeUri.getAuthority())) {
        return null;
      }

      String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
      String audioDocumentId = DocumentsContract.getDocumentId(audioUri);
      String relativePath = getRelativeDocumentPath(treeDocumentId, audioDocumentId);
      if (relativePath == null || relativePath.isEmpty()) return null;

      String parentRelativePath = getParentRelativePath(relativePath);
      DocumentFile parent = resolveTreeDocument(treeUri, parentRelativePath);
      if (parent != null && parent.isDirectory() && parent.canRead()) {
        JSObject result = findSidecarInDocumentDirectory(parent, baseName);
        if (result != null) return result;
      }

      return findSidecarByDocumentId(treeUri, treeDocumentId, parentRelativePath, baseName);
    } catch (Exception ignored) {
      return null;
    }
  }

  @Nullable
  private JSObject findSidecarFromMediaStoreInfo(
      Uri treeUri, MediaStoreAudioInfo mediaInfo, String baseName) {
    try {
      if (!"com.android.externalstorage.documents".equals(treeUri.getAuthority())) return null;
      String treeDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
      String parentRelativePath =
          AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
              treeDocumentId, mediaInfo.relativePath, mediaInfo.volumeName);
      if (parentRelativePath == null) return null;

      DocumentFile parent = resolveTreeDocument(treeUri, parentRelativePath);
      if (parent != null && parent.isDirectory() && parent.canRead()) {
        JSObject result = findSidecarInDocumentDirectory(parent, baseName);
        if (result != null) return result;
      }

      return findSidecarByDocumentId(treeUri, treeDocumentId, parentRelativePath, baseName);
    } catch (Exception ignored) {
      return null;
    }
  }

  @Nullable
  private JSObject findSidecarInDocumentDirectory(DocumentFile parent, String baseName) {
    for (String ext : SIDECAR_EXTENSIONS) {
      DocumentFile lyricFile = findChild(parent, baseName + ext);
      if (lyricFile == null || !lyricFile.isFile() || !lyricFile.canRead()) continue;
      try {
        return buildSidecarResponse(readText(lyricFile.getUri()), ext);
      } catch (Exception ignored) {
        // 继续尝试低优先级格式
      }
    }
    return null;
  }

  @Nullable
  private JSObject findSidecarByDocumentId(
      Uri treeUri, String treeDocumentId, String parentRelativePath, String baseName) {
    for (String ext : SIDECAR_EXTENSIONS) {
      String siblingRelativePath =
          parentRelativePath.isEmpty() ? baseName + ext : parentRelativePath + "/" + baseName + ext;
      String siblingDocumentId = buildDocumentId(treeDocumentId, siblingRelativePath);
      try {
        Uri siblingUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, siblingDocumentId);
        DocumentFile lyricFile = DocumentFile.fromSingleUri(getContext(), siblingUri);
        if (lyricFile == null || !lyricFile.exists() || !lyricFile.canRead()) continue;
        return buildSidecarResponse(readText(siblingUri), ext);
      } catch (Exception ignored) {
        // 继续尝试其他格式
      }
    }
    return null;
  }

  @Nullable
  private JSObject findFileSidecarLyric(String audioPath) {
    File audioFile = resolveAudioFile(audioPath);
    if (audioFile == null) return null;

    File parentDir = audioFile.getParentFile();
    if (parentDir == null || !parentDir.exists() || !parentDir.canRead()) return null;

    String baseName = stripExtension(audioFile.getName());
    for (String ext : SIDECAR_EXTENSIONS) {
      File lyricFile = new File(parentDir, baseName + ext);
      if (!lyricFile.exists() || !lyricFile.canRead()) continue;
      try {
        return buildSidecarResponse(readFileText(lyricFile), ext);
      } catch (Exception ignored) {
        // 继续尝试低优先级格式
      }
    }
    return null;
  }

  @Nullable
  private File resolveAudioFile(String audioPath) {
    try {
      Uri uri = Uri.parse(audioPath);
      String scheme = uri.getScheme();
      if ("file".equalsIgnoreCase(scheme)) {
        String path = uri.getPath();
        return path == null || path.isEmpty() ? null : new File(path);
      }
      if (scheme != null && !scheme.isEmpty()) return null;
    } catch (Exception ignored) {
      // 使用原始路径兜底
    }
    return new File(audioPath);
  }

  private JSObject buildSidecarResponse(String content, String ext) {
    JSObject response = new JSObject();
    response.put("content", content);
    response.put("format", ext.substring(1));
    return response;
  }

  private String getAudioBaseName(Uri audioUri) {
    try {
      DocumentFile audioFile = DocumentFile.fromSingleUri(getContext(), audioUri);
      String name = audioFile == null ? null : audioFile.getName();
      if (name != null && !name.isEmpty()) return stripExtension(name);
    } catch (Exception ignored) {
      // 继续使用 URI 路径兜底
    }

    try {
      String documentId = DocumentsContract.getDocumentId(audioUri);
      int slashIdx = documentId.lastIndexOf('/');
      String name = slashIdx >= 0 ? documentId.substring(slashIdx + 1) : documentId;
      if (!name.isEmpty()) return stripExtension(name);
    } catch (Exception ignored) {
      // 继续使用 URI 路径兜底
    }

    String path = audioUri.getPath();
    if (path == null || path.isEmpty()) return "";
    int slashIdx = path.lastIndexOf('/');
    String name = slashIdx >= 0 ? path.substring(slashIdx + 1) : path;
    return stripExtension(name);
  }

  @Nullable
  private DocumentFile resolveTreeDocument(Uri treeUri, String relativePath) {
    DocumentFile current = DocumentFile.fromTreeUri(getContext(), treeUri);
    if (current == null) return null;
    if (relativePath.isEmpty()) return current;

    String[] parts = relativePath.split("/");
    for (String part : parts) {
      if (part.isEmpty()) continue;
      current = findChild(current, part);
      if (current == null || !current.isDirectory()) return null;
    }
    return current;
  }

  @Nullable
  private DocumentFile findChild(DocumentFile parent, String name) {
    try {
      for (DocumentFile child : parent.listFiles()) {
        if (name.equals(child.getName())) return child;
      }
    } catch (Exception ignored) {
      return null;
    }
    return null;
  }

  @Nullable
  private String getRelativeDocumentPath(String treeDocumentId, String documentId) {
    if (documentId.equals(treeDocumentId)) return "";
    String prefix = treeDocumentId + "/";
    if (documentId.startsWith(prefix)) return documentId.substring(prefix.length());
    if (treeDocumentId.endsWith(":") && documentId.startsWith(treeDocumentId)) {
      String relative = documentId.substring(treeDocumentId.length());
      return relative.startsWith("/") ? relative.substring(1) : relative;
    }
    return null;
  }

  private String getParentRelativePath(String relativePath) {
    int slashIdx = relativePath.lastIndexOf('/');
    return slashIdx > 0 ? relativePath.substring(0, slashIdx) : "";
  }

  private String buildDocumentId(String treeDocumentId, String relativePath) {
    String cleaned = relativePath.startsWith("/") ? relativePath.substring(1) : relativePath;
    if (cleaned.isEmpty()) return treeDocumentId;
    return treeDocumentId.endsWith(":") ? treeDocumentId + cleaned : treeDocumentId + "/" + cleaned;
  }

  private String stripExtension(String name) {
    int dotIdx = name.lastIndexOf('.');
    return dotIdx > 0 ? name.substring(0, dotIdx) : name;
  }

  private void scanDirectory(
      DocumentFile directory, DirectoryInfo directoryInfo, ScanAccumulator accumulator) {
    DocumentFile[] children;
    try {
      children = directory.listFiles();
    } catch (SecurityException error) {
      accumulator.addFailure(
          directory.getUri().toString(),
          directory.getName(),
          "DIRECTORY_PERMISSION_EXPIRED",
          directoryInfo.uri);
      return;
    }

    for (DocumentFile child : children) {
      if (child.isDirectory()) {
        scanDirectory(child, directoryInfo, accumulator);
        continue;
      }

      if (!child.isFile()) continue;
      String name = child.getName();
      if (name == null || !isLyricFile(name)) continue;

      accumulator.totalFiles++;
      String fileUri = child.getUri().toString();
      String format = getFormatFromName(name);
      long lastModified = Math.max(child.lastModified(), 0L);

      try {
        AndroidLyricMetadataParser.LyricMetadata metadata =
            new AndroidLyricMetadataParser.LyricMetadata();
        if ("ttml".equals(format)) {
          String content = readText(child.getUri());
          metadata = AndroidLyricMetadataParser.extractTtmlMetadata(content);
        } else if ("lrc".equals(format)) {
          String content = readText(child.getUri());
          metadata = AndroidLyricMetadataParser.extractLrcMetadata(content);
        }

        AndroidLyricIndexEntry entry =
            new AndroidLyricIndexEntry(
                fileUri, name, lastModified, directoryInfo.uri, format, metadata);
        accumulator.addEntry(entry);

        Set<String> ids = new LinkedHashSet<>();
        if ("ttml".equals(format)
            && metadata.ncmMusicId != null
            && !metadata.ncmMusicId.isEmpty()) {
          ids.add(metadata.ncmMusicId);
        }
        String filenameId = extractFilenameId(name);
        if (filenameId != null && !filenameId.isEmpty()) ids.add(filenameId);

        if (!ids.isEmpty()) {
          accumulator.matchedFiles++;
          for (String id : ids) {
            accumulator.putIndex(id, entry);
          }
        }
      } catch (SecurityException error) {
        accumulator.failedFiles++;
        accumulator.addFailure(fileUri, name, "FILE_PERMISSION_EXPIRED", directoryInfo.uri);
      } catch (Exception error) {
        accumulator.failedFiles++;
        accumulator.addFailure(fileUri, name, "FILE_READ_FAILED", directoryInfo.uri);
      }
    }
  }

  private boolean isLyricFile(String name) {
    String lower = name.toLowerCase();
    for (String ext : LYRIC_EXTENSIONS) {
      if (lower.endsWith(ext)) return true;
    }
    return false;
  }

  private String getFormatFromName(String name) {
    String lower = name.toLowerCase();
    if (lower.endsWith(".ttml")) return "ttml";
    if (lower.endsWith(".yrc")) return "yrc";
    if (lower.endsWith(".lrc")) return "lrc";
    return "lrc";
  }

  @Nullable
  private String extractFilenameId(String fileName) {
    String baseName = fileName;
    int dotIdx = baseName.lastIndexOf('.');
    if (dotIdx > 0) baseName = baseName.substring(0, dotIdx);

    // 匹配 "123456" 或 "SongName.123456" 格式
    String[] parts = baseName.split("\\.");
    String lastPart = parts[parts.length - 1];
    if (FILENAME_ID_PATTERN.matcher(lastPart).matches() && lastPart.length() >= 2) {
      return lastPart;
    }

    // 如果整个文件名就是数字
    if (FILENAME_ID_PATTERN.matcher(baseName).matches() && baseName.length() >= 2) {
      return baseName;
    }

    return null;
  }

  private String readText(Uri uri) throws IOException {
    ContentResolver resolver = getContext().getContentResolver();
    try (InputStream input = resolver.openInputStream(uri)) {
      if (input == null) throw new IOException("Input stream unavailable");
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) != -1) {
          builder.append(buffer, 0, read);
        }
        return builder.toString();
      }
    }
  }

  private String readFileText(File file) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
      StringBuilder builder = new StringBuilder();
      char[] buffer = new char[8192];
      int read;
      while ((read = reader.read(buffer)) != -1) {
        builder.append(buffer, 0, read);
      }
      return builder.toString();
    }
  }

  private String safeName(@Nullable DocumentFile file, Uri uri) {
    String name = file == null ? null : file.getName();
    if (name != null && !name.isEmpty()) return name;
    String lastPath = uri.getLastPathSegment();
    return lastPath == null || lastPath.isEmpty() ? uri.toString() : lastPath;
  }

  private static class DirectoryInfo {
    final String uri;
    final String name;

    DirectoryInfo(String uri, String name) {
      this.uri = uri;
      this.name = name;
    }
  }

  private static class MediaStoreAudioInfo {
    final String displayName;
    final String relativePath;

    final String volumeName;

    MediaStoreAudioInfo(String displayName, String relativePath, String volumeName) {
      this.displayName = displayName;
      this.relativePath = relativePath;
      this.volumeName = volumeName;
    }
  }

  private static class AndroidLyricIndexEntry {
    final String uri;
    final String name;
    final long lastModified;
    final String directoryUri;
    final String format;
    final AndroidLyricMetadataParser.LyricMetadata metadata;

    AndroidLyricIndexEntry(
        String uri,
        String name,
        long lastModified,
        String directoryUri,
        String format,
        AndroidLyricMetadataParser.LyricMetadata metadata) {
      this.uri = uri;
      this.name = name;
      this.lastModified = lastModified;
      this.directoryUri = directoryUri;
      this.format = format;
      this.metadata = metadata;
    }

    JSObject toJSObject() {
      JSObject object = new JSObject();
      object.put("uri", uri);
      object.put("name", name);
      object.put("lastModified", lastModified);
      object.put("directoryUri", directoryUri);
      object.put("format", format);
      object.put("metadata", metadata.toJSObject());
      return object;
    }
  }

  private static class ScanAccumulator {
    int totalFiles = 0;
    int matchedFiles = 0;
    int duplicateIds = 0;
    int failedFiles = 0;
    final JSObject indexMap = new JSObject();
    final JSArray entries = new JSArray();
    final JSArray failures = new JSArray();

    void addEntry(AndroidLyricIndexEntry entry) {
      entries.put(entry.toJSObject());
    }

    void putIndex(String id, AndroidLyricIndexEntry entry) {
      JSONObject existing = indexMap.optJSONObject(id);
      if (existing != null) {
        duplicateIds++;
        long oldLastModified = existing.optLong("lastModified", 0L);
        String oldFormat = existing.optString("format", "lrc");
        if (!shouldReplace(oldLastModified, entry.lastModified, oldFormat, entry.format)) return;
      }
      indexMap.put(id, entry.toJSObject());
    }

    void addFailure(String uri, String name, String reason, String directoryUri) {
      JSObject failure = new JSObject();
      failure.put("uri", uri);
      failure.put("name", name);
      failure.put("reason", reason);
      failure.put("directoryUri", directoryUri);
      failures.put(failure);
    }

    JSObject toJSObject() {
      JSObject response = new JSObject();
      response.put("indexMap", indexMap);
      response.put("entries", entries);
      response.put("totalFiles", totalFiles);
      response.put("matchedFiles", matchedFiles);
      response.put("duplicateIds", duplicateIds);
      response.put("failedFiles", failedFiles);
      response.put("failures", failures);
      return response;
    }

    private boolean shouldReplace(
        long oldLastModified, long newLastModified, String oldFormat, String newFormat) {
      int oldPriority = formatPriority(oldFormat);
      int newPriority = formatPriority(newFormat);
      if (newPriority != oldPriority) return newPriority > oldPriority;
      // 同格式按时间戳比较
      if (oldLastModified > 0 && newLastModified > 0) {
        return newLastModified > oldLastModified;
      }
      if (oldLastModified <= 0 && newLastModified > 0) return true;
      if (oldLastModified > 0) return false;
      return true;
    }

    private int formatPriority(String format) {
      if ("ttml".equals(format)) return 3;
      if ("yrc".equals(format)) return 2;
      return 1;
    }
  }
}

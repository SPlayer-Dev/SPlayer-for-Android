package top.imsyy.splayer.android.download;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import androidx.documentfile.provider.DocumentFile;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.json.JSONObject;

/** 合并授权目录与系统音频库，优先保留授权目录的歌曲标识。 */
final class LocalMusicScanner {
  interface MetadataReader {
    JSObject read(Uri uri, String name, long size, long modified);

    boolean isAudio(String name);
  }

  private final Context context;
  private final MetadataReader reader;
  private final LocalMusicFilter filter;
  private final Set<String> seen = new HashSet<>();
  private final Set<String> filtered = new HashSet<>();
  private final JSArray songs = new JSArray();
  private int failedDirectories;

  LocalMusicScanner(
      Context context, MetadataReader reader, boolean excludeWhatsApp, JSArray exclusions) {
    this.context = context;
    this.reader = reader;
    List<String> directories = new ArrayList<>();
    for (int index = 0; index < exclusions.length(); index++) {
      String path = exclusions.optString(index, "");
      if (!path.isEmpty()) directories.add(path);
    }
    filter = new LocalMusicFilter(excludeWhatsApp, directories);
  }

  JSObject scan(JSArray directories, boolean includeDeviceAudio) throws Exception {
    for (int index = 0; index < directories.length(); index++) {
      Object item = directories.opt(index);
      String uriText =
          item instanceof JSONObject
              ? ((JSONObject) item).optString("uri", "")
              : String.valueOf(item);
      try {
        DocumentFile directory = DocumentFile.fromTreeUri(context, Uri.parse(uriText));
        if (directory == null || !directory.exists() || !directory.canRead()) {
          failedDirectories++;
          continue;
        }
        scanDirectory(directory, uriText);
      } catch (Exception error) {
        failedDirectories++;
      }
    }
    if (includeDeviceAudio) scanMediaStore();
    JSObject result = new JSObject();
    result.put("songs", songs);
    result.put("filteredCount", filtered.size());
    result.put("failedDirectories", failedDirectories);
    return result;
  }

  private void scanDirectory(DocumentFile directory, String sourceFolder) {
    if (!directory.canRead()) throw new SecurityException("本地音乐目录读取权限已失效");
    for (DocumentFile child : directory.listFiles()) {
      if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("扫描已取消");
      if (child.isDirectory()) {
        scanDirectory(child, sourceFolder);
        continue;
      }
      String name = child.getName();
      if (!child.isFile() || name == null || !reader.isAudio(name)) continue;
      Uri uri = child.getUri();
      String documentId = documentId(uri);
      String fileKey = documentId == null ? null : LocalMusicFileKey.forDocumentId(documentId);
      String key = fileKey == null ? documentKey(uri) : fileKey;
      String relativePath =
          documentId == null ? "" : documentId.substring(documentId.indexOf(':') + 1);
      if (filter.excludes(relativePath)) {
        filtered.add(key);
        continue;
      }
      if (seen.contains(key)) continue;
      JSObject song = reader.read(uri, name, child.length(), child.lastModified());
      if (song == null) continue;
      song.put("sourceFolder", sourceFolder);
      songs.put(song);
      seen.add(key);
      try {
        Uri mediaUri = MediaStore.getMediaUri(context, uri);
        if (mediaUri != null) seen.add(mediaUri.toString());
      } catch (Exception ignored) {
        // 非系统文档提供器可能无法映射媒体 URI。
      }
    }
  }

  private void scanMediaStore() throws Exception {
    String[] projection = {
      MediaStore.Audio.Media._ID,
      MediaStore.Audio.Media.DISPLAY_NAME,
      MediaStore.Audio.Media.RELATIVE_PATH,
      MediaStore.Audio.Media.SIZE,
      MediaStore.Audio.Media.DATE_MODIFIED
    };
    for (String volume : new TreeSet<>(MediaStore.getExternalVolumeNames(context))) {
      Uri collection = MediaStore.Audio.Media.getContentUri(volume);
      try (Cursor cursor =
          context
              .getContentResolver()
              .query(collection, projection, null, null, MediaStore.Audio.Media._ID + " ASC")) {
        if (cursor == null) throw new IllegalStateException("系统音乐库查询失败");
        while (cursor.moveToNext()) {
          if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("扫描已取消");
          String name = cursor.getString(1);
          if (name == null || !reader.isAudio(name)) continue;
          String parent = cursor.getString(2);
          if (parent == null) parent = "";
          String key = LocalMusicFileKey.forMedia(volume, parent, name);
          if (filter.excludes(parent + name)) {
            filtered.add(key);
            continue;
          }
          Uri uri = ContentUris.withAppendedId(collection, cursor.getLong(0));
          if (seen.contains(key) || seen.contains(uri.toString())) continue;
          JSObject song = reader.read(uri, name, cursor.getLong(3), cursor.getLong(4) * 1000L);
          if (song == null) continue;
          String folderPath = parent;
          while (folderPath.endsWith("/"))
            folderPath = folderPath.substring(0, folderPath.length() - 1);
          song.put("sourceFolder", volume + (folderPath.isEmpty() ? "" : "/" + folderPath));
          songs.put(song);
          seen.add(key);
        }
      }
    }
  }

  private String documentId(Uri uri) {
    if (!"com.android.externalstorage.documents".equals(uri.getAuthority())) return null;
    try {
      return DocumentsContract.getDocumentId(uri);
    } catch (Exception ignored) {
      return null;
    }
  }

  private String documentKey(Uri uri) {
    try {
      return uri.getAuthority() + ":" + DocumentsContract.getDocumentId(uri);
    } catch (Exception ignored) {
      return uri.toString();
    }
  }
}

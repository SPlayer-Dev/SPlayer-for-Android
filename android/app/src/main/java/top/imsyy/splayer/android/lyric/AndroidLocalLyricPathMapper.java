package top.imsyy.splayer.android.lyric;

import androidx.annotation.Nullable;
import java.util.Locale;

final class AndroidLocalLyricPathMapper {
  private AndroidLocalLyricPathMapper() {}

  @Nullable
  static String mapMediaParentToTreeRelativePath(
      String treeDocumentId, String mediaRelativePath, String mediaVolume) {
    int separator = treeDocumentId.indexOf(':');
    if (separator < 1 || mediaVolume == null || mediaVolume.isEmpty()) return null;
    String treeVolume = normalizeVolume(treeDocumentId.substring(0, separator));
    if (!treeVolume.equals(normalizeVolume(mediaVolume))) return null;
    return mapMediaParentToTreeRelativePath(treeDocumentId, mediaRelativePath);
  }

  private static String normalizeVolume(String volume) {
    String normalized = volume.toLowerCase(Locale.ROOT);
    return normalized.equals("primary") ? "external_primary" : normalized;
  }

  @Nullable
  static String mapMediaParentToTreeRelativePath(String treeDocumentId, String mediaRelativePath) {
    String treeRoot = normalizePath(getDocumentPath(treeDocumentId));
    String mediaParent = normalizePath(mediaRelativePath);
    if (mediaParent == null) return null;
    if (mediaParent.isEmpty()) return null;
    if (treeRoot == null || treeRoot.isEmpty()) return mediaParent;
    if (mediaParent.equals(treeRoot)) return "";
    String prefix = treeRoot + "/";
    if (mediaParent.startsWith(prefix)) return mediaParent.substring(prefix.length());
    return null;
  }

  @Nullable
  private static String getDocumentPath(String documentId) {
    int colonIndex = documentId.indexOf(':');
    if (colonIndex < 0) return documentId;
    return documentId.substring(colonIndex + 1);
  }

  @Nullable
  private static String normalizePath(String path) {
    if (path == null) return null;
    String normalized = path.trim().replace('\\', '/');
    while (normalized.startsWith("/")) {
      normalized = normalized.substring(1);
    }
    while (normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }
}

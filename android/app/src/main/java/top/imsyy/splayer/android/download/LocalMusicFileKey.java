package top.imsyy.splayer.android.download;

import java.util.Locale;

/** 将授权目录与系统媒体库的同一文件映射到同一键。 */
final class LocalMusicFileKey {
  private LocalMusicFileKey() {}

  static String forDocumentId(String documentId) {
    int separator = documentId.indexOf(':');
    if (separator < 1) return null;
    return key(documentId.substring(0, separator), documentId.substring(separator + 1));
  }

  static String forMedia(String volume, String parent, String name) {
    return key(volume, parent + (parent.endsWith("/") || parent.isEmpty() ? "" : "/") + name);
  }

  private static String key(String volume, String path) {
    String normalizedVolume = volume.toLowerCase(Locale.ROOT);
    if (normalizedVolume.equals("primary")) normalizedVolume = "external_primary";
    return normalizedVolume + ":" + path;
  }
}

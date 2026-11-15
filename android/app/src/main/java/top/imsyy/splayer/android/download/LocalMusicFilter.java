package top.imsyy.splayer.android.download;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 本地音乐目录过滤，不修改文件。 */
final class LocalMusicFilter {
  private final List<String> excluded = new ArrayList<>();

  LocalMusicFilter(boolean excludeWhatsApp, List<String> directories) {
    if (excludeWhatsApp) {
      addWhatsAppDirectories("", "WhatsApp", "WhatsApp");
      addWhatsAppDirectories("Android/media/com.whatsapp/", "WhatsApp", "WhatsApp");
      addWhatsAppDirectories("", "WhatsApp Business", "WhatsApp Business");
      addWhatsAppDirectories("", "WhatsApp Business", "WhatsApp");
      addWhatsAppDirectories(
          "Android/media/com.whatsapp.w4b/", "WhatsApp Business", "WhatsApp Business");
      addWhatsAppDirectories("Android/media/com.whatsapp.w4b/", "WhatsApp Business", "WhatsApp");
    }
    for (String directory : directories) {
      String normalized = normalize(directory);
      if (!normalized.isEmpty() && !normalized.contains("../") && !normalized.equals("..")) {
        excluded.add(normalized);
      }
    }
  }

  private void addWhatsAppDirectories(String prefix, String root, String name) {
    excluded.add(normalize(prefix + root + "/Media/" + name + " Audio"));
    excluded.add(normalize(prefix + root + "/Media/" + name + " Voice Notes"));
  }

  boolean excludes(String relativePath) {
    String path = normalize(relativePath);
    for (String directory : excluded) {
      if (path.equals(directory) || path.startsWith(directory + "/")) return true;
    }
    return false;
  }

  private static String normalize(String path) {
    if (path == null) return "";
    String normalized = path.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    while (normalized.startsWith("/")) normalized = normalized.substring(1);
    while (normalized.endsWith("/")) normalized = normalized.substring(0, normalized.length() - 1);
    return normalized;
  }
}

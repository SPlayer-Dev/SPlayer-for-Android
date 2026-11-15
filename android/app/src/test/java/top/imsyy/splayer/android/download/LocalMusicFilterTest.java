package top.imsyy.splayer.android.download;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class LocalMusicFilterTest {
  @Test
  public void excludesWhatsAppAudioAndVoiceNotesAcrossLayouts() {
    LocalMusicFilter filter = new LocalMusicFilter(true, Collections.emptyList());
    for (String path :
        Arrays.asList(
            "WhatsApp/Media/WhatsApp Audio/song.mp3",
            "WhatsApp/Media/WhatsApp Voice Notes/202601/a.opus",
            "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Audio/Sent/a.mp3",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice"
                + " Notes/a.opus",
            "WhatsApp Business/Media/WhatsApp Audio/a.mp3",
            "ANDROID/MEDIA/COM.WHATSAPP/WHATSAPP/MEDIA/WHATSAPP AUDIO/a.mp3",
            "WhatsApp Business/Media/WhatsApp Business Audio/a.mp3")) {
      assertTrue(path, filter.excludes(path));
    }
  }

  @Test
  public void preservesNormalMusicAndSimilarlyNamedDirectories() {
    LocalMusicFilter filter = new LocalMusicFilter(true, Collections.emptyList());
    assertFalse(filter.excludes("Music/WhatsApp Song.mp3"));
    assertFalse(filter.excludes("Music/WhatsApp/Media/WhatsApp Audio/song.mp3"));
    assertFalse(filter.excludes("WhatsApp/Media/WhatsApp Audio Backup/song.mp3"));
    assertFalse(filter.excludes("WhatsApp/Media/WhatsApp Images/photo.jpg"));
  }

  @Test
  public void supportsCustomDirectoryBoundariesAndToggle() {
    LocalMusicFilter filter =
        new LocalMusicFilter(false, Arrays.asList(" Recordings/ ", "Music/private"));
    assertFalse(filter.excludes("WhatsApp/Media/WhatsApp Audio/song.mp3"));
    assertTrue(filter.excludes("recordings/meeting/a.mp3"));
    assertTrue(filter.excludes("Music/private/a.flac"));
    assertFalse(filter.excludes("Music/private-copy/a.flac"));
    assertFalse(new LocalMusicFilter(false, Arrays.asList("", " / ")).excludes("Music/a.mp3"));
  }

  @Test
  public void mapsPrimaryDocumentAndMediaStoreToSameFileWithoutMergingVolumes() {
    assertEquals(
        LocalMusicFileKey.forDocumentId("primary:Music/album/song.mp3"),
        LocalMusicFileKey.forMedia("external_primary", "Music/album/", "song.mp3"));
    assertNotEquals(
        LocalMusicFileKey.forDocumentId("primary:Music/song.mp3"),
        LocalMusicFileKey.forDocumentId("1234-5678:Music/song.mp3"));
    assertEquals(
        LocalMusicFileKey.forDocumentId("1234-ABCD:Music/song.mp3"),
        LocalMusicFileKey.forMedia("1234-abcd", "Music/", "song.mp3"));
    assertNull(LocalMusicFileKey.forDocumentId("unmapped-provider-document"));
  }
}

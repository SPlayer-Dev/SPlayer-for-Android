package top.imsyy.splayer.android.lyric;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AndroidLocalLyricPathMapperTest {
  @Test
  public void mapsMediaStoreRelativePathInsideTree() {
    assertEquals(
        "Album",
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "primary:Music", "Music/Album/"));
  }

  @Test
  public void mapsMediaStoreRelativePathAtTreeRoot() {
    assertEquals(
        "",
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "primary:Music/Album", "Music/Album/"));
  }

  @Test
  public void mapsMediaStoreRelativePathWhenTreeIsStorageRoot() {
    assertEquals(
        "Music/Album",
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath("primary:", "Music/Album/"));
  }

  @Test
  public void rejectsMediaStoreRelativePathOutsideTree() {
    assertNull(
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "primary:Lyrics", "Music/Album/"));
  }

  @Test
  public void rejectsBlankMediaStoreRelativePath() {
    assertNull(AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath("primary:", ""));
  }

  @Test
  public void requiresMatchingStorageVolumeForMediaStoreSidecars() {
    assertEquals(
        "Album",
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "primary:Music", "Music/Album/", "external_primary"));
    assertEquals(
        "Album",
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "1234-ABCD:Music", "Music/Album/", "1234-abcd"));
    assertNull(
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "primary:Music", "Music/Album/", "1234-abcd"));
    assertNull(
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "1234-abcd:Music", "Music/Album/", "external_primary"));
    assertNull(
        AndroidLocalLyricPathMapper.mapMediaParentToTreeRelativePath(
            "primary:Music", "Music/Album/", ""));
  }
}

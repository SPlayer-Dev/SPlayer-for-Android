<template>
  <div class="local-music-directory">
    <n-space v-if="isCapacitorAndroid" vertical :size="16" style="margin-bottom: 20px">
      <n-flex align="center" justify="space-between">
        <n-thing title="扫描手机音乐" description="读取系统音乐库，无需逐个添加文件夹" />
        <n-switch
          aria-label="扫描手机音乐"
          :value="settingStore.androidScanDeviceMusic"
          :loading="requestingPermission"
          :disabled="requestingPermission"
          @update:value="changeDeviceScan"
        />
      </n-flex>
      <n-flex align="center" justify="space-between">
        <n-thing title="排除 WhatsApp 音频" description="过滤语音及音频目录，包含 Business 版" />
        <n-switch
          v-model:value="settingStore.androidExcludeWhatsApp"
          aria-label="排除 WhatsApp 音频"
        />
      </n-flex>
      <n-form-item label="排除文件夹" :show-feedback="false">
        <n-space vertical style="width: 100%">
          <n-dynamic-tags v-model:value="settingStore.androidExcludedMusicDirectories" />
          <n-text depth="3"
            >填写存储相对路径，例如 Recordings 或 Music/录音；过滤不删除文件。</n-text
          >
        </n-space>
      </n-form-item>
      <n-alert v-if="permissionDenied" type="warning" title="未获得音频访问权限">
        请在系统设置中允许访问音乐与音频，或切换为仅扫描已添加目录。
        <n-button size="small" style="margin-top: 8px" @click="scanManualOnly">
          仅扫描已添加目录
        </n-button>
      </n-alert>
      <n-button :loading="requestingPermission" @click="rescan">扫描并查看音乐</n-button>
    </n-space>
    <n-text class="local-list-tip">
      {{
        isCapacitorAndroid
          ? "也可手动添加文件夹，补充系统音乐库未收录的歌曲"
          : "请选择本地音乐文件夹，将自动扫描您添加的目录，歌曲增删实时同步"
      }}
    </n-text>
    <n-scrollbar style="max-height: 50vh">
      <n-list class="local-list" hoverable clickable bordered>
        <div v-if="!hasAnyDirectory" class="empty">
          <n-empty description="暂无目录" />
        </div>
        <!-- Electron 端：显示文件系统路径 -->
        <template v-if="!isCapacitorAndroid">
          <n-list-item v-for="(path, index) in settingStore.localFilesPath" :key="index">
            <template #prefix>
              <SvgIcon :size="20" name="Folder" />
            </template>
            <template #suffix>
              <n-button :focusable="false" quaternary @click="changeLocalMusicPath(index)">
                <template #icon>
                  <SvgIcon :size="20" name="Delete" />
                </template>
              </n-button>
            </template>
            <n-thing :title="path" />
          </n-list-item>
        </template>
        <!-- Android 端：显示 SAF 目录 -->
        <template v-else>
          <n-list-item
            v-for="(item, index) in settingStore.androidLocalMusicDirectories"
            :key="item.uri"
          >
            <template #prefix>
              <SvgIcon :size="20" name="Folder" />
            </template>
            <template #suffix>
              <n-button :focusable="false" quaternary @click="removeAndroidDirectory(index)">
                <template #icon>
                  <SvgIcon :size="20" name="Delete" />
                </template>
              </n-button>
            </template>
            <n-thing :title="item.name || '未命名目录'" :description="item.uri" />
          </n-list-item>
        </template>
      </n-list>
    </n-scrollbar>
    <n-flex justify="center" style="margin-top: 20px">
      <n-button
        class="add-path"
        strong
        secondary
        :loading="adding"
        :disabled="adding"
        @click="handleAddDirectory"
      >
        <template #icon>
          <SvgIcon name="FolderPlus" />
        </template>
        添加文件夹
      </n-button>
    </n-flex>
  </div>
</template>

<script setup lang="ts">
import { useSettingStore } from "@/stores";
import { changeLocalMusicPath } from "@/utils/helper";
import SvgIcon from "@/components/Global/SvgIcon.vue";
import { isCapacitorAndroid } from "@/utils/env";
import { AndroidDownload } from "@/plugins/androidDownload";
import { useEventBus } from "@vueuse/core";

const settingStore = useSettingStore();
const adding = ref(false);
const localEventBus = useEventBus("local");
const emit = defineEmits<{ close: [] }>();
const router = useRouter();
const requestingPermission = ref(false);
const permissionDenied = ref(false);

const requestAudioAccess = async () => {
  requestingPermission.value = true;
  try {
    const result = await AndroidDownload.requestMusicPermission();
    permissionDenied.value = !result.granted;
    return result.granted;
  } catch (error) {
    console.error("获取音频权限失败:", error);
    permissionDenied.value = true;
    window.$message.error("获取音频权限失败，请检查系统应用权限");
    return false;
  } finally {
    requestingPermission.value = false;
  }
};

const changeDeviceScan = async (enabled: boolean) => {
  if (enabled && !(await requestAudioAccess())) return;
  settingStore.androidScanDeviceMusic = enabled;
};

const rescan = async () => {
  if (settingStore.androidScanDeviceMusic && !(await requestAudioAccess())) return;
  await router.push({ name: "local-songs" });
  localEventBus.emit();
  emit("close");
};

const scanManualOnly = async () => {
  settingStore.androidScanDeviceMusic = false;
  permissionDenied.value = false;
  await rescan();
};

onMounted(async () => {
  if (!isCapacitorAndroid || !settingStore.androidScanDeviceMusic) return;
  try {
    permissionDenied.value = !(await AndroidDownload.checkMusicPermission()).granted;
  } catch {
    permissionDenied.value = true;
  }
});

// 是否存在已配置目录（兼容两端）
const hasAnyDirectory = computed(() => {
  if (isCapacitorAndroid) return settingStore.androidLocalMusicDirectories.length > 0;
  return settingStore.localFilesPath.length > 0;
});

const handleAddDirectory = async () => {
  if (!isCapacitorAndroid) {
    await changeLocalMusicPath();
    return;
  }
  // Android: 走 SAF 目录选择器
  adding.value = true;
  try {
    const result = await AndroidDownload.pickLocalMusicDirectory();
    if (result.cancelled || !result.uri) return;
    // 去重
    const exists = settingStore.androidLocalMusicDirectories.some((i) => i.uri === result.uri);
    if (exists) {
      window.$message.warning("该目录已存在");
      return;
    }
    settingStore.androidLocalMusicDirectories.push({
      uri: result.uri,
      name: result.name || "未命名目录",
    });
    window.$message.success("已添加目录，正在扫描...");
    // 通知 Local 布局重扫
    localEventBus.emit();
  } catch (error) {
    console.error("选择本地音乐目录失败:", error);
    window.$message.error("选择本地音乐目录失败");
  } finally {
    adding.value = false;
  }
};

const removeAndroidDirectory = (index: number) => {
  settingStore.androidLocalMusicDirectories.splice(index, 1);
  // 通知 Local 布局重扫
  localEventBus.emit();
};
</script>

<style scoped lang="scss">
.local-list-tip {
  display: block;
  margin-bottom: 12px;
  opacity: 0.8;
}
.local-list {
  :deep(.n-list-item__prefix) {
    display: flex;
    align-items: center;
    justify-content: center;
  }
  :deep(.n-list-item__main) {
    .n-thing-main__description {
      font-size: 13px;
      opacity: 0.6;
      word-break: break-all;
    }
  }
  .empty {
    padding: 20px 0;
  }
}
</style>

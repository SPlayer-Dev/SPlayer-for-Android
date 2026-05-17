<template>
  <div
    ref="playerRef"
    :class="[
      'main-player',
      {
        show: musicStore.isHasPlayer && statusStore.showPlayBar,
        player: statusStore.showFullPlayer,
        'phone-floating': isPhone,
      },
    ]"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerEnd"
    @pointercancel="onPointerEnd"
  >
    <!-- 进度条 -->
    <PlayerSlider />
    <!-- 信息 -->
    <div :class="['play-data', { 'hidden-cover': settingStore.hiddenCovers.player }]">
      <!-- 封面 -->
      <Transition name="fade">
        <div
          v-if="!settingStore.hiddenCovers.player"
          :key="musicStore.playSong.cover"
          class="cover"
          @click.stop="statusStore.showFullPlayer = true"
        >
          <n-image
            :src="musicStore.songCover"
            :alt="musicStore.songCover"
            class="cover-img"
            preview-disabled
            @load="coverLoaded"
          >
            <template #placeholder>
              <div class="cover-loading">
                <img src="/images/song.jpg?asset" class="loading-img" alt="loading-img" />
              </div>
            </template>
          </n-image>
          <!-- 打开播放器 -->
          <SvgIcon name="Expand" :size="30" />
        </div>
      </Transition>
      <!-- 信息 -->
      <Transition name="left-sm" mode="out-in">
        <div :key="musicStore.playSong.id" class="info">
          <div class="data">
            <!-- 名称 -->
            <TextContainer
              :key="musicStore.playSong.name"
              :text="
                settingStore.hideBracketedContent
                  ? removeBrackets(musicStore.playSong.name)
                  : musicStore.playSong.name
              "
              :speed="0.2"
              class="name"
              style="cursor: pointer"
              @click.stop="settingStore.hiddenCovers.player && (statusStore.showFullPlayer = true)"
            />
            <!-- 倍速 -->
            <n-tag
              v-if="statusStore.playRate !== 1"
              type="primary"
              size="small"
              round
              @click="openChangeRate"
            >
              {{ statusStore.playRate }}x
            </n-tag>
            <!-- 喜欢 -->
            <SvgIcon
              v-if="musicStore.playSong.type !== 'radio'"
              :name="dataStore.isLikeSong(musicStore.playSong.id) ? 'Favorite' : 'FavoriteBorder'"
              :size="20"
              class="like"
              @click="
                toLikeSong(musicStore.playSong, !dataStore.isLikeSong(musicStore.playSong.id))
              "
            />
            <!-- 更多操作 -->
            <n-dropdown :options="songMoreOptions" trigger="click" placement="top-start">
              <SvgIcon name="FormatList" :size="20" :depth="2" class="more" />
            </n-dropdown>
          </div>
          <div class="lyric-container">
            <Transition
              :name="settingStore.lyricTransition === 'fade' ? 'fade' : 'lyric-slide'"
              :mode="settingStore.lyricTransition === 'fade' ? 'out-in' : undefined"
            >
              <!-- 歌词 -->
              <TextContainer
                v-if="isShowLyrics && instantLyrics"
                :key="instantLyrics"
                :text="instantLyrics"
                :speed="0.5"
                :delay="500"
                class="lyric"
              />
              <!-- 歌手 -->
              <div v-else class="artists">
                <TextContainer :speed="0.5" class="artists-container">
                  <n-text
                    v-if="musicStore.playSong.type === 'radio'"
                    class="ar-item"
                    @click="showCreatorTip"
                  >
                    {{ musicStore.playSong.dj?.creator || "未知艺术家" }}
                  </n-text>
                  <template v-else-if="Array.isArray(musicStore.playSong.artists)">
                    <n-text
                      v-for="(item, index) in musicStore.playSong.artists"
                      :key="index"
                      class="ar-item"
                      @click="openJumpArtist(musicStore.playSong.artists, item.id)"
                    >
                      {{
                        settingStore.hideBracketedContent ? removeBrackets(item.name) : item.name
                      }}
                    </n-text>
                  </template>
                  <n-text
                    v-else
                    class="ar-item"
                    @click="openJumpArtist(musicStore.playSong.artists)"
                  >
                    {{
                      settingStore.hideBracketedContent
                        ? removeBrackets(musicStore.playSong.artists)
                        : musicStore.playSong.artists || "未知艺术家"
                    }}
                  </n-text>
                </TextContainer>
              </div>
            </Transition>
          </div>
        </div>
      </Transition>
    </div>
    <!-- 控制 -->
    <n-flex :size="8" align="center" justify="center" class="play-control">
      <!-- 随机按钮 -->
      <template v-if="musicStore.playSong.type !== 'radio' && !statusStore.personalFmMode">
        <div class="play-icon" @click.stop="player.toggleShuffle()">
          <SvgIcon
            :name="statusStore.shuffleIcon"
            :size="20"
            :depth="statusStore.shuffleMode === 'off' ? 3 : 1"
          />
        </div>
      </template>
      <!-- 不喜欢 -->
      <div
        v-if="statusStore.personalFmMode"
        class="play-icon"
        v-debounce="
          () =>
            songManager.personalFMTrash(musicStore.personalFMSong?.id, () =>
              player.nextOrPrev('next'),
            )
        "
      >
        <SvgIcon class="icon" :size="18" name="ThumbDown" />
      </div>
      <!-- 上一曲 -->
      <div v-else class="play-icon" v-debounce="() => player.nextOrPrev('prev')">
        <SvgIcon :size="26" name="SkipPrev" />
      </div>
      <!-- 播放暂停 -->
      <n-button
        :loading="statusStore.playLoading"
        :focusable="false"
        :keyboard="false"
        class="play-pause"
        type="primary"
        strong
        secondary
        circle
        @click.stop="player.playOrPause()"
      >
        <template #icon>
          <Transition name="fade" mode="out-in">
            <SvgIcon
              :key="statusStore.playStatus ? 'Pause' : 'Play'"
              :name="statusStore.playStatus ? 'Pause' : 'Play'"
              :size="28"
            />
          </Transition>
        </template>
      </n-button>
      <!-- 下一曲 -->
      <div class="play-icon" v-debounce="() => player.nextOrPrev('next')">
        <SvgIcon :size="26" name="SkipNext" />
      </div>
      <!-- 循环按钮 -->
      <template v-if="musicStore.playSong.type !== 'radio' && !statusStore.personalFmMode">
        <div class="play-icon" @click.stop="player.toggleRepeat()">
          <SvgIcon
            :name="statusStore.repeatIcon"
            :size="20"
            :depth="statusStore.repeatMode === 'off' ? 3 : 1"
          />
        </div>
      </template>
    </n-flex>
    <!-- 功能 -->
    <Transition name="fade" mode="out-in">
      <n-flex
        :key="statusStore.personalFmMode ? 'fm' : 'normal'"
        :size="[8, 0]"
        class="play-menu"
        justify="end"
      >
        <!-- 时间相关 -->
        <Transition name="fade" mode="out-in">
          <n-flex
            :key="statusStore.autoClose.enable ? 'autoClose' : 'time'"
            :size="4"
            justify="center"
            class="time-container"
            vertical
          >
            <div class="time" @click="toggleTimeFormat">
              <n-text depth="2">{{ timeDisplay[0] }}</n-text>
              <n-text depth="2">{{ timeDisplay[1] }}</n-text>
            </div>
            <!-- 定时关闭 -->
            <n-tag
              v-if="statusStore.autoClose.enable"
              size="small"
              type="primary"
              round
              @click="openAutoClose"
            >
              {{ convertSecondsToTime(statusStore.autoClose.remainTime) }}
              <template #icon>
                <SvgIcon name="TimeAuto" />
              </template>
            </n-tag>
          </n-flex>
        </Transition>
        <!-- 功能区 -->
        <PlayerRightMenu />
      </n-flex>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { usePlayerController } from "@/core/player/PlayerController";
import { useSongManager } from "@/core/player/SongManager";
import { useDataStore, useMusicStore, useSettingStore, useStatusStore } from "@/stores";
import { toLikeSong } from "@/utils/auth";
import { useTimeFormat } from "@/composables/useTimeFormat";
import { useDevice } from "@/composables/useDevice";
import { copyData, coverLoaded, renderIcon, getShareUrl } from "@/utils/helper";
import {
  openAutoClose,
  openChangeRate,
  openCopySongInfo,
  openDownloadSong,
  openJumpArtist,
  openPlaylistAdd,
} from "@/utils/modal";
import { convertSecondsToTime } from "@/utils/time";
import { removeBrackets } from "@/utils/format";
import type { DropdownOption } from "naive-ui";

const router = useRouter();
const dataStore = useDataStore();
const musicStore = useMusicStore();
const statusStore = useStatusStore();
const settingStore = useSettingStore();

const player = usePlayerController();
const songManager = useSongManager();

const { isPhone } = useDevice();

const { timeDisplay, toggleTimeFormat } = useTimeFormat();

const playerRef = ref<HTMLElement | null>(null);

// 触摸滑动切换歌曲 / 上滑跟手开启全屏播放器（自实现 Pointer 事件，配合 setPointerCapture）
let dragOpenActive = false;
let dragOpenLocked: "h" | "v" | null = null;
let dragOpenParent: HTMLElement | null = null;
let dragOpenMain: HTMLElement | null = null;
let dragOpenRaf = 0;
let dragOpenPending = 0;
let dragStartX = 0;
let dragStartY = 0;
let dragStartTop = 0; // 底栏顶部到视口顶部的距离，作为卡片起始位移
let dragLastDy = 0;
let dragOpenTravel = 0;
let dragOpenResetTimer = 0;
let dragOpenCloseTimer = 0;
const OPEN_THRESHOLD = 100;

const setDragOpenFlag = (v: boolean) => {
  (window as unknown as { __splayerDragOpen?: boolean }).__splayerDragOpen = v;
};

// 关闭分支的最终落实回调（settimeout 延迟 240ms 后才会把 showFullPlayer 置 false）
// 取消 timer 时若未执行需要立即同步执行，否则 FullPlayer 会卡在起始位置
let pendingCloseFinalize: (() => void) | null = null;

// 取消上一轮手势遗留的异步清理 timer，避免清掉新手势的 inline 样式
// flushClose=true 时如果有挂起的关闭动作，立即落实关闭，避免 FullPlayer 卡在起始位置
const cancelDragOpenTimers = (flushClose = false) => {
  if (dragOpenResetTimer) {
    window.clearTimeout(dragOpenResetTimer);
    dragOpenResetTimer = 0;
  }
  if (dragOpenCloseTimer) {
    window.clearTimeout(dragOpenCloseTimer);
    dragOpenCloseTimer = 0;
    if (flushClose && pendingCloseFinalize) {
      const finalize = pendingCloseFinalize;
      pendingCloseFinalize = null;
      finalize();
      return;
    }
  }
  if (flushClose) pendingCloseFinalize = null;
};

const writeDragOpen = (dy: number) => {
  const progress = Math.max(0, Math.min(1, dy / dragOpenTravel));
  const translate = (1 - progress) * dragStartTop;
  const scale = 0.92 + 0.08 * progress;
  if (dragOpenParent) {
    dragOpenParent.style.transform = `translate3d(0, ${translate}px, 0) scale(${scale})`;
  }
  if (dragOpenMain) {
    dragOpenMain.style.opacity = String(1 - progress);
    dragOpenMain.style.transform = `scale(${1 - 0.1 * progress})`;
  }
};

const scheduleDragOpenFlush = (dy: number) => {
  dragOpenPending = dy;
  if (dragOpenRaf) return;
  dragOpenRaf = requestAnimationFrame(() => {
    dragOpenRaf = 0;
    writeDragOpen(dragOpenPending);
  });
};

// .full-player 经 <Transition mode="out-in"> + Teleport 异步挂载，
// 在 Vue 微任务流较忙或上一次离开动画未完成时可能短暂不存在，
// 此时直接放弃会导致 FullPlayer 被 onMobileEnter 的 100vh 起始 transform 卡住，
// 因此使用一次重试，最多 8 帧，仍找不到则放弃并复位 showFullPlayer。
let initDragOpenRetry = 0;
const INIT_DRAG_OPEN_MAX_RETRY = 8;

const applyDragOpenInline = (parent: HTMLElement, main: HTMLElement | null) => {
  dragOpenParent = parent;
  parent.style.transformOrigin = "50% 0";
  parent.style.willChange = "transform";
  parent.style.transition = "none";
  // 起始放置在底栏顶部位置，让卡片从控制条向上展开
  parent.style.transform = `translate3d(0, ${dragStartTop}px, 0) scale(0.92)`;
  parent.style.borderRadius = "28px";
  parent.style.backfaceVisibility = "hidden";
  // 关键：让 FullPlayer 在拖拽期间不拦截触摸，事件继续命中底栏（playerRef）
  parent.style.pointerEvents = "none";
  if (main) {
    dragOpenMain = main;
    main.style.transition = "none";
    main.style.willChange = "transform, opacity";
    main.style.opacity = "1";
    main.style.transform = "scale(1)";
  }
};

const initDragOpen = () => {
  // 取消上一轮手势遗留的清理 timer，避免清掉本次 inline 样式
  cancelDragOpenTimers();
  initDragOpenRetry = 0;
  const tryAttach = () => {
    if (!dragOpenActive) return;
    const parent = document.querySelector(".full-player") as HTMLElement | null;
    const main = document.getElementById("main");
    if (parent) {
      applyDragOpenInline(parent, main);
      // 命中后立刻把当前 dy 写入，避免 0 dy 一帧裸态
      writeDragOpen(Math.max(dragLastDy, 0));
      return;
    }
    if (initDragOpenRetry++ < INIT_DRAG_OPEN_MAX_RETRY) {
      requestAnimationFrame(tryAttach);
      return;
    }
    // 超过重试上限，撤销开启意图，防止用户被卡在不可见的 FullPlayer 上
    console.warn("[MainPlayer] 拖拽开启时未能找到 .full-player，回退关闭");
    dragOpenActive = false;
    dragOpenLocked = null;
    setDragOpenFlag(false);
    statusStore.showFullPlayer = false;
  };
  tryAttach();
};

const resetDragOpen = () => {
  if (dragOpenRaf) {
    cancelAnimationFrame(dragOpenRaf);
    dragOpenRaf = 0;
  }
  if (dragOpenParent) {
    dragOpenParent.style.transition = "";
    dragOpenParent.style.transform = "";
    dragOpenParent.style.borderRadius = "";
    dragOpenParent.style.transformOrigin = "";
    dragOpenParent.style.willChange = "";
    dragOpenParent.style.pointerEvents = "";
    dragOpenParent.style.backfaceVisibility = "";
  }
  if (dragOpenMain) {
    dragOpenMain.style.transition = "";
    dragOpenMain.style.transform = "";
    dragOpenMain.style.opacity = "";
    dragOpenMain.style.willChange = "";
  }
  dragOpenParent = null;
  dragOpenMain = null;
  dragOpenActive = false;
  dragOpenLocked = null;
  setDragOpenFlag(false);
};

const finishDragOpen = (dy: number) => {
  const shouldOpen = dy > OPEN_THRESHOLD;
  if (dragOpenRaf) {
    cancelAnimationFrame(dragOpenRaf);
    dragOpenRaf = 0;
  }
  // 兜底：若挂载阶段重试未成功捕获到 .full-player，dragOpenParent 仍为 null，
  // 此时 onMobileEnter 已把元素定位到 100vh 离屏。无论开/关结果都必须现在重查并清掉 inline 样式，
  // 否则 FullPlayer 会被永久卡在屏幕外（showFullPlayer 为 true 但用户什么都看不到）。
  if (!dragOpenParent) {
    const parent = document.querySelector(".full-player") as HTMLElement | null;
    if (parent) {
      dragOpenParent = parent;
      // 清掉 onMobileEnter 留下的离屏 transform 及其它内联样式
      parent.style.transition = "";
      parent.style.transform = "";
      parent.style.borderRadius = "";
      parent.style.transformOrigin = "";
      parent.style.willChange = "";
      parent.style.pointerEvents = "";
      parent.style.backfaceVisibility = "";
    }
    if (!dragOpenMain) {
      dragOpenMain = document.getElementById("main");
    }
  }
  if (shouldOpen) {
    if (dragOpenParent) {
      dragOpenParent.style.transition =
        "transform 0.28s cubic-bezier(0.22, 1, 0.36, 1)";
      dragOpenParent.style.transform = "";
      // 立即恢复全屏播放器的指针事件，避免开启动画期间 (~320ms) 触摸穿透到底层主页
      dragOpenParent.style.pointerEvents = "";
    }
    if (dragOpenMain) {
      dragOpenMain.style.transition =
        "opacity 0.28s ease, transform 0.28s cubic-bezier(0.22, 1, 0.36, 1)";
      dragOpenMain.style.opacity = "";
      dragOpenMain.style.transform = "";
    }
    dragOpenResetTimer = window.setTimeout(() => {
      dragOpenResetTimer = 0;
      resetDragOpen();
    }, 320);
  } else {
    if (dragOpenParent) {
      dragOpenParent.style.transition =
        "transform 0.24s cubic-bezier(0.4, 0, 1, 1)";
      dragOpenParent.style.transform = `translate3d(0, ${dragStartTop}px, 0) scale(0.92)`;
    }
    if (dragOpenMain) {
      dragOpenMain.style.transition =
        "opacity 0.24s ease, transform 0.24s cubic-bezier(0.22, 1, 0.36, 1)";
      dragOpenMain.style.opacity = "1";
      dragOpenMain.style.transform = "scale(1)";
    }
    // 注册关闭最终落实回调，便于在新手势打断时同步执行，避免 FullPlayer 被卡在起始位置
    pendingCloseFinalize = () => {
      statusStore.showFullPlayer = false;
      // 这里不再延迟 360ms reset：新手势打断的情况下需要立即清理 inline，
      // 正常路径下 onMobileLeave 会负责接管离场动画
      resetDragOpen();
    };
    dragOpenCloseTimer = window.setTimeout(() => {
      dragOpenCloseTimer = 0;
      const finalize = pendingCloseFinalize;
      pendingCloseFinalize = null;
      if (finalize) finalize();
    }, 240);
  }
};

let pointerId = -1;
let pointerActiveTarget: HTMLElement | null = null;
let horizontalSettled = false;
let horizontalDirection: "left" | "right" | null = null;

const onPointerDown = (e: PointerEvent) => {
  if (e.pointerType === "mouse" && e.button !== 0) return;
  // 新手势开始前取消上一轮异步清理，防止 timer 把本次样式清掉。
  // flushClose=true：若上一次手势的关闭动作正在等待落实，立即执行掉，
  // 否则会出现 showFullPlayer=true 但 FullPlayer 卡在起始位置且无法响应触摸的状态
  cancelDragOpenTimers(true);
  // 兜底恢复：若 FullPlayer 标记为打开但 .full-player 残留内联 transform / pointer-events:none，
  // 说明上一次拖拽流程留下了脏状态（例如歌曲加载中竞态导致 finishDragOpen 未能落实），
  // 此处强制清掉内联让 FullPlayer 恢复正常可交互的全屏状态
  if (statusStore.showFullPlayer && !dragOpenActive) {
    const stalled = document.querySelector(".full-player") as HTMLElement | null;
    if (stalled && (stalled.style.transform || stalled.style.pointerEvents === "none")) {
      stalled.style.transition = "";
      stalled.style.transform = "";
      stalled.style.borderRadius = "";
      stalled.style.transformOrigin = "";
      stalled.style.willChange = "";
      stalled.style.pointerEvents = "";
      stalled.style.backfaceVisibility = "";
      const mainEl = document.getElementById("main");
      if (mainEl) {
        mainEl.style.transition = "";
        mainEl.style.transform = "";
        mainEl.style.opacity = "";
        mainEl.style.willChange = "";
      }
      dragOpenParent = null;
      dragOpenMain = null;
      setDragOpenFlag(false);
    }
  }
  pointerId = e.pointerId;
  dragStartX = e.clientX;
  dragStartY = e.clientY;
  dragLastDy = 0;
  dragOpenActive = false;
  dragOpenLocked = null;
  horizontalSettled = false;
  horizontalDirection = null;
  // 仅记录目标，不立即捕获指针，避免影响子元素普通点击
  pointerActiveTarget = e.currentTarget as HTMLElement;
};

const onPointerMove = (e: PointerEvent) => {
  if (e.pointerId !== pointerId) return;
  const dx = e.clientX - dragStartX;
  const dy = dragStartY - e.clientY; // 上为正
  dragLastDy = dy;
  if (!isPhone.value || (statusStore.showFullPlayer && !dragOpenActive)) return;
  const ax = Math.abs(dx);
  const ay = Math.abs(dy);
  if (!dragOpenLocked) {
    if (Math.max(ax, ay) < 8) return;
    if (ay > ax) {
      // 向上拖拽才进入开启流程
      if (dy <= 0) {
        dragOpenLocked = "h";
        return;
      }
      dragOpenLocked = "v";
      // 锁定方向后再捕获指针，确保 FullPlayer 覆盖后事件仍流向底栏
      pointerActiveTarget?.setPointerCapture?.(e.pointerId);
      // 缓存底栏顶部坐标，作为卡片起始位移
      const rect = playerRef.value?.getBoundingClientRect();
      dragStartTop = rect ? rect.top : window.innerHeight - 80;
      dragOpenTravel = Math.max(window.innerHeight * 0.55, 360);
      setDragOpenFlag(true);
      dragOpenActive = true;
      statusStore.showFullPlayer = true;
      // 等到下一帧再 initDragOpen，给 <Transition mode="out-in"> 留出挂载时机
      // 实际写入由 initDragOpen 内部基于最新 dragLastDy 完成，避免使用过期 dy
      requestAnimationFrame(() => {
        if (!dragOpenActive) return;
        initDragOpen();
      });
      return;
    }
    dragOpenLocked = "h";
    horizontalDirection = dx > 0 ? "right" : "left";
    return;
  }
  if (dragOpenLocked === "h") {
    horizontalDirection = dx > 0 ? "right" : "left";
    horizontalSettled = ax > 50;
    return;
  }
  if (dragOpenActive) scheduleDragOpenFlush(Math.max(dy, 0));
};

const onPointerEnd = (e: PointerEvent) => {
  if (e.pointerId !== pointerId) return;
  pointerId = -1;
  if (pointerActiveTarget) {
    pointerActiveTarget.releasePointerCapture?.(e.pointerId);
    pointerActiveTarget = null;
  }
  if (dragOpenActive) {
    finishDragOpen(Math.max(dragLastDy, 0));
    return;
  }
  if (dragOpenLocked === "h" && horizontalSettled && horizontalDirection) {
    if (horizontalDirection === "left") player.nextOrPrev("next");
    else player.nextOrPrev("prev");
  }
};

// 歌曲更多操作
const songMoreOptions = computed<DropdownOption[]>(() => {
  // 当前状态
  const song = musicStore.playSong;
  const isHasMv = !!song?.mv && song.mv !== 0;
  const isSong = song.type === "song";
  const isLocal = !!song?.path;
  return [
    {
      key: "more",
      label: "更多操作",
      icon: renderIcon("Menu", { size: 18 }),
      children: [
        {
          key: "code-name",
          label: `复制${song.type === "song" ? "歌曲" : "节目"}名称`,
          props: {
            onClick: () => copyData(song.name),
          },
          icon: renderIcon("Copy", { size: 18 }),
        },
        {
          key: "code-id",
          label: `复制${song.type === "song" ? "歌曲" : "节目"} ID`,
          show: !isLocal,
          props: {
            onClick: () => copyData(song.id),
          },
          icon: renderIcon("Copy", { size: 18 }),
        },
        {
          key: "copy-song-info",
          label: "复制更多信息",
          show: !isLocal && isSong,
          props: {
            onClick: () => openCopySongInfo(song.id),
          },
          icon: renderIcon("FormatList", { size: 18 }),
        },
        {
          key: "share",
          label: `分享${song.type === "song" ? "歌曲" : "节目"}链接`,
          show: !isLocal,
          props: {
            onClick: () => copyData(getShareUrl(song.type, song.id), "已复制分享链接到剪切板"),
          },
          icon: renderIcon("Share", { size: 18 }),
        },
      ],
    },
    {
      key: "search",
      label: "同名搜索",
      show: settingStore.useOnlineService,
      props: {
        onClick: () => router.push({ name: "search", query: { keyword: song.name } }),
      },
      icon: renderIcon("Search"),
    },
    {
      key: "line",
      type: "divider",
    },
    {
      key: "playlist-add",
      label: "添加到歌单",
      props: {
        onClick: () => openPlaylistAdd([song], isLocal),
      },
      icon: renderIcon("AddList"),
    },
    {
      key: "mv",
      label: "观看 MV",
      show: isSong && isHasMv,
      props: {
        onClick: () =>
          router.push({ name: "video", query: { id: musicStore.playSong.mv, type: "mv" } }),
      },
      icon: renderIcon("Video", { size: 18 }),
    },
    {
      key: "download",
      label: "下载歌曲",
      show: statusStore.isDeveloperMode && !isLocal && isSong,
      props: { onClick: () => openDownloadSong(musicStore.playSong) },
      icon: renderIcon("Download"),
    },
    {
      key: "wiki",
      label: "音乐百科",
      show: !isLocal && isSong,
      props: {
        onClick: () => router.push({ name: "song-wiki", query: { id: musicStore.playSong.id } }),
      },
      icon: renderIcon("Info"),
    },
    {
      key: "comment",
      label: "查看评论",
      show: !isLocal,
      props: {
        onClick: () => {
          const id = musicStore.playSong.id;
          const type = musicStore.playSong.type === "radio" ? 4 : 0;
          router.push({ name: "comment", query: { id, type } });
        },
      },
      icon: renderIcon("Message"),
    },
  ];
});

// 是否展示歌词
const isShowLyrics = computed(() => {
  const isHasLrc = musicStore.isHasLrc;
  return (
    isHasLrc &&
    !statusStore.lyricLoading &&
    settingStore.barLyricShow &&
    musicStore.playSong.type !== "radio" &&
    statusStore.playStatus &&
    statusStore.lyricIndex !== -1
  );
});

// 当前实时歌词
const instantLyrics = computed(() => {
  const isYrc = musicStore.songLyric.yrcData?.length && settingStore.showWordLyrics;
  const content = isYrc
    ? musicStore.songLyric.yrcData[statusStore.lyricIndex]
    : musicStore.songLyric.lrcData[statusStore.lyricIndex];
  const contentStr = content?.words?.map((v) => v.word).join("") || "";
  return content?.translatedLyric && settingStore.showTran
    ? `${contentStr}（ ${content?.translatedLyric} ）`
    : contentStr || "";
});

// 暂不支持查看主播主页
const showCreatorTip = () => window.$message.info("暂不支持查看主播主页");
</script>

<style lang="scss" scoped>
.main-player {
  position: fixed;
  left: 0;
  bottom: -90px;
  height: 80px;
  padding: 0 15px;
  width: 100%;
  background-color: var(--surface-container-hex);
  display: grid;
  grid-template-columns: 1fr auto 1fr;
  align-items: center;
  transition: bottom 0.3s;
  z-index: 10;
  // 接管指针手势，避免浏览器滚动抢占垂直方向触发取消捕获
  touch-action: pan-x;
  &.show {
    bottom: 0;
  }
  .player-slider {
    position: absolute;
    width: 100%;
    height: 16px;
    top: -8px;
    left: 0;
    margin: 0;
    --n-rail-height: 3px;
    --n-handle-size: 14px;
  }
  .play-data {
    position: relative;
    display: flex;
    flex-direction: row;
    align-items: center;
    overflow: hidden;
    height: 100%;
    max-width: 640px;
    padding-left: 68px;
    .cover {
      position: absolute;
      display: flex;
      align-items: center;
      justify-content: center;
      left: 0;
      width: 56px;
      height: 56px;
      min-width: 56px;
      border-radius: 8px;
      overflow: hidden;
      margin-right: 12px;
      transition: opacity 0.2s;
      cursor: pointer;
      :deep(img) {
        width: 56px;
        height: 56px;
        opacity: 0;
        transition:
          transform 0.3s,
          opacity 0.3s,
          filter 0.3s;
      }
      .n-icon {
        position: absolute;
        color: #eee;
        opacity: 0;
        transform: scale(0.6);
        transition:
          opacity 0.3s,
          transform 0.3s;
      }
      &:hover {
        :deep(img) {
          transform: scale(1.2);
          filter: brightness(0.6) blur(2px);
        }
        .n-icon {
          opacity: 1;
          transform: scale(1);
        }
      }
      &:active {
        .n-icon {
          transform: scale(1.2);
        }
      }
    }
    .info {
      display: flex;
      flex-direction: column;
      flex: 1;
      min-width: 0;
      .data {
        display: flex;
        align-items: center;
        .name {
          font-weight: bold;
          font-size: 16px;
          flex: 0 1 auto;
          width: auto;
          min-width: 0;
          transition: color 0.3s;
        }
        .n-tag {
          margin-left: 8px;
          flex-shrink: 0;
        }
        .like {
          color: var(--primary-hex);
          margin-left: 8px;
          transition: transform 0.3s;
          cursor: pointer;
          flex-shrink: 0;
          &:hover {
            transform: scale(1.15);
          }
          &:active {
            transform: scale(1);
          }
        }
        .more {
          margin-left: 8px;
          cursor: pointer;
          flex-shrink: 0;
        }
      }
      .lyric-container {
        position: relative;
        height: 22px;
        margin-top: 2px;
        overflow: hidden;
        .lyric,
        .artists {
          margin-top: 0;
          position: absolute;
          top: 0;
          left: 0;
          width: 100%;
          height: 100%;
          display: flex;
          align-items: center;
        }
      }
      .artists {
        width: 100%;
        overflow: hidden;

        .artists-container {
          .ar-item {
            display: inline-flex;
            transition: color 0.3s;
            cursor: pointer;
            white-space: nowrap;

            &::after {
              content: "/";
              margin: 0 6px;
              opacity: 0.6;
              transition: none;
            }
            &:last-child {
              &::after {
                display: none;
              }
            }
            &:hover {
              color: var(--primary-hex);
              &::after {
                color: var(--n-close-icon-color);
              }
            }
          }
        }
      }
    }
    &.hidden-cover {
      padding-left: 0;
    }
  }
  .play-control {
    margin: 0 60px;
    .play-pause {
      --n-width: 44px;
      --n-height: 44px;
      margin: 0 4px;
      transition:
        background-color 0.3s,
        transform 0.3s;
      .n-icon {
        transition: opacity 0.1s ease-in-out;
      }
      &:hover {
        transform: scale(1.1);
      }
      &:active {
        transform: scale(1);
      }
    }
    .play-icon {
      display: flex;
      align-items: center;
      justify-content: center;
      width: 38px;
      height: 38px;
      border-radius: 50%;
      will-change: transform;
      transition:
        background-color 0.3s,
        transform 0.3s;
      cursor: pointer;
      margin: 0 2px;
      .n-icon {
        color: var(--primary-hex);
      }
      &:hover {
        transform: scale(1.1);
        background-color: rgba(var(--primary), 0.16);
      }
      &:active {
        transform: scale(1);
      }
    }
  }
  .play-menu {
    margin-left: auto;
    max-width: 640px;
    .time-container {
      margin-right: 8px;
      .n-tag {
        justify-content: center;
        font-size: 12px;
      }
    }
    .time {
      cursor: pointer;
      display: flex;
      align-items: center;
      font-size: 12px;
      .n-text {
        color: var(--primary-hex);
        opacity: 0.8;
        &:nth-of-type(1) {
          &::after {
            content: "/";
            margin: 0 4px;
          }
        }
      }
      &:hover {
        text-decoration: underline;
        text-decoration-color: var(--primary-hex);
      }
    }
  }
  @media (max-width: 1024px) {
    .play-menu {
      .time-container {
        display: none !important;
      }
    }
  }
  @media (max-width: 810px) {
    grid-template-columns: 1fr auto auto;
    .play-control {
      margin: 0 0 0 12px;
      .play-icon {
        display: none;
      }
    }
  }

  // 手机版适配（768px 以下）
  @media (max-width: 768px) {
    height: 76px;
    padding: 0 12px;

    .play-data {
      padding-left: 60px;

      .cover {
        width: 48px;
        height: 48px;
        min-width: 48px;
        border-radius: 6px;

        :deep(img) {
          width: 48px;
          height: 48px;
        }
      }

      .info {
        .data {
          .name {
            font-size: 14px;
          }

          .like,
          .more {
            margin-left: 6px;
          }
        }

        .lyric-container {
          height: 20px;

          .lyric,
          .artists {
            font-size: 12px;
          }
        }
      }
    }

    .play-control {
      .play-pause {
        --n-width: 40px;
        --n-height: 40px;
      }

      .play-icon {
        width: 34px;
        height: 34px;

        .n-icon {
          font-size: 22px;
        }
      }
    }

    .play-menu {
      .time {
        font-size: 11px;
      }
    }
  }

  // 手机浮岛模式：圆角悬浮在底栏之上
  &.phone-floating {
    --phone-bar-height: 64px;
    left: 10px;
    right: 10px;
    width: auto;
    height: var(--phone-bar-height);
    padding: 0 10px;
    border-radius: 18px;
    background-color: var(--surface-container-hex);
    box-shadow:
      0 6px 20px rgba(0, 0, 0, 0.18),
      0 1px 3px rgba(0, 0, 0, 0.08);
    bottom: calc(-1 * (var(--phone-bar-height) + 24px));
    transition:
      bottom 0.32s var(--n-bezier),
      box-shadow 0.3s var(--n-bezier);

    &.show {
      bottom: calc(var(--phone-nav-total-height) + var(--phone-player-gap));
    }

    .player-slider {
      width: calc(100% - 20px);
      left: 10px;
      top: -7px;
      --n-rail-height: 3px;
      --n-handle-size: 12px;
    }

    .play-data {
      padding-left: 56px;

      .cover {
        width: 44px;
        height: 44px;
        min-width: 44px;
        border-radius: 7px;
        margin-right: 10px;

        :deep(img) {
          width: 44px;
          height: 44px;
        }
      }

      .info {
        .data {
          .name {
            font-size: 13px;
          }
          .like,
          .more {
            margin-left: 5px;
          }
        }
        .lyric-container {
          height: 18px;
          .lyric,
          .artists {
            font-size: 11px;
          }
        }
      }
    }

    .play-control {
      margin: 0 0 0 8px;
      .play-pause {
        --n-width: 38px;
        --n-height: 38px;
        margin: 0 2px;
      }
      .play-icon {
        width: 32px;
        height: 32px;
        margin: 0;
        .n-icon {
          font-size: 20px;
        }
      }
    }
  }
}
</style>

<script setup lang="ts">
/**
 * 歌词渲染组件 - 基于 @applemusic-like-lyrics/core
 */
import {
  type BaseRenderer,
  LyricPlayer as CoreLyricPlayer,
  type LyricLine,
  type LyricLineMouseEvent,
  type LyricPlayerBase,
  type spring,
} from "@applemusic-like-lyrics/core";
import type { PropType, Ref, ShallowRef } from "vue";
import "@applemusic-like-lyrics/core/style.css";

/**
 * Props 定义
 */
const props = defineProps({
  /**
   * 是否禁用歌词播放组件，默认为 `false`，歌词组件启用后将会开始逐帧更新歌词的动画效果，并对传入的其他参数变更做出反馈。
   *
   * 如果禁用了歌词组件动画，你也可以通过引用取得原始渲染组件实例，手动逐帧调用其 `update` 函数来更新动画效果。
   */
  disabled: {
    type: Boolean,
    default: false,
  },
  /**
   * 是否演出部分效果，目前会控制播放间奏点的动画的播放暂停与否，默认为 `true`
   */
  playing: {
    type: Boolean,
    default: true,
  },
  /**
   * 设置歌词行的对齐方式，如果为 `undefined` 则默认为 `center`
   *
   * - 设置成 `top` 的话将会向目标歌词行的顶部对齐
   * - 设置成 `bottom` 的话将会向目标歌词行的底部对齐
   * - 设置成 `center` 的话将会向目标歌词行的垂直中心对齐
   */
  alignAnchor: {
    type: String as PropType<"top" | "bottom" | "center">,
    default: "center",
  },
  /**
   * 设置默认的歌词行对齐位置，相对于整个歌词播放组件的大小位置，如果为 `undefined`
   * 则默认为 `0.5`
   *
   * 可以设置一个 `[0.0-1.0]` 之间的任意数字，代表组件高度由上到下的比例位置
   */
  alignPosition: {
    type: Number,
    default: 0.5,
  },
  /**
   * 设置是否使用物理弹簧算法实现歌词动画效果，默认启用
   *
   * 如果启用，则会通过弹簧算法实时处理歌词位置，但是需要性能足够强劲的电脑方可流畅运行
   *
   * 如果不启用，则会回退到基于 `transition` 的过渡效果，对低性能的机器比较友好，但是效果会比较单一
   */
  enableSpring: {
    type: Boolean,
    default: true,
  },
  /**
   * 设置是否启用歌词行的模糊效果，默认为 `true`
   */
  enableBlur: {
    type: Boolean,
    default: true,
  },
  /**
   * 设置是否使用物理弹簧算法实现歌词动画效果，默认启用
   *
   * 如果启用，则会通过弹簧算法实时处理歌词位置，但是需要性能足够强劲的电脑方可流畅运行
   *
   * 如果不启用，则会回退到基于 `transition` 的过渡效果，对低性能的机器比较友好，但是效果会比较单一
   */
  enableScale: {
    type: Boolean,
    default: true,
  },
  /**
   * 设置是否隐藏已经播放过的歌词行，默认不隐藏
   */
  hidePassedLines: {
    type: Boolean,
    default: false,
  },
  /**
   * 设置当前播放歌词，要注意传入后这个数组内的信息不得修改，否则会发生错误
   */
  lyricLines: {
    type: Object as PropType<LyricLine[]>,
    required: false,
  },
  /**
   * 设置当前播放进度，单位为毫秒且**必须是整数**，此时将会更新内部的歌词进度信息
   * 内部会根据调用间隔和播放进度自动决定如何滚动和显示歌词，所以这个的调用频率越快越准确越好
   */
  currentTime: {
    type: Number,
    default: 0,
  },
  /**
   * 设置文字动画的渐变宽度，单位以歌词行的主文字字体大小的倍数为单位，默认为 0.5，即一个全角字符的一半宽度
   *
   * 如果要模拟 Apple Music for Android 的效果，可以设置为 1
   *
   * 如果要模拟 Apple Music for iPad 的效果，可以设置为 0.5
   *
   * 如果想要近乎禁用渐变效果，可以设置成非常接近 0 的小数（例如 `0.0001` ），但是**不可以为 0**
   */
  wordFadeWidth: {
    type: Number,
    default: 0.5,
  },
  /**
   * 设置所有歌词行在横坐标上的弹簧属性，包括重量、弹力和阻力。
   *
   * @param params 需要设置的弹簧属性，提供的属性将会覆盖原来的属性，未提供的属性将会保持原样
   */
  linePosXSpringParams: {
    type: Object as PropType<Partial<spring.SpringParams>>,
    required: false,
  },
  /**
   * 设置所有歌词行在​纵坐标上的弹簧属性，包括重量、弹力和阻力。
   *
   * @param params 需要设置的弹簧属性，提供的属性将会覆盖原来的属性，未提供的属性将会保持原样
   */
  linePosYSpringParams: {
    type: Object as PropType<Partial<spring.SpringParams>>,
    required: false,
  },
  /**
   * 设置所有歌词行在​缩放大小上的弹簧属性，包括重量、弹力和阻力。
   *
   * @param params 需要设置的弹簧属性，提供的属性将会覆盖原来的属性，未提供的属性将会保持原样
   */
  lineScaleSpringParams: {
    type: Object as PropType<Partial<spring.SpringParams>>,
    required: false,
  },
  /**
   * 设置渲染器，如果为 `undefined` 则默认为 `MeshGradientRenderer`
   * 默认渲染器有可能会随着版本更新而更换
   */
  lyricPlayer: {
    type: Object as PropType<{
      new (...args: ConstructorParameters<typeof BaseRenderer>): BaseRenderer;
    }>,
    required: false,
  },
});

/**
 * 歌词播放组件的事件
 */
const emit = defineEmits<{
  lineClick: [event: LyricLineMouseEvent];
  lineContextmenu: [event: LyricLineMouseEvent];
}>();

/**
 * 歌词播放组件的引用
 */
export interface LyricPlayerRef {
  /**
   * 歌词播放实例
   */
  lyricPlayer: Ref<LyricPlayerBase | undefined>;
  /**
   * 将歌词播放实例的元素包裹起来的 DIV 元素实例
   */
  wrapperEl: Readonly<ShallowRef<HTMLDivElement | null>>;
}

// 模板引用
const wrapperRef = useTemplateRef<HTMLDivElement>("wrapper-ref");
// 歌词播放实例
const playerRef = ref<CoreLyricPlayer>();

// 事件处理器
const lineClickHandler = (e: Event) => emit("lineClick", e as LyricLineMouseEvent);
const lineContextMenuHandler = (e: Event) => emit("lineContextmenu", e as LyricLineMouseEvent);

// 底部行元素
const bottomLineEl = computed(() => playerRef.value?.getBottomLineElement());

type InternalLyricLineObject = {
  enable?: (time?: number, shouldPlay?: boolean) => void | Promise<void>;
};

type InternalLyricPlayer = CoreLyricPlayer & {
  hotLines?: Set<number>;
  bufferedLines?: Set<number>;
  processedLines?: LyricLine[];
  currentLyricLineObjects?: InternalLyricLineObject[];
  scrollToIndex?: number;
  resetScroll?: () => void;
  calcLayout?: () => void | Promise<void>;
};

const getInternalPlayer = () => playerRef.value as InternalLyricPlayer | undefined;

// 补齐新激活行的动画时间
const syncNewHotLineAnimations = (
  player: InternalLyricPlayer,
  previousHotLines: Set<number>,
  time: number,
) => {
  const { hotLines, currentLyricLineObjects } = player;
  if (!hotLines || !Array.isArray(currentLyricLineObjects)) return;

  for (const id of hotLines) {
    if (previousHotLines.has(id)) continue;
    void currentLyricLineObjects[id]?.enable?.(time, props.playing);
  }
};

// 正常播放时同步时间
const syncPlaybackTime = (time: number) => {
  const player = getInternalPlayer();
  if (!player) return;

  const previousHotLines: Set<number> = new Set(player.hotLines ?? []);
  player.setCurrentTime(time, false);
  syncNewHotLineAnimations(player, previousHotLines, time);
};

// 跳转或重载歌词时强制落位
const syncSeekTime = (time: number) => {
  const player = getInternalPlayer();
  if (!player) return;

  player.setCurrentTime(time, true);

  if (player.bufferedLines && player.hotLines && player.processedLines) {
    player.bufferedLines.clear();
    for (const v of player.hotLines) {
      player.bufferedLines.add(v);
    }

    if (player.bufferedLines.size > 0) {
      player.scrollToIndex = Math.min(...player.bufferedLines);
    } else {
      const foundIndex = player.processedLines.findIndex((line) => line.startTime >= time);
      player.scrollToIndex = foundIndex === -1 ? player.processedLines.length : foundIndex;
    }

    player.resetScroll?.();
    void player.calcLayout?.();
  }
};

// 组件挂载时初始化
onMounted(() => {
  const wrapper = wrapperRef.value;
  if (wrapper) {
    // AMLL 核心库在构造时注册非 passive 的 touchstart/touchmove 监听器，
    // 产生 Chrome [Violation] 警告。临时补丁强制改为 passive，
    // 配合 CSS touch-action: none 让浏览器不处理触摸滚动
    const origAddEventListener = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = function (
      type: string,
      listener: EventListenerOrEventListenerObject,
      options?: boolean | AddEventListenerOptions,
    ) {
      if (type === "touchstart" || type === "touchmove") {
        const merged: AddEventListenerOptions =
          typeof options === "object" && options !== null
            ? { ...options, passive: true }
            : { capture: options === true, passive: true };
        return origAddEventListener.call(this, type, listener, merged);
      }
      return origAddEventListener.call(this, type, listener, options);
    };

    try {
      playerRef.value = new CoreLyricPlayer();
    } finally {
      // 无论构造是否抛错，都必须还原全局原型，避免污染整个应用
      EventTarget.prototype.addEventListener = origAddEventListener;
    }

    const el = playerRef.value.getElement();
    el.style.touchAction = "none";
    wrapper.appendChild(el);
    playerRef.value.addEventListener("line-click", lineClickHandler);
    playerRef.value.addEventListener("line-contextmenu", lineContextMenuHandler);
  }
});

// 组件卸载时清理
onUnmounted(() => {
  const player = playerRef.value;
  if (player) {
    player.removeEventListener("line-click", lineClickHandler);
    player.removeEventListener("line-contextmenu", lineContextMenuHandler);
    player.dispose();
  }
});

// 动画帧更新
watchEffect((onCleanup) => {
  if (!props.disabled) {
    let canceled = false;
    let lastTime = -1;
    const onFrame = (time: number) => {
      if (canceled) return;
      if (lastTime === -1) {
        lastTime = time;
      }
      playerRef.value?.update(time - lastTime);
      lastTime = time;
      requestAnimationFrame(onFrame);
    };
    requestAnimationFrame(onFrame);
    onCleanup(() => {
      canceled = true;
    });
  }
});

// 播放/暂停状态
watchEffect(() => {
  if (props.playing !== undefined) {
    if (props.playing) {
      playerRef.value?.resume();
    } else {
      playerRef.value?.pause();
    }
  } else playerRef.value?.resume();
});

// 对齐锚点
watchEffect(() => {
  if (props.alignAnchor !== undefined) playerRef.value?.setAlignAnchor(props.alignAnchor);
});

// 隐藏已播放歌词行
watchEffect(() => {
  if (props.hidePassedLines !== undefined)
    playerRef.value?.setHidePassedLines(props.hidePassedLines);
});

// 对齐位置
watchEffect(() => {
  if (props.alignPosition !== undefined) playerRef.value?.setAlignPosition(props.alignPosition);
});

// 弹簧动画
watchEffect(() => {
  if (props.enableSpring !== undefined) playerRef.value?.setEnableSpring(props.enableSpring);
  else playerRef.value?.setEnableSpring(true);
});

// 模糊效果
watchEffect(() => {
  if (props.enableBlur !== undefined) playerRef.value?.setEnableBlur(props.enableBlur);
  else playerRef.value?.setEnableBlur(true);
});

// 缩放效果
watchEffect(() => {
  if (props.enableScale !== undefined) playerRef.value?.setEnableScale(props.enableScale);
  else playerRef.value?.setEnableScale(true);
});

// 歌词行数据
watch(
  [() => props.lyricLines, playerRef],
  ([lines, player]) => {
    if (lines === undefined || !player) return;
    player.setLyricLines(lines);
    syncSeekTime(props.currentTime);
  },
  { immediate: true },
);

// 当前播放时间
watch(
  () => props.currentTime,
  (time, oldTime) => {
    if (time === undefined) return;
    const isSeek = oldTime !== undefined && Math.abs(time - oldTime) > 1000;

    if (isSeek) {
      syncSeekTime(time);
    } else {
      syncPlaybackTime(time);
    }
  },
  { immediate: true },
);

// 渐变宽度
watchEffect(() => {
  if (props.wordFadeWidth !== undefined) playerRef.value?.setWordFadeWidth(props.wordFadeWidth);
});

// X 轴弹簧参数
watchEffect(() => {
  if (props.linePosXSpringParams !== undefined)
    playerRef.value?.setLinePosXSpringParams(props.linePosXSpringParams);
});

// Y 轴弹簧参数
watchEffect(() => {
  if (props.linePosYSpringParams !== undefined)
    playerRef.value?.setLinePosYSpringParams(props.linePosYSpringParams);
});

// 缩放弹簧参数
watchEffect(() => {
  if (props.lineScaleSpringParams !== undefined)
    playerRef.value?.setLineScaleSpringParams(props.lineScaleSpringParams);
});

// 暴露给父组件
defineExpose<LyricPlayerRef>({
  lyricPlayer: playerRef,
  wrapperEl: wrapperRef,
});
</script>

<template>
  <div ref="wrapper-ref">
    <Teleport v-if="bottomLineEl" :to="bottomLineEl">
      <slot name="bottom-line" />
    </Teleport>
  </div>
</template>

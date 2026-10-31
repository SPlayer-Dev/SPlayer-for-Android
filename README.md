# 🎵 SPlayer for Android

<p align="center">
  <img src="https://img.shields.io/badge/version-3.0.0--rc.4-blue?style=flat-square" alt="version">
  <img src="https://img.shields.io/badge/platform-Android%2010%2B-3DDC84?style=flat-square&logo=android&logoColor=white" alt="platform">
  <img src="https://img.shields.io/badge/license-AGPL--3.0-red?style=flat-square" alt="license">
  <img src="https://img.shields.io/badge/Vue-3-4FC08D?style=flat-square&logo=vue.js&logoColor=white" alt="vue">
  <img src="https://img.shields.io/badge/Capacitor-8-119EFF?style=flat-square&logo=capacitor&logoColor=white" alt="capacitor">
  <img src="https://img.shields.io/badge/TypeScript-5-3178C6?style=flat-square&logo=typescript&logoColor=white" alt="typescript">
</p>

> 基于 [**SPlayer**](https://github.com/imsyy/SPlayer) 移植与重做的 Android 音乐播放器。保留原版精华，针对手机与平板彻底重构交互——**一个能装进口袋的 SPlayer** 🎶

> 致一直关注 SPlayer for Android 的大家：这段时间开发者的身体偶有不适，心理状态也出现了一些问题，很抱歉因此推迟了这么久才更新。感谢大家的理解与耐心，我会尽快调整和恢复自己的身体与心理状态，也会在状态允许时继续维护项目。

---

## 📸 界面预览

<table>
<tr>
  <td align="center"><b>📱 手机</b></td>
  <td align="center"><b>💻 平板</b></td>
</tr>
<tr>
  <td><img src="./screenshots/手机主界面.jpg" width="260"></td>
  <td><img src="./screenshots/平板主界面.jpg" width="400"></td>
</tr>
<tr>
  <td><img src="./screenshots/手机播放页面1.jpg" width="260"></td>
  <td><img src="./screenshots/平板播放界面.jpg" width="400"></td>
</tr>
<tr>
  <td><img src="./screenshots/手机播放页面2.jpg" width="260"></td>
  <td></td>
</tr>
</table>

---

## ✨ 特性一览

| 分类               | 描述                                                                                                                      |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------- |
| 🎨 **双端 UI**     | 手机 / 平板自适应布局，沉浸式全屏，状态栏可切换                                                                           |
| 📐 **手机端布局**  | 全宽贴底底栏 + 圆角浮岛播放栏，底栏选中态滑动指示器；列表 / 卡片 / 设置项轻度紧凑                                         |
| 🔍 **页面缩放**    | 50%–150% 全局缩放，缩小后等效视口变宽，达到阈值可切换 Pad 布局（尚未完全适配）                                            |
| 🎵 **播放引擎**    | 原生 ExoPlayer + WebView 双引擎，长时间后台稳定，seek / gapless 切歌进度条同步                                            |
| 📝 **逐字歌词**    | 毫秒级插值高亮，翻译 / 罗马音，拖动吸附最近行，支持全局歌词偏移                                                           |
| 📂 **本地音乐**    | [系统音乐库自动扫描与目录补充](docs/android-local-music.md)，支持 WhatsApp 音频过滤、自定义排除目录及 TTML / LRC 歌词匹配 |
| ☁️ **WebDAV 音乐** | 通过 WebDAV 连接远程音乐，在线播放与浏览，扩展私人曲库                                                                    |
| 🪟 **桌面歌词**    | `WindowManager` 悬浮窗，逐字动画、锁定穿透、拖拽、播控                                                                    |
| 🔔 **通知栏**      | 原生 `MediaSession`，完整播控，支持桌面歌词一键开关                                                                       |
| 🎚️ **精细控制**    | 渐入渐出、进度吸附歌词、允许与其他应用同时播放                                                                            |
| 🌐 **在线音乐**    | 网易云 + Jellyfin / Navidrome / Emby / Subsonic / OpenSubsonic / Last.fm                                                  |
| 🔗 **网络代理**    | 支持配置 HTTP/HTTPS 代理，内置逆向 API 请求可走代理访问网易云接口                                                         |
| ⬇️ **音乐下载**    | 开发者模式下可下载歌曲至 SAF 授权目录，支持自定义子目录分类、歌词/ASS 附件下载                                            |
| 🧩 **内置 API**    | `nodejs-mobile-cordova` 嵌入网易云 API，离线可用                                                                          |
| 📦 **分架构打包**  | `arm64-v8a` / `armeabi-v7a` / `x86_64` / `x86` 独立 APK                                                                   |

---

## 📱 平台兼容性

**最低要求：Android 10（API 29）**

> ⚠️ 更早的 Android 版本（Android 9 及以下）没有计划去实现支持。原因是：
>
> - 旧版 Android 性能不足以流畅运行本应用的 WebView 渲染和音频处理
> - Android 10 以下可用的系统 API 较少，无法实现 SAF 文件访问、MediaStyle 通知、深色模式适配等核心功能
> - 这些版本的设备硬件早已过时，完全不适合运行本应用

---

## 📦 下载与安装

前往 [**Releases**](../../releases) 选择对应 CPU 架构的 APK：

|       ABI       | 适用设备                       |   推荐度   |
| :-------------: | ------------------------------ | :--------: |
| **`arm64-v8a`** | 绝大多数现代手机 / 平板        | ⭐⭐⭐⭐⭐ |
|  `armeabi-v7a`  | 2015 年前的老旧 32 位 ARM 设备 |    ⭐⭐    |
|    `x86_64`     | Intel 平板、Android 模拟器     |     ⭐     |
|      `x86`      | 极少数 32 位 Intel 设备        |     ⭐     |

> 💡 不清楚自己设备架构？装个 **CPU-Z**，或者无脑选 `arm64-v8a`——99% 都是它。

---

## 🆕 更新日志

> 完整更新记录请查看 [**CHANGELOG.md**](./CHANGELOG.md)

**v3.0.0-rc.4** 主要更新：

- 🎴 新增 **移动端卡片式播放器** — 支持动画过渡与手势交互（上滑展开/下滑收起）
- 🎛️ 新增 **播放器快捷操作菜单** — 一键切换音质、收藏、下载等常用操作
- 📱 新增 **手机横屏沉浸式播放模式** — 全屏封面 + 歌词，无干扰聆听
- 🖼️ 新增 **页面磨砂效果** — 部分界面背景高斯模糊，视觉层次更丰富
- 🎵 新增 **本地歌曲封面提取** — 自动读取内嵌封面
- 🎧 新增 **音频频谱可视化** — 移动端播放页底栏频谱柱状图
- ⚡ 优化 **冷启动速度** 与渲染性能
- ⬅️ 完善 **Android 全局返回键处理** — 弹窗/菜单统一返回拦截，退出清理后台服务
- ↔️ 重构 **页面缩放逻辑** — 适配多设备缩放场景
- 🎯 修复 AMLL 歌词对齐、进度条跳变、播放状态同步等问题
- 📱 移动端 / 平板端布局全面优化
- 🗂️ 重新整理 **Android 端设置分组** — Android 专用项独立为「Android 系统设置」
- 💾 新增 **设置导入/导出** — 支持 JSON 配置文件备份与迁移

**v3.0.0-rc.3** 主要更新：

- ☁️ 新增 **WebDAV 音乐源** — 支持通过 WebDAV 连接并播放远程音乐
- 🎵 新增 **本地歌曲** — 扫描本地音乐文件，与在线曲库统一管理
- 🔍 新增 **页面缩放** — 50%–150% 全局缩放（尚未完全适配，欢迎 PR 帮忙完善；没有 PR 的话也尽量在下个版本彻底做好）
- 📂 新增本地 TTML/LRC 歌词支持（歌词目录扫描 + 同目录查找）
- 🔗 新增网络代理设置（HTTP/HTTPS 代理用于内置 API 请求）
- ⬇️ 新增音乐下载（开发者模式 + SAF 目录选择）
- 🌐 完善流媒体支持（Navidrome / Jellyfin / Emby / Subsonic / OpenSubsonic）
- 📐 重做手机端底栏 / 播放栏布局（全宽贴底底栏 + 圆角浮岛播放栏 + 滑动指示器）
- 🎯 手机端整体轻度紧凑约 -15%（平板保持原样）
- 🔧 修复显示系统状态栏滑动后自动隐藏的问题
- 🔧 修复允许与其他应用同时播放无效的问题
- 🔧 修复 Android 触控跳转异常
- 🔧 修复跳转后 seek 位置丢失回到 0:00
- 🔧 修复自动切下一首进度条跳到非 0:00 错误位置
- 🎚️ 新增全局歌词偏移设置（PR #11）
- 📱 最低兼容性调整为 Android 10

---

## 🚀 快速开始

### 环境要求

| 工具              | 版本    |
| ----------------- | ------- |
| Node.js           | `>= 20` |
| pnpm              | `>= 10` |
| JDK               | `21`    |
| Android SDK / NDK | 最新    |

### 一键构建

```bash
pnpm install
pnpm build:android
cd android && ./gradlew assembleDebug
```

产物：`android/app/build/outputs/apk/debug/`（按 ABI 分包）

### 分步构建

```bash
pnpm build:web                  # 前端 Vite 构建
pnpm build:android:node         # 内置 Node API Bundle
pnpm prepare:android:embedded   # 准备嵌入资源
npx cap sync android            # 同步 Capacitor 工程
npx cap open android            # Android Studio 打开
```

---

## ❓ FAQ

<details>
<summary><b>🔑 桌面歌词打不开，提示需要权限？</b></summary>

<br>

需要授予 **SYSTEM_ALERT_WINDOW（显示在其他应用上层）** 权限。首次启用会自动引导，如果拒绝过，可以去 **系统设置 → 应用 → SPlayer → 权限** 里手动开启。

</details>

<details>
<summary><b>🎧 想和其他应用同时播放？</b></summary>

<br>

进入 **设置 → 播放 → Android 系统设置 → 允许与其他应用同时播放**。

> ⚠️ 注意：开启后将切换为 ExoPlayer 引擎（handleAudioFocus=false），不再抢占音频焦点，可与其他应用同时播放。

</details>

<details>
<summary><b>📦 我该下载哪个 APK？</b></summary>

<br>

无脑选 **`arm64-v8a`**。只有极老的设备（通常 2015 年前）才需要 `armeabi-v7a`。`x86` / `x86_64` 几乎只用于模拟器。

</details>

<details>
<summary><b>🖥️ 能在电脑上运行吗？</b></summary>

<br>

本仓库已剥离 Electron 桌面端打包链路，**仅保留 Android 构建**。需要桌面版请前往 [原版 SPlayer](https://github.com/imsyy/SPlayer)。

</details>

<details>
<summary><b>🔕 通知栏控制器不显示？</b></summary>

<br>

1. **设置 → 播放** 里打开 **"通知栏音乐控制器"**
2. 系统设置里允许 SPlayer 发送通知（Android 13+ 首次启用会弹权限框）

</details>

<details>
<summary><b>🌙 支持后台播放吗？</b></summary>

<br>

支持。通知栏控制器启用后，应用进入后台仍会继续播放，不会被系统快速回收。

</details>

<details>
<summary><b>📂 本地歌词不显示？</b></summary>

<br>

1. 进入 **设置 → 本地与缓存 → 本地歌词覆盖在线歌词**，添加歌词所在目录
2. 歌词文件可按 `歌曲ID.ttml` / `歌曲ID.lrc` 或 `歌名.歌曲ID.ttml` / `歌名.歌曲ID.lrc` 命名
3. TTML 文件内含 `ncmMusicId` 元数据的也可自动匹配
4. 放在音乐同目录下、与音频同名的歌词文件（如 `歌曲名.lrc`）也会自动识别

</details>

<details>
<summary><b>⬇️ 如何下载歌曲？</b></summary>

<br>

1. 进入 **设置 → 常规**，连续点击版本号 5 次开启开发者模式
2. 在 **设置 → 本地与缓存 → 下载配置** 中选择下载目录（SAF 授权）
3. 在歌曲列表中点击菜单即可看到下载选项

</details>

---

## 💬 交流 & 反馈

<table>
<tr>
  <td width="260" align="center">
    <img src="./screenshots/QQ群二维码.jpg" width="220">
  </td>
  <td valign="middle">
    <b>SPlayer for Android 交流群</b><br>
    <br>
    群号：<code>602750631</code><br>
    一键加群：<a href="https://qm.qq.com/q/AjIqKftqgM">https://qm.qq.com/q/AjIqKftqgM</a><br>
    <br>
    群内可讨论使用问题、交流经验，也欢迎反馈 Bug。<br>
    <b>⚠️ Bug 反馈仍建议优先</b> <a href="../../issues">提交 Issue</a>，便于跟踪与修复。
  </td>
</tr>
</table>

---

## 🤖 CI / 发布

手动触发 [`Android Release`](./.github/workflows/android-release.yml) 工作流即可分架构构建 & 发布 APK。

签名所需 Secrets：

| Secret                      | 说明                    |
| --------------------------- | ----------------------- |
| `ANDROID_KEYSTORE_BASE64`   | Keystore 的 Base64 编码 |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore 密码           |
| `ANDROID_KEY_ALIAS`         | Key 别名                |
| `ANDROID_KEY_PASSWORD`      | Key 密码                |

详细说明见 [`.github/ANDROID_RELEASE_SECRETS.md`](./.github/ANDROID_RELEASE_SECRETS.md)。

---

## 🧑‍💻 参与贡献

欢迎提 Issue 与 PR！提交前请先阅读 [**CONTRIBUTING.md**](./CONTRIBUTING.md)，了解 Issue / PR / Commit 规范。

- 🐞 [提交 Bug](../../issues/new?template=bug.yml)
- ✨ [提交功能建议](../../issues/new?template=feature.yml)
- 📮 [发起 Pull Request](../../compare)

### 👥 贡献者

感谢每一位为本项目付出时间与代码的伙伴 ❤️

<a href="https://github.com/SPlayer-Dev/SPlayer-for-Android/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=SPlayer-Dev/SPlayer-for-Android" alt="contributors">
</a>

> 图片由 [contrib.rocks](https://contrib.rocks) 自动生成，随 GitHub 贡献图谱更新。

---

## 🤝 致谢

本项目基于 [**SPlayer**](https://github.com/imsyy/SPlayer) 移植，向原作者 [@imsyy](https://github.com/imsyy) 与所有贡献者致以最诚挚的感谢 ❤️

---

## 📄 许可证

[**AGPL-3.0**](./LICENSE) —— 与上游保持一致

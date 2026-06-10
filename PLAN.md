# QML 音乐播放器 — 实施计划

SPlayer 风格音乐播放器：qml4j (纯 Java QML 引擎) 渲染 UI，复用 Haedus 的网易云 client + 歌词解析器，
播放器核心独立 Maven 模块，Android shell 用 Gradle (termux 原生 aapt2/d8 覆盖)。

## 决策 (已确认)

- **功能范围**：全功能 SPlayer 风格 (PLAN_NETEASE.md 全部页面)。
- **引擎来源**：Maven Central `io.github.timer-err:qml4j-core:0.1.1`。
- **Android 构建**：保留 Gradle 结构，AGP 用 termux 原生 `aapt2` 覆盖 + 纯 Java d8 (r8 jar)。

## 关键约束 (调研结论)

1. **qml4j UI 是 QML，Haedus UI 是 Skija 直绘 Java** → Haedus 的 UI 层 (`ui/`, `render/`) **不可复用**，
   全部 UI 用 md3.Core QML 组件重写。
2. **可 100% 复用的纯 Java 逻辑** (两端通用，Android 兼容)：
   - `netease/NeteaseClient` — 纯 `HttpURLConnection`，无 Java 11 HttpClient。
   - `netease/NeteaseCrypto` — `javax.crypto`/`java.security`/`Base64`，API 26 全有。
   - `netease/dto/*` + Gson。
   - `lyric/*` — LRC/ESLRC/LYS/QRC/TTML/YRC 全部 parser + LyricLine + Syllable，纯逻辑。
3. **音频引擎必须抽象**：Haedus 的 `ImplMusicAPI` 用 `javax.sound.sampled.SourceDataLine`，
   **Android 没有 javax.sound**。→ 定义 `AudioBackend` 接口：
   - `DesktopAudioBackend` (javax.sound + jaudiotagger + JLayer/jflac SPI) — 在 desktop-host。
   - `AndroidAudioBackend` (`android.media.MediaPlayer`，原生支持 http url + mp3/flac/m4a) — 在 android-shell。
4. **桥接机制**：`QmlView.context("player", controller)` 把 Java 对象暴露为 JS 全局；
   QML 通过 getter/method 访问，公开的 `io.github.timer_err.qml4j.engine.binding.Property<T>` 字段
   提供响应式绑定 (读取注册依赖，变更触发重算)。渲染器单线程：音频线程的状态更新经每帧 drain 队列
   刷进 Property，position() 由 QML Timer (~250ms) 轮询。
5. **存储路径可配**：Haedus 写死 `~/.haedus/`；改为可注入 baseDir，桌面 `~/.musicplayer/`，
   Android 用 `context.getFilesDir()`。
6. **Java 8 字节码**：qml4j-core 是 `--release 8`；player-core 也编 `--release 8` 保证可 dex。

## 目标结构

```
musicplayer/
├── qml4j/                  已克隆 (参考；artifact 走 Maven Central)
├── Haedus/                 已克隆 (复用 Java 源码的来源)
├── pom.xml                 新建 父 POM (player-core + desktop-host)
├── player-core/            新建 Maven 模块 — 引擎无关的播放逻辑 + QML UI 资源
│   ├── pom.xml             依赖 qml4j-core:0.1.1 (skija provided)、gson、jaudiotagger 等
│   ├── src/main/java/com/musicplayer/
│   │   ├── netease/        NeteaseClient/Crypto + dto/  (从 Haedus 复制，去 mc 依赖)
│   │   ├── lyric/          全部 parser + LyricLine + Syllable  (逐字复制)
│   │   ├── audio/          AudioBackend 接口 + 解码/库扫描公共逻辑
│   │   ├── library/        LibraryScanner
│   │   ├── model/          Track (统一 TrackMetadata)
│   │   ├── store/          cookie / recent / config 持久化 (baseDir 可注入)
│   │   └── bridge/         PlayerController — Property<T> 字段 + 动作方法，QML 门面
│   └── src/main/resources/qml/
│       ├── Main.qml        ApplicationWindow + sidebar+page+miniplayer 布局
│       ├── md3/Core/        (来自 shared-qml/md3/Core，构建期拷入)
│       ├── fonts/
│       ├── components/     Sidebar, MiniPlayer, Card, SongRow, GreetingHeader,
│       │                    LoginDialog(二维码), LyricView(逐字滚动)
│       └── pages/          Home, Search, PlaylistDetail, LikedSongs, Favorites, Recent, Local
├── desktop-host/           新建 Maven 模块 — LWJGL/GLFW host (仿 qml4j-demo-desktop)
│   └── DesktopMain         建 QmlView、注入 PlayerController(DesktopAudioBackend)、渲染循环
└── android-shell/          新建 Gradle 工程 — 改自 qml4j/android-shell
    └── app/src/main/
        ├── java/.../MainActivity.java   加载 Main.qml、注入 PlayerController(AndroidAudioBackend)
        ├── assets/qml/                  player-core 的 qml/ 树 (构建期同步)
        └── (Skija android arm64 .so 抽取沿用 qml4j shell 的 build.gradle.kts)
```

## 桥接：PlayerController API 草案

```java
public final class PlayerController {
    // 响应式状态 (QML 绑定，渲染线程刷新)
    public final Property<Boolean> playing   = ...;
    public final Property<String>  title     = ...;
    public final Property<String>  artist    = ...;
    public final Property<Long>    durationMs= ...;
    public final Property<Boolean> loggedIn  = ...;
    public final Property<String>  userName  = ...;
    // 动作 (QML 事件处理器调用)
    public void toggle(); public void next(); public void prev();
    public void seek(long ms); public void setVolume(float v);
    public long position();                 // QML Timer 轮询
    public void search(String kw);          // 结果写入一个 ListModel-friendly 结构
    public void playNetease(long songId);
    public String qrLoginContent();         // 登录对话框
    public int    qrLoginCheck();           // 803=成功
    public Object[] currentLyrics();        // LyricLine[] 给 LyricView
}
```

## 实施顺序 (每步可独立验证)

1. **脚手架** — 父 POM + player-core POM + desktop-host POM；`mvn -version` / 拉取 qml4j-core:0.1.1。
2. **复用层移植** — 复制 `netease/*` + `lyric/*` 到 player-core，改包名 `com.musicplayer.*`，去掉 mc Logger 等依赖，
   `mvn compile` 通过。
3. **音频抽象 + 桌面后端** — `AudioBackend` 接口；`DesktopAudioBackend` 从 ImplMusicAPI 适配；LibraryScanner。
4. **PlayerController** — Property 桥 + 动作 + 每帧 drain；本地播放端到端 (无 UI 用单测/CLI 验证)。
5. **桌面 host + 最小 QML** — DesktopMain 起 QmlView，Main.qml 出 MiniPlayer + 本地列表，桌面可见可播。
6. **网易云接入** — 搜索/播放 url/歌词 串到 controller；SearchPage + 流式播放跑通。
7. **二维码登录** — LoginDialog (base64 PNG → Image，800ms 轮询 803) + cookie 持久化。
8. **SPlayer 主界面** — Sidebar(6 项) + HomePage(推荐/私人FM/歌单网格) + PlaylistDetail + Liked/Favorites/Recent。
9. **LyricView** — 逐字 (YRC/QRC syllable) 滚动歌词页，slide-up 动画。
10. **Android 后端** — AndroidAudioBackend (MediaPlayer)；store baseDir → filesDir。
11. **Android shell 构建** — 改 android-shell 指向我们的 qml；gradle.properties 加
    `android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2`；gradlew 拉 gradle 8.7；出 APK。
12. **打磨** — config (playLevel / enableNetease)、recent LRU、灰色歌曲标灰、收尾。

## 风险

- **AGP on aarch64 termux**：aapt2 用 termux 覆盖；d8 走 r8 jar (纯 Java) 应可；JDK 21 工具链 OK。
  若 AGP 仍卡，退路是手写 aapt2+d8+apksigner 脚本 (步骤 11 内决定)。
- **Skija-on-Android JNI 脆弱** (qml4j 自述)：android-shell 是 frozen reference，可能要逐个绕 native crash。
- **weapi 协议变动**：网易偶尔改 RSA mod/endpoint，跟随上游修。
- **MediaPlayer vs seek 精度**：Android 端 position 精度不如桌面 SourceDataLine，可接受。
```

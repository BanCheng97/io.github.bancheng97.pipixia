# 皮皮虾净化

皮皮虾（`com.sup.android.superb`）的 LSPosed 增强模块：去广告、视频无水印、自动送神评、信息流净化。

- 模块包名：`io.github.bancheng97.pipixia`
- 作者：半城
- 当前版本：**6.2**（versionCode 33）
- 适配：皮皮虾 **6.6.1** / Android 12 / arm64-v8a / LSPosed 2.2.1（API 102）
- 许可：GPL-3.0

---

## 功能

设置界面按母类分组，每个子项可单独开关。

### 去广告

| 开关 | 作用 |
|---|---|
| 自研广告（m_ad） | 皮皮虾自家广告体系（开屏、信息流、评论区广告都在这），拦 `load/show/request` 并回调 `onNoAd` |
| 第三方广告 SDK | 穿山甲 `com.bytedance.sdk.openadsdk`、优量汇 `com.qq.e`、快手 `com.kwad` 的入口方法 |
| 开屏广告 | 开屏策略类（`m_ad.multi_splash_ads.*`）的布尔判定全部打 false |
| 弹窗 / 插屏 / 浮层 | 名字带 ad 的 `show/open/jump` 方法直接返回 |
| 广告开关 | `isShowAd` / `hasAd` / `canShowAd` 一类布尔 getter 一律 false |

### 去水印 / 保存

| 开关 | 作用 |
|---|---|
| 视频无水印 | 走皮皮虾签名播放接口取无水印地址，写回 `VideoModel.getUrlList()`，**播放和保存到相册都是干净链** |
| 图片保存原图 | `NewGalleryActivity#onDownload` 把地址换成 pstatp 原图 CDN 再下载 |

### 自动

| 开关 | 作用 |
|---|---|
| 自动送神评 | 评论区右侧「送神评」按钮一出现就 `performClick`，带最小间隔与单次上限 |
| 最小间隔 / 单次上限 | 默认 1500ms / 30 次，防止连点失控 |

### 内容净化

| 开关 | 作用 |
|---|---|
| 信息流净化 | 总开关，挂在 feed 响应处理上 |
| 去直播卡 | 移除 `LiveSaasFeedCell` |
| 去带货卡 | `getPromotionInfo()` 非空即移除 |
| 去官方号 | 认证信息含「官方账号 / 视频号 / 新媒体」 |
| 屏蔽词 | 标题内容命中即移除，逗号分隔 |

### 杂项

| 开关 | 作用 |
|---|---|
| 去红点 | 底部 tab、消息角标、我的页 badge 全部清零 |
| 禁止自动更新 | `UpdateService#checkUpdateByAutomatic` 直接返回 |
| 倍速 | 正常速按配置提速，1.0 = 不改 |

### 日志（落盘）

| 开关 | 作用 |
|---|---|
| `logs` **总开关**（默认关） | 关掉 = 一个文件都不写，功能照常 |
| 运行状态 | `pipixia_status.txt`：拦截广告 / 摘广告 / 送神评 / 无水印成功等计数 |
| 崩溃记录 | `pipixia_crash.txt`：未捕获异常 + 栈 |
| 类名侦察 | `pipixia_hooks.txt`：挂钩点自检 + dex 候选类名 |
| 视频地址 / 类名导出 | `pipixia_urls.txt` / `pipixia_classes.txt` |

日志写在模块数据目录 `/data/data/io.github.bancheng97.pipixia/files/logs/`，**卸载模块即清空**。
设置界面「日志」按钮可查看 / 清空 / 导出（导出才会写一份到 `Download/DSH-Go/`）。

---

## 原理

皮皮虾是字节系，业务类名（`com.sup.android.*`）基本不混淆，混淆的只有零星实现类。所以主力靠**真名挂钩**，辅以两类辅助手段。

### 1. 无水印：签名播放接口

皮皮虾的视频是抖音 CDN 上的对象，带不带水印取决于请求哪个接口：

```
GET https://i.snssdk.com/video/play/1/bds/{ts}/{sign}/mp4/{uri}
sign = md5("ts" + ts + "userbdsversion1video" + uri + "vtypemp4f425df23905d4ee38685e276072faa0c")
```

返回的 `video_info.data.video_list.video_5..video_0.main_url` 是 Base64 编码的**无水印直链**，
取最高画质解出来，写回模型里的地址列表，播放和保存就都干净了。

- `uri` 来自 `VideoModel.getUri()`（`v0300…` 这种）；
- 为空时用 `item_id` 换：`https://h5.pipix.com/bds/webapi/item/detail/?item_id=` → `data.item.origin_video_id`；
- 挂钩点：`com.sup.android.video.VideoDownloadHelper#doDownload(...)`（保存）和
  `com.sup.android.base.model.VideoModel#getUrlList()`（播放），结果按 uri 缓存；
- 下载类被混淆成 `com.sup.android.video.g`，靠内部类 `VideoDownloadHelper$xxx` 的 `getEnclosingClass()` 反推。

### 2. 广告：皮皮虾自研 m_ad

6.6.1 的广告主体不在穿山甲公开类里，而是自家包：

- `com.sup.android.superb.m_ad.bean.InStreamAdResponse#setInStreamAdList(List)` → 参数换成空列表
- `com.sup.android.superb.m_ad.bean.CommentAdResponseData#setCommentAdList(List)` → 同上
- `com.sup.android.superb.m_ad.multi_splash_ads.*` → 布尔判定打 false
- `com.sup.android.mi.feed.repo.bean.ad.*` → `getAdInfo/getBannerData` 置空、`isXxxAd` 打 false

### 3. 类名发现：dex 枚举，不碰 ClassLoader

启动期只按已知类名 `findClassIfExists` 精确挂，**绝不在启动期 hook `ClassLoader.loadClass`**（那会把主线程拖死）。
需要找混淆类时用 `pathList.dexElements[].dexFile.entries()` 只读枚举 dex（10.6 万个类名，不加载），
按包前缀 + 字段/方法特征定位，例如开屏类 = `com.sup.android.superb.m_ad.initializer` 下带 `CopyOnWriteArraySet` 字段的类。

### 4. 入口：改数据列表，不塞 View

「我的 → 更多功能」里的入口沿用 [皮皮虾助手](https://github.com/Secack/ppx) 的思路：
hook 生成 tab 列表的方法（`com.sup.android.m_mine.utils` 下、带 `Context`/`String` 字段、无参返回 `ArrayList`），
往列表里塞一个克隆的 `MyTabItem`，`tabSchema = sdk://halfcity.settings`，再按 schema 认领点击。
界面注入那种做法一换版本就废，改数据才稳。

---

## 安装

1. 装 `pipixia-adfree-v6.2-release.apk`；
2. LSPosed 管理器 → 模块 → 启用「皮皮虾净化」→ 作用域勾选 **皮皮虾**；
3. 设置 → 应用 → 皮皮虾 → **强行停止**，再打开（模块只在进程启动时注入）。

入口：**我的 → 更多功能 → 净化设置**（橙色「净」字格子）。

> 6.0 换了包名（`io.github.bancheng97.pipixia`），旧包 `com.halfcity.pipixia` 请先卸载，
> LSPosed 里也要重新勾作用域。皮皮虾本身的数据不受影响。

---

## 设置与优先级

- 设置界面（模块内）：每个功能一个开关，改完即生效（下次进程启动读取）。
- 配置文件：`/sdcard/Android/data/com.sup.android.superb/files/pipixia_adfree.conf`（`key=value`，可写 `force` / `skip` / `extra_prefix`）。
- 优先级：**设置界面（ContentProvider）> 配置文件 > 代码默认值**。

跨进程读取：皮皮虾进程和模块不是同一 uid，模块 App 通过 `PrefsProvider`
（`content://io.github.bancheng97.pipixia.prefs`）暴露键值，皮皮虾进程 `ContentResolver.call(..., "all")` 一次拉全。

---

## 版本适配验证

设置界面顶部有「版本适配验证」，列出：皮皮虾版本、dex 类总数、`VideoModel#getUrlList` 是否挂上、
`VideoDownloadHelper` 是否找到、自研广告类数/挂载数、开屏类命中数、上次运行计数，并给一句结论。

这些数据由皮皮虾进程自检后通过 provider 回填，所以**先打开一次皮皮虾再回来看**；
需要 `logs` + `discover` 打开才有值。

---

## 已知边界

- **片源水印**：如果视频像素里本身烧了 logo（非播放器浮层、非地址参数），客户端改不动；
  签名接口能拿到的是抖音原片，原片带水印的那种只能换源，模块不提供危险开关。
- **自绘运营位**：不带 ad 标记的活动位/推广位拦不到。
- **未适配**：直播卡依赖 `LiveSaasFeedCell`，版本改名会失效；送神评依赖按钮文字/描述，纯图标版本会漏。
- **安全模式**：`safe=true` 只注入不装规则，用于排查闪退。

---

## 版本历史

| 版本 | 关键变化 |
|---|---|
| 1.0 | 首版：启动期 hook `ClassLoader` 做规则扫描 —— 白屏卡死 |
| 1.2 | 改成 UI 起来后懒扫描 + 180 秒自动摘钩子；启动期只挂已知 SDK 类 |
| 1.3 | 加自动送神评（UI 点击）与 `playwm→play` 去水印 —— 后者在皮皮虾上无效 |
| 1.4 | 加地址取证 `pipixia_urls.txt`，确认水印来源 |
| 2.0 | 换签名播放接口，真去水印；真名挂钩 `VideoDownloadHelper`、`VideoModel` |
| 2.1 | 修 `getUrlList` 自我递归导致的滑动卡死；配置版本迁移 |
| 3.0 | 扩展功能：去红点、禁更新、倍速、信息流净化、图片原图 |
| 3.1–3.3 | 崩溃栈落盘、参数类型校验、状态文件（计数自证效果） |
| 4.0–4.2 | 设置界面（母类/子项/计数/批量开关）、版本适配验证 |
| 4.3–4.7 | 隐藏桌面图标、入口注入（我的 → 更多功能）、按文字认领点击 |
| 5.0 | 精简：删掉 playwm 方案、换源、长按返回、扩展总开关 |
| 5.1–5.2 | 状态文件只主进程写、内容不变不写；崩溃/侦察独立开关 |
| 5.3–5.4 | 关掉开关即删旧文件；日志总开关（默认关） |
| 5.5 | 日志搬进模块数据目录，卸载即清 |
| 6.0 | 包名改 `io.github.bancheng97.pipixia`，上架 LSPosed 模块仓库 |
| 6.1 | 修换包名后「更多功能 → 净化设置」拉起失败（`SETTINGS_PKG` 还指旧包名） |
| 6.2 | 清单里 activity/provider 改成全限定类名（`.SettingsActivity` 会被拼成 `io.github…` 前缀，类找不到） |

---

## 构建

不依赖 gradle / Android Studio，本机（arm64 Ubuntu）直接出包：

```bash
./build_local.sh
# javac → d8 → aapt → apksigner
# 产物：build/pipixia-adfree-6.2.apk（已签名 v1+v2）
```

依赖：`openjdk-17`、`aapt`、`/root/android/{android.jar,d8.jar,apksigner.jar}`、Xposed API 82（`api-82.jar`）。
工程本身是标准 AGP 结构，也可以用 `./gradlew assembleRelease`（需自行补 gradle wrapper）。

---

## 许可与致谢

GPL-3.0。签名播放接口与入口注入的思路参考了 [皮皮虾助手 Secack/ppx](https://github.com/Secack/ppx)（GPL-3.0），
实现为 Java 重写，非直接移植。

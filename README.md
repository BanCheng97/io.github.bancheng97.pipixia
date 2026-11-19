# 皮皮虾净化 · 皮皮虾去广告模块（半城）

- 作者：半城　版本 5.2
- 模块包名：`io.github.bancheng97.pipixia`
- 目标：`com.sup.android.superb`（皮皮虾，截图那台是 **6.6.1**，红魔 7S Pro / Android 12）
- 产物：`build/pipixia-adfree-5.2.apk`（已签名 v1+v2，可直装；桌面也会出现图标）

日志里搜 `PXXHook`，第一行是 `已注入`，后面跟 `SDK 入口类命中 N/22` —— 这两行都有就说明模块正常干活了。

> **1.1 白屏的原因（1.2 已修）**：1.1 在启动期就 hook 了 `ClassLoader.loadClass`。
> App 启动要连加载上万个类，每个类都进一次 Java hook，主线程被拖死；Xposed 挂方法和 ART 定义类抢同一套锁，还容易死锁。
> 现象就是日志停在第四行、界面全白、进程不退出。1.2 改成：启动期只用**已知 SDK 公开类名**精确挂（`TTAdNative`/`TTSplashAd`/`SplashAD` 这些不混淆），
> 等 UI 起来之后才装 ClassLoader 钩子做懒扫描，并且 180 秒后自动摘钩子。

---

## 一八、5.5 日志搬进模块数据目录（卸载即清）

不再往 `/sdcard/Download/DSH-Go/` 丢文件了。日志现在写在这里：

```
/data/data/io.github.bancheng97.pipixia/files/logs/
  pipixia_status.txt
  pipixia_crash.txt
  pipixia_hooks.txt
  pipixia_urls.txt
  pipixia_classes.txt
```

**卸载模块 = 日志一起消失**，不会在手机上留垃圾。

跨进程怎么写的：日志由皮皮虾进程产生，目标目录属于模块 App，两个 uid 不能互写。
所以皮皮虾进程 `call("log", name, {text})` → `PrefsProvider` 在模块进程里落盘。
provider 挂了就退到皮皮虾自己的私有目录（卸载皮皮虾也会清）。

设置界面顶部多了「日志」按钮：

- **查看**：直接弹出五个文件的内容（各截最近 32KB）；
- **清空**：删掉全部；
- **导出**：只有你点这个，才会通过 MediaStore 写一份到 `Download/DSH-Go/`（给我排查用）。

总开关 `logs`（日志组，默认 false）依旧是硬开关：关着就一个字节都不写。

## 一七、5.4 日志总开关（默认关，一个文件都不写）

设置界面多了「日志」母类：

| 开关 | 作用 |
|---|---|
| `logs` **总开关（默认 false）** | 关掉 = `pipixia_status / crash / hooks / urls / classes` 五个文件一个都不写，启动时还会把旧的删掉 |
| `status_file` | 运行状态（`logs=true` 才生效） |
| `crash_file` | 崩溃记录 |
| `discover` | 类名侦察 / 钩子自检 |
| `dump_urls` | 视频地址 |
| `dump_classes` | 类名导出 |

**日志跟功能没有任何关系**：拦截广告、去水印、送神评、净化这些全靠 hook 干活，
写不写日志都不影响。日志只是把「拦截了几次、无水印成功几次」记下来，给我排查用。
关掉之后最多少一份体检报告，功能一点不少。

## 一六、5.2 三个落盘开关都能关

「调试」组里现在三项独立开关，全部默认开，关掉就完全不产生对应文件：

| 开关 | 对应文件 | 关了之后 |
|---|---|---|
| `status_file` | `pipixia_status.txt` | 不写运行状态，计数不落盘 |
| `crash_file` | `pipixia_crash.txt` | 崩溃只进 Xposed 日志，不写文件 |
| `discover` | `pipixia_hooks.txt` | 不跑类名侦察、不写钩子自检（设置界面的「版本适配验证」会显示 —） |

## 一五、5.1 状态文件不再每次刷

`pipixia_status.txt` 之前一打开皮皮虾就出现/被重写，原因两条：

1. `Engine.loop()` 每 10 秒无条件调 `writeStatus()`，内容没变也整份重写，mtime 一直动；
2. 皮皮虾有 4 个进程（主进程 + push + 子服务），**每个进程都注入、都写同一份文件**，
   互相覆盖 —— `pipixia_crash.txt` 里同一秒 4 条「启动」就是证据。

5.1 的修法：

- 只 **主进程**（进程名 == `com.sup.android.superb`）写状态和启动记录，子进程不写；
- 内容没变就不写（对比上次文本，相同直接返回）；
- 新增开关 `status_file`（调试组，默认 true），关掉就完全不产生这个文件。

## 一四、5.0 精简 + 母类/子项分类

**删掉的（实测没用或会误导）**：

| 删掉的东西 | 原因 |
|---|---|
| `save_nowm`（playwm → play） | 皮皮虾的地址不是抖音那套，改参数毫无作用，整段代码已删除 |
| `force_clean`（按 video_id 换源） | 不是抖音来源的视频会 404，属于危险开关 |
| 长按返回键入口 `entry_back` | 你这台/手势导航下拿不到 repeat 事件，实测不灵 |
| 扩展功能总开关 `extras` | 每个功能有自己的开关，总开关只会让人以为没生效 |
| 信息流广告条目 `hook_feed`、业务层加载、隐藏浮层水印 | 实测命中 0 次，留着只是心理安慰（代码保留但不进界面） |

**界面改成 母类 → 子项**，每个母类右边显示 `已开/总数`（点「全开 / 全关」可整组切换）：

- **去广告**：自研广告（m_ad）· 第三方 SDK · 开屏 · 弹窗/插屏 · 广告开关
- **去水印**：视频无水印（签名接口）· 图片保存原图
- **自动**：自动送神评 · 最小间隔 · 单次上限
- **内容净化**：信息流净化 · 去直播 · 去带货 · 去官方号 · 屏蔽词
- **杂项**：去红点 · 禁自动更新 · 倍速
- **入口**：「我的 → 更多功能」里的净化设置
- **调试**：详细日志 · 安全模式 · 地址记录 · 类名导出

## 一三、4.7 点不动？加「按文字认领点击」

4.6 的格子出来了但点了没反应，原因大概率是点击没走我们 hook 的那个 `$1.doClick`
（内部类编号/方法名换版本会变）。4.7 加一层**不依赖类名**的兜底：

- hook 框架的 `View#performClick`，只要点到的 View 自己或它的直接子 View 文字等于「净化设置」，
  就 `startActivity` 拉起模块设置并把这次点击吞掉（`setResult(true)`）；
- 入口每走一步都写进 `pipixia_crash.txt`：`列表已挂 …`、`点击已挂 …`、`performClick 命中并打开设置`、
  `没找到 myTabList`、`没找到 tabView 类`、`退回胶囊方案` —— 点了没反应也能从文件看出断在哪。

## 一二、4.6 学皮皮虾助手：改列表，不是塞 View

4.5 往界面里 addView 的做法在这版没出来（页面结构不是我以为的那样）。扒了
[皮皮虾助手 Secack/ppx](https://github.com/Secack/ppx) 的 `InnerOpenHook` + `findMyTabList`，它是这么干的：

1. dex 里找 `com.sup.android.m_mine.utils` 下、**同时有 `Context` 和 `String` 字段**、
   且有个**无参返回 `ArrayList`** 的方法 —— 这就是「更多功能」的 tab 列表；
2. hook 它，往返回列表里塞一个 **`MyTabItem` 复制品**（反射无参构造 + 复制字段），然后
   `setTabName("净化设置")` / `setTabSchema("sdk://halfcity.settings")` / `setType(4)` /
   `setSchemaNeedLogin(false)` / `setExtra("{\"icon_list\":null,\"alert\":false}")`；
3. 再找 `com.sup.android.m_mine.view.subview` 下**带 `LinearLayout` 字段**、
   且有个静态方法 `(自己) → MyTabItem` 的类，hook 它内部类 `$1.doClick(View)`：
   取出 tabView 反查 MyTabItem，`getTabSchema()` 等于我们的就拉起模块设置并吞掉点击。

列表项是皮皮虾自己的 bean，渲染和点击都走它原有逻辑，我们只换数据 —— 这才是稳的做法。
克隆失败时退回「覆盖一个现成格子」；列表都拿不到才退回右上角胶囊。

## 一一、4.5 入口放进「更多功能」那一格

4.4 的悬浮胶囊能出（截图已确认），但位置浮在顶部不协调。4.5 改成**塞进「我的」页
「更多功能」网格里**，跟神评鉴定 / 应用中心 / 设置 排在一起：

- 找法：在「我的」页的视图树里找文字为「更多功能」的 TextView → 看它父容器里
  子 View ≥ 4 的那个 ViewGroup（就是那个图标网格）；
- 造一个自己的格子（橙色圆角「净」+ 下方「净化设置」），按父容器类型生成参数：
  `GridLayout` 用 `GridLayout.spec(UNDEFINED,1)`，`LinearLayout` 复制模板的 weight；
- 「我的」页是异步铺的，加 250ms × 6 次重试；实在注入不进去才退回右上角胶囊。

## 一〇、4.4 「我的」页入口（长按返回不灵之后的方案）

4.3 的「长按返回键」在有些机器/手势导航下拿不到 repeat 事件，实测不灵。4.4 改成**在「我的」页右上角叠一个橙色胶囊「净化设置」**：

- 不碰皮皮虾的列表/适配器（那种改法换版本就崩），而是在「我的」页所在 Activity 的 `android.R.id.content`
  （FrameLayout）上 `addView` 一个属于我们自己的 TextView 胶囊，点击 `startActivity` 拉起模块设置；
- 判断「我的」页：hook `androidx.fragment.app.Fragment#onResume / onViewCreated` 和 `Activity#onResume`，
  类名里含 `mine` / `profile` / `usercenter` 就加；同一个 Activity 只加一次（按 tag 去重）；
- 开关 `entry_mine`（默认开）。长按返回（`entry_back`）保留当备选。

## 〇九、4.3 隐藏桌面图标 + 从皮皮虾进入设置

- **桌面图标隐藏**：`SettingsActivity` 去掉了 MAIN/LAUNCHER intent-filter，桌面上不再出现图标
  （activity 仍 `exported=true`，跨 App 可拉起）；
- **两个入口（都在皮皮虾里）**：
  1. **长按返回键** ≈0.6 秒（`Activity#dispatchKeyEvent` 判 BACK + repeatCount ≥ 2，拉起后把这下返回吞掉，不会误退出）；
  2. **2.5 秒内连按 5 次返回**（兜底：手势导航下长按返回不一定给 repeat）；
- 对应开关 `entry_back`（默认开），关掉两个入口就一起失效。

## 〇八、4.1 / 4.2 界面 + 版本适配验证

- **4.1 美化**：深色底 + 圆角卡片 + 橙色强调、顶部总览、分组「全开」、底部路径说明；
- **4.1 修 bug**：总览卡（含「全部开启」）之前建好了但**没加进布局**，所以点了没反应 —— 已修，
  并且分组「全开」现在会弹 toast、再点一次变「全关」；
- **4.2 版本适配验证**：设置界面顶部多一块「版本适配验证」，列出
  皮皮虾版本、dex 类总数、`VideoModel#getUrlList`、`VideoDownloadHelper`、自研广告类数/挂载数、
  开屏类命中数、上次运行计数、自检时间，并给一句结论（关键挂钩点是否命中当前版本）。

数据是谁填的：皮皮虾进程里跑完自检后，通过 `PrefsProvider`（`content://io.github.bancheng97.pipixia.prefs`）
把结果 call 进模块 App 的 SharedPreferences，设置界面直接读——所以**打开一次皮皮虾再回来看**就有值。

## 〇七、4.0 每个功能一个开关（设置界面）

装完在桌面会多一个 **「皮皮虾净化 · 半城」** 图标，点开就是开关列表，分组：

- **去广告**：广告 SDK、自研广告（m_ad，开屏也在这）、开屏、信息流、弹窗、广告开关、业务层加载、广告模型
- **去水印**：无水印（签名接口）、图片原图、换源、隐藏浮层水印、拦画水印方法、地址取证
- **自动**：自动送神评、最小间隔、单次上限
- **内容净化**：信息流净化、去直播、去带货、去官方号、屏蔽词
- **杂项**：扩展总开关、去红点、禁更新、倍速
- **调试**：详细日志、安全模式、UI 后扫描、类名导出

改完 **强行停止皮皮虾再打开** 生效。

实现上是怎么跨进程的：皮皮虾进程读不到模块 App 的 SharedPreferences，模块 App 也写不进皮皮虾目录，
所以模块 App 里放了个 `PrefsProvider`（authority `io.github.bancheng97.pipixia.prefs`），
皮皮虾进程里的 `Config` 用 `ContentResolver.call(..., "all")` 一次性拉全部键值，
优先级：**设置界面 > pipixia_adfree.conf > 代码默认值**。

## 〇六、3.3 效果自证（不用再靠感觉）

装完之后刷 10 条视频、保存 1 条、拉开一次评论区，然后看
`/sdcard/Download/DSH-Go/pipixia_status.txt`（每 10 秒刷新一次），长这样：

```
# 皮皮虾净化 v3.3 运行状态
进程 pid=12345  已注入=true
配置 safe=false debug=true ... clean=true extras=false ...
计数 拦截广告=37 摘广告=6 送神评=1 无水印成功=3 无水印失败=0 回写地址=18 净化条数=0 已挂钩子=126
最近命中的规则：
  com.bytedance.sdk.openadsdk.xxx.TTAdNative#loadSplashAd#[...]
  com.sup.android.base.model.VideoModel#getUrlList#[]
```

- `拦截广告` / `摘广告` 往上跳 = 去广告在起作用；
- `无水印成功` > 0 且 `回写地址` > 0 = 保存/播放走的是干净链；
- 全是 0 = 规则没命中，把这份文件发出来，照着换挂钩点。

崩溃另存 `pipixia_crash.txt`，带完整栈。

## 〇五、3.2 默认回到「已验证可用」的基线

3.1 还在闪，说明 3.0 那批扩展里至少有一条真的会让 App 崩，而我手上没有栈，靠猜不划算。3.2 的做法：

1. **`extras=false` 默认**（3.0 那批：红点/更新/倍速/净化/原图，全部不装）——等价于你验证过能跑的 2.1；
2. **崩溃落盘**：任何未捕获异常写进 `/sdcard/Download/DSH-Go/pipixia_crash.txt`
   （直接写失败就退到 MediaStore Downloads，再退到 App 私有目录），带时间、异常、前 30 帧栈；
   每 10 秒一次的启动/配置也会记一笔，能确认模块是否活着；
3. **参数替换加类型校验**：`handleBadgeAndPopup` 按真实参数类型（long/int）塞 0，
   之前不管三七二十一塞 Long——如果这版是 int，就会在启动路径上直接崩，这是我目前最怀疑的一条。

要用 3.0 的功能：`extras=true`，然后一项项开（`disable_update` / `save_image` / `clean_feed` / `remove_red_dot`）。
哪一步崩，`pipixia_crash.txt` 里就有栈，直接照着修。

## 〇四、3.1 修闪退 + 崩溃取证

3.0 闪退的两个已知雷点已修：

1. `UpdateService#checkUpdateByAutomatic` 之前用 `setResult(null)`——如果它返回的是 boolean/int，塞 null 会当场崩。
   现在按方法真实返回类型给默认值（`AdHooks.defaultOf(rt)`），图片下载那条同理；
2. 新增**崩溃记录器**：hook `Thread#dispatchUncaughtException`，App 任何未捕获异常都会打进模块日志，
   带类名 + 前 25 帧栈。以后再闪退，日志里直接看 `崩溃 java.lang.XXX` 就知道崩在哪，不用猜。

再补一个**紧急开关**：文件存在就整份停手，不用去 LSPosed 关模块
`/sdcard/Android/data/com.sup.android.superb/files/pipixia_off`

新加总开关 `extras`（默认 true），出问题可以先 `extras=false` 二分：
只留去广告 + 去水印，3.0 那批（红点/更新/倍速/净化/原图）全停。

## 〇三、3.0 新增功能（都按真名 / dex 特征挂，默认全开，配置可关）

| 功能 | 挂钩点 | 说明 |
|---|---|---|
| 去红点 | `FeedTabFragmentV2` 的 (boolean,int,int) 方法参数打 0；`MainActivity#handleBadgeAndPopup(0)`；`MyProfileHeaderLayout#a(false)` | 底部 tab、消息、我的页的角标全清 |
| 禁自动更新 | `com.sup.android.m_update.UpdateService#checkUpdateByAutomatic` | 不再弹更新、不后台下包 |
| 播放倍速 | `TTVideoEnginePlayer#setPlaySpeed(float)` | 正常速按 `play_speed` 提速，默认 1.0 不动 |
| 信息流净化 | dex 找 `com.sup.android.module.feed.repo.manager` 下参数 (String, FeedResponse, boolean, int) 的方法 | 删直播卡、带货卡、官方号、自定义屏蔽词 |
| 图片保存原图 | `NewGalleryActivity#onDownload` | 地址换成 `sf1-ttcdn-tos.pstatp.com/obj/{uri}` 原图再下载，绕开压缩/水印 |

配置新增（都在 `pipixia_adfree.conf` 里）：`remove_red_dot` / `disable_update` / `save_image` /
`clean_feed` / `remove_live` / `remove_promo` / `remove_official` / `block_keywords` / `play_speed`。

## 〇、2.1 修掉「滑一下就卡死」

2.0 的 `VideoModel#getUrlList` 钩子里，after 又调了一次 `getUrlList()` 去改地址——
而 getUrlList 本身被我们 hook 着，于是 A→hook→A→hook 无限递归，主线程栈溢出，
表现就是**滑到第一个视频就卡着不动**。

2.1 的纪律：

- 只改 hook 拿到的那个 List：`patchList(param.getResult(), clean)`，**绝不回头再调 getUrlList**；
- `patchList` 带 ThreadLocal 重入保护，就算哪天被人套娃调用也只会静默返回；
- 主线程绝不联网：`doDownload` 在主线程时只丢后台解析，下载线程才同步解析；
- 后台解析并发上限 4、HTTP 超时 3 秒，避免线程堆积；
- **旧配置迁移**：1.x 写的 conf 里 `save_nowm=true`、`scan_after_ui=true` 这类项现在只会拖性能，
  2.1 首次启动发现文件里没有 `conf_version` 就整份重写成新默认（你自己加的 `force` / `skip` / `extra_prefix` 会保留）。

还卡的话把 `scan_after_ui=false`（关掉通用扫描，只留真名 hook）。

## 〇、2.0 真去水印（换掉前面那套 playwm 的写法）

**前面 1.3/1.4 的「playwm → play」在皮皮虾上是不成立的**：皮皮虾的 url_list 不是抖音那套
watermark 参数，它自家 CDN 的转码版就是带水印的片子，改哪个参数都不干净。

真正的做法是走皮皮虾自己的**签名播放接口**（皮皮虾的视频其实是抖音 CDN 对象）：

```
GET https://i.snssdk.com/video/play/1/bds/{ts}/{sign}/mp4/{uri}
sign = md5("ts" + ts + "userbdsversion1video" + uri + "vtypemp4f425df23905d4ee38685e276072faa0c")
```

返回的 `video_info.data.video_list.video_5..video_0.main_url` 是 Base64 的**无水印直链**，
取最高画质那条解出来就是原片；模型里 `getUrlList()` 的每个 `setUrl()` 换成它，
**播放和「保存到相册」就都干净了**。

- `uri` 从 `VideoModel.getUri()` 拿（`v0300…` 这种），空的就用 `item_id` 换：
  `https://h5.pipix.com/bds/webapi/item/detail/?item_id=` → `data.item.origin_video_id`；
- 挂钩点：`com.sup.android.video.VideoDownloadHelper#doDownload(...)`（保存路径）和
  `com.sup.android.base.model.VideoModel#getUrlList()`（播放路径），结果按 uri 缓存；
- 这些类名在皮皮虾里**没有混淆**，所以直接按名字挂，不再靠瞎猜。

> 接口验证：拿假 uri 打 `/video/play/1/bds/{ts}/{sign}/mp4/{uri}`，返回
> `{"video_info":{"message":"vids doesn't exist in our records","code":1}}` —— 说明签名校验已经通过、
> 只是 uri 不存在，接口和签名串是对的。
> 算法参考 [皮皮虾助手 Secack/ppx](https://github.com/Secack/ppx)（GPL-3.0）的 VideoHook，Java 重写。

**顺手按真名补的广告挂钩**（这些也是不混淆的业务类）：
`com.sup.android.mi.feed.repo.bean.ad.AdFeedCell#getAdInfo → null`、
`com.sup.android.base.model.BannerModel#getBannerData → null`、
开屏类（`com.sup.android.superb.m_ad.initializer` 下带 `CopyOnWriteArraySet` 字段的那个）的 `b() → false`，
开屏类用 dex 枚举（`pathList.dexElements[].dexFile.entries()`）按特征找，不加载全部类。

## 〇二、1.3 新功能

**自动送神评**：评论右侧的大拇指文字是「送神评」，点完变「已送神」，当天送过的显示「已送满」。
模块挂了 `TextView.setText` + `View.setContentDescription`，按钮一出现（含纯图标版靠 description）就
`postDelayed(400ms) → performClick()`，你只要把评论区拉开、往下划，剩下的它替你点。
三道保险：同一个 View 只点一次、两次间隔 ≥1.5 秒、单进程最多 30 次（都能在配置里调）。
日志关键词：`送神评 已点`。

**保存无水印（1.4 重做）**：字节系视频地址里 `playwm` 换成 `play` 就是无水印原件（服务端只认 video_id + 签名，
路径不签名，所以这招长期有效）。模块在信息流列表和详情页模型 getter 上反射改写地址字段
（深度 ≤4，带环检测，只动字符串，`watermark=1` 也顺带改成 0），保存和播放都走干净地址。
日志关键词：`改地址 域名 -> 域名`、`换源 v0xxx 改写 N 条地址`。

1.4 补上了三件东西，因为「还有水印」有三种完全不同的来源：

| 水印类型 | 表现 | 处理 |
|---|---|---|
| A 地址带的 | 地址里有 `playwm` | `save_nowm` 换掉，`改地址` 日志会打出来 |
| B 客户端浮层画的 | 屏幕上右下角一直有个 logo，保存的文件里没有 | `hide_wm_view` / `hook_wm_method` 藏掉、掐掉 |
| C 片源烧死的 | 屏上有、存下来也有，像素里就是 logo | 地址怎么改都没用，只能换源：`force_clean=true` 拿 `video_id` 去抖音 play 接口重拼干净地址（默认关，因为皮皮虾有些片子不是抖音来的，拼出来会 404） |

**地址取证**：`dump_urls=true`（默认开）会把见过的视频地址去重写到
`/sdcard/Android/data/com.sup.android.superb/files/pipixia_urls.txt`（最多 80 条）。
文件里**有 `playwm`** = 属于 A 类，能换；**只有 `play` 却还有水印** = 属于 C 类，只能开 `force_clean` 或换别的解析源。

## 一、它到底删了什么（原理）

皮皮虾是字节系，广告分四种，打法不一样：

| 广告类型 | 长什么样 | 这个模块怎么处理 |
|---|---|---|
| 穿山甲/优量汇 SDK 广告 | 开屏、原生信息流卡片、激励视频 | hook SDK 的 `load*/request*/preload*/show*`，参数带 `*Listener/*Callback` 的，一进去就判“加载失败”并回调 `onNoAd`；广告请求根本没发出去，没下载、没曝光 |
| 皮皮虾自己封装的广告 | `loadXxxAd(slot, listener)` / `showXxxAd(cb)` | 同上，直接判失败 |
| 信息流里混进来的广告条目 | 列表里的 `is_ad` / `ad_info` / `ad_type` 条目 | 在取数方法返回 `List` 时把广告条目从列表里摘掉（数据层就没了，不会错位） |
| 广告弹窗/浮层/插屏/跳广告页 | `showAdDialog`、`openAdActivity` | 名字带 ad 且是 `show/open/jump/goto` 的直接 `return` |
| 广告开关/模型字段 | `isShowAd()`、`xxxAdInfo` 模型 | 布尔 getter 一律 false；广告模型构造时把 ad 字段刷成 0/null |

关键点：**不写死混淆类名**。皮皮虾两周一个版本，硬编码 `XXXAdManager.isShowAd` 的模块必死。这里靠：

1. hook `ClassLoader.loadClass`，目标包的类一加载就入队（钩子里只入队，不扫方法，避免启动期 `ClassCircularityError`）；
2. 后台低优先级线程慢慢扫，按**成员名分词**判规则：`isShowAd` → `[is, show, ad]`；
3. 命中就挂 hook，日志写 `改值 / 拦广告 / 摘广告 / 拦显示`。

---

## 二、安装（Root + LSPosed，推荐）

这台机器实测：**KernelSU 3.3.1（LKM）+ LSPosed 2.2.1（API 102）**，Android 12，arm64-v8a，管理器包名被藏成 `com.android.shell`。

1. 装模块：文件管理器点开 `pipixia-adfree-5.2.apk` → 安装（**不用卸载皮皮虾**，LSPosed 是运行时注入，不动原包）。
2. 打开 LSPosed（桌面那个名字可能是「LSPosed」或伪装过的图标）→ 底部 **模块** → 找到「皮皮虾净化 · 半城」→ 打开开关 → 点进作用域。
3. 作用域里勾 **皮皮虾**（清单已内置 `xposedscope`，正常会自动勾上；没勾上就是没生效）。
4. 设置 → 应用 → 皮皮虾 → **强行停止**，再重新打开。
5. 看日志：LSPosed → 日志 → 搜 `PXXHook`。

模块是标准 LSPosed 模块（`xposedmodule` 元数据 + `assets/xposed_init`，minversion 82，API 102 完全兼容），不是 LSPatch 专用的那种。

> 想顺带用 LSPatch 也行，`LSPatch-manager-v0.6.apk` 我放在同目录了。但你有 root + LSPosed，没必要——LSPatch 得重签名重装，会掉登录数据。

## 三、验证

看日志（LSPosed 管理器 → 日志；LSPatch 用 `logcat -s LSPosed` 或模块自带的日志入口）：

```
[PXXHook] 皮皮虾净化 v5.2 by 半城 已注入
[PXXHook] 配置: safe=false debug=true adSdk=true ... feed=true ...
[PXXHook]D 过滤列表 com.sup.android.superb.xxx.FeedPresenter#getAwemeList#[]
[PXXHook]D 摘广告 1 条 com.sup.android.superb.xxx.FeedPresenter#getAwemeList#[]
[PXXHook]D 拦广告 com.bytedance.sdk.openadsdk.xxx.TTAdNative#loadFeedAd#[...]
```

`摘广告` `拦广告` 越多说明命中越准。模块还会把命中过的规则写到
`/sdcard/Android/data/com.sup.android.superb/files/pipixia_report.txt`，
版本更新后失效，把这份发出来就能补规则，不用重新编译。

---

## 四、配置（不用重装模块就能调）

路径：`/sdcard/Android/data/com.sup.android.superb/files/pipixia_adfree.conf`
（皮皮虾自己的外部私有目录，模块读写都不用权限；不存在会自动生成全开默认值）

```properties
safe=false              # 打不开/闪退：改 true，只注入不装规则
debug=true              # 日志开关
hook_ad_sdk=true        # 穿山甲/优量汇/快手/百度 SDK 拦截（最稳的一条）
hook_ad_notify=true     # 拦的时候回调 onNoAd，避免一直转圈
hook_ad_local=true      # 皮皮虾自己的 loadXxxAd/showXxxAd
hook_splash=true        # 开屏
hook_feed=true          # 信息流广告条目
hook_popup=true         # 弹窗/浮层/插屏
hook_ad_switch=true     # isShowAd 之类开关
patch_ad_model=true     # 广告模型字段
dump_classes=false      # 导出扫到的类名到 pipixia_classes.txt（补规则用）
scan_after_ui=true      # UI 起来后才扫类；设 false = 只保留 SDK 精确拦截（最保守）
scan_seconds=180        # 扫描窗口秒数，到点自动摘 ClassLoader 钩子；0=不限
# extra_prefix=com.sup.ad        # 额外扫描前缀，可多行
# skip=类名#方法名                # 某条规则误伤就跳过
# force=类名#方法名=false         # 强制返回值 true/false/数字/字符串/null
```

改完杀进程重开皮皮虾就生效。

---

## 五、更新后失效怎么修（30 秒）

1. 打开 App 看还有没有广告，配合日志确认 `摘广告/拦广告` 是否还在打；
2. 用 MT 管理器 / jadx 反编译皮皮虾，全局搜 `is_ad`、`ad_info`、`adType`、`isShowAd`、`loadFeedAd`；
3. 拿到 `类名#方法名`，配置里 `force=类名#方法名=false`（置空值写 `null`），重启 App；
4. 误伤就 `skip=类名#方法名`。

---

## 六、排障

| 现象 | 处理 |
|---|---|
| 打开还是白屏（1.2 之后） | 先看日志有没有 `SDK 入口类命中`：有 → 把 `scan_after_ui=false` 关掉扫描再试；还是没有 → LSPosed 里关掉模块开关，确认能正常打开（这就是二分） |
| 应用闪退 | 配置 `safe=true`，只注入不装规则；能开就一条条打开定位 |
| 日志完全没有 `已注入` | 作用域没勾、或皮皮虾没被强行停止（LSPosed 只在进程启动时注入） |
| 广告还在 | `dump_classes=true` 导出类名，按第五节补 `force=类名#方法名=false` |

真要临时彻底停模块：LSPosed → 模块 → 关掉「皮皮虾净化 · 半城」的开关，**不用卸载**。

## 七、边界（实话）

- **留白**：SDK 被我们在客户端拦死，原本放广告的槽位会空一块（或者只剩一条分割线）。比广告干净，但不是“把广告位让给内容”——那是服务端的事。
- **服务端广告**：开屏/信息流广告是服务端下发、本地渲染的，客户端拦得掉；如果哪天皮皮虾改成“广告内容自带在正片接口里”，界面会显示"加载失败"占位，得换抓包改响应体的路子。
- **自绘 banner/活动位**：有些是运营位（不是 SDK 广告），类名不带 ad，拦不到；想删得靠 UI 层遮罩，那种做法遇版本更新就崩，这里不做。
- **不碰会员/付费**：这个模块只做去广告，不动 VIP 判定。
- 别开 `safe=false` 之外的花活去改包体：皮皮虾有签名/完整性保护，改 APK 重打包容易直接闪退，LSPatch 的“运行时注入”比改包安全得多。

---

## 八、源码结构

```
app/src/main/java/com/halfcity/pipixia/
├── MainHook.java   入口：只进 com.sup.android.superb，只挂 ClassLoader + Application.attach
├── Config.java     配置解析（conf 文件、force/skip/extra_prefix）
├── Engine.java     规则引擎：入队 → 后台扫 → 按成员名分词判规则 → 挂 hook → 写报告
├── AdHooks.java    广告 SDK / 业务广告加载拦截 + 回调 onNoAd
├── FeedFilter.java 信息流广告条目识别与摘除 + 广告模型字段补丁
└── Log.java        日志（搜 PXXHook）
```

本机（无 gradle）重新编译：

```bash
./build_local.sh     # javac → d8 → aapt → apksigner，产物在 build/pipixia-adfree-5.2.apk
```

需要：`openjdk-17`、`aapt`、`/root/android/{android.jar,d8.jar,apksigner.jar}`、`xposed api-82.jar`（都在 `/root/android/dl/`）。用 Android Studio 的话直接 `./gradlew assembleRelease` 也行（工程是标准 AGP 结构）。

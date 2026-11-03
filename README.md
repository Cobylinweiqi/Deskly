<div align="center">

# Deskly

**让小米桌面自己整理成该有的样子。**

按颜色 / 按功能一键重排全部图标 · 备份精确到每一个图标 · LSPosed 模块

[![Release](https://img.shields.io/github/v/release/Cobylinweiqi/Deskly?label=release&color=4F46E5)](https://github.com/Cobylinweiqi/Deskly/releases)
[![Stars](https://img.shields.io/github/stars/Cobylinweiqi/Deskly?label=stars&color=4F46E5)](https://github.com/Cobylinweiqi/Deskly/stargazers)
[![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)](#环境要求)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20101-7F5AF0)](https://github.com/LSPosed/LSPosed)

**[⬇️ 下载最新版](https://github.com/Cobylinweiqi/Deskly/releases/latest)** ·
**[📖 使用步骤](#使用步骤)** ·
**[❓ 常见问题](docs/技术内幕.md)** ·
**[☕ 请作者喝咖啡](#请作者喝咖啡)**

如果这个模块帮你省下了整理桌面的时间，**点一下右上角的 ⭐ Star** —— 这是我继续维护下去最直接的动力。

</div>

---

## 它解决什么问题

小米桌面只能手动拖。图标一多，就得一个一个往文件夹里塞；换个主题、装几个新应用，桌面又乱了。

Deskly 做两件事：

1. **整张桌面按一个规则重排**——把全部图标按「颜色」或「功能」分类，自动建成文件夹、自动分屏，一次写完；
2. **改坏了能退回去**——每次动手前自动拍快照，备份精确到「哪个图标在哪个文件夹、文件夹在第几屏第几格」，恢复是整库覆盖。

它**不是**通过无障碍服务模拟拖拽，而是直接读写桌面自己的布局数据库（`launcher4x6.db`），所以速度快、结果稳定，也不会在你手上抢焦点。

---

## 功能

| 功能 | 说明 |
|---|---|
| **按颜色分类** | 把图标 rasterize 成小图，按主色归到 12 个色系，色系再合成文件夹 |
| **按功能分类** | 按包名与系统标记分到 通讯 / 社交 / 影音 / 摄影 / 工具 / 金融 / 出行 / 购物 / 办公 / 游戏 / 系统 等组 |
| **方案卡片可点选** | 两套方案并排显示，选中立刻按它重算分组与屏位 |
| **分屏预览** | 每个文件夹落在第几屏、第几格先给你看；点屏号可以手动钉到某一屏 |
| **第一屏留空** | 想保留第一屏只放小组件，勾一下即可 |
| **每屏从左上角密排** | 不做「见缝插针」，填满一行再换行 |
| **精细归位** | 不动整体结构，只把散落和放错的图标挪进合适的文件夹，每条可改可取消 |
| **自动快照** | 写入前自动存一份，出问题随时退回 |
| **备份与恢复** | 每份备份可展开到每个图标；恢复直接覆盖，不再额外备份 |
| **运行引导** | LSPosed / 作用域 / root / 重启桌面 / 电池优化，能查的写清状态，查不到的直说 |

---

## 环境要求

| 项 | 要求 |
|---|---|
| 系统 | Android 8.0（API 26）及以上 |
| 桌面 | **小米桌面 `com.miui.home`**（MIUI / HyperOS）。其它 ROM 的布局库结构不同，**未适配**，不要直接使用 |
| 框架 | [LSPosed](https://github.com/LSPosed/LSPosed)（基于 [libxposed API 101](https://libxposed.github.io/api/)），需在模块里启用并勾选作用域 |
| 权限 | **root**（Magisk / KernelSU / APatch）。读写桌面数据库、备份与恢复全部依赖 root |
| 不需要 | 无障碍服务、Xposed 之外的特殊权限、联网 |

> 已在 Xiaomi 2509FPN0BC（HyperOS）上实测。换机型 / 换 ROM 前请务必先手动备份一次桌面。

---

## 安装

1. 到 **[Releases](https://github.com/Cobylinweiqi/Deskly/releases/latest)** 下载最新的 `Deskly-x.y.z.apk`；
2. 安装（系统会提示「未知来源」，允许即可）；
3. 打开 **LSPosed 管理器 → 模块 → Deskly**，打开开关；
4. 同一页的**作用域**里勾选「**小米桌面**」（`com.miui.home`）；
5. 在 **Magisk / KernelSU** 里给 Deskly 授予 **root**；
6. 回到 Deskly → 底部切到 **「配置」** → 展开 **「运行引导」**，按 1~5 步走一遍（第 4 步「重启桌面」必须点一次，模块只在桌面进程启动时注入）；
7. 一切就绪后回到 **「整理」**，开始用。

> 覆盖升级不需要重新勾作用域。**更新 Deskly 后建议重启一次桌面**，否则桌面进程里跑的还是旧版模块。

---

## 使用步骤

### 一、整理整张桌面（主功能）

```
分析桌面全部图标  →  选一个方案  →  预览并写入
```

1. **分析**：点「分析桌面全部图标」。Deskly 读一次桌面库，把文件夹内的和散落在桌面上的图标合成一批，同时按颜色与功能各归一次类，然后告诉你三个数字：**应用图标 / 现有文件夹 / 桌面屏数**；
2. **选方案**：并排两张卡，**方案一按颜色**、**方案二按功能**。点哪张就用哪张，下面的分组与屏位立刻重算；
   - 预览里每个文件夹是一块，每行显示 7 个图标，超过 7 个可以「展开全部 N 个」；
   - 点文件夹右侧的**屏号**，可以把它钉到指定的一屏；勾「第一屏留空」则第一屏只放小组件；
3. **写入**：底栏的「应用方案」始终可点，滚到哪儿都在。写入前会自动拍一份快照；写完后桌面自动重启一次，布局立刻生效。

状态行会给出回读证据，例如：

```
已整理 147 个图标 · 9 个文件夹 · 清掉 3 个旧文件夹 · 已写入并重启桌面
回读 147/147 个图标已在新文件夹里
位置与计划一致（每屏从左上角开始排）
```

> **分析范围是「桌面上已有的图标」**（含文件夹内）。装了但没放到桌面上的应用，Deskly 不会自动加进来——它只重排已有条目。差多少个，界面上会直接写出来。

### 二、配置页

- **运行引导**（默认折叠）：5 步接入清单，每步带一个能直接点的动作。作用域那一步框架没有查询接口，只能自己确认，界面会如实说明，不假装打勾。
- **备份与恢复**（默认折叠）：
  - 「备份当前桌面」存一份完整快照，显示 **N 个图标**；
  - 点「展开明细」可看到每个文件夹在第几屏、里面装了哪些应用（带图标）；
  - 恢复**直接覆盖**，不会先替你备份。想留退路，请先手动点一次备份。

---

## 与 LSPosed 官方仓库的关系

本项目是**第三方模块**，不是 LSPosed 官方项目。它与官方的关系只有技术依赖：

- 模块基于 [libxposed](https://github.com/libxposed/api) 的 **API 101** 开发，描述文件（`META-INF/xposed/module.prop`）遵循其规范；
- 作用域声明在 [`app/src/main/resources/META-INF/xposed/scope.list`](app/src/main/resources/META-INF/xposed/scope.list)，默认 `com.miui.home`；
- 运行需要 [LSPosed](https://github.com/LSPosed/LSPosed) 框架。

> 关于上架 LSPosed 在线模块库（[modules.lsposed.org](https://modules.lsposed.org)）：官方要求包名使用 `io.github.<用户名>.*` 前缀，或自持域名并配置 TXT 校验。本项目包名是 `com.coby.deskly`，因此**暂未提交**官方仓库。如果你希望它能被 LSPosed 客户端一键安装，见 [docs/上架官方模块库.md](docs/上架官方模块库.md)。

---

## 从源码构建

```bash
git clone https://github.com/Cobylinweiqi/Deskly.git
cd Deskly

# 需要 JDK 17 与 Android SDK 34
echo "sdk.dir=/path/to/Android/sdk" > local.properties

./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

`release` 用本机的 `~/.android/debug.keystore` 签名，目的是让升级安装不会因签名不同被拒——**这份 APK 不适合上架任何应用商店**，请自行换签名后再分发。

---

## 常见问题

- 点了「应用方案」，桌面没变化？
- 提示「整理失败」但桌面其实整理好了？
- 文件夹没落在钉住的那一屏？
- 提示没有 root / 读不到桌面数据库？

这些都在 **[docs/技术内幕.md](docs/技术内幕.md)** 里按现象分了类，含实测数据、根因与修复版本。遇到问题先搜一眼，提 Issue 时请带上**版本号（左上角 banner 右侧）**与现象截图。

---

## 免责声明

> **请完整阅读后再使用。**

1. 本项目会**直接修改系统桌面的布局数据库**（`/data/user_de/0/com.miui.home/databases/launcher4x6.db`），属于高风险操作。**使用前请自行备份重要数据**，并确认你愿意承担相应风险。
2. 本项目按「现状」提供，**不附带任何明示或暗示的担保**，包括但不限于可用性、无错误、适用于特定目的。因使用或无法使用本项目导致的**桌面布局错乱、图标丢失、数据损坏、设备异常、时间损失或任何间接损失**，作者**不承担任何责任**。
3. root、LSPosed、系统修改本身可能使设备**失去保修**、触发安全风控或影响部分应用（如银行、支付类）的正常使用。这些后果由使用者自行判断与承担。
4. 本项目**不收集任何数据**、不联网、不含统计与广告 SDK。所有操作都在你的设备本地完成。
5. 本项目与小米（Xiaomi）、MIUI / HyperOS、LSPosed 官方**均无隶属或背书关系**，相关名称与商标归各自权利人所有。
6. 若你不同意上述任何一条，请**立即停止使用并卸载**。

---

## 版权与转载

**Copyright © 2026 Coby Lin. All rights reserved.**

- ✅ 欢迎**分享本仓库链接**，让更多人看到；
- ✅ 欢迎在个人博客 / 视频 / 文章里**引用本项目的名称、截图与功能介绍**，并注明出处与本仓库地址；
- ❌ **未经作者书面授权，禁止任何形式的转载与二次分发**，包括但不限于：把 APK 重新打包、搬运到第三方应用市场 / 网盘 / 公众号 / 论坛附件、去除署名后发布、打包进「XX 合集」「XX 工具箱」；
- ❌ **禁止任何商业用途**（含付费下载、以捐赠/会员/广告等形式变相收费、随硬件或服务捆绑分发）；
- ❌ 禁止移除或修改本项目的署名、版权信息与免责声明。

需要授权（转载、商用、二次开发分发）请先通过 [Issues](https://github.com/Cobylinweiqi/Deskly/issues) 联系作者，说明用途与范围，取得**书面许可**后再进行。

---

## 请作者喝咖啡

Deskly 是业余时间写的，不收钱、不放广告、不收集数据。如果它确实帮你把桌面收拾干净了，可以请我喝一杯咖啡 —— **金额随意，不打赏也完全不影响使用**。

<div align="center">
  <img src="docs/donate.svg" width="260" alt="请作者喝咖啡">
  <br>
  <sub>微信 / 支付宝 扫码 · 感谢每一份心意</sub>
</div>

<!-- 维护者：把 docs/donate.svg 换成你自己的收款码图片（png/svg 均可），再更新上面的 src。 -->

---

## 关注 & Star

- **⭐ Star**：点右上角 Star，收藏的同时也让我知道有多少人在用；
- **👀 Watch**：选 `Releases only`，有新版会自动通知；
- **🐛 Issue**：遇到问题请带上版本号、机型与 ROM 版本、复现步骤，以及「整理」页的状态行文字；
- **➡️ Follow**：[@Cobylinweiqi](https://github.com/Cobylinweiqi)，后续同类工具会第一时间发在这里。

<div align="center">
<br>
<b>如果它让你的桌面清爽了一点，那就点个 Star 吧 ⭐</b>
</div>

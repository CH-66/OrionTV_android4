# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

# Repository Guidelines

## 项目范围

本仓库当前只关注独立的 Android 4.0.4 原生旧版客户端：

- 目标模块：`legacy-android-ics/`
- 包名：`com.oriontv.legacy`
- 目标系统：Android 4.0.4 / API 15

除非用户明确要求，不处理 Expo / React Native 目录中的新版本客户端。

## 项目结构

- `legacy-android-ics/`：Android 4.0.4 / API 15 Java 原生客户端。
- `.github/workflows/android-ics.yml`：旧安卓 APK 构建 workflow，名称为 `Build Android 4.0.4 Legacy APK`。
- `.codex/skills/legacy-android-ics-smoke/`：旧安卓构建、安装、模拟器冒烟测试规范 skill。
- `docs/`、`.codex/`：说明文档、验证记录与任务留痕。

## 本机 Android 4.0 测试环境

统一使用英文路径：

```text
E:\android-4.0
```

目录约定：

- SDK：`E:\android-4.0\sdk`
- AVD：`E:\android-4.0\avd`
- APK 产物：`E:\android-4.0\apk`
- 测试日志与截图：`E:\android-4.0\logs`

旧路径 `E:\安卓4.0` 只作为历史遗留路径，不再写入新文档、脚本或命令。若 Windows 因 emulator/qemu 残留句柄无法立即重命名旧目录，可暂时保留 `E:\android-4.0` 到旧目录的 junction，但所有新流程必须只使用 `E:\android-4.0`。

## 必用 Skill

涉及以下任务时必须优先使用项目内 skill：

```text
.codex/skills/legacy-android-ics-smoke/SKILL.md
```

适用任务：

- 构建或下载 Android 4.0.4 APK
- 安装 APK 到 API 15 模拟器
- 执行旧安卓冒烟测试
- 排查 Android 4.0.4 emulator / ADB / logcat / 截图问题
- 汇总旧安卓测试证据

## Android 4.0.4 APK 构建流程

旧安卓客户端优先使用 GitHub Actions 构建 APK，不依赖本地 Gradle 环境。

1. 确认或触发 workflow：`Build Android 4.0.4 Legacy APK`。
2. 构建成功后下载 artifact：

```powershell
gh run download <run-id> --dir "E:\android-4.0\apk\run-<run-id>"
```

3. 默认安装 `*-android-ics-debug.apk`，除非用户明确要求 release。

## Emulator 启动规范

默认自动化用例优先使用：

- AVD：`android404_api15_nexus10`
- 端口：优先 `5566`
- serial：`emulator-5566`

原因：`5554/5555` 容易被历史 offline 实例占用；如端口已占用，使用另一个未占用的偶数端口。

启动前设置：

```powershell
$env:ANDROID_SDK_ROOT='E:\android-4.0\sdk'
$env:ANDROID_HOME='E:\android-4.0\sdk'
$env:ANDROID_AVD_HOME='E:\android-4.0\avd'
```

若 qemu 报 `could not load PC BIOS 'bios-256k.bin'`，从以下英文工作目录启动 emulator：

```text
E:\android-4.0\sdk\emulator\qemu\windows-x86_64
```

## 固定冒烟用例

每次旧安卓冒烟至少执行以下用例，并保存证据：

1. **安装 APK**
   - `adb install -r <apk>`
   - 验收：输出 `Success`，且 `pm path com.oriontv.legacy` 返回 APK 路径。

2. **启动应用**
   - 清空 logcat，force-stop 后启动应用。
   - 验收：当前焦点或 activity 栈包含 `com.oriontv.legacy/.MainActivity`。

3. **服务配置加载**
   - 检查 `shared_prefs/oriontv_legacy.xml`。
   - 验收：存在 `mytv_settings`，且 `apiBaseUrl` 非空。

4. **首页 DPAD 导航**
   - 发送 DPAD 方向键。
   - 验收：应用不崩溃、不 ANR，仍保持在应用 activity。

5. **进入详情页**
   - 在首页内容上发送 OK / Enter。
   - 验收：activity 栈包含 `com.oriontv.legacy/.DetailActivity`。

6. **尝试播放**
   - 在详情页选择剧集或播放源并按 OK。
   - 验收：activity 栈包含 `com.oriontv.legacy/.PlayerActivity`。
   - 播放成功需单独确认；若仅进入播放器但 `MediaPlayer` 报错，记为“导航通过，播放失败或部分通过”。

## 证据要求

日志和截图保存到：

```text
E:\android-4.0\logs
```

推荐命名：

- `run-<run-id>-smoke-<port>-main.png`
- `run-<run-id>-smoke-<port>-final.png`
- `run-<run-id>-smoke-<port>-final-logcat.txt`

Android 4.0.4 可能不支持 `adb exec-out screencap`，应使用设备端截图后 pull：

```powershell
adb -s emulator-5566 shell screencap -p /sdcard/run-<run-id>-smoke-final.png
adb -s emulator-5566 pull /sdcard/run-<run-id>-smoke-final.png "E:\android-4.0\logs\run-<run-id>-smoke-5566-final.png"
```

headless 截图可能全黑。若截图不可作为视觉证据，必须用 `dumpsys window`、`dumpsys activity activities` 和 logcat 作为验收依据，并在交付说明中明确说明。

## 代码风格

旧版 Android 代码位于 `com.oriontv.legacy` 包下。所有改动必须兼容 API 15，避免直接使用新 Android API，除非有明确兼容保护。

## 测试要求

旧安卓改动必须至少通过 GitHub Actions APK 构建，并下载最新产物到 `E:\android-4.0` 后完成模拟器冒烟。

若本机模拟器因 offline 端口、qemu 残留句柄或 Windows 路径占用无法完成，必须说明：

- 失败命令
- 失败现象
- 已保存的日志路径
- 下一步恢复方式，例如重启 Windows 后清理旧 emulator 句柄

## 提交与 PR 规范

提交信息使用简短祈使句，可使用 conventional 前缀。示例：

- `fix: harden legacy API URL and cookie handling`
- `Fix legacy poster loading and DPAD focus`

PR 需说明变更范围、测试结果、相关 workflow run 链接；涉及 UI 时附截图；涉及旧安卓时明确影响的 Android 版本。

## 安全与配置

不要提交账号密码、签名文件、下载的 APK、模拟器数据、截图、logcat 或本地 IDE 文件。

`E:\android-4.0` 仅作为本机测试目录，不应纳入仓库。

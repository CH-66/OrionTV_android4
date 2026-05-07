# Repository Guidelines

## 项目结构与模块组织

本项目仅限于 独立的 Android 4.0.4 原生旧版客户端。legacy-android-ics ，其他不必考虑

- `app/`：Expo Router 页面与路由入口。
- `components/`、`hooks/`、`stores/`、`services/`、`utils/`：通用 UI、状态、接口与工具代码。
- `assets/`：图片、字体等静态资源。
- `xml/`：预构建时复制到 Android 工程的 XML 配置。
- `legacy-android-ics/`：面向 Android 4.0.4 / API 15 的 Java 原生客户端。
- `.github/workflows/`：GitHub Actions 构建流程，包含旧安卓 APK 构建。
- `docs/`、`.codex/`：说明文档、验证记录与任务留痕。

## Android 4.0.4 APK 构建与冒烟流程

旧安卓客户端优先使用 GitHub Actions 构建 APK，不依赖本地 Gradle 环境。

1. 将改动提交并推送到 GitHub。
2. 手动运行 workflow：`Build Android 4.0.4 Legacy APK`。
3. 构建成功后下载 artifact，例如：

```sh
gh run download <run-id> --dir "E:\安卓4.0\apk\run-<run-id>"
```

4. 使用 `E:\安卓4.0` 中已配置的 Android 4.0.4 / API 15 模拟器安装并冒烟测试。
5. 截图与日志保存到 `E:\安卓4.0\logs`。

基础冒烟项：应用可启动、服务配置可加载、首页列表出现、DPAD 焦点可移动、OK/Enter 可进入详情、详情可加载播放源并尝试播放。

## 代码风格与命名约定

旧版 Android 代码位于 `com.oriontv.legacy` 包下。所有改动必须兼容 API 15，避免直接使用新 Android API，除非有明确兼容保护。

## 测试要求

旧安卓改动必须至少通过 GitHub Actions APK 构建，并下载最新产物到 `E:\安卓4.0` 后完成模拟器冒烟。

## 提交与 PR 规范

提交信息使用简短祈使句，可使用 conventional 前缀。示例：`fix: harden legacy API URL and cookie handling`、`Fix legacy poster loading and DPAD focus`。

PR 需说明变更范围、测试结果、相关 workflow run 链接；涉及 UI 时附截图；涉及旧安卓时明确影响的 Android 版本。

## 安全与配置提示

不要提交账号密码、签名文件、下载的 APK、模拟器数据、截图、logcat 或本地 IDE 文件。`E:\安卓4.0` 仅作为本机测试目录，不应纳入仓库。

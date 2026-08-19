# GitHub 正式 Release 更新检查设计

## 目标

将 Android 应用的更新检查源从 `https://updates.rikka-ai.com/` 切换为当前 fork 的 GitHub 正式 Release：
`1873412297-art/mystudy-rikkahub`。应用只检查正式 Release，不把 `nightly` 等预发行版本当作可更新版本。
同时将应用在桌面、系统设置和启动器中的显示名称改为 `RhStudy`。

## 现状

`UpdateChecker` 当前请求自定义 JSON，并将响应映射为 `UpdateInfo`。`UpdateCard` 已负责版本比较、变更日志展示和下载动作，因此更新源替换不需要改变 UI 或下载器。

应用显示名由各语言资源中的 `app_name` 提供，包名、命名空间、应用 ID 和深层链接协议保持不变。

目标仓库的每日构建使用固定 `nightly` 预发行标签；GitHub `releases/latest` 接口会自动排除预发行和草稿，适合“仅正式版”需求。

## 方案

### 请求与映射

`UpdateChecker` 请求：

`GET https://api.github.com/repos/1873412297-art/mystudy-rikkahub/releases/latest`

请求保留现有 User-Agent，并增加 GitHub API 所需的 `Accept: application/vnd.github+json`。GitHub 响应映射规则：

- `tag_name` 去除可选的 `v`/`V` 前缀后作为 `UpdateInfo.version`，兼容 `v2.4.6` 和 `2.4.6`。
- `published_at` 作为发布时间。
- `body` 作为变更日志，空值降级为空字符串。
- 仅保留文件名以 `.apk`（大小写不敏感）结尾的资产。
- 每个 APK 资产使用 `name`、`browser_download_url` 和字节数格式化后的大小生成 `UpdateDownload`。
- 没有 APK 资产时抛出错误并走现有 `UiState.Error`，不展示源码压缩包作为安装包。

### 错误处理

非 2xx 响应、JSON 解析失败、缺少有效版本号或没有 APK 资产均视为更新检查失败，沿用现有错误卡片。下载失败时继续使用现有 DownloadManager 和浏览器回退。

### 测试

先新增失败测试，再实现最小代码：

1. GitHub Release JSON 正确转换为 `UpdateInfo`，并过滤非 APK 资产。
2. `v` 前缀版本被规范化，现有 `Version` 比较能识别新版本。
3. 没有 APK 资产时转换失败。

完成后运行更新器相关 JVM 测试、`app:testDebugUnitTest`、`app:compileDebugKotlin` 和 `app:assembleDebug`。

显示名验证覆盖所有现有语言资源的 `app_name` 均为 `RhStudy`，并通过 Debug 构建确认资源编译成功。

## 不在本次范围

- 不修改 GitHub Actions 的发布流程。
- 不支持预发行、草稿或 `nightly` 更新。
- 不新增设置项、更新 UI 或自动安装逻辑。
- 不修改包名、命名空间、应用 ID 或深层链接协议。

# GitHub Release 更新检查与 RhStudy 更名实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (\`- [ ]\`) syntax for tracking.

**Goal:** 让应用只从 \`1873412297-art/mystudy-rikkahub\` 的正式 GitHub Release 检查更新，并将所有现有语言资源中的应用显示名改为 \`RhStudy\`。

**Architecture:** 保留 \`UpdateChecker\` 的 Flow、\`UpdateInfo\`、\`UpdateDownload\`、DownloadManager 和现有 UI，仅把网络响应从自定义更新 JSON 改为 GitHub Release DTO，再通过一个纯映射函数转换为现有模型。显示名称继续由 \`@string/app_name\` 提供，只替换各 locale 的资源值；包名、namespace、applicationId 和深层链接不变。

**Tech Stack:** Kotlin, kotlinx.serialization, OkHttp, Kotlin coroutines/Flow, JUnit 4, Android Gradle Plugin.

## Global Constraints

- 只请求 GitHub \`releases/latest\`，不支持预发行、草稿或固定 \`nightly\` 标签。
- 更新源固定为 \`https://api.github.com/repos/1873412297-art/mystudy-rikkahub/releases/latest\`。
- 只将 \`.apk\` 资产转换为可下载更新项；没有 APK 资产必须报错。
- \`tag_name\` 支持可选的 \`v\`/\`V\` 前缀，传给 \`Version\` 前必须规范化。
- 显示名固定为 \`RhStudy\`；不修改包名、namespace、applicationId 或深层链接协议。
- 遵循 TDD：每项生产代码前先写能正确失败的测试，并运行测试确认失败原因。

---

### Task 1: 添加 GitHub Release 映射的失败测试

**Files:**
- Create: \`app/src/test/java/me/rerere/rikkahub/utils/UpdateCheckerTest.kt\`
- Read: \`app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt\`

**Interfaces:**
- Consumes: 待新增的 \`GitHubRelease\` 到 \`UpdateInfo\` 的纯映射接口。
- Produces: 明确的测试契约，后续实现必须提供可从测试包调用的映射行为。

- [ ] **Step 1: Write the failing test**

在测试文件中创建 GitHub Release JSON，包含 \`tag_name = "v2.4.6"\`、发布时间、变更日志、一个 APK 资产和一个 \`.zip\` 资产；通过 \`Json.decodeFromString<GitHubRelease>(json).toUpdateInfo()\` 断言版本为 \`2.4.6\`、变更日志与 APK URL/名称/大小正确，且下载列表只有一个 APK。再添加两个测试：无 APK 资产抛出 \`IllegalArgumentException\`；\`v\` 前缀规范化后 \`Version("2.4.6") > Version(BuildConfig.VERSION_NAME)\` 的比较成立。使用 \`assertEquals\`、\`assertThrows\`，不引入网络 mock。

- [ ] **Step 2: Run test to verify it fails**

Run:

\`\`\`powershell
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.utils.UpdateCheckerTest'
\`\`\`

Expected: FAIL because \`GitHubRelease\` 和 \`toUpdateInfo()\` 尚不存在；不要继续到生产代码实现，直到失败原因是缺少目标行为而非测试语法错误。

- [ ] **Step 3: Commit the red test**

\`\`\`powershell
git add app/src/test/java/me/rerere/rikkahub/utils/UpdateCheckerTest.kt
git commit -m "test: define GitHub release update mapping"
\`\`\`

### Task 2: 实现 GitHub 正式 Release 更新源

**Files:**
- Modify: \`app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt\`
- Test: \`app/src/test/java/me/rerere/rikkahub/utils/UpdateCheckerTest.kt\`

**Interfaces:**
- Consumes: Task 1 的 JSON 映射测试。
- Produces: \`@Serializable internal data class GitHubRelease\`、\`@Serializable internal data class GitHubReleaseAsset\` 和 \`internal fun GitHubRelease.toUpdateInfo(): UpdateInfo\`；\`UpdateChecker.checkUpdate()\` 使用 GitHub API 并返回现有 \`UiState\`。

- [ ] **Step 1: Add serializable GitHub DTOs and pure mapper**

在 \`UpdateChecker.kt\` 中为 \`tag_name\`、\`published_at\`、\`body\`、\`assets\`、资产 \`name\`、\`browser_download_url\`、\`size\` 建立 DTO。映射函数执行以下逻辑：去除 \`v\`/\`V\` 前缀并校验版本至少包含数字主版本和次版本；筛选 \`name.endsWith(".apk", ignoreCase = true)\`；将大小转换为现有 UI 可读字符串（字节、KB、MB、GB，保留最多一位小数）；无 APK 时抛出 \`IllegalArgumentException\`；\`body ?: ""\`。

- [ ] **Step 2: Run the focused test to verify it passes**

\`\`\`powershell
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.utils.UpdateCheckerTest'
\`\`\`

Expected: PASS for all mapping, filtering, version-normalization and no-APK tests.

- [ ] **Step 3: Switch \`checkUpdate()\` to GitHub \`releases/latest\`**

将 URL 常量替换为 \`https://api.github.com/repos/1873412297-art/mystudy-rikkahub/releases/latest\`；请求保留现有 User-Agent，并加入 \`Accept: application/vnd.github+json\`。成功响应使用 \`json.decodeFromString<GitHubRelease>(response.body.string()).toUpdateInfo()\`；非 2xx 和解析/映射异常继续沿用现有 \`UiState.Error\`。

- [ ] **Step 4: Run focused and existing utility tests**

\`\`\`powershell
./gradlew :app:testDebugUnitTest --tests 'me.rerere.rikkahub.utils.UpdateCheckerTest' --tests 'me.rerere.rikkahub.utils.VersionTest'
\`\`\`

Expected: PASS with no new warnings or failures.

- [ ] **Step 5: Commit the update checker**

\`\`\`powershell
git add app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt app/src/test/java/me/rerere/rikkahub/utils/UpdateCheckerTest.kt
git commit -m "feat: check updates from GitHub releases"
\`\`\`

### Task 3: 将应用显示名改为 RhStudy

**Files:**
- Modify: \`app/src/main/res/values/strings.xml:10\`
- Modify: \`app/src/main/res/values-zh/strings.xml:10\`
- Modify: \`app/src/main/res/values-zh-rTW/strings.xml:10\`
- Modify: \`app/src/main/res/values-ja/strings.xml:10\`
- Modify: \`app/src/main/res/values-ko-rKR/strings.xml:10\`
- Modify: \`app/src/main/res/values-ru/strings.xml:10\`

**Interfaces:**
- Consumes: Android manifest 已引用的 \`@string/app_name\`。
- Produces: 所有现有语言环境的系统/桌面显示名 \`RhStudy\`，不改包标识。

- [ ] **Step 1: Write the resource assertion before editing**

运行以下断言作为 RED 检查，确认现状仍包含旧名称：

\`\`\`powershell
$old = rg -l '<string name="app_name">RikkaHub</string>' app/src/main/res --glob 'strings.xml'
if ($old.Count -ne 6) { throw "Expected 6 old app_name resources, found $($old.Count)" }
\`\`\`

Expected: 命令成功，列出 6 个资源文件；这是资源变更前的基线。

- [ ] **Step 2: Replace only \`app_name\` values**

在上述 6 个文件中将唯一的 \`<string name="app_name">RikkaHub</string>\` 改为 \`<string name="app_name">RhStudy</string>\`；不要替换其他品牌说明、包名、协议或业务文案。

- [ ] **Step 3: Verify the resource behavior**

\`\`\`powershell
$new = rg -l '<string name="app_name">RhStudy</string>' app/src/main/res --glob 'strings.xml'
if ($new.Count -ne 6) { throw "Expected 6 RhStudy resources, found $($new.Count)" }
if (rg -n '<string name="app_name">RikkaHub</string>' app/src/main/res --glob 'strings.xml') { throw "Old app name remains" }
\`\`\`

Expected: 6 个 locale 资源为 \`RhStudy\`，旧 \`app_name\` 不再存在。

- [ ] **Step 4: Commit the display-name change**

\`\`\`powershell
git add app/src/main/res/values/strings.xml app/src/main/res/values-zh/strings.xml app/src/main/res/values-zh-rTW/strings.xml app/src/main/res/values-ja/strings.xml app/src/main/res/values-ko-rKR/strings.xml app/src/main/res/values-ru/strings.xml
git commit -m "feat: rename app display name to RhStudy"
\`\`\`

### Task 4: 全量验证与交付检查

**Files:**
- Verify: \`app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt\`
- Verify: \`app/src/main/res/*/strings.xml\`

**Interfaces:**
- Consumes: Tasks 2-3 的提交。
- Produces: 可编译、测试通过的 Debug 构建，且工作区无本次未提交改动。

- [ ] **Step 1: Run all app JVM tests**

\`\`\`powershell
./gradlew :app:testDebugUnitTest
\`\`\`

Expected: BUILD SUCCESSFUL，所有 app JVM 测试通过。

- [ ] **Step 2: Compile and assemble Debug APK**

\`\`\`powershell
./gradlew :app:compileDebugKotlin :app:assembleDebug
\`\`\`

Expected: BUILD SUCCESSFUL，生成现有 ABI split/universal Debug APK 产物。

- [ ] **Step 3: Inspect final diff and status**

\`\`\`powershell
git diff --check
git status --short
git log -4 --oneline
\`\`\`

Expected: \`git diff --check\` 无输出；状态只保留仓库既有未跟踪目录，不出现本次代码/资源修改；最近提交包含更新器、RhStudy 资源和测试。

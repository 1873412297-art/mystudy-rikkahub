# 私有 Fork 同步工作流

本文记录如何把官方 `rikkahub/rikkahub` 的更新增量同步到我们的私有 fork 上，
而不破坏私有功能。

## 仓库结构

| Remote | 指向 | 何时用 |
|---|---|---|
| `upstream` | `https://github.com/rikkahub/rikkahub.git`（**只读**） | `git fetch upstream` 拉官方更新 |
| `origin` | `https://github.com/<你的用户名>/<你的fork>.git` | `git push origin private-main` 备份/分发 |

主分支：**`private-main`**（不要叫 `master`，避免和官方 `master` 混淆）。

私有功能维持为多个独立的 commit（`feat(stability)` / `feat(assistant)` / `feat(html-render)` /
`feat(tavern)` / `feat(group)` / `feat(slash)` / `feat(status)` / `feat(db)` / `chore(i18n)` / `style(misc)`），
**禁止 squash 成单个大 commit**——squash 后下一次 rebase 冲突会变成不可读的天书。

## 同步官方新版本（每次官方发版时执行）

```bash
# 1. 拉最新 tag 和分支
git fetch upstream --tags

# 2. 切到私有主干
git switch private-main

# 3. 把官方更新 rebase 进来。冲突重点关注下面那张"热点文件"表
git rebase upstream/master

# 4. 解冲突。每解决完一个文件：
git add <file>
# 解决完一个 commit 的所有冲突：
git rebase --continue

# 5. 编译验证
./gradlew :app:assembleDebug

# 6. 真机/模拟器跑烟雾测试（见下方清单）

# 7. 推到自己 fork
git push --force-with-lease origin private-main
```

冲突解不下去？**任何时候都可以中止**：
```bash
git rebase --abort
```

## 热点冲突文件清单

每次 rebase，下面这些文件几乎必冲突。已经按合并语义在 `.claude/plans/jazzy-bouncing-sparkle.md` 阶段3 中记录。

- `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`
- `app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`
- `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`
- `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`
- `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt`
- `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`
- `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`
- `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`
- `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`
- `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`
- `ai/src/main/java/me/rerere/ai/ui/Message.kt`
- 6 份 `app/src/main/res/values*/strings.xml`

合并原则：**保留上游字段在前，私有字段追加在后**——这样下次 rebase 时上游 hunk 上下文才能对齐。

## DB schema 版本号管理

每次官方升 schema，私有 schema 必须跟着往后挪一位。例如：

- 当前：上游 v23 → 私有 v24（私有 4 列）
- 假设官方下次升到 v24：私有就要变成 v25。需要：
  1. 删除旧的 `app/schemas/.../24.json`（如果是私有的话）
  2. `AppDatabase.kt` 改成 `version = 25`
  3. `autoMigrations` 数组里把 `from=23, to=24` 改成 `from=24, to=25`
  4. Gradle 编译会自动生成新的 `app/schemas/.../25.json`

## 烟雾测试清单（每次 rebase 后必跑）

- [ ] 编译通过：`./gradlew :app:assembleDebug`
- [ ] 旧版用户能从原 schema 平滑升级到新 schema 不闪退
- [ ] 群组助手：新建群组 + 加成员 + 发消息 + 切换 turn-taking
- [ ] 角色卡：导入 SillyTavern PNG → greeting picker → HTML 渲染
- [ ] Slash：输入命令被 `SlashCommandInterceptor` 拦截
- [ ] 状态变量：消息含 `<status>{...}</status>` 时 `MultiCharacterStatusView` 渲染
- [ ] 上游新功能未被破坏（例如 workspace、表格卡片、动态渐变背景）

## 备份/恢复

阶段 0 留下的灾备文件：

- `.git/private-features-backup.patch`（已提交后无需保留）
- `.git/private-untracked.tar`（已提交后无需保留）
- `git stash@{0}`（标签 `rikkahub-private-features-pre-2.3.0-backup`）

完成 2.3.0 升级且烟雾测全过后，可以用以下命令清理：

```bash
git stash drop stash@{0}
rm -f .git/private-features-backup.patch .git/private-untracked.tar .git/private-untracked-files.txt .git/private-untracked-keep.txt
rm -rf .git/private-junk-quarantine
```

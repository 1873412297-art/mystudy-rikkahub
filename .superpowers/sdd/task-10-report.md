# Task 10 Report: 酒馆数据 store + 服务层 + SSE 接线

- **状态**: DONE
- **提交哈希**: `2b591375`（`feat: add tavern variable/card stores and status_variables SSE wiring`，4 files changed, 88 insertions(+), 2 deletions(-)）
- **日期**: 2026-08-13

## 变更内容

按计划 `2026-08-13-web-ui-tavern-rendering.md` "### Task 10" Steps 1-5 逐字实施：

1. **新建 `web-ui/app/stores/tavern.ts`**：`useTavernStore`（zustand）——`variablesByConversation`、`cardsByAssistant`、`loadingAssistantIds`、`setVariables`、`ensureCardLoaded`、`cardOf`。代码与计划一致。
2. **新建 `web-ui/app/services/tavern.ts`**：`TavernRenderDto` 接口 + `fetchTavernRender(assistantId)`（`api.get` 封装，路径 `assistant/{id}/tavern-render`，与 ky `prefixUrl: "/api"` 拼接）。
3. **修改 `web-ui/app/stores/index.ts`**：新增 `export { useTavernStore } from "~/stores/tavern";`。
4. **修改 `web-ui/app/routes/conversations.tsx`**：
   - `ConversationStreamEvent` union 新增 `StatusVariablesEventDto` 成员。
   - import：`useTavernStore` 并入既有 `~/stores` import；`StatusVariablesEventDto` 并入既有 `~/types` 类型 import 块（合并进现有块而非新增独立 import，遵循文件既有风格，语义与计划一致）。
   - 初始 GET `.then`：`setDetail(data)` 后新增 `statusVariables` 同步 + `ensureCardLoaded`。
   - `onMessage`：error 分支之后、snapshot 分支之前新增 `status_variables` 分支（`setVariables(data.conversationId, data.variables)` + return）。
   - snapshot 分支：`setDetail(data.conversation)` 后新增 `statusVariables` 同步 + `ensureCardLoaded`。

## 验证

- **typecheck**（`pnpm typecheck`）：仅剩已知 3 处 TS2366（Task 12 修复范围）：
  - `app/components/message/chat-message.tsx(60,50)` TS2366
  - `app/components/message/chat-message.tsx(77,64)` TS2366
  - `app/routes/conversations.tsx(104,4)` TS2366（getQuickJumpPreview，pre-existing）
  - **0 新增错误**。
- **test**（`pnpm test`）：3 files / 41 tests passed。

## 自审

- **ConversationStreamEvent union 含 StatusVariablesEventDto**: ✅ 已加入（第四成员）。
- **ensureCardLoaded 动态 import 是否必要**: 按计划逐字保留动态 `await import("~/services/tavern")`。经自审：**并非必需**——依赖链为 `stores/tavern.ts → services/tavern.ts → services/api.ts`，无循环依赖，静态 import 亦可（且更简单、可享 tree-shaking/类型检查一致性）。未改为静态 import 的理由：指令要求计划代码逐字使用；动态 import 功能上无害（避免 stores 入口加载时立即拉入服务模块，亦可为后续按需加载留空间）。若后续任务或评审要求，可一行改为静态 import。
- **unused import 检查**: `StatusVariablesEventDto` 在文件中被 union 使用；`useTavernStore` 在 useEffect 回调内以 `useTavernStore.getState()` 非 hook 形式使用（符合约束），无未使用导入。

## 疑虑

- 无功能性疑虑。唯一备注：上述动态 import 可简化为静态 import（非阻塞，见自审）。
- 提交后 Git 提示 `services/tavern.ts`、`stores/tavern.ts` 工作区 LF→CRLF 换行转换警告，属仓库既有 autocrlf 行为，不影响内容。

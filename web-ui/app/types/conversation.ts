import type { UIMessage } from "./message";
import type { MessageRole } from "./core";

/**
 * Author's Note (@ Depth)
 * @see app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt - AuthorNote
 */
export interface AuthorNote {
  enabled: boolean;
  content: string;
  depth: number;
  role: MessageRole;
  interval: number;
}

/**
 * Message node - container for message branching
 * @see app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt - MessageNode
 */
export interface MessageNode {
  id: string;
  messages: UIMessage[];
  selectIndex: number;
}

/**
 * Conversation
 * @see app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt - Conversation
 */
export interface Conversation {
  id: string;
  assistantId: string;
  title: string;
  messageNodes: MessageNode[];
  chatSuggestions: string[];
  isPinned: boolean;
  customSystemPrompt?: string | null;
  modeInjectionIds?: string[];
  lorebookIds?: string[];
  /** Absolute path inside the workspace rootfs */
  workspaceCwd?: string | null;
  /** 所属文件夹（助手内分组），null 表示未归入任何文件夹 */
  folderId?: string | null;
  /** 会话级作者注释，仅在助手允许时生效 */
  authorNote?: AuthorNote | null;
  createAt: number;
  updateAt: number;
}

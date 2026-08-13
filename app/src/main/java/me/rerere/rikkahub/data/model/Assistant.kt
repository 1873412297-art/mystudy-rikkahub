package me.rerere.rikkahub.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.core.ReasoningLevel
import me.rerere.rikkahub.data.ai.tools.local.LocalToolOption
import me.rerere.rikkahub.utils.SimpleCache
import java.util.concurrent.TimeUnit
import kotlin.uuid.Uuid

@Serializable
data class Assistant(
    val id: Uuid = Uuid.random(),
    val chatModelId: Uuid? = null, // 如果为null, 使用全局默认模型
    val name: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val useAssistantAvatar: Boolean = false, // 使用助手头像替代模型头像
    val tags: List<Uuid> = emptyList(),
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    // 上下文消息条数上限, 超出后阶梯式截断; 0 表示不限制
    val contextMessageLimit: Int = 0,
    val streamOutput: Boolean = true,
    val enableMemory: Boolean = false,
    val useGlobalMemory: Boolean = false, // 使用全局共享记忆而非助手隔离记忆
    val enableRecentChatsReference: Boolean = false,
    val messageTemplate: String = "{{ message }}",
    val presetMessages: List<UIMessage> = emptyList(),
    val quickMessageIds: Set<Uuid> = emptySet(),
    val regexes: List<AssistantRegex> = emptyList(),
    val reasoningLevel: ReasoningLevel = ReasoningLevel.AUTO,
    val maxTokens: Int? = null,
    val customHeaders: List<CustomHeader> = emptyList(),
    val customBodies: List<CustomBody> = emptyList(),
    val mcpServers: Set<Uuid> = emptySet(),
    val localTools: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo),
    val enableWebSearch: Boolean = false, // 网络搜索开关(每个助手独立)
    val workspaceId: Uuid? = null,
    val background: String? = null, // 聊天页背景图地址(本地文件 URI 或网络 URL), 为 null 时无背景
    val backgroundOpacity: Float = 1.0f, // 背景图不透明度(0~1)
    val useGradientBackground: Boolean = false, // 开启后聊天页使用动态渐变背景
    val modeInjectionIds: Set<Uuid> = emptySet(),      // 关联的模式注入 ID
    val lorebookIds: Set<Uuid> = emptySet(),            // 关联的 Lorebook ID
    val enabledSkills: Set<String> = emptySet(),        // 启用的 skill 名称列表
    val enableTimeReminder: Boolean = false,            // 时间间隔提醒注入
    val allowConversationSystemPrompt: Boolean = false, // 允许对话单独重写 system prompt
    val allowConversationPromptInjection: Boolean = false, // 允许对话单独绑定提示词注入
    val enableEnhancementPrompt: Boolean = false, // 在最后一条用户消息后追加增强提示词
    val enhancementPrompt: String = "",           // 增强提示词正文
    val tavernCardJson: String? = null,           // SillyTavern 角色卡原始 JSON（V2/V3 格式），用于查看/导出
    val statusRenderJs: String? = null,           // 状态渲染 JS 脚本（renderStatus 函数）
    // ── 群组助手 ──
    val assistantType: AssistantType = AssistantType.SOLO,
    val groupMembers: List<GroupMember> = emptyList(),
    val turnTakingStrategy: TurnTakingStrategy = TurnTakingStrategy.MANUAL,
    val groupReplyOptions: GroupReplyOptions = GroupReplyOptions(),
    val groupContextOptions: GroupContextOptions = GroupContextOptions(),
    val groupMemberCombos: List<GroupMemberCombo> = emptyList(),
    val hiddenQuickMessageIds: Set<Uuid> = emptySet(), // 助手级隐藏的全局快捷指令 ID
    // ── 作者注释（Author's Note @ Depth）──
    val authorNote: AuthorNote = AuthorNote(),          // 助手级作者注释
    val allowConversationAuthorNote: Boolean = false,   // 允许会话级作者注释覆盖助手配置
)

@Serializable
data class QuickMessage(
    val id: Uuid = Uuid.random(),
    val title: String = "",
    val content: String = "",
    val autoSend: Boolean = false,                          // 填入后立即发送
    val mode: QuickMessageMode = QuickMessageMode.APPEND,   // 填入模式
    val order: Int = 0,                                     // 排序权重，越小越靠前
)

/**
 * 快捷指令填入模式
 */
@Serializable
enum class QuickMessageMode {
    @SerialName("append")
    APPEND,     // 追加到当前输入

    @SerialName("replace")
    REPLACE,    // 替换当前输入
}

/**
 * 解析助手可见的快捷指令：已绑定（[quickMessageIds]）且未被助手级隐藏（[hiddenQuickMessageIds]），
 * 按 [QuickMessage.order] 升序排列（相同 order 保持原有相对顺序）。
 */
fun resolveVisibleQuickMessages(
    quickMessages: List<QuickMessage>,
    quickMessageIds: Set<Uuid>,
    hiddenQuickMessageIds: Set<Uuid>,
): List<QuickMessage> = quickMessages
    .filter { it.id in quickMessageIds && it.id !in hiddenQuickMessageIds }
    .sortedBy { it.order }

/**
 * 清理助手上过期的快捷指令引用：全局条目被删除后，同步移除绑定与助手级隐藏记录。
 */
fun Assistant.sanitizeQuickMessageRefs(validQuickMessageIds: Set<Uuid>): Assistant = copy(
    quickMessageIds = quickMessageIds.filter { it in validQuickMessageIds }.toSet(),
    hiddenQuickMessageIds = hiddenQuickMessageIds.filter { it in validQuickMessageIds }.toSet(),
)

@Serializable
data class AssistantMemory(
    val id: Int,
    val content: String = "",
)

@Serializable
enum class AssistantAffectScope {
    USER,
    ASSISTANT,
}

@Serializable
data class AssistantRegex(
    val id: Uuid,
    val name: String = "",
    val enabled: Boolean = true,
    val findRegex: String = "", // 正则表达式
    val replaceString: String = "", // 替换字符串
    val affectingScope: Set<AssistantAffectScope> = setOf(),
    val visualOnly: Boolean = false, // 是否仅在视觉上影响
    val options: Set<RegexOption> = emptySet(), // 正则修饰标志（对应 ST 的 i/m/s：IGNORE_CASE/MULTILINE/DOT_MATCHES_ALL）
    val minDepth: Int? = null, // 应用的消息倒序深度下限（0 = 最新消息），null = 不限
    val maxDepth: Int? = null, // 应用的消息倒序深度上限，null = 不限
)

/**
 * 判断规则是否命中指定的消息倒序深度（0 = 最新消息）。
 * minDepth/maxDepth 为 null 的一侧不限制。
 */
fun AssistantRegex.matchesDepth(depth: Int): Boolean =
    (minDepth == null || depth >= minDepth) && (maxDepth == null || depth <= maxDepth)

// 流式输出时每个chunk都会调用replaceRegexes，正则必须缓存编译结果，
// 否则长回复期间会重复编译上万次；编译失败也缓存，避免反复构造异常
private val regexCache = SimpleCache.builder<String, Result<Regex>>()
    .expireAfterWrite(10, TimeUnit.MINUTES)
    .build()

// 缓存键必须同时区分 pattern 与 options，避免同 pattern 不同标志的规则互相污染；
// 用 options 名序列 + pattern 长度做前缀，保证键与 (options, pattern) 一一对应
private fun regexCacheKey(pattern: String, options: Set<RegexOption>): String {
    val optionsKey = options.sortedBy { it.ordinal }.joinToString(separator = ",") { it.name }
    return "$optionsKey|${pattern.length}|$pattern"
}

private fun compileRegexCached(pattern: String, options: Set<RegexOption>): Regex? {
    val key = regexCacheKey(pattern, options)
    regexCache.getIfPresent(key)?.let { return it.getOrNull() }
    val result = runCatching {
        if (options.isEmpty()) Regex(pattern) else Regex(pattern, options)
    }.onFailure { it.printStackTrace() }
    regexCache.put(key, result)
    return result.getOrNull()
}

fun String.replaceRegexes(
    assistant: Assistant?,
    scope: AssistantAffectScope,
    visual: Boolean = false,
    depth: Int? = null, // 消息倒序深度；null 表示调用方无深度上下文，不做深度过滤（保持旧行为）
): String {
    if (assistant == null) return this
    if (assistant.regexes.isEmpty()) return this
    return assistant.regexes.fold(this) { acc, regex ->
        if (regex.enabled && regex.visualOnly == visual && regex.affectingScope.contains(scope) &&
            (depth == null || regex.matchesDepth(depth))
        ) {
            val compiled = compileRegexCached(regex.findRegex, regex.options) ?: return@fold acc
            try {
                acc.replace(
                    regex = compiled,
                    replacement = regex.replaceString,
                )
            } catch (e: Exception) {
                e.printStackTrace()
                // 替换字符串可能引用不存在的分组，失败时返回原字符串
                acc
            }
        } else {
            acc
        }
    }
}

/**
 * 作者注释（Author's Note）
 *
 * 以 [depth] 指定的深度（从最新消息往前数）注入到对话上下文中，
 * 合成 PromptInjection.ModeInjection(position = AT_DEPTH) 后走统一的注入管线，
 * 自动获得安全插入、同深度同 role 合并、优先级排序与 PromptTrace 记录。
 *
 * [interval] 为注入间隔（按用户消息轮数取模，见 PromptInjectionTransformer 的确定性规则）。
 */
@Serializable
data class AuthorNote(
    val enabled: Boolean = false,
    val content: String = "",
    val depth: Int = 4,
    val role: MessageRole = MessageRole.USER,
    val interval: Int = 1,
    val position: InjectionPosition = InjectionPosition.AT_DEPTH, // 注入位置（ST Position 对齐；TOP/BOTTOM 时 depth 无效）
)

/**
 * 注入位置
 */
@Serializable
enum class InjectionPosition {
    @SerialName("before_system_prompt")
    BEFORE_SYSTEM_PROMPT,   // 系统提示词之前

    @SerialName("after_system_prompt")
    AFTER_SYSTEM_PROMPT,    // 系统提示词之后（最常用）

    @SerialName("top_of_chat")
    TOP_OF_CHAT,            // 对话最开头（第一条用户消息之前）

    @SerialName("bottom_of_chat")
    BOTTOM_OF_CHAT,         // 最新消息之前（当前用户输入之前）

    @SerialName("at_depth")
    AT_DEPTH,               // 在指定深度位置插入（从最新消息往前数）
}

/**
 * 提示词注入
 *
 * - ModeInjection: 基于模式开关的注入（如学习模式）
 * - RegexInjection: 基于正则匹配的注入（Lorebook）
 */
@Serializable
sealed class PromptInjection {
    abstract val id: Uuid
    abstract val name: String
    abstract val enabled: Boolean
    abstract val priority: Int
    abstract val position: InjectionPosition
    abstract val content: String
    abstract val injectDepth: Int  // 当 position 为 AT_DEPTH 时使用，表示从最新消息往前数的位置
    abstract val role: MessageRole  // 注入角色：USER 或 ASSISTANT

    /**
     * 模式注入 - 基于开关状态触发
     */
    @Serializable
    @SerialName("mode")
    data class ModeInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
    ) : PromptInjection()

    /**
     * 正则注入 - 基于内容匹配触发（世界书）
     */
    @Serializable
    @SerialName("regex")
    data class RegexInjection(
        override val id: Uuid = Uuid.random(),
        override val name: String = "",
        override val enabled: Boolean = true,
        override val priority: Int = 0,
        override val position: InjectionPosition = InjectionPosition.AFTER_SYSTEM_PROMPT,
        override val content: String = "",
        override val injectDepth: Int = 4,
        override val role: MessageRole = MessageRole.USER,
        val keywords: List<String> = emptyList(),  // 触发关键词
        val useRegex: Boolean = false,             // 是否使用正则匹配
        val caseSensitive: Boolean = false,        // 大小写敏感
        val matchWholeWords: Boolean = false,      // 整词匹配（ST Match Whole Words；正则模式不叠加）
        val scanDepth: Int = 4,                    // 扫描最近N条消息
        val constantActive: Boolean = false,       // 常驻激活（无需匹配）
        val secondaryKeywords: List<String> = emptyList(), // 次要关键词（selective 时需与主关键词同时命中）
        val selective: Boolean = false,            // 选择性触发：主关键词与次关键词都命中才注入
        val probability: Int = 100,                // 触发概率（0-100，100 为必定注入）
        val sticky: Int = 0,                       // 粘性：命中后持续注入 N 个用户轮次（0 = 关闭）
        val cooldown: Int = 0,                     // 冷却：命中后 N 个用户轮次内不再触发（0 = 关闭）
        val delay: Int = 0,                        // 延迟：对话前 N 个用户轮次不触发（0 = 关闭）
    ) : PromptInjection()
}

/**
 * Lorebook - 组织管理多个 RegexInjection
 */
@Serializable
data class Lorebook(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val description: String = "",
    val enabled: Boolean = true,
    val entries: List<PromptInjection.RegexInjection> = emptyList(),
    val tokenBudget: Int = 0,               // 注入预算（按字符数近似，0 = 不限制）
    val recursiveScanning: Boolean = false, // 递归扫描：已命中条目的内容纳入扫描文本继续匹配
)

// region 群组助手相关类型

@Serializable
enum class AssistantType {
    @SerialName("solo") SOLO,
    @SerialName("group") GROUP,
}

@Serializable
enum class ContextScope {
    @SerialName("all") ALL,
    @SerialName("self") SELF,
    @SerialName("member_list") MEMBER_LIST,
    @SerialName("directed") DIRECTED,
}

@Serializable
data class ContextFilter(
    val scope: ContextScope = ContextScope.ALL,
    val visibleMemberIds: List<Uuid> = emptyList(),
    val excludedMemberIds: List<Uuid> = emptyList(),
    val mentionEnabled: Boolean = false,
    val mentionKeywords: List<String> = emptyList(),
    val maxMessages: Int = 0,  // 0 = 不限制
)

@Serializable
data class GroupMember(
    val id: Uuid = Uuid.random(),
    val assistantId: Uuid,
    val displayName: String = "",
    val avatar: Avatar = Avatar.Dummy,
    val systemPromptOverride: String? = null,
    val chatModelIdOverride: Uuid? = null,
    val enabled: Boolean = true,
    val contextFilter: ContextFilter = ContextFilter(),
)

@Serializable
enum class TurnTakingStrategy {
    @SerialName("manual") MANUAL,
    @SerialName("auto_round_robin") AUTO_ROUND_ROBIN,
    @SerialName("auto_moderator") AUTO_MODERATOR,
}

/**
 * 群组手动模式下的「常用成员组合」—— 用户保存一组成员（含顺序），下次直接一键应用。
 * 顺序就是发言顺序，所以用 List 而非 Set。
 */
@Serializable
data class GroupMemberCombo(
    val id: Uuid = Uuid.random(),
    val name: String = "",
    val memberIds: List<Uuid> = emptyList(),
)

@Serializable
data class GroupReplyOptions(
    val allowConsecutiveSameSpeaker: Boolean = false,
    val maxAutoRepliesPerUserTurn: Int = 1,
)

@Serializable
data class GroupContextOptions(
    val enableLayeredContext: Boolean = true,
    val enablePrivateViewpoint: Boolean = true,
    val enableRelationshipNotes: Boolean = true,
    val enableSceneState: Boolean = true,
    val enableMotivationScoring: Boolean = true,
    val maxPrivateNoteChars: Int = 800,
    val maxSceneSummaryChars: Int = 800,
)

// endregion

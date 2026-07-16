package me.rerere.rikkahub.service

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.model.Conversation
import java.util.concurrent.atomic.AtomicInteger
import kotlin.uuid.Uuid

private const val TAG = "ConversationSession"
private const val IDLE_TIMEOUT_MS = 5_000L

internal data class GroupGenerationHandoffResult<T>(
    val value: T,
    val shouldContinue: Boolean,
)

class ConversationSession(
    val id: Uuid,
    initial: Conversation,
    private val scope: CoroutineScope,
    private val onIdle: (Uuid) -> Unit,
) {
    // 会话状态
    val state = MutableStateFlow(initial)

    // 原子引用计数
    private val refCount = AtomicInteger(0)

    // 处理状态（如 OCR 识别中）
    val processingStatus = MutableStateFlow<String?>(null)

    // 生成任务（内聚在 session 中）
    private val _generationJob = MutableStateFlow<Job?>(null)
    val generationJob: StateFlow<Job?> = _generationJob.asStateFlow()
    val isGenerating: Boolean get() = _generationJob.value?.isActive == true
    val isInUse: Boolean get() = refCount.get() > 0 || isGenerating

    private val groupDirectorMutex = Mutex()
    private var groupReplyActiveJob: Job? = null

    suspend fun <T> withGroupDirectorLock(block: suspend () -> T): T =
        groupDirectorMutex.withLock { block() }

    internal fun markGroupReplyStartedLocked(job: Job?) {
        groupReplyActiveJob = job
    }

    internal fun isGroupReplyActiveLocked(): Boolean =
        groupReplyActiveJob != null && groupReplyActiveJob === _generationJob.value

    internal fun releaseGroupGenerationLocked(job: Job?) {
        if (groupReplyActiveJob === job) {
            groupReplyActiveJob = null
        }
        if (job != null) {
            _generationJob.compareAndSet(job, null)
        }
    }

    internal suspend fun <T> completeGroupReplyHandoff(
        job: Job?,
        block: suspend () -> GroupGenerationHandoffResult<T>,
    ): GroupGenerationHandoffResult<T> = groupDirectorMutex.withLock {
        try {
            block().also { result ->
                if (groupReplyActiveJob === job) {
                    groupReplyActiveJob = null
                }
                if (!result.shouldContinue && job != null) {
                    _generationJob.compareAndSet(job, null)
                }
            }
        } catch (error: Throwable) {
            releaseGroupGenerationLocked(job)
            throw error
        }
    }

    internal suspend fun <T> completeOwnedGroupCancellation(
        job: Job?,
        staleValue: () -> T,
        block: suspend () -> T,
    ): T = groupDirectorMutex.withLock {
        val currentGenerationJob = _generationJob.value
        // A successor installs its generation job before it marks the reply phase.
        val ownsGenerationOrReply = job != null && if (currentGenerationJob != null) {
            currentGenerationJob === job
        } else {
            groupReplyActiveJob === job
        }
        if (!ownsGenerationOrReply) {
            if (groupReplyActiveJob === job) {
                groupReplyActiveJob = null
            }
            return@withLock staleValue()
        }
        try {
            block()
        } finally {
            releaseGroupGenerationLocked(job)
        }
    }

    // 空闲检查任务
    private var idleCheckJob: Job? = null

    fun acquire(): Int = refCount.incrementAndGet().also {
        cancelIdleCheck()
        Log.d(TAG, "acquire $id (refs=$it)")
    }

    fun release(): Int = refCount.decrementAndGet().also {
        Log.d(TAG, "release $id (refs=$it)")
        if (it <= 0) scheduleIdleCheck()
    }

    // 作用域 API - 短请求（REST）
    inline fun <T> withRef(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    // 作用域 API - 长连接（SSE、挂起函数）
    suspend inline fun <T> withRefSuspend(block: () -> T): T {
        acquire()
        try {
            return block()
        } finally {
            release()
        }
    }

    fun setJob(job: Job?) {
        _generationJob.value?.cancel()
        _generationJob.value = job
        job?.invokeOnCompletion {
            _generationJob.compareAndSet(job, null)
            if (refCount.get() <= 0) {
                scheduleIdleCheck()
            }
        }
    }

    fun getJob(): Job? = _generationJob.value

    private fun scheduleIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = scope.launch {
            delay(IDLE_TIMEOUT_MS)
            if (refCount.get() <= 0 && !isGenerating) {
                onIdle(id)
            }
        }
    }

    private fun cancelIdleCheck() {
        idleCheckJob?.cancel()
        idleCheckJob = null
    }

    fun cleanup() {
        _generationJob.value?.cancel()
        _generationJob.value = null
        groupReplyActiveJob = null
        idleCheckJob?.cancel()
        idleCheckJob = null
    }
}

package com.qilu.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qilu.ai.api.dto.CampusAssistantResponse;
import com.qilu.ai.api.dto.CampusAssistantSourceDTO;
import com.qilu.ai.api.dto.CampusMemoryActionDraftDTO;
import com.qilu.ai.api.dto.CampusMemoryDTO;
import com.qilu.ai.api.dto.CampusMemoryDiagnosticsDTO;
import com.qilu.ai.api.dto.CampusMemoryEntitiesDTO;
import com.qilu.ai.api.dto.CampusMemoryEntityDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryRequestDTO;
import com.qilu.ai.api.dto.CampusMemorySummaryResponseDTO;
import com.qilu.ai.api.dto.CampusMemoryTurnDTO;
import com.qilu.ai.api.service.AiCampusAssistantService;
import com.qilu.config.AiMemoryProperties;
import com.qilu.dto.ai.AiMemoryBuildResult;
import com.qilu.dto.ai.AiMessageTurn;
import com.qilu.entity.AiMessage;
import com.qilu.entity.AiSessionMemory;
import com.qilu.mapper.AiSessionMemoryMapper;
import com.qilu.metrics.AiMainMetrics;
import com.qilu.service.IAiMessageService;
import com.qilu.service.IAiSessionService;
import com.qilu.service.IAiSessionMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import gamer.springboot.starter.annotation.RpcReference;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

@Service
public class AiSessionMemoryServiceImpl
        extends ServiceImpl<AiSessionMemoryMapper, AiSessionMemory>
        implements IAiSessionMemoryService {

    private static final Logger log = LoggerFactory.getLogger(AiSessionMemoryServiceImpl.class);
    private static final int MAX_ENTITY_CANDIDATES = 3;
    private static final int MAX_OPTIMISTIC_RETRIES = 2;
    private static final Object[] MEMORY_UPDATE_LOCKS = createUpdateLocks(64);
    private static final Set<String> SUMMARY_SOURCES = new LinkedHashSet<>(
            Arrays.asList("deterministic", "model"));
    private static final Set<String> SUMMARY_STATUSES = new LinkedHashSet<>(
            Arrays.asList("ready", "pending", "degraded", "rebuild_required"));
    private static final Pattern URL_PATTERN = Pattern.compile(
            "https?://[^\\s,;，；]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECRET_PATTERN = Pattern.compile(
            "(?i)(api[_-]?key|access[_-]?token|token|password|secret|密码)\\s*[:=：]\\s*[^\\s,;，；]+");
    private static final Pattern ENTITY_ID_PATTERN = Pattern.compile(
            "(?i)(?:(工单|预约|服务点|ticket|appointment|service\\s*point)"
                    + "\\s*(?:[_-]?id|#|号)?\\s*[:=：]?\\s*\\d+"
                    + "|\\d+\\s*(?:号|#)?\\s*(工单|预约|服务点|ticket|appointment|service\\s*point))");
    private static final Pattern INSTRUCTION_PATTERN = Pattern.compile(
            "(?i)(ignore\\s+(all\\s+)?previous|system\\s+prompt|忽略.{0,8}(指令|要求)|系统提示词)");

    @Resource
    private IAiMessageService aiMessageService;

    @Resource
    private IAiSessionService aiSessionService;

    @Resource
    private AiMemoryProperties properties;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private AiMainMetrics aiMainMetrics;

    @Resource(name = "aiMemorySummaryExecutor")
    private Executor summaryExecutor;

    @RpcReference(interfaceClass = AiCampusAssistantService.class)
    private AiCampusAssistantService aiCampusAssistantService;

    /** 同一 session 最多一个模型摘要任务，防止并发请求放大后台调用。 */
    private final Set<Long> summaryInFlight = ConcurrentHashMap.newKeySet();

    @Override
    public AiMemoryBuildResult buildMemory(Long sessionId, Long userId) {
        properties.validate();
        if (sessionId == null || userId == null
                || !aiSessionService.canAccessSession(sessionId, userId)) {
            return buildTransientResult(sessionId, Collections.emptyList(), "MEMORY_IDENTITY_INVALID");
        }
        List<AiMessageTurn> turns = aiMessageService.queryRecentCompleteTurns(
                sessionId,
                userId,
                properties.getRebuildTurns());

        MemoryLoad load = loadOrRebuild(sessionId, userId, turns);
        CampusMemoryDTO memory = toCampusMemory(sessionId, turns, load.record);
        boolean summaryDegraded = "degraded".equals(load.record.getSummaryStatus());
        String degradedReason = load.degradedReason != null
                ? load.degradedReason
                : summaryDegraded ? "MEMORY_SUMMARY_DEGRADED" : null;
        resumePendingModelSummary(sessionId, userId, turns, load.record);
        CampusMemoryDiagnosticsDTO diagnostics = diagnostics(
                memory,
                degradedReason != null,
                degradedReason,
                load.resolutionSource);
        aiMainMetrics.recordMemoryBuild(
                memory.getMode(),
                degradedReason == null ? "success" : "degraded",
                memory.getRecentTurns().size(),
                memory.getEstimatedTokens(),
                Boolean.TRUE.equals(memory.getTruncated()));
        return new AiMemoryBuildResult(memory, diagnostics);
    }

    @Override
    public void updateAfterAssistantMessage(
            Long sessionId,
            Long userId,
            String turnId,
            AiMessage assistantMessage,
            CampusAssistantResponse response) {
        if (sessionId == null || userId == null || assistantMessage == null
                || assistantMessage.getId() == null || response == null
                || !aiSessionService.canAccessSession(sessionId, userId)) {
            return;
        }
        try {
            SummaryWork summaryWork;
            // 同 JVM 同会话先按固定条带串行合并，跨实例仍由数据库 version 乐观锁兜底。
            synchronized (memoryUpdateLock(sessionId)) {
                summaryWork = updateWithOptimisticLock(
                        sessionId, userId, turnId, assistantMessage, response);
            }
            if (summaryWork != null) {
                submitModelSummary(summaryWork);
            }
        } catch (RuntimeException error) {
            // Memory 是增强能力，持久化失败不得覆盖已成功形成的主回答。
            aiMainMetrics.recordMemoryDegraded("MEMORY_UPDATE_FAILED");
            log.warn("AI Memory update degraded, reason=MEMORY_UPDATE_FAILED, errorType={}",
                    error.getClass().getSimpleName());
        }
    }

    /**
     * 读取并校验持久化记录。Schema、归属、位置或 JSON 任一不可信时，
     * 只从当前 session 的完整 MySQL 轮次重建，不跨会话寻找相似历史。
     */
    private MemoryLoad loadOrRebuild(Long sessionId, Long userId, List<AiMessageTurn> turns) {
        try {
            AiSessionMemory current = getById(sessionId);
            if (isValidRecord(current, userId, turns)) {
                return new MemoryLoad(current, null, "mysql_memory");
            }
            aiMainMetrics.recordMemoryRebuild(rebuildReason(current, userId, turns));
            AiSessionMemory rebuilt = rebuildAndPersist(sessionId, userId, turns, current);
            return new MemoryLoad(rebuilt, null, "mysql_rebuild");
        } catch (RuntimeException error) {
            aiMainMetrics.recordMemoryDegraded("MEMORY_STORE_UNAVAILABLE");
            log.warn("AI Memory read degraded, reason=MEMORY_STORE_UNAVAILABLE, errorType={}",
                    error.getClass().getSimpleName());
            return new MemoryLoad(transientRecord(sessionId, userId, turns),
                    "MEMORY_STORE_UNAVAILABLE", "recent_turns");
        }
    }

    private AiSessionMemory rebuildAndPersist(
            Long sessionId,
            Long userId,
            List<AiMessageTurn> turns,
            AiSessionMemory initial) {
        AiSessionMemory current = initial;
        for (int attempt = 0; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            CampusMemoryEntitiesDTO entities = rebuildEntities(turns);
            AiSessionMemory rebuilt = newRecord(sessionId, userId, turns, entities);
            if (current == null) {
                try {
                    save(rebuilt);
                    return rebuilt;
                } catch (DuplicateKeyException conflict) {
                    current = getById(sessionId);
                    continue;
                }
            }

            long currentVersion = safeVersion(current);
            long nextVersion = currentVersion + 1;
            rebuilt.setVersion(nextVersion);
            int updated = baseMapper.update(null, updateWrapper(current, rebuilt));
            if (updated == 1) {
                return rebuilt;
            }
            current = getById(sessionId);
            if (isValidRecord(current, userId, turns)) {
                return current;
            }
        }
        throw new IllegalStateException("AI Memory rebuild optimistic lock exhausted");
    }

    /** 同一 assistant 消息最多更新一次；冲突后重读并合并，绝不覆盖较新版本。 */
    private SummaryWork updateWithOptimisticLock(
            Long sessionId,
            Long userId,
            String turnId,
            AiMessage assistantMessage,
            CampusAssistantResponse response) {
        properties.validate();
        for (int attempt = 0; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            List<AiMessageTurn> turns = aiMessageService.queryRecentCompleteTurns(
                    sessionId,
                    userId,
                    properties.getRebuildTurns());
            MemoryLoad load = loadOrRebuild(sessionId, userId, turns);
            AiSessionMemory current = load.record;
            if (load.degradedReason != null) {
                return null;
            }

            CampusMemoryEntitiesDTO entities = parseEntities(current.getEntitiesJson());
            String beforeJson = writeEntities(entities);
            mergeResponseEntities(entities, turnId, assistantMessage.getId(), response);
            String entitiesJson = writeEntities(entities);
            long previousLastProcessed = safeLastProcessed(current);
            String previousSummary = StrUtil.blankToDefault(current.getRollingSummary(), "");
            String deterministicSummary = buildDeterministicSummary(
                    previousSummary, turns, previousLastProcessed);
            if (assistantMessage.getId() <= previousLastProcessed
                    && beforeJson.equals(entitiesJson)
                    && previousSummary.equals(deterministicSummary)) {
                return null;
            }
            // 查询窗口可能已包含其他并发完成轮次；摘要处理位置必须覆盖本次实际合并的全部轮次。
            long nextLastProcessed = Math.max(
                    Math.max(previousLastProcessed, assistantMessage.getId()),
                    latestMessageId(turns));
            long currentVersion = safeVersion(current);
            long nextVersion = currentVersion + 1;
            SummaryWork summaryWork = createSummaryWork(
                    sessionId,
                    userId,
                    nextVersion,
                    nextLastProcessed,
                    safeLastModelSummary(current),
                    deterministicSummary,
                    turns);
            boolean summaryChanged = !previousSummary.equals(deterministicSummary);
            UpdateWrapper<AiSessionMemory> update = new UpdateWrapper<AiSessionMemory>()
                    .eq("session_id", sessionId)
                    .eq("user_id", userId)
                    .eq("version", currentVersion)
                    .set("last_processed_message_id", nextLastProcessed)
                    .set("rolling_summary", deterministicSummary)
                    .set("entities_json", entitiesJson)
                    .set("summary_source", summaryChanged
                            ? "deterministic" : current.getSummarySource())
                    .set("summary_status", summaryWork == null ? "ready" : "pending")
                    .set("version", nextVersion)
                    .set("update_time", LocalDateTime.now());
            if (baseMapper.update(null, update) == 1) {
                aiMainMetrics.recordMemoryConflict(attempt == 0 ? "no_conflict" : "retried");
                if (summaryChanged) {
                    aiMainMetrics.recordMemorySummary("deterministic", "success");
                }
                return summaryWork;
            }
            aiMainMetrics.recordMemoryConflict("conflict");
        }
        markRebuildRequired(sessionId, userId);
        aiMainMetrics.recordMemoryConflict("exhausted");
        return null;
    }

    private void markRebuildRequired(Long sessionId, Long userId) {
        AiSessionMemory latest = getById(sessionId);
        if (latest == null || !Objects.equals(userId, latest.getUserId())) {
            return;
        }
        long version = safeVersion(latest);
        baseMapper.update(null, new UpdateWrapper<AiSessionMemory>()
                .eq("session_id", sessionId)
                .eq("user_id", userId)
                .eq("version", version)
                .set("summary_status", "rebuild_required")
                .set("version", version + 1)
                .set("update_time", LocalDateTime.now()));
    }

    private UpdateWrapper<AiSessionMemory> updateWrapper(
            AiSessionMemory current,
            AiSessionMemory rebuilt) {
        return new UpdateWrapper<AiSessionMemory>()
                .eq("session_id", current.getSessionId())
                .eq("version", safeVersion(current))
                .set("user_id", rebuilt.getUserId())
                .set("schema_version", rebuilt.getSchemaVersion())
                .set("last_processed_message_id", rebuilt.getLastProcessedMessageId())
                .set("last_model_summary_message_id", rebuilt.getLastModelSummaryMessageId())
                .set("rolling_summary", rebuilt.getRollingSummary())
                .set("entities_json", rebuilt.getEntitiesJson())
                .set("summary_source", rebuilt.getSummarySource())
                .set("summary_status", rebuilt.getSummaryStatus())
                .set("version", rebuilt.getVersion())
                .set("update_time", LocalDateTime.now());
    }

    private boolean isValidRecord(
            AiSessionMemory record,
            Long userId,
            List<AiMessageTurn> turns) {
        if (record == null
                || !Objects.equals(userId, record.getUserId())
                || !properties.getSchemaVersion().equals(record.getSchemaVersion())
                || safeVersion(record) < 0
                || safeLastProcessed(record) < 0
                || safeLastModelSummary(record) < 0
                || safeLastModelSummary(record) > safeLastProcessed(record)
                || !SUMMARY_SOURCES.contains(record.getSummarySource())
                || !SUMMARY_STATUSES.contains(record.getSummaryStatus())
                || "rebuild_required".equals(record.getSummaryStatus())) {
            return false;
        }
        String summary = StrUtil.blankToDefault(record.getRollingSummary(), "");
        if (summary.codePointCount(0, summary.length()) > properties.getSummaryMaxChars()) {
            return false;
        }
        long latestMessageId = latestMessageId(turns);
        if (safeLastProcessed(record) > latestMessageId) {
            return false;
        }
        try {
            parseEntities(record.getEntitiesJson());
            return true;
        } catch (RuntimeException invalidJson) {
            return false;
        }
    }

    private AiSessionMemory newRecord(
            Long sessionId,
            Long userId,
            List<AiMessageTurn> turns,
            CampusMemoryEntitiesDTO entities) {
        LocalDateTime now = LocalDateTime.now();
        return new AiSessionMemory()
                .setSessionId(sessionId)
                .setUserId(userId)
                .setSchemaVersion(properties.getSchemaVersion())
                .setLastProcessedMessageId(latestMessageId(turns))
                .setLastModelSummaryMessageId(0L)
                .setRollingSummary(buildDeterministicSummary("", turns, 0L))
                .setEntitiesJson(writeEntities(entities))
                .setSummarySource("deterministic")
                .setSummaryStatus("ready")
                .setVersion(0L)
                .setCreateTime(now)
                .setUpdateTime(now);
    }

    private AiSessionMemory transientRecord(
            Long sessionId,
            Long userId,
            List<AiMessageTurn> turns) {
        return newRecord(sessionId, userId, turns, rebuildEntities(turns));
    }

    private AiMemoryBuildResult buildTransientResult(
            Long sessionId,
            List<AiMessageTurn> turns,
            String reason) {
        AiSessionMemory record = transientRecord(sessionId, null, turns);
        CampusMemoryDTO memory = toCampusMemory(sessionId, turns, record);
        return new AiMemoryBuildResult(
                memory,
                diagnostics(memory, true, reason, "recent_turns"));
    }

    /**
     * 从最新轮次向前加入窗口；先执行单字段字符上限，再执行总 Token 预算，
     * 任何裁剪都显式设置 truncated，避免请求体随总历史线性增长。
     */
    private CampusMemoryDTO toCampusMemory(
            Long sessionId,
            List<AiMessageTurn> turns,
            AiSessionMemory record) {
        CampusMemoryEntitiesDTO entities = parseEntities(record.getEntitiesJson());
        String summary = StrUtil.blankToDefault(record.getRollingSummary(), "");
        int estimatedTokens = estimateTokens(summary) + estimateTokens(writeEntities(entities)) + 16;
        boolean truncated = turns.size() > properties.getRecentTurns();
        List<CampusMemoryTurnDTO> recent = new ArrayList<>();
        int first = Math.max(0, turns.size() - properties.getRecentTurns());
        for (int index = turns.size() - 1; index >= first; index--) {
            BoundedTurn bounded = boundedTurn(turns.get(index));
            int turnTokens = estimateTurnTokens(bounded.turn);
            if (estimatedTokens + turnTokens > properties.getMaxInputTokens()) {
                truncated = true;
                break;
            }
            recent.add(0, bounded.turn);
            estimatedTokens += turnTokens;
            truncated = truncated || bounded.truncated;
        }

        CampusMemoryDTO memory = new CampusMemoryDTO();
        memory.setMode(properties.getMode());
        memory.setSchemaVersion(properties.getSchemaVersion());
        memory.setConversationId(String.valueOf(sessionId));
        memory.setRecentTurns(recent);
        memory.setRollingSummary(summary);
        memory.setEntities(entities);
        memory.setLastProcessedMessageId(safeLastProcessed(record));
        memory.setSummaryVersion(safeVersion(record));
        memory.setTruncated(truncated);
        memory.setEstimatedTokens(Math.min(estimatedTokens, properties.getMaxInputTokens()));
        return memory;
    }

    private BoundedTurn boundedTurn(AiMessageTurn source) {
        AiMessage user = source.getUserMessage();
        AiMessage assistant = source.getAssistantMessage();
        BoundedText question = boundText(user == null ? null : user.getContent());
        BoundedText answer = boundText(assistant == null ? null : assistant.getContent());
        CampusMemoryTurnDTO turn = new CampusMemoryTurnDTO();
        turn.setTurnId(firstNonBlank(
                assistant == null ? null : assistant.getTurnId(),
                user == null ? null : user.getTurnId()));
        turn.setQuestion(question.value);
        turn.setAnswer(answer.value);
        turn.setIntent(assistant == null ? null : assistant.getIntent());
        return new BoundedTurn(turn, question.truncated || answer.truncated);
    }

    private BoundedText boundText(String value) {
        String text = StrUtil.blankToDefault(value, "");
        int codePoints = text.codePointCount(0, text.length());
        if (codePoints <= properties.getMaxTurnChars()) {
            return new BoundedText(text, false);
        }
        int end = text.offsetByCodePoints(0, properties.getMaxTurnChars());
        return new BoundedText(text.substring(0, end), true);
    }

    private int estimateTurnTokens(CampusMemoryTurnDTO turn) {
        return estimateTokens(turn.getTurnId())
                + estimateTokens(turn.getQuestion())
                + estimateTokens(turn.getAnswer())
                + estimateTokens(turn.getIntent())
                + 8;
    }

    /** 中文及其他非 ASCII 字符按一字符一 Token 保守估算，ASCII 按四字符一 Token。 */
    private int estimateTokens(String value) {
        if (StrUtil.isEmpty(value)) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int offset = 0; offset < value.length(); ) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint <= 0x7F) {
                asciiRun++;
            } else {
                tokens += (asciiRun + 3) / 4;
                asciiRun = 0;
                tokens++;
            }
        }
        return tokens + (asciiRun + 3) / 4;
    }

    /**
     * 确定性摘要只记录用户主题和稳定 intent，不复制 assistant 回答、业务状态或工具结果。
     * 输入先脱敏并移除业务 ID/提示注入表达，再按 Unicode 字符数执行硬上限。
     */
    private String buildDeterministicSummary(
            String existingSummary,
            List<AiMessageTurn> turns,
            long afterMessageId) {
        String summary = sanitizeMemoryText(existingSummary, properties.getSummaryMaxChars());
        for (AiMessageTurn turn : turns) {
            if (turn == null || turn.getCompletionMessageId() == null
                    || turn.getCompletionMessageId() <= afterMessageId
                    || turn.getUserMessage() == null) {
                continue;
            }
            String intent = sanitizeMemoryText(
                    turn.getAssistantMessage() == null ? null : turn.getAssistantMessage().getIntent(),
                    64);
            String topic = sanitizeMemoryText(turn.getUserMessage().getContent(), 120);
            if (StrUtil.isBlank(topic)) {
                continue;
            }
            String segment = "intent=" + StrUtil.blankToDefault(intent, "general")
                    + ", topic=" + topic;
            summary = StrUtil.isBlank(summary) ? segment : summary + "；" + segment;
            summary = keepRightCodePoints(summary, properties.getSummaryMaxChars());
        }
        return summary;
    }

    private SummaryWork createSummaryWork(
            Long sessionId,
            Long userId,
            long baseVersion,
            long lastProcessedMessageId,
            long lastModelSummaryMessageId,
            String deterministicSummary,
            List<AiMessageTurn> turns) {
        if (!properties.isSummarizerEnabled()) {
            return null;
        }
        List<CampusMemoryTurnDTO> pending = new ArrayList<>();
        int pendingTokens = 0;
        for (AiMessageTurn turn : turns) {
            if (turn == null || turn.getCompletionMessageId() == null
                    || turn.getCompletionMessageId() <= lastModelSummaryMessageId) {
                continue;
            }
            CampusMemoryTurnDTO controlled = controlledSummaryTurn(turn);
            pending.add(controlled);
            pendingTokens += estimateTokens(controlled.getQuestion())
                    + estimateTokens(controlled.getIntent()) + 4;
        }
        boolean nearLimit = deterministicSummary.codePointCount(0, deterministicSummary.length())
                >= Math.max(64, properties.getSummaryMaxChars() * 4 / 5);
        if (pending.size() < properties.getSummaryTriggerTurns()
                && pendingTokens < properties.getSummaryTriggerTokens()
                && !nearLimit) {
            return null;
        }
        if (pending.size() > 20) {
            pending = new ArrayList<>(pending.subList(pending.size() - 20, pending.size()));
        }
        CampusMemorySummaryRequestDTO request = new CampusMemorySummaryRequestDTO();
        request.setSchemaVersion(properties.getSchemaVersion());
        request.setConversationId(String.valueOf(sessionId));
        request.setBaseVersion(baseVersion);
        request.setLastProcessedMessageId(lastProcessedMessageId);
        request.setPreviousSummary(deterministicSummary);
        request.setTurns(pending);
        request.setMaxSummaryChars(properties.getSummaryMaxChars());
        request.setTimeoutSeconds(properties.getSummarizerTimeoutSeconds());
        request.setMaxRetries(properties.getSummarizerMaxRetries());
        return new SummaryWork(sessionId, userId, baseVersion, lastProcessedMessageId, request);
    }

    private CampusMemoryTurnDTO controlledSummaryTurn(AiMessageTurn turn) {
        CampusMemoryTurnDTO controlled = new CampusMemoryTurnDTO();
        controlled.setTurnId(null);
        controlled.setQuestion(sanitizeMemoryText(
                turn.getUserMessage() == null ? null : turn.getUserMessage().getContent(), 240));
        controlled.setAnswer("");
        controlled.setIntent(sanitizeMemoryText(
                turn.getAssistantMessage() == null ? null : turn.getAssistantMessage().getIntent(), 64));
        return controlled;
    }

    private void submitModelSummary(SummaryWork work) {
        if (!summaryInFlight.add(work.sessionId)) {
            return;
        }
        try {
            summaryExecutor.execute(() -> runModelSummary(work));
        } catch (RejectedExecutionException rejected) {
            summaryInFlight.remove(work.sessionId);
            if (!markSummaryFailure(work, "SUMMARY_QUEUE_FULL")) {
                // 入队前版本已推进时，最新记录同样没有后台任务，必须显式结束 pending 状态。
                markLatestSummaryFailure(work, "SUMMARY_QUEUE_FULL");
            }
        }
    }

    private void runModelSummary(SummaryWork work) {
        boolean stale = false;
        try {
            CampusMemorySummaryResponseDTO response = aiCampusAssistantService.summarizeMemory(work.request);
            String summary = validatedModelSummary(response);
            if (summary == null) {
                stale = !markSummaryFailure(work, stableSummaryError(response));
                return;
            }
            int updated = baseMapper.update(null, new UpdateWrapper<AiSessionMemory>()
                    .eq("session_id", work.sessionId)
                    .eq("user_id", work.userId)
                    .eq("version", work.baseVersion)
                    .eq("last_processed_message_id", work.lastProcessedMessageId)
                    .set("rolling_summary", summary)
                    .set("last_model_summary_message_id", work.lastProcessedMessageId)
                    .set("summary_source", "model")
                    .set("summary_status", "ready")
                    .set("version", work.baseVersion + 1)
                    .set("update_time", LocalDateTime.now()));
            stale = updated != 1;
            aiMainMetrics.recordMemorySummary("model", stale ? "stale" : "success");
        } catch (RuntimeException error) {
            stale = !markSummaryFailure(work, "SUMMARY_UNAVAILABLE");
        } finally {
            summaryInFlight.remove(work.sessionId);
            if (stale) {
                submitLatestSummaryIfNeeded(work.sessionId, work.userId);
            }
        }
    }

    /** 新请求已推进版本时，旧模型结果作废，并基于最新受控状态重新排队一次。 */
    private void submitLatestSummaryIfNeeded(Long sessionId, Long userId) {
        try {
            AiSessionMemory latest = getById(sessionId);
            if (latest == null || !Objects.equals(userId, latest.getUserId())) {
                return;
            }
            List<AiMessageTurn> turns = aiMessageService.queryRecentCompleteTurns(
                    sessionId, userId, properties.getRebuildTurns());
            SummaryWork latestWork = createSummaryWork(
                    sessionId,
                    userId,
                    safeVersion(latest),
                    safeLastProcessed(latest),
                    safeLastModelSummary(latest),
                    StrUtil.blankToDefault(latest.getRollingSummary(), ""),
                    turns);
            if (latestWork != null) {
                submitModelSummary(latestWork);
            }
        } catch (RuntimeException error) {
            aiMainMetrics.recordMemoryDegraded("SUMMARY_RESCHEDULE_FAILED");
        }
    }

    /**
     * 摘要失败只在任务对应的版本和消息位置仍为最新时写入 degraded。
     * 返回 false 表示版本已经推进，调用方应决定重排或结束最新 pending 状态。
     */
    private boolean markSummaryFailure(SummaryWork work, String reason) {
        int updated = baseMapper.update(null, new UpdateWrapper<AiSessionMemory>()
                .eq("session_id", work.sessionId)
                .eq("user_id", work.userId)
                .eq("version", work.baseVersion)
                .eq("last_processed_message_id", work.lastProcessedMessageId)
                .set("summary_status", "degraded")
                .set("version", work.baseVersion + 1)
                .set("update_time", LocalDateTime.now()));
        aiMainMetrics.recordMemorySummary("model", updated == 1 ? reason : "stale_failure");
        if (updated == 1) {
            aiMainMetrics.recordMemoryDegraded(reason);
        }
        return updated == 1;
    }

    /** 队列拒绝不会留下可执行任务，因此用固定次数乐观重试关闭最新 pending 状态。 */
    private void markLatestSummaryFailure(SummaryWork work, String reason) {
        for (int attempt = 0; attempt <= MAX_OPTIMISTIC_RETRIES; attempt++) {
            AiSessionMemory latest = getById(work.sessionId);
            if (latest == null
                    || !Objects.equals(work.userId, latest.getUserId())
                    || !"pending".equals(latest.getSummaryStatus())) {
                return;
            }
            SummaryWork latestWork = new SummaryWork(
                    work.sessionId,
                    work.userId,
                    safeVersion(latest),
                    safeLastProcessed(latest),
                    work.request);
            if (markSummaryFailure(latestWork, reason)) {
                return;
            }
        }
        aiMainMetrics.recordMemoryDegraded("SUMMARY_FAILURE_UPDATE_EXHAUSTED");
    }

    /** 进程重启会丢失内存中的任务；读取到 pending 时按最新受控状态恢复一次后台摘要。 */
    private void resumePendingModelSummary(
            Long sessionId,
            Long userId,
            List<AiMessageTurn> turns,
            AiSessionMemory record) {
        if (!properties.isSummarizerEnabled() || !"pending".equals(record.getSummaryStatus())) {
            return;
        }
        try {
            SummaryWork work = createSummaryWork(
                    sessionId,
                    userId,
                    safeVersion(record),
                    safeLastProcessed(record),
                    safeLastModelSummary(record),
                    StrUtil.blankToDefault(record.getRollingSummary(), ""),
                    turns);
            if (work != null) {
                submitModelSummary(work);
            }
        } catch (RuntimeException error) {
            // 恢复后台增强失败时只记录降级，当前 Memory 构造和聊天主链路继续返回。
            aiMainMetrics.recordMemoryDegraded("SUMMARY_RESUME_FAILED");
        }
    }

    private String validatedModelSummary(CampusMemorySummaryResponseDTO response) {
        if (response == null || !Boolean.TRUE.equals(response.getSuccess())
                || StrUtil.isBlank(response.getRollingSummary())) {
            return null;
        }
        String summary = response.getRollingSummary().replaceAll("\\s+", " ").trim();
        if (summary.codePointCount(0, summary.length()) > properties.getSummaryMaxChars()
                || containsForbiddenSummaryContent(summary)) {
            return null;
        }
        return summary;
    }

    private String stableSummaryError(CampusMemorySummaryResponseDTO response) {
        String error = response == null ? null : response.getErrorCode();
        Set<String> allowed = new LinkedHashSet<>(Arrays.asList(
                "SUMMARY_TIMEOUT",
                "SUMMARY_UNAVAILABLE",
                "SUMMARY_INVALID_JSON",
                "SUMMARY_INVALID_SCHEMA",
                "SUMMARY_INVALID_RESPONSE",
                "SUMMARY_OUTPUT_TOO_LARGE",
                "SUMMARY_SENSITIVE_OUTPUT"));
        return allowed.contains(error) ? error : "SUMMARY_INVALID_RESPONSE";
    }

    private String sanitizeMemoryText(String value, int maxChars) {
        String text = StrUtil.blankToDefault(value, "").replaceAll("\\s+", " ").trim();
        text = URL_PATTERN.matcher(text).replaceAll("[url]");
        text = PHONE_PATTERN.matcher(text).replaceAll("[phone]");
        text = EMAIL_PATTERN.matcher(text).replaceAll("[email]");
        text = SECRET_PATTERN.matcher(text).replaceAll("$1=[secret]");
        text = ENTITY_ID_PATTERN.matcher(text).replaceAll("[business-entity]");
        text = INSTRUCTION_PATTERN.matcher(text).replaceAll("[instruction]");
        return keepLeftCodePoints(text, maxChars);
    }

    private boolean containsForbiddenSummaryContent(String value) {
        return URL_PATTERN.matcher(value).find()
                || PHONE_PATTERN.matcher(value).find()
                || EMAIL_PATTERN.matcher(value).find()
                || SECRET_PATTERN.matcher(value).find()
                || ENTITY_ID_PATTERN.matcher(value).find()
                || INSTRUCTION_PATTERN.matcher(value).find();
    }

    private String keepLeftCodePoints(String value, int maxChars) {
        int count = value.codePointCount(0, value.length());
        return count <= maxChars ? value : value.substring(0, value.offsetByCodePoints(0, maxChars));
    }

    private String keepRightCodePoints(String value, int maxChars) {
        int count = value.codePointCount(0, value.length());
        if (count <= maxChars) {
            return value;
        }
        int start = value.offsetByCodePoints(0, count - maxChars);
        return value.substring(start);
    }

    private CampusMemoryDiagnosticsDTO diagnostics(
            CampusMemoryDTO memory,
            boolean degraded,
            String degradedReason,
            String resolutionSource) {
        CampusMemoryDiagnosticsDTO diagnostics = new CampusMemoryDiagnosticsDTO();
        diagnostics.setMode(memory.getMode());
        diagnostics.setSchemaVersion(memory.getSchemaVersion());
        diagnostics.setRecentTurnCount(memory.getRecentTurns().size());
        diagnostics.setSummaryVersion(memory.getSummaryVersion());
        diagnostics.setEntityTypes(entityTypes(memory.getEntities()));
        diagnostics.setResolutionSource(resolutionSource);
        diagnostics.setDegraded(degraded);
        diagnostics.setDegradedReason(degradedReason);
        return diagnostics;
    }

    private List<String> entityTypes(CampusMemoryEntitiesDTO entities) {
        List<String> types = new ArrayList<>();
        if (!entities.getTickets().isEmpty()) {
            types.add("ticket");
        }
        if (!entities.getAppointments().isEmpty()) {
            types.add("appointment");
        }
        if (!entities.getServicePoints().isEmpty()) {
            types.add("service_point");
        }
        return types;
    }

    private CampusMemoryEntitiesDTO rebuildEntities(List<AiMessageTurn> turns) {
        CampusMemoryEntitiesDTO entities = emptyEntities();
        for (AiMessageTurn turn : turns) {
            AiMessage assistant = turn.getAssistantMessage();
            if (assistant == null || StrUtil.isBlank(assistant.getMetadata())) {
                continue;
            }
            try {
                JsonNode metadata = objectMapper.readTree(assistant.getMetadata());
                String turnId = firstNonBlank(assistant.getTurnId(), turn.getUserMessage().getTurnId());
                mergeJsonEntities(entities, metadata.get("sources"), turnId, assistant.getId());
                mergeJsonEntities(entities, metadata.get("businessCards"), turnId, assistant.getId());
                mergeJsonActionDrafts(entities, metadata.get("actionDrafts"));
            } catch (JsonProcessingException ignored) {
                // 单条历史 metadata 损坏时仅跳过该条，不能宽松解释或影响其他可信轮次。
            }
        }
        return entities;
    }

    private void mergeResponseEntities(
            CampusMemoryEntitiesDTO entities,
            String turnId,
            Long messageId,
            CampusAssistantResponse response) {
        for (CampusAssistantSourceDTO source : safeList(response.getSources())) {
            if (source != null) {
                mergeEntity(entities, source.getType(), source.getId(), turnId, messageId);
            }
        }
        for (Map<String, Object> card : safeList(response.getBusinessCards())) {
            mergeMapEntity(entities, card, turnId, messageId);
        }
        mergeMapActionDrafts(entities, response.getActionDrafts());
    }

    private void mergeJsonEntities(
            CampusMemoryEntitiesDTO entities,
            JsonNode items,
            String turnId,
            Long messageId) {
        if (items == null || !items.isArray()) {
            return;
        }
        for (JsonNode item : items) {
            if (!item.isObject()) {
                continue;
            }
            JsonNode id = item.get("id");
            mergeEntity(
                    entities,
                    item.path("type").asText(null),
                    id != null && id.isIntegralNumber() ? id.longValue() : null,
                    turnId,
                    messageId);
        }
    }

    private void mergeMapEntity(
            CampusMemoryEntitiesDTO entities,
            Map<String, Object> item,
            String turnId,
            Long messageId) {
        if (item == null) {
            return;
        }
        Object id = item.get("id");
        mergeEntity(
                entities,
                item.get("type") instanceof String ? (String) item.get("type") : null,
                id instanceof Number ? ((Number) id).longValue() : null,
                turnId,
                messageId);
    }

    private void mergeEntity(
            CampusMemoryEntitiesDTO entities,
            String type,
            Long id,
            String turnId,
            Long messageId) {
        if (id == null || id <= 0 || messageId == null || messageId < 0) {
            return;
        }
        List<CampusMemoryEntityDTO> target = entityList(entities, type);
        if (target == null) {
            return;
        }
        CampusMemoryEntityDTO existing = target.stream()
                .filter(candidate -> Objects.equals(id, candidate.getId()))
                .findFirst()
                .orElse(null);
        if (existing != null && existing.getLastSeenMessageId() >= messageId) {
            return;
        }
        target.removeIf(candidate -> Objects.equals(id, candidate.getId()));
        CampusMemoryEntityDTO candidate = new CampusMemoryEntityDTO();
        candidate.setId(id);
        candidate.setLastSeenTurnId(StrUtil.blankToDefault(turnId, null));
        candidate.setLastSeenMessageId(messageId);
        target.add(candidate);
        // 并发请求可能乱序完成更新，候选顺序必须由可信消息 ID 决定。
        target.sort((left, right) -> {
            int messageOrder = Long.compare(
                    right.getLastSeenMessageId(),
                    left.getLastSeenMessageId());
            return messageOrder != 0 ? messageOrder : Long.compare(right.getId(), left.getId());
        });
        while (target.size() > MAX_ENTITY_CANDIDATES) {
            target.remove(target.size() - 1);
        }
    }

    private List<CampusMemoryEntityDTO> entityList(CampusMemoryEntitiesDTO entities, String type) {
        if ("ticket".equals(type)) {
            return entities.getTickets();
        }
        if ("appointment".equals(type)) {
            return entities.getAppointments();
        }
        if ("service_point".equals(type)) {
            return entities.getServicePoints();
        }
        return null;
    }

    private void mergeJsonActionDrafts(CampusMemoryEntitiesDTO entities, JsonNode drafts) {
        if (drafts == null || !drafts.isArray()) {
            return;
        }
        for (JsonNode draft : drafts) {
            if (draft.isObject()) {
                CampusMemoryActionDraftDTO controlled = controlledDraft(
                        draft.path("type").asText(null),
                        draft.get("targetType"),
                        draft.get("targetId"),
                        draft.get("payload"));
                if (controlled != null) {
                    entities.setPendingActionDraft(controlled);
                }
            }
        }
    }

    private void mergeMapActionDrafts(
            CampusMemoryEntitiesDTO entities,
            List<Map<String, Object>> drafts) {
        for (Map<String, Object> draft : safeList(drafts)) {
            if (draft == null || !(draft.get("type") instanceof String)) {
                continue;
            }
            JsonNode tree = objectMapper.valueToTree(draft);
            CampusMemoryActionDraftDTO controlled = controlledDraft(
                    (String) draft.get("type"),
                    tree.get("targetType"),
                    tree.get("targetId"),
                    tree.get("payload"));
            if (controlled != null) {
                entities.setPendingActionDraft(controlled);
            }
        }
    }

    private CampusMemoryActionDraftDTO controlledDraft(
            String type,
            JsonNode targetTypeNode,
            JsonNode targetIdNode,
            JsonNode payload) {
        String targetType = targetTypeNode != null && targetTypeNode.isTextual()
                ? targetTypeNode.textValue() : null;
        Long targetId = integralValue(targetIdNode);
        if ("reply_ticket_draft".equals(type)) {
            targetType = "ticket";
            targetId = firstPositive(targetId, integralValue(payload == null ? null : payload.get("ticketId")));
        } else if ("appointment_query_draft".equals(type)
                || "create_ticket_draft".equals(type)) {
            targetType = "service_point";
            targetId = firstPositive(targetId, integralValue(payload == null ? null : payload.get("servicePointId")));
        } else {
            return null;
        }
        if (targetId == null || targetId <= 0) {
            return null;
        }
        CampusMemoryActionDraftDTO draft = new CampusMemoryActionDraftDTO();
        draft.setType(type);
        draft.setTargetType(targetType);
        draft.setTargetId(targetId);
        return draft;
    }

    private Long integralValue(JsonNode value) {
        return value != null && value.isIntegralNumber() ? value.longValue() : null;
    }

    private Long firstPositive(Long first, Long second) {
        return first != null && first > 0 ? first : second;
    }

    private CampusMemoryEntitiesDTO parseEntities(String json) {
        if (StrUtil.isBlank(json)) {
            return emptyEntities();
        }
        try {
            ObjectMapper strict = objectMapper.copy()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
            CampusMemoryEntitiesDTO entities = strict.readValue(json, CampusMemoryEntitiesDTO.class);
            normalizeAndValidate(entities);
            return entities;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid AI Memory entities JSON", error);
        }
    }

    private String writeEntities(CampusMemoryEntitiesDTO entities) {
        normalizeAndValidate(entities);
        try {
            return objectMapper.writeValueAsString(entities);
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Unable to serialize AI Memory entities", error);
        }
    }

    private void normalizeAndValidate(CampusMemoryEntitiesDTO entities) {
        if (entities == null) {
            throw new IllegalArgumentException("AI Memory entities are required");
        }
        if (entities.getTickets() == null) {
            entities.setTickets(new ArrayList<>());
        }
        if (entities.getAppointments() == null) {
            entities.setAppointments(new ArrayList<>());
        }
        if (entities.getServicePoints() == null) {
            entities.setServicePoints(new ArrayList<>());
        }
        validateEntityList(entities.getTickets());
        validateEntityList(entities.getAppointments());
        validateEntityList(entities.getServicePoints());
        validateDraft(entities.getPendingActionDraft());
    }

    private void validateEntityList(List<CampusMemoryEntityDTO> entities) {
        if (entities.size() > MAX_ENTITY_CANDIDATES) {
            throw new IllegalArgumentException("AI Memory entity candidate limit exceeded");
        }
        Set<Long> ids = new LinkedHashSet<>();
        for (CampusMemoryEntityDTO entity : entities) {
            if (entity == null || entity.getId() == null || entity.getId() <= 0
                    || entity.getLastSeenMessageId() == null || entity.getLastSeenMessageId() < 0
                    || !ids.add(entity.getId())) {
                throw new IllegalArgumentException("Invalid AI Memory entity reference");
            }
            if (entity.getLastSeenTurnId() != null && entity.getLastSeenTurnId().length() > 64) {
                throw new IllegalArgumentException("AI Memory turn ID is too long");
            }
        }
    }

    private void validateDraft(CampusMemoryActionDraftDTO draft) {
        if (draft == null) {
            return;
        }
        boolean validType = "reply_ticket_draft".equals(draft.getType())
                || "appointment_query_draft".equals(draft.getType())
                || "create_ticket_draft".equals(draft.getType());
        boolean validTargetType = "ticket".equals(draft.getTargetType())
                || "appointment".equals(draft.getTargetType())
                || "service_point".equals(draft.getTargetType());
        if (!validType || !validTargetType || draft.getTargetId() == null || draft.getTargetId() <= 0) {
            throw new IllegalArgumentException("Invalid AI Memory action draft");
        }
    }

    private CampusMemoryEntitiesDTO emptyEntities() {
        CampusMemoryEntitiesDTO entities = new CampusMemoryEntitiesDTO();
        entities.setTickets(new ArrayList<>());
        entities.setAppointments(new ArrayList<>());
        entities.setServicePoints(new ArrayList<>());
        return entities;
    }

    private long latestMessageId(List<AiMessageTurn> turns) {
        long latest = 0L;
        for (AiMessageTurn turn : turns) {
            if (turn.getCompletionMessageId() != null) {
                latest = Math.max(latest, turn.getCompletionMessageId());
            }
        }
        return latest;
    }

    private long safeVersion(AiSessionMemory memory) {
        return memory == null || memory.getVersion() == null ? 0L : memory.getVersion();
    }

    private long safeLastProcessed(AiSessionMemory memory) {
        return memory == null || memory.getLastProcessedMessageId() == null
                ? 0L : memory.getLastProcessedMessageId();
    }

    private long safeLastModelSummary(AiSessionMemory memory) {
        return memory == null || memory.getLastModelSummaryMessageId() == null
                ? 0L : memory.getLastModelSummaryMessageId();
    }

    private String rebuildReason(AiSessionMemory record, Long userId, List<AiMessageTurn> turns) {
        if (record == null) {
            return "missing";
        }
        if (!Objects.equals(userId, record.getUserId())) {
            return "ownership";
        }
        if (!properties.getSchemaVersion().equals(record.getSchemaVersion())) {
            return "schema";
        }
        if (safeLastProcessed(record) > latestMessageId(turns)
                || safeLastModelSummary(record) > safeLastProcessed(record)) {
            return "position";
        }
        return "invalid_payload";
    }

    private String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : StrUtil.blankToDefault(second, null);
    }

    private Object memoryUpdateLock(Long sessionId) {
        int hash = sessionId == null ? 0 : Long.hashCode(sessionId);
        return MEMORY_UPDATE_LOCKS[(hash & Integer.MAX_VALUE) % MEMORY_UPDATE_LOCKS.length];
    }

    private static Object[] createUpdateLocks(int count) {
        Object[] locks = new Object[count];
        for (int index = 0; index < count; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? Collections.emptyList() : value;
    }

    private static class MemoryLoad {

        private final AiSessionMemory record;
        private final String degradedReason;
        private final String resolutionSource;

        private MemoryLoad(AiSessionMemory record, String degradedReason, String resolutionSource) {
            this.record = record;
            this.degradedReason = degradedReason;
            this.resolutionSource = resolutionSource;
        }
    }

    private static class BoundedText {

        private final String value;
        private final boolean truncated;

        private BoundedText(String value, boolean truncated) {
            this.value = value;
            this.truncated = truncated;
        }
    }

    private static class BoundedTurn {

        private final CampusMemoryTurnDTO turn;
        private final boolean truncated;

        private BoundedTurn(CampusMemoryTurnDTO turn, boolean truncated) {
            this.turn = turn;
            this.truncated = truncated;
        }
    }

    private static class SummaryWork {

        private final Long sessionId;
        private final Long userId;
        private final long baseVersion;
        private final long lastProcessedMessageId;
        private final CampusMemorySummaryRequestDTO request;

        private SummaryWork(
                Long sessionId,
                Long userId,
                long baseVersion,
                long lastProcessedMessageId,
                CampusMemorySummaryRequestDTO request) {
            this.sessionId = sessionId;
            this.userId = userId;
            this.baseVersion = baseVersion;
            this.lastProcessedMessageId = lastProcessedMessageId;
            this.request = request;
        }
    }
}

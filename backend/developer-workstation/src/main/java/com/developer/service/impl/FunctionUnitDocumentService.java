package com.developer.service.impl;

import com.developer.entity.AiDocument;
import com.developer.entity.AiStudioThreadState;
import com.developer.enums.AiDocumentType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.AiDocumentRepository;
import com.developer.repository.AiStudioThreadStateRepository;
import com.developer.util.PgText;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 功能单元的 Requirements / Function Unit Design 文档（{@code dw_ai_documents}）。
 *
 * <p>每次保存追加一个版本，"当前内容"就是版本号最大的一行；历史不删不改。版本来源写在
 * {@code summary}。手动保存与 AI 同步都带着开始时看到的版本号，落库前比对，不一致即
 * {@code CONFLICT_DOCUMENT_VERSION}（409）——谁都不静默覆盖别人的修改。</p>
 *
 * <p>权限在 Component 层校验；导入 / 回滚 / 克隆走 {@link #appendFromPackage}，不比对版本。</p>
 */
@Slf4j
@Service
public class FunctionUnitDocumentService {

    public static final int MAX_CONTENT_BYTES = 200 * 1024;

    /*
     * 版本来源（summary 列）存机器可读的代码，界面按代码翻译（前端 utils/functionUnitDocumentSource.ts）；
     * 旧 AI Generate 写入的自由文本原样显示。
     */
    public static final String SUMMARY_MANUAL = "MANUAL";
    public static final String SUMMARY_IMPORTED = "IMPORTED";
    public static final String SUMMARY_CLONED = "CLONED";
    /** {@code RESTORED:<major>.<minor>} */
    public static final String SUMMARY_RESTORED_PREFIX = "RESTORED:";
    /** {@code ROLLBACK:<功能单元版本号>} */
    public static final String SUMMARY_ROLLBACK_PREFIX = "ROLLBACK:";
    /** {@code AI_SYNC:<阶段 key,逗号分隔>}；阶段为空表示全量核对 */
    public static final String SUMMARY_AI_SYNC_PREFIX = "AI_SYNC:";

    /** 版本快照 / 导入包解析结果里文档的键：{@code {"REQUIREMENTS": "...", "DESIGN": "..."}} */
    public static final String PACKAGE_KEY = "documents";

    /** 导出 ZIP 里每类文档的文件名 */
    public static final Map<AiDocumentType, String> PACKAGE_FILES = Map.of(
            AiDocumentType.REQUIREMENTS, "documents/requirements.md",
            AiDocumentType.DESIGN, "documents/design.md");

    private final AiDocumentRepository repository;
    private final AiStudioThreadStateRepository threadStateRepository;

    public FunctionUnitDocumentService(AiDocumentRepository repository,
                                       AiStudioThreadStateRepository threadStateRepository) {
        this.repository = repository;
        this.threadStateRepository = threadStateRepository;
    }

    /** 当前设计轮次（主版本）；没有 AI Studio 进度记录时是第 1 轮。 */
    @Transactional(readOnly = true)
    public int currentMajor(Long functionUnitId) {
        return threadStateRepository.findById(functionUnitId)
                .map(AiStudioThreadState::getDocumentMajor)
                .orElse(1);
    }

    /**
     * 开始新一轮设计（AI Studio 的 "Start a new AI design"）：主版本 +1，下一次保存就是 v{major}.1。
     * 还没有任何文档时不进位——否则第一份文档会从 v2.1 开始。
     *
     * @return 新的当前轮次
     */
    @Transactional
    public int startNewRound(Long functionUnitId) {
        int current = currentMajor(functionUnitId);
        if (latestContents(functionUnitId).isEmpty()) {
            return current;
        }
        AiStudioThreadState state = threadStateRepository.findById(functionUnitId)
                .orElseGet(() -> AiStudioThreadState.builder().functionUnitId(functionUnitId).build());
        state.setDocumentMajor(current + 1);
        state.setUpdatedAt(java.time.Instant.now());
        threadStateRepository.save(state);
        log.info("Function unit documents start a new design round: functionUnitId={}, major={}",
                functionUnitId, current + 1);
        return current + 1;
    }

    /** 显示用版本标签 v{major}.{minor}。 */
    public static String label(AiDocument document) {
        return "v" + document.getMajorVersion() + "." + document.getMinorVersion();
    }

    @Transactional(readOnly = true)
    public Optional<AiDocument> latest(Long functionUnitId, AiDocumentType type) {
        return repository.findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(functionUnitId, type);
    }

    /** 各类型的最新内容；没有文档的类型不在结果里。 */
    @Transactional(readOnly = true)
    public Map<AiDocumentType, String> latestContents(Long functionUnitId) {
        Map<AiDocumentType, String> contents = new EnumMap<>(AiDocumentType.class);
        for (AiDocumentType type : AiDocumentType.values()) {
            latest(functionUnitId, type).ifPresent(doc -> contents.put(type, doc.getContent()));
        }
        return contents;
    }

    /** 快照 / 导出用：类型名 → 最新内容；没有文档的类型不出现。 */
    @Transactional(readOnly = true)
    public Map<String, String> packagePayload(Long functionUnitId) {
        Map<String, String> payload = new LinkedHashMap<>();
        latestContents(functionUnitId).forEach((type, content) -> payload.put(type.name(), content));
        return payload;
    }

    /**
     * 读快照 / 导入包里的文档。字段不存在（旧包、旧快照）返回空 map——调用方据此不动现有文档；
     * 结构不对显式失败，不猜。
     */
    public static Map<AiDocumentType, String> fromPackage(Object value) {
        Map<AiDocumentType, String> documents = new EnumMap<>(AiDocumentType.class);
        if (value == null) {
            return documents;
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new DeveloperBusinessException("IMPORT_INVALID_DOCUMENTS", "documents must be an object");
        }
        map.forEach((key, content) -> {
            AiDocumentType type = Arrays.stream(AiDocumentType.values())
                    .filter(t -> t.name().equals(key))
                    .findFirst()
                    .orElseThrow(() -> new DeveloperBusinessException("IMPORT_INVALID_DOCUMENTS",
                            "Unknown document type: " + key));
            if (!(content instanceof String text)) {
                throw new DeveloperBusinessException("IMPORT_INVALID_DOCUMENTS",
                        "Document " + key + " must be text");
            }
            documents.put(type, text);
        });
        return documents;
    }

    /** 当前版本号；还没有文档时为 0。 */
    @Transactional(readOnly = true)
    public int currentVersion(Long functionUnitId, AiDocumentType type) {
        return latest(functionUnitId, type).map(AiDocument::getVersion).orElse(0);
    }

    @Transactional(readOnly = true)
    public List<AiDocument> history(Long functionUnitId, AiDocumentType type) {
        return repository.findByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(functionUnitId, type);
    }

    @Transactional(readOnly = true)
    public AiDocument version(Long functionUnitId, AiDocumentType type, int version) {
        return repository.findByFunctionUnitIdAndDocumentTypeAndVersion(functionUnitId, type, version)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "FunctionUnitDocument", functionUnitId + "/" + type + "/v" + version));
    }

    /**
     * 追加一个版本；{@code baseVersion} 不是当前最新版本时抛 409。
     */
    @Transactional
    public AiDocument append(Long functionUnitId, AiDocumentType type, String content, int baseVersion,
                             String summary, String userId) {
        String clean = PgText.clean(Objects.requireNonNull(content, "content"));
        if (clean.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new DeveloperBusinessException("DOCUMENT_TOO_LARGE",
                    "Document exceeds " + (MAX_CONTENT_BYTES / 1024) + " KB");
        }
        Optional<AiDocument> latest = latest(functionUnitId, type);
        int current = latest.map(AiDocument::getVersion).orElse(0);
        if (current != baseVersion) {
            throw conflict(type, baseVersion, latest.orElse(null));
        }
        return insert(functionUnitId, type, current + 1, clean, summary, userId);
    }

    /** 以历史版本 {@code version} 的内容追加一个新版本。 */
    @Transactional
    public AiDocument restore(Long functionUnitId, AiDocumentType type, int version, int baseVersion, String userId) {
        AiDocument source = version(functionUnitId, type, version);
        return append(functionUnitId, type, source.getContent(), baseVersion,
                SUMMARY_RESTORED_PREFIX + source.getMajorVersion() + "." + source.getMinorVersion(), userId);
    }

    /**
     * 导入 / 回滚 / 克隆：把包里的文档追加为新版本，与当前内容相同的跳过。不比对版本——
     * 这些操作本身就是"以包为准"。
     */
    @Transactional
    public void appendFromPackage(Long functionUnitId, Map<AiDocumentType, String> documents, String summary,
                                  String userId) {
        documents.forEach((type, content) -> {
            Optional<AiDocument> latest = latest(functionUnitId, type);
            String clean = PgText.clean(content);
            if (latest.isPresent() && latest.get().getContent().equals(clean)) {
                return;
            }
            insert(functionUnitId, type, latest.map(AiDocument::getVersion).orElse(0) + 1, clean, summary, userId);
        });
    }

    /**
     * {@code created_by} 实际由 JPA 审计（{@code @CreatedBy}，当前登录名）写入，传入的 userId 会被覆盖——
     * 作业线程因此要装上提交者的 SecurityContext（见 AiStudioDocumentSyncService）。
     */
    private AiDocument insert(Long functionUnitId, AiDocumentType type, int version, String content,
                              String summary, String userId) {
        int major = currentMajor(functionUnitId);
        // 本轮内的序号：同一轮里上一条 +1，本轮第一条从 1 开始
        int minor = latest(functionUnitId, type)
                .filter(previous -> previous.getMajorVersion() == major)
                .map(previous -> previous.getMinorVersion() + 1)
                .orElse(1);
        try {
            AiDocument saved = repository.saveAndFlush(AiDocument.builder()
                    .functionUnitId(functionUnitId)
                    .documentType(type)
                    .version(version)
                    .majorVersion(major)
                    .minorVersion(minor)
                    .content(content)
                    .summary(summary)
                    .createdBy(userId)
                    .build());
            log.info("Function unit document saved: functionUnitId={}, type={}, version={}, label=v{}.{}, summary={}",
                    functionUnitId, type, version, major, minor, summary);
            return saved;
        } catch (DataIntegrityViolationException e) {
            // uk_ai_document_version：并发保存时后到的一方
            throw conflict(type, version - 1, null);
        }
    }

    private static DeveloperBusinessException conflict(AiDocumentType type, int baseVersion, AiDocument latest) {
        String by = latest != null
                ? " (now v" + latest.getVersion() + " by " + latest.getCreatedBy() + ")"
                : "";
        return new DeveloperBusinessException("CONFLICT_DOCUMENT_VERSION",
                type + " document changed since v" + baseVersion + by);
    }
}

package com.developer.service.impl;

import com.developer.entity.AiDocument;
import com.developer.entity.AiStudioThreadState;
import com.developer.enums.AiDocumentType;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.AiDocumentRepository;
import com.developer.repository.AiStudioThreadStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 文档版本：只追加、版本号比对（409）、大小上限、恢复与"以包为准"的追加。仓库用内存假实现。
 */
class FunctionUnitDocumentServiceTest {

    private final List<AiDocument> rows = new ArrayList<>();
    private final Map<Long, AiStudioThreadState> states = new java.util.HashMap<>();
    private FunctionUnitDocumentService service;
    private boolean nextInsertCollides;

    @BeforeEach
    void setUp() {
        AiDocumentRepository repo = mock(AiDocumentRepository.class);
        lenient().when(repo.findTopByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(anyLong(), any()))
                .thenAnswer(inv -> rows(inv.getArgument(0), inv.getArgument(1)).stream().findFirst());
        lenient().when(repo.findByFunctionUnitIdAndDocumentTypeOrderByVersionDesc(anyLong(), any()))
                .thenAnswer(inv -> rows(inv.getArgument(0), inv.getArgument(1)));
        lenient().when(repo.findByFunctionUnitIdAndDocumentTypeAndVersion(anyLong(), any(), anyInt()))
                .thenAnswer(inv -> rows(inv.getArgument(0), inv.getArgument(1)).stream()
                        .filter(d -> d.getVersion().equals(inv.getArgument(2))).findFirst());
        lenient().when(repo.saveAndFlush(any(AiDocument.class))).thenAnswer(inv -> {
            if (nextInsertCollides) {
                nextInsertCollides = false;
                throw new DataIntegrityViolationException("uk_ai_document_version");
            }
            AiDocument d = inv.getArgument(0);
            rows.add(d);
            return d;
        });
        AiStudioThreadStateRepository stateRepo = mock(AiStudioThreadStateRepository.class);
        lenient().when(stateRepo.findById(anyLong()))
                .thenAnswer(inv -> java.util.Optional.ofNullable(states.get(inv.<Long>getArgument(0))));
        lenient().when(stateRepo.save(any(AiStudioThreadState.class))).thenAnswer(inv -> {
            AiStudioThreadState state = inv.getArgument(0);
            states.put(state.getFunctionUnitId(), state);
            return state;
        });
        service = new FunctionUnitDocumentService(repo, stateRepo);
    }

    private List<AiDocument> rows(Long fu, AiDocumentType type) {
        return rows.stream()
                .filter(d -> d.getFunctionUnitId().equals(fu) && d.getDocumentType() == type)
                .sorted(Comparator.comparing(AiDocument::getVersion).reversed())
                .toList();
    }

    @Test
    void savesAppendNewVersionsAndKeepHistory() {
        service.append(1L, AiDocumentType.REQUIREMENTS, "v1", 0, "MANUAL", "u1");
        AiDocument second = service.append(1L, AiDocumentType.REQUIREMENTS, "v2", 1, "MANUAL", "u1");

        assertEquals(2, second.getVersion());
        assertEquals(List.of(2, 1), service.history(1L, AiDocumentType.REQUIREMENTS).stream()
                .map(AiDocument::getVersion).toList());
        assertEquals(0, service.currentVersion(1L, AiDocumentType.DESIGN));
        assertEquals(Map.of(AiDocumentType.REQUIREMENTS, "v2"), service.latestContents(1L));
    }

    @Test
    void versionsAreMinorWithinTheSameDesignRound() {
        service.append(1L, AiDocumentType.REQUIREMENTS, "a", 0, "MANUAL", "u1");
        AiDocument second = service.append(1L, AiDocumentType.REQUIREMENTS, "b", 1, "MANUAL", "u1");

        assertEquals("v1.1", FunctionUnitDocumentService.label(service.version(1L, AiDocumentType.REQUIREMENTS, 1)));
        assertEquals("v1.2", FunctionUnitDocumentService.label(second));
        assertEquals(2, second.getVersion(), "the internal sequence keeps counting");
    }

    @Test
    void aNewDesignRoundStartsTheNextMajorAtMinorOne() {
        service.append(1L, AiDocumentType.REQUIREMENTS, "a", 0, "MANUAL", "u1");
        service.append(1L, AiDocumentType.DESIGN, "d", 0, "MANUAL", "u1");

        assertEquals(2, service.startNewRound(1L));
        assertEquals(2, service.currentMajor(1L));

        assertEquals("v2.1", FunctionUnitDocumentService.label(
                service.append(1L, AiDocumentType.REQUIREMENTS, "b", 1, "MANUAL", "u1")));
        assertEquals("v2.2", FunctionUnitDocumentService.label(
                service.append(1L, AiDocumentType.REQUIREMENTS, "c", 2, "MANUAL", "u1")));
        // 另一份文档同一轮，独立计小版本
        assertEquals("v2.1", FunctionUnitDocumentService.label(
                service.append(1L, AiDocumentType.DESIGN, "d2", 1, "MANUAL", "u1")));
    }

    @Test
    void withoutAnyDocumentTheRoundStaysAtOne() {
        assertEquals(1, service.startNewRound(1L));

        assertEquals("v1.1", FunctionUnitDocumentService.label(
                service.append(1L, AiDocumentType.REQUIREMENTS, "first", 0, "MANUAL", "u1")));
    }

    @Test
    void staleBaseVersionIsAConflict() {
        service.append(1L, AiDocumentType.DESIGN, "mine", 0, "MANUAL", "u1");

        DeveloperBusinessException e = assertThrows(DeveloperBusinessException.class,
                () -> service.append(1L, AiDocumentType.DESIGN, "theirs", 0, "MANUAL", "u2"));
        assertEquals("CONFLICT_DOCUMENT_VERSION", e.getErrorCode());
        assertEquals(1, rows.size());
    }

    @Test
    void concurrentInsertOnTheSameVersionIsAConflict() {
        nextInsertCollides = true;
        assertEquals("CONFLICT_DOCUMENT_VERSION", assertThrows(DeveloperBusinessException.class,
                () -> service.append(1L, AiDocumentType.DESIGN, "x", 0, "MANUAL", "u1")).getErrorCode());
    }

    @Test
    void oversizedContentIsRejected() {
        String big = "a".repeat(FunctionUnitDocumentService.MAX_CONTENT_BYTES + 1);
        assertEquals("DOCUMENT_TOO_LARGE", assertThrows(DeveloperBusinessException.class,
                () -> service.append(1L, AiDocumentType.DESIGN, big, 0, "MANUAL", "u1")).getErrorCode());
        service.append(1L, AiDocumentType.DESIGN, "", 0, "MANUAL", "u1");
        assertEquals("", service.latest(1L, AiDocumentType.DESIGN).orElseThrow().getContent());
    }

    @Test
    void restoreAppendsTheOldContentAsANewVersion() {
        service.append(1L, AiDocumentType.DESIGN, "first", 0, "MANUAL", "u1");
        service.append(1L, AiDocumentType.DESIGN, "second", 1, "MANUAL", "u1");

        AiDocument restored = service.restore(1L, AiDocumentType.DESIGN, 1, 2, "u2");

        assertEquals(3, restored.getVersion());
        assertEquals("first", restored.getContent());
        assertEquals("RESTORED:1.1", restored.getSummary(), "the source is named by its display label");
        assertThrows(DeveloperBusinessException.class, () -> service.restore(1L, AiDocumentType.DESIGN, 1, 2, "u2"));
    }

    @Test
    void packageContentIsAppendedUnlessItIsAlreadyCurrent() {
        service.append(1L, AiDocumentType.REQUIREMENTS, "same", 0, "MANUAL", "u1");

        service.appendFromPackage(1L, Map.of(AiDocumentType.REQUIREMENTS, "same", AiDocumentType.DESIGN, "d"),
                "IMPORTED", "u1");

        assertEquals(1, service.currentVersion(1L, AiDocumentType.REQUIREMENTS));
        AiDocument design = service.latest(1L, AiDocumentType.DESIGN).orElseThrow();
        assertEquals(1, design.getVersion());
        assertEquals("IMPORTED", design.getSummary());
    }
}

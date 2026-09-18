package com.developer.service.impl;

import com.developer.dto.AiStudioThreadEvent;
import com.developer.dto.FunctionUnitContextDTO;
import com.developer.entity.AiDocument;
import com.developer.enums.AiDocumentType;
import com.developer.exception.AiGenerationException;
import com.developer.exception.DeveloperBusinessException;
import com.developer.service.AiGenerationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文档同步：只写有改动的文档、版本号变了就放弃、输出格式不对重试一次、运行期间的提交合并成一次、
 * 任何失败都落成 FAILED 消息而不是抛给确认阶段的请求。
 */
class AiStudioDocumentSyncServiceTest {

    private static final AiStudioThreadService.Author ALICE = new AiStudioThreadService.Author("u-alice", "Alice");
    private static final Map<String, Object> HTTP = Map.of("status", 200);

    private final FunctionUnitDocumentService documents = mock(FunctionUnitDocumentService.class);
    private final AiStudioThreadService threads = mock(AiStudioThreadService.class);
    private final AiGenerationService generation = mock(AiGenerationService.class);
    private final AiGatewayClient gateway = mock(AiGatewayClient.class);
    private final AiResponseParser parser = mock(AiResponseParser.class);
    private final List<AiStudioThreadEvent> events = new ArrayList<>();
    private final ManualExecutor executor = new ManualExecutor();
    private AiStudioDocumentSyncService service;

    @BeforeEach
    void setUp() {
        when(generation.serializeFunctionUnitContext(1L)).thenReturn(new FunctionUnitContextDTO());
        when(threads.recentHistory(eq(1L), anyString())).thenReturn(List.of());
        when(documents.latest(eq(1L), any())).thenReturn(Optional.empty());
        when(gateway.chat(any(), eq("tok"))).thenReturn(HTTP);
        service = new AiStudioDocumentSyncService(documents, threads, generation, new AiStudioContextDigest(),
                gateway, parser, e -> events.add((AiStudioThreadEvent) e), executor);
    }

    private static AiDocument doc(AiDocumentType type, int version, String content, String by) {
        return AiDocument.builder().functionUnitId(1L).documentType(type).version(version).content(content)
                .majorVersion(1).minorVersion(version).createdBy(by).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> recordedDocSync() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(threads).appendDocSyncMessage(eq(1L), anyString(), anyString(), captor.capture(), eq(ALICE));
        return captor.getValue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void writesOnlyTheChangedDocumentAgainstItsBaseVersion() {
        when(documents.latest(1L, AiDocumentType.REQUIREMENTS))
                .thenReturn(Optional.of(doc(AiDocumentType.REQUIREMENTS, 2, "old req", "u-bob")));
        when(parser.assistantText(HTTP)).thenReturn("""
                ---REQUIREMENTS_DOC_START---
                new req
                ---REQUIREMENTS_DOC_END---
                ---DESIGN_UNCHANGED---
                ---CHANGE_SUMMARY---
                Added an open question.""");
        when(documents.append(eq(1L), eq(AiDocumentType.REQUIREMENTS), eq("new req"), eq(2), anyString(), anyString()))
                .thenReturn(doc(AiDocumentType.REQUIREMENTS, 3, "new req", "u-alice"));

        service.submit(1L, List.of("TABLE_DESIGN"), "TABLE_DESIGN", "tok", ALICE);
        executor.runAll();

        verify(documents).append(1L, AiDocumentType.REQUIREMENTS, "new req", 2,
                "AI_SYNC:TABLE_DESIGN", "u-alice");
        verify(documents, never()).append(eq(1L), eq(AiDocumentType.DESIGN), anyString(), anyInt(), anyString(),
                anyString());
        Map<String, Object> sync = recordedDocSync();
        assertEquals("UPDATED", sync.get("status"));
        assertEquals("Added an open question.", sync.get("changeSummary"));
        Map<String, Object> docs = (Map<String, Object>) sync.get("documents");
        assertEquals(Map.of("fromVersion", 2, "toVersion", 3, "fromLabel", "v1.2", "toLabel", "v1.3"),
                docs.get("REQUIREMENTS"));
        assertEquals(Map.of("fromVersion", 0, "toVersion", 0), docs.get("DESIGN"),
                "no document yet: no version labels either");
        assertEquals(List.of(AiStudioThreadEvent.DOC_SYNC_STARTED, AiStudioThreadEvent.DOC_SYNC_FINISHED),
                events.stream().map(AiStudioThreadEvent::type).toList());
        assertFalse(service.isRunning(1L));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aManualSaveDuringTheRunWinsOverTheAiResult() {
        when(parser.assistantText(HTTP)).thenReturn("""
                ---REQUIREMENTS_UNCHANGED---
                ---DESIGN_DOC_START---
                ai design
                ---DESIGN_DOC_END---
                ---CHANGE_SUMMARY---
                x""");
        when(documents.append(eq(1L), eq(AiDocumentType.DESIGN), anyString(), eq(0), anyString(), anyString()))
                .thenThrow(new DeveloperBusinessException("CONFLICT_DOCUMENT_VERSION", "changed"));

        // 开始时还没有 Design；模型运行期间 Bob 手动保存了 v1
        when(documents.latest(1L, AiDocumentType.DESIGN))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doc(AiDocumentType.DESIGN, 1, "bob's", "u-bob")));

        service.submit(1L, List.of("FORM_DESIGN"), "FORM_DESIGN", "tok", ALICE);
        executor.runAll();

        Map<String, Object> sync = recordedDocSync();
        assertEquals("SKIPPED", sync.get("status"));
        Map<String, Object> design = (Map<String, Object>) ((Map<String, Object>) sync.get("documents")).get("DESIGN");
        assertEquals("u-bob", design.get("blockedBy"));
        assertEquals("v1.1", design.get("toLabel"), "the card shows the version the manual save produced");
    }

    @Test
    void identicalContentIsNotANewVersion() {
        when(documents.latest(1L, AiDocumentType.DESIGN))
                .thenReturn(Optional.of(doc(AiDocumentType.DESIGN, 4, "same\n", "u-bob")));
        when(parser.assistantText(HTTP)).thenReturn("""
                ---REQUIREMENTS_UNCHANGED---
                ---DESIGN_DOC_START---
                same
                ---DESIGN_DOC_END---""");

        service.submit(1L, List.of(), "VALIDATION", "tok", ALICE);
        executor.runAll();

        verify(documents, never()).append(any(), any(), any(), anyInt(), any(), any());
        assertEquals("UNCHANGED", recordedDocSync().get("status"));
    }

    @Test
    void outputWithoutDocumentBlocksIsRetriedOnceThenRecordedAsFailure() {
        when(parser.assistantText(HTTP)).thenReturn("Sure! Here are my thoughts…");

        service.submit(1L, List.of("TABLE_DESIGN"), "TABLE_DESIGN", "tok", ALICE);
        executor.runAll();

        verify(gateway, times(2)).chat(any(), eq("tok"));
        Map<String, Object> sync = recordedDocSync();
        assertEquals("FAILED", sync.get("status"));
        assertEquals("AI_STUDIO_DOC_SYNC_BAD_OUTPUT", sync.get("errorCode"));
        assertFalse(service.isRunning(1L));
    }

    @Test
    void preparationFailureIsRecordedWithoutFailingTheCaller() {
        when(generation.serializeFunctionUnitContext(1L))
                .thenThrow(new AiGenerationException("AI_CONTEXT_TOO_LARGE", "too big"));

        service.submit(1L, List.of("TABLE_DESIGN"), "TABLE_DESIGN", "tok", ALICE);

        assertEquals("AI_CONTEXT_TOO_LARGE", recordedDocSync().get("errorCode"));
        assertEquals(0, executor.tasks.size());
        assertFalse(service.isRunning(1L));
    }

    @Test
    void submissionsDuringARunAreMergedIntoOneFollowUpRun() {
        when(parser.assistantText(HTTP)).thenReturn("---REQUIREMENTS_UNCHANGED---\n---DESIGN_UNCHANGED---");

        service.submit(1L, List.of("TABLE_DESIGN"), "TABLE_DESIGN", "tok", ALICE);
        assertTrue(service.isRunning(1L));
        service.submit(1L, List.of("FORM_DESIGN"), "FORM_DESIGN", "tok", ALICE);
        service.submit(1L, List.of("VIEW_DESIGN", "FORM_DESIGN"), "VIEW_DESIGN", "tok", ALICE);
        assertEquals(1, executor.tasks.size(), "one worker task per function unit");

        executor.runAll();

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompts =
                ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(gateway, times(2)).chat(prompts.capture(), eq("tok"));
        assertTrue(prompts.getAllValues().get(0).user().contains("Phases to check: Table Design\n"));
        assertTrue(prompts.getAllValues().get(1).user().contains("Phases to check: Form Design, View Design\n"));
        verify(threads, times(2)).appendDocSyncMessage(eq(1L), anyString(), anyString(), any(), eq(ALICE));
        verify(threads).appendDocSyncMessage(eq(1L), eq("VIEW_DESIGN"), anyString(), any(), eq(ALICE));
        assertEquals(List.of(AiStudioThreadEvent.DOC_SYNC_STARTED, AiStudioThreadEvent.DOC_SYNC_FINISHED),
                events.stream().map(AiStudioThreadEvent::type).toList());
        assertFalse(service.isRunning(1L));
    }

    @Test
    void promptCarriesBothDocumentsTheDesignListingAndTheConversation() {
        when(documents.latest(1L, AiDocumentType.REQUIREMENTS))
                .thenReturn(Optional.of(doc(AiDocumentType.REQUIREMENTS, 5, "Amounts keep 4 decimals.", "u")));
        when(parser.assistantText(HTTP)).thenReturn("---REQUIREMENTS_UNCHANGED---\n---DESIGN_UNCHANGED---");

        service.submit(1L, List.of(), "TABLE_DESIGN", "tok", ALICE);
        executor.runAll();

        ArgumentCaptor<AiPromptBuilder.RenderedPrompt> prompt =
                ArgumentCaptor.forClass(AiPromptBuilder.RenderedPrompt.class);
        verify(gateway).chat(prompt.capture(), eq("tok"));
        String user = prompt.getValue().user();
        assertTrue(user.contains("Phases to check: all phases (full check)"));
        assertTrue(user.contains("## Current Requirements document (v5)\nAmounts keep 4 decimals."));
        assertTrue(user.contains("## Current Function Unit Design document\n(empty)"));
        assertTrue(user.contains("### Table Design"));
        assertTrue(prompt.getValue().system().contains("Open Questions"));
        verify(threads).recentHistory(1L, "TABLE_DESIGN");
    }

    @Test
    void parseOutputRequiresABlockOrAnUnchangedMarkerForEachDocument() {
        assertTrue(AiStudioDocumentSyncService.parseOutput("---REQUIREMENTS_UNCHANGED---").isEmpty());
        var out = AiStudioDocumentSyncService.parseOutput(
                "---REQUIREMENTS_DOC_START---\n# R\n---REQUIREMENTS_DOC_END---\n---DESIGN_UNCHANGED---").orElseThrow();
        assertEquals(Map.of(AiDocumentType.REQUIREMENTS, "# R"), out.documents());
        assertEquals("", out.changeSummary());
    }

    /** 手动推进的执行器：submit 只排队，runAll 在测试线程里跑完。 */
    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        @Override
        public void shutdown() {
        }

        @Override
        public List<Runnable> shutdownNow() {
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return false;
        }

        @Override
        public boolean isTerminated() {
            return false;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}

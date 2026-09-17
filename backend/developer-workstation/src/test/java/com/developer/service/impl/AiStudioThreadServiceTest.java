package com.developer.service.impl;

import com.developer.dto.AiStudioChatRequest;
import com.developer.dto.AiStudioProposalPreview;
import com.developer.dto.AiStudioThreadEvent;
import com.developer.dto.AiStudioThreadImportRequest;
import com.developer.dto.AiStudioThreadMessageDTO;
import com.developer.entity.AiStudioMessage;
import com.developer.entity.AiStudioThreadState;
import com.developer.exception.DeveloperBusinessException;
import com.developer.repository.AiStudioMessageRepository;
import com.developer.repository.AiStudioThreadStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * 共享线程：按功能单元共享、每阶段 50 条截断、Apply 标记只由本人撤回、导入只填空阶段、
 * 读侧不外泄撤销令牌（DTO 里根本没有这个字段）。仓库用内存假实现，覆盖真实的排序与截断语义。
 */
class AiStudioThreadServiceTest {

    private final List<AiStudioMessage> rows = new ArrayList<>();
    private final Map<Long, AiStudioThreadState> states = new HashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private final List<AiStudioThreadEvent> published = new ArrayList<>();
    private AiStudioThreadService service;

    private static final AiStudioThreadService.Author ALICE = new AiStudioThreadService.Author("u-alice", "Alice");
    private static final AiStudioThreadService.Author BOB = new AiStudioThreadService.Author("u-bob", "Bob");

    @BeforeEach
    void setUp() {
        AiStudioMessageRepository messages = mock(AiStudioMessageRepository.class);
        lenient().when(messages.save(any(AiStudioMessage.class))).thenAnswer(inv -> {
            AiStudioMessage m = inv.getArgument(0);
            if (m.getId() == null) {
                m.setId(ids.incrementAndGet());
                rows.add(m);
            }
            return m;
        });
        lenient().when(messages.findByFunctionUnitIdAndPhaseOrderByIdDesc(anyLong(), anyString(), any(Pageable.class)))
                .thenAnswer(inv -> rows.stream()
                        .filter(m -> m.getFunctionUnitId().equals(inv.getArgument(0)) && m.getPhase().equals(inv.getArgument(1)))
                        .sorted(Comparator.comparing(AiStudioMessage::getId).reversed())
                        .limit(((Pageable) inv.getArgument(2)).getPageSize())
                        .collect(Collectors.toList()));
        lenient().when(messages.trimThread(anyLong(), anyString(), anyInt())).thenAnswer(inv -> {
            Long fu = inv.getArgument(0);
            String phase = inv.getArgument(1);
            int keep = inv.getArgument(2);
            List<AiStudioMessage> thread = rows.stream()
                    .filter(m -> m.getFunctionUnitId().equals(fu) && m.getPhase().equals(phase))
                    .sorted(Comparator.comparing(AiStudioMessage::getId).reversed())
                    .toList();
            List<AiStudioMessage> drop = thread.subList(Math.min(keep, thread.size()), thread.size());
            rows.removeAll(drop);
            return drop.size();
        });
        lenient().when(messages.countByPhase(anyLong())).thenAnswer(inv -> rows.stream()
                .filter(m -> m.getFunctionUnitId().equals(inv.getArgument(0)))
                .collect(Collectors.groupingBy(AiStudioMessage::getPhase, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream().map(e -> new Object[]{e.getKey(), e.getValue()}).collect(Collectors.toList()));
        lenient().when(messages.findByIdAndFunctionUnitId(anyLong(), anyLong())).thenAnswer(inv -> rows.stream()
                .filter(m -> m.getId().equals(inv.getArgument(0)) && m.getFunctionUnitId().equals(inv.getArgument(1)))
                .findFirst());

        AiStudioThreadStateRepository stateRepo = mock(AiStudioThreadStateRepository.class);
        lenient().when(stateRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(states.get(inv.<Long>getArgument(0))));
        lenient().when(stateRepo.save(any(AiStudioThreadState.class))).thenAnswer(inv -> {
            AiStudioThreadState s = inv.getArgument(0);
            states.put(s.getFunctionUnitId(), s);
            return s;
        });

        lenient().when(messages.findByFunctionUnitIdAndPhaseAndIdGreaterThanOrderByIdAsc(anyLong(), anyString(), anyLong(), any(Pageable.class)))
                .thenAnswer(inv -> rows.stream()
                        .filter(m -> m.getFunctionUnitId().equals(inv.getArgument(0)) && m.getPhase().equals(inv.getArgument(1))
                                && m.getId() > inv.<Long>getArgument(2))
                        .sorted(Comparator.comparing(AiStudioMessage::getId))
                        .limit(((Pageable) inv.getArgument(3)).getPageSize())
                        .collect(Collectors.toList()));

        service = new AiStudioThreadService(messages, stateRepo, new ObjectMapper().registerModule(new JavaTimeModule()),
                e -> published.add((AiStudioThreadEvent) e));
    }

    @Test
    void threadIsSharedPerFunctionUnitAndMarksTheViewersOwnMessages() {
        service.appendUserMessage(1L, "TABLE_DESIGN", "add an orders table", ALICE);
        service.appendAssistantMessage(1L, "TABLE_DESIGN", "Here is the proposed change.", "TABLES",
                Map.of("tableDefinitions", List.of(Map.of("tableName", "orders"))),
                AiStudioProposalPreview.builder().checked(true).build(), ALICE);
        service.appendUserMessage(2L, "TABLE_DESIGN", "other unit", BOB);
        service.appendUserMessage(1L, "FORM_DESIGN", "other phase", BOB);

        List<AiStudioThreadMessageDTO> asBob = service.messages(1L, "TABLE_DESIGN", BOB.userId());
        assertEquals(2, asBob.size());
        assertEquals("add an orders table", asBob.get(0).getContent());
        assertEquals("Alice", asBob.get(0).getAuthorName());
        assertFalse(asBob.get(0).isMine(), "Bob sees Alice's message as someone else's");
        assertEquals("TABLES", asBob.get(1).getProposal().getScope());
        assertEquals(Boolean.TRUE, asBob.get(1).getProposal().getPreview().get("checked"));
        assertTrue(service.messages(1L, "TABLE_DESIGN", ALICE.userId()).get(0).isMine());

        assertEquals(Map.of("TABLE_DESIGN", 2L, "FORM_DESIGN", 1L), service.messageCounts(1L));
    }

    @Test
    void eachPhaseKeepsOnlyTheLatestFiftyMessages() {
        for (int i = 0; i < 55; i++) {
            service.appendAssistantMessage(1L, "PROCESS_DESIGN", "reply " + i, null, null, null, ALICE);
        }
        List<AiStudioThreadMessageDTO> thread = service.messages(1L, "PROCESS_DESIGN", ALICE.userId());
        assertEquals(AiStudioThreadService.MESSAGES_PER_PHASE_CAP, thread.size());
        assertEquals("reply 5", thread.get(0).getContent());
        assertEquals("reply 54", thread.get(49).getContent());
        assertEquals(50, rows.size());
    }

    @Test
    void repeatedSubmitOfTheSameQuestionIsRecordedOnce() {
        AiStudioMessage first = service.appendUserMessage(1L, "TABLE_DESIGN", "add a table", ALICE);
        assertSame(first, service.appendUserMessage(1L, "TABLE_DESIGN", "add a table", ALICE));
        // 别人问同一句、或同一个人在回复之后再问，都是新消息
        service.appendUserMessage(1L, "TABLE_DESIGN", "add a table", BOB);
        service.appendAssistantMessage(1L, "TABLE_DESIGN", "ok", null, null, null, ALICE);
        service.appendUserMessage(1L, "TABLE_DESIGN", "add a table", ALICE);
        assertEquals(4, rows.size());
    }

    @Test
    void historyComesFromTheSharedThreadWithProposals() {
        service.appendUserMessage(1L, "EMAIL_TEMPLATES", "q1", ALICE);
        service.appendAssistantMessage(1L, "EMAIL_TEMPLATES", "proposal", "EMAIL_TEMPLATES",
                Map.of("emailTemplates", List.of(Map.of("name", "Shipped"))), null, ALICE);
        for (int i = 0; i < 9; i++) {
            service.appendUserMessage(1L, "EMAIL_TEMPLATES", "bob " + i, BOB);
        }
        List<AiStudioChatRequest.HistoryMessage> history = service.recentHistory(1L, "EMAIL_TEMPLATES");
        assertEquals(AiStudioThreadService.HISTORY_WINDOW, history.size());
        assertEquals("ASSISTANT", history.get(0).getRole(), "oldest message falls out of the window");
        assertEquals("EMAIL_TEMPLATES", history.get(0).getProposalScope());
        assertEquals(List.of(Map.of("name", "Shipped")), history.get(0).getProposal().get("emailTemplates"));
        assertEquals("bob 8", history.get(9).getContent());
        assertTrue(service.recentHistory(1L, "CONNECTIONS").isEmpty());
    }

    @Test
    void appliedStateIsSharedButOnlyTheApplierCanRevertIt() {
        AiStudioMessage card = service.appendAssistantMessage(1L, "VIEW_DESIGN", "p", "VIEWS",
                Map.of("mainTableViews", List.of()), null, ALICE);
        AiStudioMessage plain = service.appendAssistantMessage(1L, "VIEW_DESIGN", "text only", null, null, null, ALICE);

        AiStudioThreadMessageDTO applied = service.markApplied(1L, card.getId(), true, ALICE);
        assertTrue(applied.getProposal().isApplied());
        assertTrue(applied.getProposal().isAppliedByMe());

        AiStudioThreadMessageDTO bobView = service.messages(1L, "VIEW_DESIGN", BOB.userId()).get(0);
        assertTrue(bobView.getProposal().isApplied());
        assertEquals("Alice", bobView.getProposal().getAppliedByName());
        assertFalse(bobView.getProposal().isAppliedByMe(), "Bob gets no undo affordance");

        DeveloperBusinessException denied = assertThrows(DeveloperBusinessException.class,
                () -> service.markApplied(1L, card.getId(), false, BOB));
        assertEquals("AI_STUDIO_APPLIED_BY_OTHER", denied.getErrorCode());

        AiStudioThreadMessageDTO reverted = service.markApplied(1L, card.getId(), false, ALICE);
        assertFalse(reverted.getProposal().isApplied());
        assertNull(reverted.getProposal().getAppliedByName());

        assertEquals("AI_STUDIO_MESSAGE_NOT_PROPOSAL", assertThrows(DeveloperBusinessException.class,
                () -> service.markApplied(1L, plain.getId(), true, ALICE)).getErrorCode());
        // 别的功能单元的消息 id 当作不存在
        assertThrows(com.developer.exception.ResourceNotFoundException.class,
                () -> service.markApplied(2L, card.getId(), true, ALICE));
    }

    @Test
    void importFillsOnlyEmptyPhasesAndSeedsProgressOnce() {
        service.appendUserMessage(1L, "TABLE_DESIGN", "already shared", BOB);

        // 先来的人本地没确认过任何阶段：不能写出一行空进度，否则会挡住后来者的真实进度
        AiStudioThreadImportRequest empty = new AiStudioThreadImportRequest();
        empty.setThreads(Map.of("PROCESS_DESIGN", List.of(msg("USER", "bob local", null))));
        empty.setCompletedPhases(List.of());
        service.importThreads(1L, empty, BOB);
        assertTrue(states.isEmpty(), "an empty local progress does not claim the shared progress");

        AiStudioThreadImportRequest request = new AiStudioThreadImportRequest();
        Map<String, List<AiStudioThreadImportRequest.Message>> threads = new LinkedHashMap<>();
        threads.put("TABLE_DESIGN", List.of(msg("USER", "local table talk", null)));
        List<AiStudioThreadImportRequest.Message> formThread = new ArrayList<>();
        for (int i = 0; i < 60; i++) formThread.add(msg("USER", "form " + i, null));
        formThread.add(msg("ASSISTANT", "card", Map.of("scope", "FORMS",
                "data", Map.of("formDefinitions", List.of()), "applied", true, "undo", Map.of("token", "secret"))));
        threads.put("FORM_DESIGN", formThread);
        threads.put("NOT_A_PHASE", List.of(msg("USER", "x", null)));
        request.setThreads(threads);
        request.setCompletedPhases(List.of("PROCESS_DESIGN", "BOGUS", "PROCESS_DESIGN", "TABLE_DESIGN"));

        assertEquals(List.of("FORM_DESIGN"), service.importThreads(1L, request, ALICE));

        List<AiStudioThreadMessageDTO> tables = service.messages(1L, "TABLE_DESIGN", ALICE.userId());
        assertEquals(List.of("already shared"), tables.stream().map(AiStudioThreadMessageDTO::getContent).toList());
        List<AiStudioThreadMessageDTO> forms = service.messages(1L, "FORM_DESIGN", ALICE.userId());
        assertEquals(50, forms.size());
        AiStudioThreadMessageDTO card = forms.get(49);
        assertTrue(card.getProposal().isApplied());
        assertTrue(card.getProposal().isAppliedByMe());
        assertFalse(rows.stream().anyMatch(r -> r.getProposal() != null && r.getProposal().containsKey("undo")),
                "undo tokens never reach the shared table");

        assertEquals(List.of("PROCESS_DESIGN", "TABLE_DESIGN"), states.get(1L).getCompletedPhases());
        // 共享进度已存在时，再次导入不覆盖
        request.setCompletedPhases(List.of());
        service.importThreads(1L, request, BOB);
        assertEquals(List.of("PROCESS_DESIGN", "TABLE_DESIGN"), states.get(1L).getCompletedPhases());
    }

    @Test
    void completedPhasesAreUpsertedAndCleaned() {
        assertTrue(service.state(1L).isEmpty());
        assertEquals(List.of("PROCESS_DESIGN"), service.saveCompletedPhases(1L, List.of("PROCESS_DESIGN", "REVIEW"), "u-alice"));
        assertEquals(List.of("PROCESS_DESIGN", "TABLE_DESIGN"),
                service.saveCompletedPhases(1L, List.of("PROCESS_DESIGN", "TABLE_DESIGN"), "u-bob"));
        AiStudioThreadState state = service.state(1L).orElseThrow();
        assertEquals("u-bob", state.getUpdatedBy());
        assertEquals(List.of(), service.saveCompletedPhases(1L, null, "u-bob"));
    }

    @Test
    void incrementalReadsReturnOnlyNewerMessages() {
        AiStudioMessage first = service.appendUserMessage(1L, "TABLE_DESIGN", "q1", ALICE);
        service.appendAssistantMessage(1L, "TABLE_DESIGN", "a1", null, null, null, ALICE);
        service.appendUserMessage(1L, "TABLE_DESIGN", "q2", BOB);
        service.appendUserMessage(1L, "FORM_DESIGN", "elsewhere", BOB);

        List<AiStudioThreadMessageDTO> newer = service.messagesAfter(1L, "TABLE_DESIGN", first.getId(), BOB.userId());
        assertEquals(List.of("a1", "q2"), newer.stream().map(AiStudioThreadMessageDTO::getContent).toList());
        assertTrue(newer.get(1).isMine());
        assertTrue(service.messagesAfter(1L, "TABLE_DESIGN", 999L, BOB.userId()).isEmpty());
        assertEquals("q1", service.message(1L, first.getId(), BOB.userId()).getContent());
        assertThrows(com.developer.exception.ResourceNotFoundException.class,
                () -> service.message(2L, first.getId(), BOB.userId()));
    }

    @Test
    void everyWritePublishesAThreadEventWithIdsOnly() {
        AiStudioMessage q = service.appendUserMessage(1L, "VIEW_DESIGN", "q", ALICE);
        service.appendUserMessage(1L, "VIEW_DESIGN", "q", ALICE); // 去重：不再发事件
        AiStudioMessage card = service.appendAssistantMessage(1L, "VIEW_DESIGN", "p", "VIEWS",
                Map.of("mainTableViews", List.of()), null, ALICE);
        service.markApplied(1L, card.getId(), true, BOB);
        service.saveCompletedPhases(1L, List.of("VIEW_DESIGN"), BOB.userId());

        assertEquals(List.of(
                AiStudioThreadEvent.message(AiStudioThreadEvent.MESSAGE_ADDED, 1L, "VIEW_DESIGN", q.getId(), "u-alice"),
                AiStudioThreadEvent.message(AiStudioThreadEvent.MESSAGE_ADDED, 1L, "VIEW_DESIGN", card.getId(), "u-alice"),
                AiStudioThreadEvent.message(AiStudioThreadEvent.MESSAGE_UPDATED, 1L, "VIEW_DESIGN", card.getId(), "u-bob"),
                AiStudioThreadEvent.progress(1L, "u-bob")), published);
    }

    @Test
    void docSyncResultIsExposedSeparatelyAndCannotBeMarkedApplied() {
        Map<String, Object> docSync = new LinkedHashMap<>();
        docSync.put("status", "UPDATED");
        docSync.put("phases", List.of("TABLE_DESIGN"));
        AiStudioMessage row = service.appendDocSyncMessage(1L, "TABLE_DESIGN", "Documents check (UPDATED): x",
                docSync, ALICE);

        AiStudioThreadMessageDTO dto = service.messages(1L, "TABLE_DESIGN", BOB.userId()).get(0);
        assertEquals("ASSISTANT", dto.getRole());
        assertNull(dto.getProposal(), "a document check is not an applicable proposal");
        assertEquals("UPDATED", dto.getDocSync().get("status"));
        assertEquals("AI_STUDIO_MESSAGE_NOT_PROPOSAL", assertThrows(DeveloperBusinessException.class,
                () -> service.markApplied(1L, row.getId(), true, ALICE)).getErrorCode());
        assertNull(service.recentHistory(1L, "TABLE_DESIGN").get(0).getProposal());
    }

    @Test
    void unknownPhaseIsRejected() {
        assertThrows(DeveloperBusinessException.class, () -> service.appendUserMessage(1L, "REVIEW", "x", ALICE));
        assertThrows(DeveloperBusinessException.class, () -> service.messages(1L, "nope", "u"));
    }

    private static AiStudioThreadImportRequest.Message msg(String role, String content, Map<String, Object> proposal) {
        AiStudioThreadImportRequest.Message m = new AiStudioThreadImportRequest.Message();
        m.setRole(role);
        m.setContent(content);
        m.setProposal(proposal);
        return m;
    }
}

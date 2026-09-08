package com.admin.component;

import com.admin.entity.EmailMonitorRule;
import com.admin.entity.FunctionUnit;
import com.admin.repository.EmailMonitorRuleRepository;
import com.admin.repository.FunctionUnitRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailMonitorSyncComponentImplTest {

    @Mock
    private EmailMonitorRuleRepository emailMonitorRuleRepository;
    @Mock
    private FunctionUnitRepository functionUnitRepository;

    @InjectMocks
    private EmailMonitorSyncComponentImpl syncComponent;

    @Test
    void syncMonitorRules_skipsTemplatesWithoutStartEvent() {
        FunctionUnit functionUnit = FunctionUnit.builder().id("fu-1").code("fu_demo").build();
        when(functionUnitRepository.findById("fu-1")).thenReturn(Optional.of(functionUnit));
        when(emailMonitorRuleRepository.save(any(EmailMonitorRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> template = Map.of(
                "ruleUid", "tmpl-1",
                "name", "Inbound template",
                "connectionUid", "uid-1");
        Map<String, Object> binding = Map.of(
                "ruleUid", "bind-1",
                "name", "Inbound template → StartEvent_1",
                "connectionUid", "uid-1",
                "startEventId", "StartEvent_1");

        when(emailMonitorRuleRepository.findByFunctionUnitId("fu-1")).thenReturn(List.of());

        syncComponent.syncMonitorRules("fu-1", List.of(template, binding));

        ArgumentCaptor<EmailMonitorRule> captor = ArgumentCaptor.forClass(EmailMonitorRule.class);
        verify(emailMonitorRuleRepository, times(1)).save(captor.capture());
        verify(emailMonitorRuleRepository, never()).deleteByFunctionUnitId("fu-1");
        assertEquals("bind-1", captor.getValue().getId());
        assertEquals("StartEvent_1", captor.getValue().getStartEventId());
    }

    @Test
    void syncMonitorRules_preservesLastSyncCursorForSameRuleUid() {
        FunctionUnit functionUnit = FunctionUnit.builder().id("fu-1").code("fu_demo").build();
        when(functionUnitRepository.findById("fu-1")).thenReturn(Optional.of(functionUnit));
        EmailMonitorRule existing = EmailMonitorRule.builder()
                .id("bind-1")
                .functionUnit(functionUnit)
                .name("old")
                .connectionUid("uid-1")
                .startEventId("StartEvent_1")
                .lastSyncCursor("3479")
                .lastSyncedAt(Instant.parse("2026-09-08T10:42:40Z"))
                .build();
        when(emailMonitorRuleRepository.findByFunctionUnitId("fu-1")).thenReturn(List.of(existing));
        when(emailMonitorRuleRepository.save(any(EmailMonitorRule.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> binding = Map.of(
                "ruleUid", "bind-1",
                "name", "Inbound template → StartEvent_1",
                "connectionUid", "uid-1",
                "startEventId", "StartEvent_1");

        syncComponent.syncMonitorRules("fu-1", List.of(binding));

        ArgumentCaptor<EmailMonitorRule> captor = ArgumentCaptor.forClass(EmailMonitorRule.class);
        verify(emailMonitorRuleRepository).save(captor.capture());
        assertEquals("3479", captor.getValue().getLastSyncCursor());
        assertEquals(existing.getLastSyncedAt(), captor.getValue().getLastSyncedAt());
    }
}

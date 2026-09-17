package com.developer.enums;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiStudioPhaseTest {

    @Test
    void keyPatternListsExactlyTheEnumConstantsInOrder() {
        List<String> names = Arrays.stream(AiStudioPhase.values()).map(Enum::name).toList();
        assertEquals(names, List.of(AiStudioPhase.KEY_PATTERN.split("\\|")));
    }

    /** 常量名是落库 / API 的 key，改名等于数据迁移。 */
    @Test
    void keysAreTheStoredPhaseStringsInDesignerOrder() {
        assertEquals(List.of("PROCESS_DESIGN", "TABLE_DESIGN", "FORM_DESIGN", "VIEW_DESIGN", "ACTION_DESIGN",
                        "AUTOMATION", "CONNECTIONS", "EMAIL_TEMPLATES", "EMAIL_MONITORS", "DECISION_DESIGN",
                        "VALIDATION"),
                Arrays.stream(AiStudioPhase.values()).map(Enum::name).toList());
    }

    @Test
    void onlyValidationHasNoProposalScope() {
        for (AiStudioPhase phase : AiStudioPhase.values()) {
            assertEquals(phase != AiStudioPhase.VALIDATION, phase.proposalScope().isPresent(), phase.name());
        }
    }

    @Test
    void fromKeyIsExactAndNullSafe() {
        assertEquals(AiStudioPhase.VIEW_DESIGN, AiStudioPhase.fromKey("VIEW_DESIGN").orElseThrow());
        assertTrue(AiStudioPhase.fromKey(null).isEmpty());
        assertFalse(AiStudioPhase.isValid("view_design"));
        assertFalse(AiStudioPhase.isValid("REVIEW"));
    }
}

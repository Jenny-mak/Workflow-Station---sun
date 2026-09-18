package com.developer.component.impl;

import com.developer.component.FunctionUnitDocumentComponent;
import com.developer.dto.FunctionUnitDocumentDTO;
import com.developer.enums.AiDocumentType;
import com.developer.security.FunctionUnitWorkspaceAccessService;
import com.developer.security.WorkspaceAccessAction;
import com.developer.service.impl.FunctionUnitDocumentService;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class FunctionUnitDocumentComponentImpl implements FunctionUnitDocumentComponent {

    private final FunctionUnitDocumentService documentService;
    private final FunctionUnitWorkspaceAccessService accessService;

    public FunctionUnitDocumentComponentImpl(FunctionUnitDocumentService documentService,
                                             FunctionUnitWorkspaceAccessService accessService) {
        this.documentService = documentService;
        this.accessService = accessService;
    }

    @Override
    public Map<AiDocumentType, FunctionUnitDocumentDTO> current(Long functionUnitId) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        Map<AiDocumentType, FunctionUnitDocumentDTO> result = new EnumMap<>(AiDocumentType.class);
        for (AiDocumentType type : AiDocumentType.values()) {
            result.put(type, documentService.latest(functionUnitId, type)
                    .map(doc -> FunctionUnitDocumentDTO.of(doc, true))
                    .orElse(null));
        }
        return result;
    }

    @Override
    public List<FunctionUnitDocumentDTO> history(Long functionUnitId, AiDocumentType type) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        return documentService.history(functionUnitId, type).stream()
                .map(doc -> FunctionUnitDocumentDTO.of(doc, false))
                .toList();
    }

    @Override
    public FunctionUnitDocumentDTO version(Long functionUnitId, AiDocumentType type, int version) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.VIEW);
        return FunctionUnitDocumentDTO.of(documentService.version(functionUnitId, type, version), true);
    }

    @Override
    public FunctionUnitDocumentDTO save(Long functionUnitId, AiDocumentType type, String content, int baseVersion) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        String userId = AiStudioThreadComponentImpl.currentAuthor(null).userId();
        return FunctionUnitDocumentDTO.of(documentService.append(functionUnitId, type, content, baseVersion,
                FunctionUnitDocumentService.SUMMARY_MANUAL, userId), true);
    }

    @Override
    public int startNewRound(Long functionUnitId) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        return documentService.startNewRound(functionUnitId);
    }

    @Override
    public FunctionUnitDocumentDTO restore(Long functionUnitId, AiDocumentType type, int version, int baseVersion) {
        accessService.assertCanAccess(functionUnitId, WorkspaceAccessAction.MODIFY);
        String userId = AiStudioThreadComponentImpl.currentAuthor(null).userId();
        return FunctionUnitDocumentDTO.of(
                documentService.restore(functionUnitId, type, version, baseVersion, userId), true);
    }
}

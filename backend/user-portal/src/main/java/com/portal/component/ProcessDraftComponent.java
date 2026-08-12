package com.portal.component;

import com.portal.dto.PageResponse;
import com.portal.entity.ProcessDraft;
import com.portal.repository.ProcessDraftRepository;
import com.portal.util.PortalColumnFilterSupport;
import com.portal.util.ProcessDraftListSpec;
import com.platform.common.util.ApiResponseBodyUnwrap;
import com.platform.common.util.SafeUrlInput;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 草稿管理组件
 * 负责流程草稿的 CRUD 操作。
 * 
 * 从 ProcessComponent 中提取，降低该类的复杂度。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessDraftComponent {

    private final ProcessDraftRepository processDraftRepository;
    private final FunctionUnitAccessComponent functionUnitAccessComponent;
    private final RestTemplate restTemplate;
    private final EntityManager entityManager;

    @Value("${admin-center.url:http://localhost:8090}")
    private String adminCenterUrl;

    /**
     * 保存草稿
     */
    @Transactional
    public ProcessDraft saveDraft(String userId, String processKey, Map<String, Object> formData) {
        Optional<ProcessDraft> existing = processDraftRepository
                .findFirstByUserIdAndProcessDefinitionKeyOrderByUpdatedAtDesc(userId, processKey);
        ProcessDraft draft;
        if (existing.isPresent()) {
            draft = existing.get();
            draft.setFormData(formData);
            draft.setUpdatedAt(LocalDateTime.now());
        } else {
            draft = new ProcessDraft();
            draft.setUserId(userId);
            draft.setProcessDefinitionKey(processKey);
            draft.setFormData(formData);
            draft.setCreatedAt(LocalDateTime.now());
            draft.setUpdatedAt(LocalDateTime.now());
        }
        return processDraftRepository.save(draft);
    }

    /**
     * 获取草稿
     */
    public Optional<ProcessDraft> getDraft(String userId, String processKey) {
        return processDraftRepository
                .findFirstByUserIdAndProcessDefinitionKeyOrderByUpdatedAtDesc(userId, processKey);
    }

    /**
     * 删除草稿
     */
    @Transactional
    public void deleteDraft(String userId, String processKey) {
        processDraftRepository
                .findFirstByUserIdAndProcessDefinitionKeyOrderByUpdatedAtDesc(userId, processKey)
                .ifPresent(processDraftRepository::delete);
    }

    /**
     * 获取用户的草稿列表（全量，兼容旧客户端）
     */
    public List<Map<String, Object>> getDraftList(String userId) {
        log.info("Getting draft list for user: {}", userId);
        List<ProcessDraft> drafts = processDraftRepository.findByUserIdOrderByUpdatedAtDesc(userId);
        return toDraftInfoList(drafts);
    }

    /**
     * 分页获取用户草稿列表（0-based page）。
     */
    public PageResponse<Map<String, Object>> getDraftPage(String userId, int page, int size) {
        return getDraftPage(userId, page, size, null, null, null, null);
    }

    /**
     * Paged drafts with optional server-side column filters / sort / groupBy.
     */
    public PageResponse<Map<String, Object>> getDraftPage(
            String userId,
            int page,
            int size,
            String sortField,
            String sortDirection,
            Map<String, Map<String, Object>> filters,
            String groupBy) {
        int safePage = Math.max(0, page);
        int safeSize = size < 1 ? 20 : Math.min(size, 200);
        String safeGroupBy = ProcessDraftListSpec.sanitizeGroupBy(groupBy);
        log.info("Getting draft page for user: {}, page={}, size={}, sortField={}, groupBy={}, filterCount={}",
                userId, safePage, safeSize, sortField, safeGroupBy, filters != null ? filters.size() : 0);

        var columnFilters = ProcessDraftListSpec.parseFilters(filters);
        Specification<ProcessDraft> spec = ProcessDraftListSpec.build(userId, columnFilters);

        // Display name is resolved from admin-center — filter after enrich, then page in memory.
        if (ProcessDraftListSpec.hasProcessDefinitionNameFilter(filters)) {
            List<ProcessDraft> all = processDraftRepository.findAll(spec, Sort.by(Sort.Direction.DESC, "updatedAt"));
            List<Map<String, Object>> enriched = toDraftInfoList(all);
            PortalColumnFilterSupport.ColumnFilter nameFilter =
                    ProcessDraftListSpec.processDefinitionNameFilter(filters);
            if (nameFilter != null) {
                enriched = enriched.stream()
                        .filter(row -> PortalColumnFilterSupport.matchesText(
                                Objects.toString(row.get("processDefinitionName"), ""),
                                nameFilter.operator(),
                                nameFilter.value()))
                        .collect(Collectors.toCollection(ArrayList::new));
            }
            String sort = sortField != null ? sortField.trim() : "";
            if ("processDefinitionName".equals(sort) || "processDefinitionKey".equals(sort)
                    || "updatedAt".equals(sort) || "createdAt".equals(sort)) {
                boolean asc = "ASC".equalsIgnoreCase(sortDirection);
                String key = "processDefinitionName".equals(sort) ? "processDefinitionName" : sort;
                enriched.sort((a, b) -> {
                    Comparable left = (Comparable) a.get(key);
                    Comparable right = (Comparable) b.get(key);
                    if (left == null && right == null) {
                        return 0;
                    }
                    if (left == null) {
                        return 1;
                    }
                    if (right == null) {
                        return -1;
                    }
                    @SuppressWarnings("unchecked")
                    int cmp = left.compareTo(right);
                    return asc ? cmp : -cmp;
                });
            }
            if (safeGroupBy != null) {
                // Group on enriched maps when name filter forced memory path
                Map<String, Long> counts = enriched.stream().collect(Collectors.groupingBy(
                        row -> PortalColumnFilterSupport.groupLabel(row.get(safeGroupBy)),
                        LinkedHashMap::new,
                        Collectors.counting()));
                int from = Math.min(safePage * safeSize, enriched.size());
                int to = Math.min(from + safeSize, enriched.size());
                PageResponse<Map<String, Object>> response =
                        PageResponse.of(enriched.subList(from, to), safePage, safeSize, enriched.size());
                response.setGroupCounts(counts);
                return response;
            }
            int from = Math.min(safePage * safeSize, enriched.size());
            int to = Math.min(from + safeSize, enriched.size());
            return PageResponse.of(enriched.subList(from, to), safePage, safeSize, enriched.size());
        }

        var pageable = ProcessDraftListSpec.withSort(
                PageRequest.of(safePage, safeSize), sortField, sortDirection, safeGroupBy);
        Page<ProcessDraft> draftPage = processDraftRepository.findAll(spec, pageable);
        List<Map<String, Object>> content = toDraftInfoList(draftPage.getContent());
        PageResponse<Map<String, Object>> response =
                PageResponse.of(content, safePage, safeSize, draftPage.getTotalElements());
        if (safeGroupBy != null) {
            response.setGroupCounts(
                    PortalColumnFilterSupport.computeGroupCounts(
                            entityManager, ProcessDraft.class, spec, safeGroupBy));
        }
        return response;
    }

    private List<Map<String, Object>> toDraftInfoList(List<ProcessDraft> drafts) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ProcessDraft draft : drafts) {
            Map<String, Object> draftInfo = new HashMap<>();
            draftInfo.put("id", draft.getId());
            draftInfo.put("processDefinitionKey", draft.getProcessDefinitionKey());
            draftInfo.put("formData", draft.getFormData());
            draftInfo.put("createdAt", draft.getCreatedAt());
            draftInfo.put("updatedAt", draft.getUpdatedAt());

            try {
                String name = resolveFunctionUnitName(draft.getProcessDefinitionKey());
                draftInfo.put("processDefinitionName", name != null ? name : draft.getProcessDefinitionKey());
            } catch (Exception e) {
                log.warn("Failed to get function unit name for {}: {}", draft.getProcessDefinitionKey(), e.getMessage());
                draftInfo.put("processDefinitionName", draft.getProcessDefinitionKey());
            }

            result.add(draftInfo);
        }
        return result;
    }

    /**
     * 根据ID删除草稿
     */
    @Transactional
    public void deleteDraftById(String userId, Long draftId) {
        processDraftRepository.findById(draftId).ifPresent(draft -> {
            if (draft.getUserId().equals(userId)) {
                processDraftRepository.delete(draft);
            }
        });
    }

    /**
     * 从 admin-center 获取功能单元名称
     */
    private String resolveFunctionUnitName(String processDefinitionKey) {
        try {
            String functionUnitId = functionUnitAccessComponent.resolveFunctionUnitId(processDefinitionKey);
            String url = adminCenterUrl + "/api/v1/admin/function-units/" + SafeUrlInput.requirePathToken(functionUnitId) + "/content";

            @SuppressWarnings("unchecked")
            Map<String, Object> response = restTemplate.getForObject(url, Map.class);
            Map<String, Object> payload = ApiResponseBodyUnwrap.unwrapDataMap(response);
            if (!payload.isEmpty() && payload.get("name") != null) {
                return (String) payload.get("name");
            }
        } catch (Exception e) {
            log.debug("Failed to resolve function unit name for {}: {}", processDefinitionKey, e.getMessage());
        }
        return null;
    }
}

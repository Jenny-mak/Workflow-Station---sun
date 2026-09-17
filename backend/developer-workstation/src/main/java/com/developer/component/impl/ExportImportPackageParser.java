package com.developer.component.impl;

import com.developer.exception.DeveloperBusinessException;
import com.developer.service.impl.FunctionUnitDocumentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 导入包解析协作类。
 * 负责把上传的 ZIP 包解包为内存结构（manifest/process/tables/forms/actions/decisions/checksum），
 * 以及解析描述符（manifest.json 优先，metadata.json 兼容）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ExportImportPackageParser {

    private final ObjectMapper objectMapper;

    Map<String, Object> parseImportPackage(MultipartFile file) {
        Map<String, Object> result = new HashMap<>();
        Map<String, byte[]> rawFiles = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int len;
                while ((len = zis.read(buffer)) > 0) {
                    baos.write(buffer, 0, len);
                }
                rawFiles.put(entry.getName(), baos.toByteArray());
            }
        } catch (IOException e) {
            throw new DeveloperBusinessException("SYS_IMPORT_ERROR", "Failed to parse import package: " + e.getMessage());
        }

        try {
            // Parse manifest.json (new) or metadata.json (legacy)
            if (rawFiles.containsKey("manifest.json")) {
                result.put("manifest", objectMapper.readValue(rawFiles.get("manifest.json"), Map.class));
            } else if (rawFiles.containsKey("metadata.json")) {
                result.put("metadata", objectMapper.readValue(rawFiles.get("metadata.json"), Map.class));
            }

            // Parse process file
            for (String fileName : rawFiles.keySet()) {
                if (fileName.endsWith(".bpmn")) {
                    result.put("process", new String(rawFiles.get(fileName), StandardCharsets.UTF_8));
                    break;
                }
            }

            // Parse table definitions (old and new formats)
            List<Map<String, Object>> tables = new ArrayList<>();
            if (rawFiles.containsKey("tables.json")) {
                tables = objectMapper.readValue(rawFiles.get("tables.json"), List.class);
            } else {
                for (String fileName : rawFiles.keySet()) {
                    if (fileName.startsWith("tables/") && fileName.endsWith(".json")) {
                        tables.add(objectMapper.readValue(rawFiles.get(fileName), Map.class));
                    }
                }
            }
            result.put("tables", tables);

            // Parse table relations
            if (rawFiles.containsKey("relations/table_relations.json")) {
                result.put("tableRelations", objectMapper.readValue(
                        rawFiles.get("relations/table_relations.json"), List.class));
            }

            // Parse relation-table (rt_) structures
            if (rawFiles.containsKey("relation-tables/relation_tables.json")) {
                result.put("relationTables", objectMapper.readValue(
                        rawFiles.get("relation-tables/relation_tables.json"), List.class));
            }

            // Parse link form components (absent in older packages)
            if (rawFiles.containsKey("link-form-components/link_form_components.json")) {
                result.put("linkFormComponents", objectMapper.readValue(
                        rawFiles.get("link-form-components/link_form_components.json"), List.class));
            }

            // FR-B12: packages no longer carry Automation (Activepieces) flow bodies. Legacy
            // packages may still contain automation-flows/ entries — record the file names only,
            // so the importer can log that they are ignored (flows migrate via the dedicated
            // Automation migration channel).
            List<String> legacyAutomationFlowFiles = rawFiles.keySet().stream()
                    .filter(name -> name.startsWith("automation-flows/") && name.endsWith(".json"))
                    .sorted()
                    .toList();
            if (!legacyAutomationFlowFiles.isEmpty()) {
                result.put("legacyAutomationFlowFiles", legacyAutomationFlowFiles);
            }

            // Parse "View Design" main-table view configs
            if (rawFiles.containsKey("views/main_table_views.json")) {
                result.put("mainTableViews", objectMapper.readValue(
                        rawFiles.get("views/main_table_views.json"), List.class));
            }

            // Parse form definitions
            List<Map<String, Object>> forms = new ArrayList<>();
            if (rawFiles.containsKey("forms.json")) {
                forms = objectMapper.readValue(rawFiles.get("forms.json"), List.class);
            } else {
                for (String fileName : rawFiles.keySet()) {
                    if (fileName.startsWith("forms/") && fileName.endsWith(".json")) {
                        forms.add(objectMapper.readValue(rawFiles.get(fileName), Map.class));
                    }
                }
            }
            result.put("forms", forms);

            // Parse action definitions
            List<Map<String, Object>> actions = new ArrayList<>();
            if (rawFiles.containsKey("actions.json")) {
                actions = objectMapper.readValue(rawFiles.get("actions.json"), List.class);
            } else {
                for (String fileName : rawFiles.keySet()) {
                    if (fileName.startsWith("actions/") && fileName.endsWith(".json")) {
                        actions.add(objectMapper.readValue(rawFiles.get(fileName), Map.class));
                    }
                }
            }
            result.put("actions", actions);

            // Parse decision definitions (DMN XML files)
            List<String> decisions = new ArrayList<>();
            for (String fileName : rawFiles.keySet()) {
                if (fileName.startsWith("decisions/") && fileName.endsWith(".dmn")) {
                    decisions.add(new String(rawFiles.get(fileName), StandardCharsets.UTF_8));
                }
            }
            result.put("decisions", decisions);

            // Parse email connections
            List<Map<String, Object>> connections = new ArrayList<>();
            for (String fileName : rawFiles.keySet()) {
                if (fileName.startsWith("connections/") && fileName.endsWith(".json")) {
                    connections.add(objectMapper.readValue(rawFiles.get(fileName), Map.class));
                }
            }
            result.put("connections", connections);

            // Parse inbound email monitor rules
            List<Map<String, Object>> emailMonitors = new ArrayList<>();
            for (String fileName : rawFiles.keySet()) {
                if (fileName.startsWith("email-monitors/") && fileName.endsWith(".json")) {
                    emailMonitors.add(objectMapper.readValue(rawFiles.get(fileName), Map.class));
                }
            }
            result.put("emailMonitors", emailMonitors);

            // Parse Send Email templates
            List<Map<String, Object>> emailTemplates = new ArrayList<>();
            for (String fileName : rawFiles.keySet()) {
                if (fileName.startsWith("email-templates/") && fileName.endsWith(".json")) {
                    emailTemplates.add(objectMapper.readValue(rawFiles.get(fileName), Map.class));
                }
            }
            result.put("emailTemplates", emailTemplates);

            // Parse Requirements / Design documents (absent in packages exported before they existed)
            Map<String, String> documents = new LinkedHashMap<>();
            FunctionUnitDocumentService.PACKAGE_FILES.forEach((type, fileName) -> {
                if (rawFiles.containsKey(fileName)) {
                    documents.put(type.name(), new String(rawFiles.get(fileName), StandardCharsets.UTF_8));
                }
            });
            if (!documents.isEmpty()) {
                result.put(FunctionUnitDocumentService.PACKAGE_KEY, documents);
            }

            // Store checksum for verification
            if (rawFiles.containsKey("checksum.sha256")) {
                result.put("checksum", new String(rawFiles.get("checksum.sha256"), StandardCharsets.UTF_8));
            }

        } catch (IOException e) {
            throw new DeveloperBusinessException("SYS_IMPORT_ERROR", "Failed to parse import package content: " + e.getMessage());
        }

        return result;
    }

    /**
     * Same as importFunctionUnit: prefer manifest.json; legacy metadata.json.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> resolvePackageDescriptor(Map<String, Object> packageData) {
        if (packageData.containsKey("manifest")) {
            return (Map<String, Object>) packageData.get("manifest");
        }
        if (packageData.containsKey("metadata")) {
            return (Map<String, Object>) packageData.get("metadata");
        }
        return null;
    }
}

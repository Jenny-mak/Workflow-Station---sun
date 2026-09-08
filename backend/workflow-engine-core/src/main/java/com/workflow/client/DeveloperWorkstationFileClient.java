package com.workflow.client;

import com.platform.common.constant.PlatformConstants;
import com.platform.common.util.SafeUrlInput;
import com.workflow.config.RestTemplateConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads uploaded file bytes from developer-workstation for Send Email attachments,
 * and uploads inbound Email Monitor attachments through the same store.
 */
@Slf4j
@Component
public class DeveloperWorkstationFileClient {

    private static final Pattern UPLOAD_PATH = Pattern.compile(
            ".*/upload/files/([^/?#]+)(?:\\?([^#]*))?", Pattern.CASE_INSENSITIVE);
    private static final Pattern ORIGINAL_NAME = Pattern.compile(
            "(?:^|&)originalName=([^&]*)", Pattern.CASE_INSENSITIVE);

    private final RestTemplate restTemplate;
    private final RestTemplate outboundRestTemplate;
    private final String serviceInternalToken;

    @Value("${file-service.base-url:http://localhost:8083}")
    private String developerWorkstationBaseUrl;

    public DeveloperWorkstationFileClient(
            @Qualifier(RestTemplateConfig.INTERNAL_API_REST_TEMPLATE) RestTemplate restTemplate,
            RestTemplate outboundRestTemplate,
            @Value("${service.internal-token:}") String serviceInternalToken) {
        this.restTemplate = restTemplate;
        this.outboundRestTemplate = outboundRestTemplate;
        this.serviceInternalToken = serviceInternalToken;
    }

    public Optional<DownloadedFile> downloadByStoredUrl(String storedUrl) {
        if (!StringUtils.hasText(storedUrl)) {
            return Optional.empty();
        }
        Matcher matcher = UPLOAD_PATH.matcher(storedUrl.trim());
        if (!matcher.find()) {
            log.warn("Not a platform upload URL; skip attachment download");
            return Optional.empty();
        }
        String storedName = matcher.group(1);
        String query = matcher.group(2);
        String originalName = extractOriginalName(query).orElse(storedName);
        try {
            String base = trimTrailingSlash(developerWorkstationBaseUrl);
            String url = base + "/api/v1/upload/files/" + SafeUrlInput.requirePathToken(storedName);
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    URI.create(url), HttpMethod.GET, null, byte[].class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return Optional.empty();
            }
            return Optional.of(new DownloadedFile(originalName, response.getBody()));
        } catch (Exception e) {
            // FALLBACK(external): download failure returns empty; caller decides skip vs fail.
            log.error("Failed to download uploaded file {}: {}", storedName, e.getMessage());
            return Optional.empty();
        }
    }

    private static Optional<String> extractOriginalName(String query) {
        if (!StringUtils.hasText(query)) {
            return Optional.empty();
        }
        Matcher m = ORIGINAL_NAME.matcher(query);
        if (!m.find()) {
            return Optional.empty();
        }
        try {
            String decoded = java.net.URLDecoder.decode(m.group(1), StandardCharsets.UTF_8);
            return StringUtils.hasText(decoded) ? Optional.of(decoded.trim()) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String trimTrailingSlash(String base) {
        if (base == null || base.isEmpty()) {
            return "";
        }
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    public Optional<UploadedFileRef> upload(String filename, String contentType, byte[] content) {
        if (!StringUtils.hasText(filename) || content == null || content.length == 0) {
            return Optional.empty();
        }
        String safeName = filename.replace('\\', '/');
        int slash = safeName.lastIndexOf('/');
        if (slash >= 0) {
            safeName = safeName.substring(slash + 1);
        }
        if (!StringUtils.hasText(safeName)) {
            return Optional.empty();
        }
        try {
            String url = trimTrailingSlash(developerWorkstationBaseUrl) + "/api/v1/upload";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            if (StringUtils.hasText(serviceInternalToken)) {
                headers.set(PlatformConstants.HEADER_SERVICE_TOKEN, serviceInternalToken);
            }
            String partName = safeName;
            ByteArrayResource resource = new ByteArrayResource(content) {
                @Override
                public String getFilename() {
                    return partName;
                }
            };
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", resource);
            ResponseEntity<Map> response = outboundRestTemplate.postForEntity(
                    url, new HttpEntity<>(body, headers), Map.class);
            return parseUploadResponse(response.getBody(), safeName);
        } catch (Exception e) {
            // FALLBACK(external): DW POST /upload failed; binder records attachmentErrors.
            // Non-required mappings still start (plan); required + empty FILE → manual review.
            log.error("Failed to upload inbound attachment {}: {}", safeName, e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private static Optional<UploadedFileRef> parseUploadResponse(Map<?, ?> body, String fallbackName) {
        if (body == null) {
            return Optional.empty();
        }
        Object data = body.get("data");
        if (!(data instanceof Map<?, ?> payload)) {
            return Optional.empty();
        }
        Object url = payload.get("url");
        if (!(url instanceof String urlText) || urlText.isBlank()) {
            return Optional.empty();
        }
        Object name = payload.get("name");
        String storedName = name instanceof String n && StringUtils.hasText(n) ? n : fallbackName;
        return Optional.of(new UploadedFileRef(urlText.trim(), storedName));
    }

    public record DownloadedFile(String fileName, byte[] content) {}

    public record UploadedFileRef(String url, String name) {}
}

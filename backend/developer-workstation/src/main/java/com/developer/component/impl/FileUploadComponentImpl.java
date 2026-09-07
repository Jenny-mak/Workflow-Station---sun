package com.developer.component.impl;

import com.developer.component.FileUploadComponent;
import com.developer.entity.UploadedFile;
import com.developer.exception.DeveloperBusinessException;
import com.developer.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Implementation of the generic file upload component. */
@Component
@Slf4j
@RequiredArgsConstructor
public class FileUploadComponentImpl implements FileUploadComponent {

    private static final long MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024;

    private final FileStorageService fileStorageService;

    @Value("${file.upload.base-url:/api/v1/upload/files}")
    private String baseUrl;

    @Override
    public Map<String, Object> upload(MultipartFile file) throws IOException {
        validateUpload(file);

        String originalFilename = file.getOriginalFilename();
        String extension = getExtension(originalFilename).toLowerCase();
        String storedName = UUID.randomUUID() + extension;
        UploadedFile storedFile = fileStorageService.store(
                storedName,
                originalFilename,
                file.getContentType(),
                file.getSize(),
                file.getBytes()
        );

        String encodedOriginalName = URLEncoder.encode(originalFilename, StandardCharsets.UTF_8);
        String fileUrl = baseUrl + "/" + storedName + "?originalName=" + encodedOriginalName;

        Map<String, Object> result = new HashMap<>();
        result.put("id", storedFile.getStoredName());
        result.put("name", storedFile.getOriginalName());
        result.put("url", fileUrl);
        result.put("size", storedFile.getFileSize());
        result.put("type", storedFile.getContentType());

        log.info("File uploaded to database: {} -> {}", originalFilename, storedName);
        return result;
    }

    @Override
    public UploadedFile getFile(String storedName) {
        return fileStorageService.getByStoredName(storedName);
    }

    @Override
    public void deleteFile(String storedName) {
        fileStorageService.deleteByStoredName(storedName);
        log.info("File deleted from database: {}", storedName);
    }

    private void validateUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new DeveloperBusinessException("FILE_EMPTY", "File must not be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new DeveloperBusinessException("FILE_TOO_LARGE", "File size must not exceed 50MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new DeveloperBusinessException("INVALID_FILENAME", "Invalid filename");
        }
        // Any file type is accepted. Safety lives on the serving side: FileUploadController
        // only previews inline-safe content types; everything else downloads as attachment.
    }

    /**
     * Extension for the stored name, sanitized so it is safe inside a path segment and a
     * Content-Disposition header (letters/digits/dots only, capped length). The original
     * name is preserved separately and returned via the {@code originalName} query param.
     */
    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        if (dotIndex < 0) return "";
        String ext = filename.substring(dotIndex).replaceAll("[^A-Za-z0-9.]", "");
        if (ext.length() > 16) ext = ext.substring(0, 16);
        return ext.equals(".") ? "" : ext;
    }
}

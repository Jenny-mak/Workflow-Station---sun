package com.developer.component.impl;

import com.developer.component.FileTransferComponent;
import com.developer.dto.FileTransferResponse;
import com.developer.entity.UploadedFile;
import com.developer.entity.UploadedFileTransfer;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.UploadedFileRepository;
import com.developer.repository.UploadedFileTransferRepository;
import com.developer.util.StoredFileNameGuard;
import com.platform.common.dto.UserPrincipal;
import com.platform.security.util.SecurityContextUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
public class FileTransferComponentImpl implements FileTransferComponent {

    private final UploadedFileRepository uploadedFileRepository;
    private final UploadedFileTransferRepository transferRepository;

    @Override
    @Transactional(readOnly = true)
    public List<FileTransferResponse> query(List<String> storedNames) {
        List<String> names = StoredFileNameGuard.requireSafeDistinct(storedNames);
        Map<String, UploadedFileTransfer> byName = transferRepository.findByStoredNameIn(names).stream()
                .collect(Collectors.toMap(UploadedFileTransfer::getStoredName, Function.identity(), (a, b) -> a));
        List<FileTransferResponse> rows = new ArrayList<>(names.size());
        for (String name : names) {
            UploadedFileTransfer existing = byName.get(name);
            rows.add(toResponse(name, existing == null ? null : existing.getFileDescription()));
        }
        return rows;
    }

    @Override
    @Transactional
    public FileTransferResponse updateDescription(String storedName, String fileDescription) {
        String name = StoredFileNameGuard.requireSafe(storedName);
        UploadedFile file = uploadedFileRepository.findByStoredName(name)
                .orElseThrow(() -> new ResourceNotFoundException("UploadedFile", name));
        assertCanUpdate(file);
        String description = fileDescription == null ? "" : fileDescription.trim();
        UploadedFileTransfer row = transferRepository.findByStoredName(name)
                .orElseGet(() -> UploadedFileTransfer.builder()
                        .storedName(name)
                        .sendStatus("PENDING")
                        .attemptCount(0)
                        .build());
        row.setFileDescription(description);
        transferRepository.save(row);
        return toResponse(name, description);
    }

    private void assertCanUpdate(UploadedFile file) {
        String owner = file.getCreatedBy();
        Optional<UserPrincipal> current = SecurityContextUtils.getCurrentUser();
        if (isUnknownOwner(owner)) {
            // FALLBACK(migration): AuditorAware writes "system" when DW does not see a portal JWT
            if (current.isEmpty()) {
                throw forbidden();
            }
            return;
        }
        if (current.isEmpty() || !isSameActor(owner, current.get())) {
            throw forbidden();
        }
    }

    private static boolean isUnknownOwner(String owner) {
        return owner == null || owner.isBlank() || "system".equalsIgnoreCase(owner);
    }

    private static boolean isSameActor(String owner, UserPrincipal user) {
        return owner.equals(user.getUsername()) || owner.equals(user.getUserId());
    }

    private static DeveloperBusinessException forbidden() {
        return new DeveloperBusinessException("FILE_TRANSFER_FORBIDDEN", "Not allowed to update this file description");
    }

    private static FileTransferResponse toResponse(String storedName, String description) {
        return FileTransferResponse.builder()
                .storedName(storedName)
                .fileDescription(description)
                .build();
    }
}

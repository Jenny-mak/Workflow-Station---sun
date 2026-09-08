package com.developer.component.impl;

import com.developer.dto.FileTransferResponse;
import com.developer.entity.UploadedFile;
import com.developer.entity.UploadedFileTransfer;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.UploadedFileRepository;
import com.developer.repository.UploadedFileTransferRepository;
import com.platform.common.dto.UserPrincipal;
import com.platform.security.util.SecurityContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FileTransferComponentImplTest {

    @Mock
    private UploadedFileRepository uploadedFileRepository;

    @Mock
    private UploadedFileTransferRepository transferRepository;

    private FileTransferComponentImpl component;
    private MockedStatic<SecurityContextUtils> security;

    @BeforeEach
    void setUp() {
        component = new FileTransferComponentImpl(uploadedFileRepository, transferRepository);
        security = mockStatic(SecurityContextUtils.class);
        security.when(SecurityContextUtils::getCurrentUser).thenReturn(Optional.of(
                UserPrincipal.builder().userId("u-alice").username("alice").build()));
        security.when(SecurityContextUtils::getCurrentUsername).thenReturn(Optional.of("alice"));
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    @Test
    void query_shouldReturnEmptyDescriptionWithoutWriting_whenNoTransferRow() {
        when(transferRepository.findByStoredNameIn(List.of("a.pdf"))).thenReturn(List.of());

        List<FileTransferResponse> rows = component.query(List.of("a.pdf"));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getStoredName()).isEqualTo("a.pdf");
        assertThat(rows.get(0).getFileDescription()).isNull();
    }

    @Test
    void query_shouldMergeExistingDescription() {
        UploadedFileTransfer existing = UploadedFileTransfer.builder()
                .storedName("a.pdf")
                .fileDescription("invoice")
                .build();
        when(transferRepository.findByStoredNameIn(List.of("a.pdf"))).thenReturn(List.of(existing));

        List<FileTransferResponse> rows = component.query(List.of("a.pdf"));

        assertThat(rows.get(0).getFileDescription()).isEqualTo("invoice");
    }

    @Test
    void query_shouldRejectPathTraversal() {
        assertThatThrownBy(() -> component.query(List.of("../secret.pdf")))
                .isInstanceOf(DeveloperBusinessException.class)
                .hasMessage("Invalid stored file name");
    }

    @Test
    void updateDescription_shouldInsertWhenMissing() {
        when(uploadedFileRepository.findByStoredName("a.pdf"))
                .thenReturn(Optional.of(UploadedFile.builder().storedName("a.pdf").createdBy("alice").build()));
        when(transferRepository.findByStoredName("a.pdf")).thenReturn(Optional.empty());
        when(transferRepository.save(any(UploadedFileTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FileTransferResponse result = component.updateDescription("a.pdf", "  note  ");

        ArgumentCaptor<UploadedFileTransfer> captor = ArgumentCaptor.forClass(UploadedFileTransfer.class);
        verify(transferRepository).save(captor.capture());
        assertThat(captor.getValue().getStoredName()).isEqualTo("a.pdf");
        assertThat(captor.getValue().getFileDescription()).isEqualTo("note");
        assertThat(result.getFileDescription()).isEqualTo("note");
    }

    @Test
    void updateDescription_shouldRejectOtherUploader() {
        when(uploadedFileRepository.findByStoredName("a.pdf"))
                .thenReturn(Optional.of(UploadedFile.builder().storedName("a.pdf").createdBy("bob").build()));

        assertThatThrownBy(() -> component.updateDescription("a.pdf", "x"))
                .isInstanceOf(DeveloperBusinessException.class)
                .hasMessage("Not allowed to update this file description");
    }

    @Test
    void updateDescription_shouldRejectUnknownFile() {
        when(uploadedFileRepository.findByStoredName("missing.pdf")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> component.updateDescription("missing.pdf", "x"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateDescription_shouldAllowAuditorFallbackSystemOwner_whenCallerAuthenticated() {
        when(uploadedFileRepository.findByStoredName("a.pdf"))
                .thenReturn(Optional.of(UploadedFile.builder().storedName("a.pdf").createdBy("system").build()));
        when(transferRepository.findByStoredName("a.pdf")).thenReturn(Optional.empty());
        when(transferRepository.save(any(UploadedFileTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FileTransferResponse result = component.updateDescription("a.pdf", "portal note");

        assertThat(result.getFileDescription()).isEqualTo("portal note");
    }

    @Test
    void updateDescription_shouldAllowWhenOwnerMatchesUserId() {
        when(uploadedFileRepository.findByStoredName("a.pdf"))
                .thenReturn(Optional.of(UploadedFile.builder().storedName("a.pdf").createdBy("u-alice").build()));
        when(transferRepository.findByStoredName("a.pdf")).thenReturn(Optional.empty());
        when(transferRepository.save(any(UploadedFileTransfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        FileTransferResponse result = component.updateDescription("a.pdf", "by id");

        assertThat(result.getFileDescription()).isEqualTo("by id");
    }

    @Test
    void updateDescription_shouldRejectSystemOwnerWhenUnauthenticated() {
        security.when(SecurityContextUtils::getCurrentUser).thenReturn(Optional.empty());
        when(uploadedFileRepository.findByStoredName("a.pdf"))
                .thenReturn(Optional.of(UploadedFile.builder().storedName("a.pdf").createdBy("system").build()));

        assertThatThrownBy(() -> component.updateDescription("a.pdf", "x"))
                .isInstanceOf(DeveloperBusinessException.class)
                .hasMessage("Not allowed to update this file description");
    }
}

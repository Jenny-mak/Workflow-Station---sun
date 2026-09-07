package com.developer.controller;

import com.developer.component.FileUploadComponent;
import com.developer.entity.UploadedFile;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.platform.security.util.SecurityContextUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class FileUploadControllerTest {

    @Mock
    private FileUploadComponent fileUploadComponent;

    @InjectMocks
    private FileUploadController controller;

    private MockMvc mockMvc;
    private MockedStatic<SecurityContextUtils> security;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
        security = mockStatic(SecurityContextUtils.class);
        security.when(SecurityContextUtils::isAuthenticated).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        security.close();
    }

    @Test
    void upload_shouldReturnUnauthorizedWhenAnonymous() throws Exception {
        security.when(SecurityContextUtils::isAuthenticated).thenReturn(false);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "hello".getBytes()
        );

        mockMvc.perform(multipart("/upload").file(file))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UNAUTHENTICATED"));
    }

    @Test
    void upload_shouldReturnCompatibleSuccessPayload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "hello".getBytes()
        );
        when(fileUploadComponent.upload(any()))
                .thenReturn(Map.of(
                        "id", "abc.pdf",
                        "name", "invoice.pdf",
                        "url", "/api/v1/upload/files/abc.pdf?originalName=invoice.pdf",
                        "size", 5L,
                        "type", MediaType.APPLICATION_PDF_VALUE
                ));

        mockMvc.perform(multipart("/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("abc.pdf"))
                .andExpect(jsonPath("$.data.url").value("/api/v1/upload/files/abc.pdf?originalName=invoice.pdf"));
    }

    @Test
    void upload_shouldReturnBadRequestForValidationFailure() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "hello".getBytes()
        );
        when(fileUploadComponent.upload(any()))
                .thenThrow(new DeveloperBusinessException("FILE_TOO_LARGE", "File size must not exceed 50MB"));

        mockMvc.perform(multipart("/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void upload_shouldReturnServerErrorForReadFailure() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invoice.pdf",
                MediaType.APPLICATION_PDF_VALUE,
                "hello".getBytes()
        );
        when(fileUploadComponent.upload(any())).thenThrow(new IOException("boom"));

        mockMvc.perform(multipart("/upload").file(file))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("UPLOAD_FAILED"));
    }

    @Test
    void getFile_shouldReturnBytesFromDatabase() throws Exception {
        when(fileUploadComponent.getFile("abc.pdf"))
                .thenReturn(UploadedFile.builder()
                        .storedName("abc.pdf")
                        .originalName("invoice.pdf")
                        .contentType(MediaType.APPLICATION_PDF_VALUE)
                        .fileSize(5L)
                        .content("hello".getBytes())
                        .build());

        mockMvc.perform(get("/upload/files/abc.pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andExpect(content().bytes("hello".getBytes()));
    }

    @Test
    void getFile_shouldRejectPathTraversal() throws Exception {
        assertThat(controller.getFile("../secret.txt").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void getFile_shouldServeNonInlineSafeTypesAsAttachment() throws Exception {
        when(fileUploadComponent.getFile("page.html"))
                .thenReturn(UploadedFile.builder()
                        .storedName("page.html")
                        .originalName("page.html")
                        .contentType("text/html")
                        .fileSize(20L)
                        .content("<script>x</script>".getBytes())
                        .build());

        // Stored-XSS guard: html/svg/etc. must never render inline in the platform origin.
        mockMvc.perform(get("/upload/files/page.html"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/octet-stream"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"page.html\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void getFile_shouldKeepInlinePreviewForSafeTypes() throws Exception {
        when(fileUploadComponent.getFile("pic.png"))
                .thenReturn(UploadedFile.builder()
                        .storedName("pic.png")
                        .originalName("pic.png")
                        .contentType("image/png")
                        .fileSize(3L)
                        .content("png".getBytes())
                        .build());

        mockMvc.perform(get("/upload/files/pic.png"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"pic.png\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void getFile_shouldReturnNotFoundWhenMissing() throws Exception {
        when(fileUploadComponent.getFile("missing.pdf"))
                .thenThrow(new ResourceNotFoundException("UploadedFile", "missing.pdf"));

        mockMvc.perform(get("/upload/files/missing.pdf"))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteFile_shouldDeleteExistingDatabaseRow() throws Exception {
        doNothing().when(fileUploadComponent).deleteFile("abc.pdf");

        mockMvc.perform(delete("/upload/files/abc.pdf"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void deleteFile_shouldRejectPathTraversal() throws Exception {
        assertThat(controller.deleteFile("../secret.txt").getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void deleteFile_shouldReturnNotFoundWhenMissing() throws Exception {
        doThrow(new ResourceNotFoundException("UploadedFile", "missing.pdf"))
                .when(fileUploadComponent).deleteFile(eq("missing.pdf"));

        mockMvc.perform(delete("/upload/files/missing.pdf"))
                .andExpect(status().isNotFound());
    }
}

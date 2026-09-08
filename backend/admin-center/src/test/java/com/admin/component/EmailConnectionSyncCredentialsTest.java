package com.admin.component;

import com.admin.entity.EmailConnection;
import com.admin.entity.FunctionUnit;
import com.admin.repository.EmailConnectionRepository;
import com.admin.repository.FunctionUnitRepository;
import com.platform.security.encryption.EncryptionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmailConnectionSyncCredentialsTest {

    @Mock
    private EmailConnectionRepository emailConnectionRepository;

    @Mock
    private FunctionUnitRepository functionUnitRepository;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private SystemSmtpConfigResolver systemSmtpConfigResolver;

    @InjectMocks
    private EmailConnectionSyncComponentImpl syncComponent;

    @Test
    void getCredentials_outbound_overlaysSystemSmtp() {
        EmailConnection conn = EmailConnection.builder()
                .id("conn-1")
                .connectionType("SMTP")
                .host("stale.example.com")
                .port(25)
                .useTls(false)
                .username("user")
                .fromEmail("from@example.com")
                .fromName("From")
                .enabled(true)
                .direction("OUTBOUND")
                .credentialEncrypted("enc")
                .build();

        when(emailConnectionRepository.findByFunctionUnitIdAndId("fu-1", "conn-1"))
                .thenReturn(Optional.of(conn));
        when(encryptionService.decrypt("enc")).thenReturn("secret");
        when(systemSmtpConfigResolver.requireSystemSmtpEndpoint())
                .thenReturn(new SystemSmtpConfigResolver.SystemSmtpEndpoint("smtp.system.local", 587, true));

        Optional<Map<String, Object>> creds = syncComponent.getCredentials("fu-1", "conn-1");

        assertTrue(creds.isPresent());
        assertEquals("smtp.system.local", creds.get().get("host"));
        assertEquals(587, creds.get().get("port"));
        assertEquals(true, creds.get().get("useTls"));
        assertEquals("user", creds.get().get("username"));
        assertEquals("secret", creds.get().get("password"));
    }

    @Test
    void getCredentials_inbound_keepsConnectionEndpoint() {
        EmailConnection conn = EmailConnection.builder()
                .id("conn-2")
                .connectionType("SMTP")
                .host("imap-smtp.example.com")
                .port(465)
                .useTls(true)
                .username("in")
                .fromEmail("in@example.com")
                .enabled(true)
                .direction("INBOUND")
                .build();

        when(emailConnectionRepository.findByFunctionUnitIdAndId("fu-1", "conn-2"))
                .thenReturn(Optional.of(conn));

        Optional<Map<String, Object>> creds = syncComponent.getCredentials("fu-1", "conn-2");

        assertTrue(creds.isPresent());
        assertEquals("imap-smtp.example.com", creds.get().get("host"));
        assertEquals(465, creds.get().get("port"));
        assertEquals(true, creds.get().get("useTls"));
    }

    @Test
    void getCredentials_fallsBackToConnectionIdWhenSameFunctionUnitCode() {
        FunctionUnit currentFu = FunctionUnit.builder()
                .id("catalog-fu-new")
                .code("LEAVE_APP")
                .name("Leave")
                .version("2")
                .build();
        FunctionUnit requestFu = FunctionUnit.builder()
                .id("26f58e33-9d20-4aa4-8ee3-db32de999b15")
                .code("LEAVE_APP")
                .name("Leave")
                .version("1")
                .build();
        EmailConnection conn = EmailConnection.builder()
                .id("ad35bb02-442d-471b-a32e-9c56d1f2694b")
                .functionUnit(currentFu)
                .connectionType("SMTP")
                .host("smtp.qq.com")
                .port(465)
                .useTls(true)
                .username("user")
                .fromEmail("from@example.com")
                .enabled(true)
                .direction("OUTBOUND")
                .credentialEncrypted("enc")
                .build();

        when(emailConnectionRepository.findByFunctionUnitIdAndId(
                "26f58e33-9d20-4aa4-8ee3-db32de999b15", "ad35bb02-442d-471b-a32e-9c56d1f2694b"))
                .thenReturn(Optional.empty());
        when(emailConnectionRepository.findById("ad35bb02-442d-471b-a32e-9c56d1f2694b"))
                .thenReturn(Optional.of(conn));
        when(functionUnitRepository.findById("26f58e33-9d20-4aa4-8ee3-db32de999b15"))
                .thenReturn(Optional.of(requestFu));
        when(encryptionService.decrypt("enc")).thenReturn("secret");
        when(systemSmtpConfigResolver.requireSystemSmtpEndpoint())
                .thenReturn(new SystemSmtpConfigResolver.SystemSmtpEndpoint("smtp.qq.com", 465, true));

        Optional<Map<String, Object>> creds = syncComponent.getCredentials(
                "26f58e33-9d20-4aa4-8ee3-db32de999b15",
                "ad35bb02-442d-471b-a32e-9c56d1f2694b");

        assertTrue(creds.isPresent());
        assertEquals("smtp.qq.com", creds.get().get("host"));
        assertEquals("secret", creds.get().get("password"));
    }

    @Test
    void getCredentials_findByIdFallback_rejectsDifferentFunctionUnitCode() {
        FunctionUnit currentFu = FunctionUnit.builder()
                .id("other-fu")
                .code("OTHER_APP")
                .name("Other")
                .version("1")
                .build();
        FunctionUnit requestFu = FunctionUnit.builder()
                .id("request-fu")
                .code("LEAVE_APP")
                .name("Leave")
                .version("1")
                .build();
        EmailConnection conn = EmailConnection.builder()
                .id("conn-x")
                .functionUnit(currentFu)
                .connectionType("SMTP")
                .host("smtp.example.com")
                .port(25)
                .useTls(false)
                .fromEmail("from@example.com")
                .enabled(true)
                .direction("OUTBOUND")
                .build();

        when(emailConnectionRepository.findByFunctionUnitIdAndId("request-fu", "conn-x"))
                .thenReturn(Optional.empty());
        when(emailConnectionRepository.findById("conn-x")).thenReturn(Optional.of(conn));
        when(functionUnitRepository.findById("request-fu")).thenReturn(Optional.of(requestFu));

        Optional<Map<String, Object>> creds = syncComponent.getCredentials("request-fu", "conn-x");

        assertTrue(creds.isEmpty());
    }

    @Test
    void getCredentials_outbound_missingSystemSmtp_throws() {
        EmailConnection conn = EmailConnection.builder()
                .id("conn-3")
                .connectionType("SMTP")
                .host("stale.example.com")
                .port(25)
                .useTls(false)
                .fromEmail("from@example.com")
                .enabled(true)
                .direction("BOTH")
                .build();

        when(emailConnectionRepository.findByFunctionUnitIdAndId("fu-1", "conn-3"))
                .thenReturn(Optional.of(conn));
        when(systemSmtpConfigResolver.requireSystemSmtpEndpoint())
                .thenThrow(new IllegalStateException("System SMTP host is not configured (smtp.host)"));

        IllegalStateException ex = assertThrows(
                IllegalStateException.class,
                () -> syncComponent.getCredentials("fu-1", "conn-3"));
        assertTrue(ex.getMessage().contains("smtp.host"));
    }

    @Test
    void syncConnections_clearsSecretThatCannotBeDecrypted() {
        FunctionUnit fu = FunctionUnit.builder()
                .id("fu-1")
                .code("pr")
                .name("Purchase Request")
                .version("1.0.0")
                .build();
        when(functionUnitRepository.findById("fu-1")).thenReturn(Optional.of(fu));
        when(encryptionService.decrypt("ENC:bad")).thenThrow(new RuntimeException("Decryption failed"));
        when(emailConnectionRepository.save(any(EmailConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        syncComponent.syncConnections("fu-1", List.of(Map.of(
                "connectionUid", "conn-x",
                "name", "Mail",
                "host", "smtp.example.com",
                "fromEmail", "from@example.com",
                "credentialEncrypted", "ENC:bad")));

        org.mockito.ArgumentCaptor<EmailConnection> captor =
                org.mockito.ArgumentCaptor.forClass(EmailConnection.class);
        verify(emailConnectionRepository).save(captor.capture());
        assertNull(captor.getValue().getCredentialEncrypted());
        assertEquals("smtp.example.com", captor.getValue().getHost());
    }

    @Test
    void syncConnections_acceptsLegacyPasswordEncryptedKey() {
        FunctionUnit fu = FunctionUnit.builder()
                .id("fu-1")
                .code("pr")
                .name("Purchase Request")
                .version("1.0.0")
                .build();
        when(functionUnitRepository.findById("fu-1")).thenReturn(Optional.of(fu));
        when(encryptionService.decrypt("enc-legacy")).thenReturn("secret");
        when(emailConnectionRepository.save(any(EmailConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        syncComponent.syncConnections("fu-1", List.of(Map.of(
                "connectionUid", "conn-x",
                "name", "Mail",
                "host", "smtp.example.com",
                "fromEmail", "from@example.com",
                "passwordEncrypted", "enc-legacy")));

        org.mockito.ArgumentCaptor<EmailConnection> captor =
                org.mockito.ArgumentCaptor.forClass(EmailConnection.class);
        verify(emailConnectionRepository).save(captor.capture());
        assertEquals("enc-legacy", captor.getValue().getCredentialEncrypted());
    }
}

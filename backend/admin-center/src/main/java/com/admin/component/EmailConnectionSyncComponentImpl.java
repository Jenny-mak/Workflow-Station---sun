package com.admin.component;

import com.admin.entity.EmailConnection;
import com.admin.entity.FunctionUnit;
import com.admin.repository.EmailConnectionRepository;
import com.admin.repository.FunctionUnitRepository;
import com.platform.common.mail.EmailConnectionPortability;
import com.platform.security.encryption.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailConnectionSyncComponentImpl implements EmailConnectionSyncComponent {

    private final EmailConnectionRepository emailConnectionRepository;
    private final FunctionUnitRepository functionUnitRepository;
    private final EncryptionService encryptionService;
    private final SystemSmtpConfigResolver systemSmtpConfigResolver;

    @Override
    @Transactional
    public void syncConnections(String functionUnitId, List<Map<String, Object>> connections) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new IllegalArgumentException("Function unit not found: " + functionUnitId));

        emailConnectionRepository.deleteByFunctionUnitId(functionUnitId);

        if (connections == null || connections.isEmpty()) {
            log.info("No email connections to sync for function unit {}", functionUnitId);
            return;
        }

        for (Map<String, Object> conn : connections) {
            String connectionUid = String.valueOf(conn.get("connectionUid"));
            EmailConnection entity = EmailConnection.builder()
                    .id(connectionUid)
                    .functionUnit(functionUnit)
                    .name((String) conn.get("name"))
                    .connectionType(conn.get("connectionType") != null ? conn.get("connectionType").toString() : "SMTP")
                    .host((String) conn.get("host"))
                    .port(conn.get("port") != null ? ((Number) conn.get("port")).intValue() : 587)
                    .username((String) conn.get("username"))
                    .credentialEncrypted(usableSecret(
                            EmailConnectionPortability.readEncryptedCredential(conn), connectionUid, "credential"))
                    .fromEmail((String) conn.get("fromEmail"))
                    .fromName((String) conn.get("fromName"))
                    .useTls(conn.get("useTls") != null ? (Boolean) conn.get("useTls") : true)
                    .enabled(conn.get("enabled") != null ? (Boolean) conn.get("enabled") : true)
                    .direction(conn.get("direction") != null ? conn.get("direction").toString() : "OUTBOUND")
                    .oauthProvider((String) conn.get("oauthProvider"))
                    .oauthRefreshTokenEncrypted(usableSecret(
                            (String) conn.get("oauthRefreshTokenEncrypted"), connectionUid, "oauth refresh token"))
                    .oauthAccessTokenEncrypted(usableSecret(
                            (String) conn.get("oauthAccessTokenEncrypted"), connectionUid, "oauth access token"))
                    .tokenExpiresAt(parseInstant(conn.get("tokenExpiresAt")))
                    .mailboxAddress((String) conn.get("mailboxAddress"))
                    .imapHost((String) conn.get("imapHost"))
                    .imapPort(conn.get("imapPort") != null ? ((Number) conn.get("imapPort")).intValue() : null)
                    .imapUseSsl(conn.get("imapUseSsl") != null ? (Boolean) conn.get("imapUseSsl") : null)
                    .oauthScopes((String) conn.get("oauthScopes"))
                    .syncedAt(Instant.now())
                    .build();
            emailConnectionRepository.save(entity);
        }

        log.info("Synced {} email connections for function unit {}", connections.size(), functionUnitId);
    }

    /**
     * Ciphertext from another environment cannot be decrypted here. Keep the connection row
     * but drop the secret so operators re-enter it instead of storing a useless blob.
     */
    private String usableSecret(String encrypted, String connectionUid, String kind) {
        if (encrypted == null || encrypted.isBlank()) {
            return encrypted;
        }
        try {
            encryptionService.decrypt(encrypted);
            return encrypted;
        } catch (RuntimeException e) {
            log.warn("Imported email connection {} {} cannot be decrypted in this environment; cleared",
                    connectionUid, kind);
            return null;
        }
    }

    private Instant parseInstant(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value.toString());
        } catch (RuntimeException e) {
            log.warn("Ignoring unparseable tokenExpiresAt during sync: {}", value);
            return null;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Map<String, Object>> getCredentials(String functionUnitId, String connectionId) {
        Optional<EmailConnection> byPair =
                emailConnectionRepository.findByFunctionUnitIdAndId(functionUnitId, connectionId);
        // FALLBACK(migration): connectionUid is the global PK. Redeploy sync moves the row to the
        // latest catalog functionUnitId while long-running process instances still carry the
        // start-time functionUnitId — prefer exact FU+id match, then resolve by connection id
        // only when both FUs share the same code (same design lineage).
        Optional<EmailConnection> resolved = byPair.isPresent()
                ? byPair
                : emailConnectionRepository.findById(connectionId)
                        .filter(conn -> sameFunctionUnitFamily(functionUnitId, conn));
        return resolved.filter(EmailConnection::getEnabled).map(this::toCredentialMap);
    }

    private boolean sameFunctionUnitFamily(String requestFunctionUnitId, EmailConnection conn) {
        if (requestFunctionUnitId == null || requestFunctionUnitId.isBlank() || conn.getFunctionUnit() == null) {
            return false;
        }
        FunctionUnit connFu = conn.getFunctionUnit();
        if (requestFunctionUnitId.equals(connFu.getId())) {
            return true;
        }
        String connCode = connFu.getCode();
        if (connCode == null || connCode.isBlank()) {
            return false;
        }
        return functionUnitRepository.findById(requestFunctionUnitId)
                .map(FunctionUnit::getCode)
                .filter(code -> !code.isBlank() && connCode.equals(code))
                .isPresent();
    }

    private Map<String, Object> toCredentialMap(EmailConnection conn) {
        Map<String, Object> creds = new HashMap<>();
        creds.put("connectionId", conn.getId());
        creds.put("connectionType", conn.getConnectionType());
        creds.put("username", conn.getUsername());
        creds.put("fromEmail", conn.getFromEmail());
        creds.put("fromName", conn.getFromName());
        if (conn.getCredentialEncrypted() != null) {
            creds.put("password", encryptionService.decrypt(conn.getCredentialEncrypted()));
        }

        if (SystemSmtpConfigResolver.isOutboundCapable(conn.getDirection())) {
            SystemSmtpConfigResolver.SystemSmtpEndpoint endpoint =
                    systemSmtpConfigResolver.requireSystemSmtpEndpoint();
            creds.put("host", endpoint.host());
            creds.put("port", endpoint.port());
            creds.put("useTls", endpoint.useTls());
        } else {
            creds.put("host", conn.getHost());
            creds.put("port", conn.getPort());
            creds.put("useTls", conn.getUseTls());
        }
        return creds;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveFunctionUnitIdByCode(String functionUnitCode) {
        List<FunctionUnit> units = functionUnitRepository.findByCodeOrderByVersionDesc(functionUnitCode);
        if (units.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(units.get(0).getId());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> resolveFunctionUnitCodeById(String functionUnitId) {
        if (functionUnitId == null || functionUnitId.isBlank()) {
            return Optional.empty();
        }
        return functionUnitRepository.findById(functionUnitId)
                .map(FunctionUnit::getCode)
                .filter(code -> code != null && !code.isBlank());
    }
}

package com.developer.component.impl;

import com.developer.client.AdminCenterSystemImapClient;
import com.developer.client.AdminCenterSystemSmtpClient;
import com.developer.component.EmailConnectionComponent;
import com.developer.dto.EmailConnectionRequest;
import com.developer.dto.EmailConnectionResponse;
import com.developer.entity.EmailConnection;
import com.developer.entity.FunctionUnit;
import com.developer.enums.EmailConnectionDirection;
import com.developer.exception.DeveloperBusinessException;
import com.developer.exception.ResourceNotFoundException;
import com.developer.repository.EmailConnectionRepository;
import com.developer.repository.FunctionUnitRepository;
import com.developer.util.SmtpMailSender;
import com.platform.common.mail.MailDiagnostics;
import com.platform.common.security.SsrfProtection;
import com.platform.common.i18n.I18nService;
import com.platform.security.encryption.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@Slf4j
@RequiredArgsConstructor
public class EmailConnectionComponentImpl implements EmailConnectionComponent {

    private final EmailConnectionRepository emailConnectionRepository;
    private final FunctionUnitRepository functionUnitRepository;
    private final EncryptionService encryptionService;
    private final I18nService i18nService;
    private final AdminCenterSystemSmtpClient adminCenterSystemSmtpClient;
    private final AdminCenterSystemImapClient adminCenterSystemImapClient;

    /** Corporate SMTP relays / internal hosts permitted (same config as webhook SSRF allowlist). */
    @Value("${ssrf.allowed-hosts:localhost,activepieces}")
    private List<String> ssrfAllowedHosts;

    @Override
    @Transactional(readOnly = true)
    public List<EmailConnectionResponse> listByFunctionUnitId(Long functionUnitId) {
        ensureFunctionUnitExists(functionUnitId);
        return emailConnectionRepository.findByFunctionUnitIdOrderByNameAsc(functionUnitId).stream()
                .map(EmailConnectionResponse::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EmailConnectionResponse getById(Long functionUnitId, Long connectionId) {
        return EmailConnectionResponse.fromEntity(getEntity(functionUnitId, connectionId));
    }

    @Override
    @Transactional
    public EmailConnectionResponse create(Long functionUnitId, EmailConnectionRequest request) {
        FunctionUnit functionUnit = functionUnitRepository.findById(functionUnitId)
                .orElseThrow(() -> new ResourceNotFoundException("FunctionUnit", functionUnitId));

        EmailConnectionDirection direction = request.getDirection() != null
                ? request.getDirection() : EmailConnectionDirection.OUTBOUND;
        rejectBothDirection(direction);
        assertNameUniqueForDirection(functionUnitId, request.getName(), direction, null);

        if (!StringUtils.hasText(request.getUsername())) {
            if (StringUtils.hasText(request.getPassword())) {
                throw new DeveloperBusinessException("VALIDATION_USERNAME_REQUIRED",
                        i18nService.getMessage("email.connection.password_requires_username"));
            }
        } else if (!StringUtils.hasText(request.getPassword())) {
            throw new DeveloperBusinessException("VALIDATION_PASSWORD_REQUIRED",
                    i18nService.getMessage("email.connection.username_requires_password"));
        }

        String emailAddress = request.getName().trim();
        String username = StringUtils.hasText(request.getUsername()) ? request.getUsername().trim() : null;
        ResolvedSmtpEndpoint endpoint = resolveSmtpEndpoint(request, null, direction);
        ResolvedImapEndpoint imap = resolveImapEndpoint(request, null, direction);

        EmailConnection connection = EmailConnection.builder()
                .connectionUid(UUID.randomUUID().toString())
                .functionUnit(functionUnit)
                .name(emailAddress)
                .connectionType(request.getConnectionType())
                .host(endpoint.host())
                .port(endpoint.port())
                .username(username)
                .credentialEncrypted(StringUtils.hasText(request.getPassword())
                        ? encryptionService.encrypt(request.getPassword())
                        : null)
                .fromEmail(emailAddress)
                .fromName(request.getFromName())
                .useTls(endpoint.useTls())
                .enabled(request.getEnabled() != null ? request.getEnabled() : true)
                .direction(direction)
                .mailboxAddress(request.getMailboxAddress())
                .imapHost(imap.host())
                .imapPort(imap.port())
                .imapUseSsl(imap.useSsl())
                .build();

        return EmailConnectionResponse.fromEntity(emailConnectionRepository.save(connection));
    }

    @Override
    @Transactional
    public EmailConnectionResponse update(Long functionUnitId, Long connectionId, EmailConnectionRequest request) {
        EmailConnection connection = getEntity(functionUnitId, connectionId);

        EmailConnectionDirection direction = request.getDirection() != null
                ? request.getDirection() : connection.getDirection();
        if (direction == null) {
            direction = EmailConnectionDirection.OUTBOUND;
        }
        rejectBothDirection(direction);
        assertNameUniqueForDirection(functionUnitId, request.getName(), direction, connectionId);

        String emailAddress = request.getName().trim();
        String username = StringUtils.hasText(request.getUsername()) ? request.getUsername().trim() : null;
        ResolvedSmtpEndpoint endpoint = resolveSmtpEndpoint(request, connection, direction);
        ResolvedImapEndpoint imap = resolveImapEndpoint(request, connection, direction);

        connection.setName(emailAddress);
        connection.setConnectionType(request.getConnectionType());
        connection.setHost(endpoint.host());
        connection.setPort(endpoint.port());
        connection.setUsername(username);
        connection.setFromEmail(emailAddress);
        connection.setFromName(request.getFromName());
        connection.setUseTls(endpoint.useTls());
        if (request.getEnabled() != null) {
            connection.setEnabled(request.getEnabled());
        }
        if (request.getDirection() != null) {
            connection.setDirection(request.getDirection());
        }
        connection.setMailboxAddress(request.getMailboxAddress());
        connection.setImapHost(imap.host());
        connection.setImapPort(imap.port());
        connection.setImapUseSsl(imap.useSsl());
        if (!StringUtils.hasText(request.getUsername())) {
            connection.setCredentialEncrypted(null);
        } else if (StringUtils.hasText(request.getPassword())) {
            connection.setCredentialEncrypted(encryptionService.encrypt(request.getPassword()));
        } else if (!StringUtils.hasText(connection.getUsername())) {
            throw new DeveloperBusinessException("VALIDATION_PASSWORD_REQUIRED",
                    i18nService.getMessage("email.connection.username_requires_password"));
        }

        return EmailConnectionResponse.fromEntity(emailConnectionRepository.save(connection));
    }

    @Override
    @Transactional
    public void delete(Long functionUnitId, Long connectionId) {
        emailConnectionRepository.delete(getEntity(functionUnitId, connectionId));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<String, Object> testConnection(Long functionUnitId, Long connectionId, String testRecipient) {
        EmailConnection connection = getEntity(functionUnitId, connectionId);
        if (!StringUtils.hasText(testRecipient)) {
            throw new DeveloperBusinessException("VALIDATION_RECIPIENT_REQUIRED",
                    i18nService.getMessage("email.connection.test_recipient_required"));
        }

        ResolvedSmtpEndpoint endpoint = resolveRuntimeSmtpEndpoint(connection);
        log.info("[SMTP-TEST] request functionUnitId={} connectionId={} name={} host={} port={} useTls={} direction={} recipient={}",
                functionUnitId, connectionId, connection.getName(), endpoint.host(), endpoint.port(),
                endpoint.useTls(), connection.getDirection(), testRecipient);

        Map<String, Object> result = new HashMap<>();
        try {
            validateSmtpHost(endpoint.host());
            SmtpMailSender.SmtpConfig config = toSmtpConfig(connection, endpoint);
            SmtpMailSender.send(config, testRecipient, null,
                    "Workflow Platform - Connection Test",
                    "<p>This is a test email from connection <strong>" + connection.getName() + "</strong>.</p>");
            result.put("success", true);
            result.put("message", i18nService.getMessage("email.connection.test_success"));
        } catch (Exception e) {
            String causeChain = MailDiagnostics.causeChain(e);
            String rootCause = MailDiagnostics.rootCause(e);
            log.error("[SMTP-TEST] FAILED connectionId={} host={} port={} useTls={} | causeChain={} | rootCause={}",
                    connectionId, endpoint.host(), endpoint.port(), endpoint.useTls(),
                    causeChain, rootCause, e);
            result.put("success", false);
            result.put("detail", rootCause);
            result.put("message", i18nService.getMessage("email.connection.test_failed", rootCause));
            result.put("causeChain", causeChain);
        }
        return result;
    }

    private void validateSmtpHost(String host) {
        try {
            SsrfProtection.validateHostname(host, allowedSmtpHosts());
        } catch (SsrfProtection.SsrfException ex) {
            throw new DeveloperBusinessException("SSRF_SMTP_HOST_BLOCKED", ex.getMessage());
        }
    }

    private Set<String> allowedSmtpHosts() {
        if (ssrfAllowedHosts == null || ssrfAllowedHosts.isEmpty()) {
            return Set.of();
        }
        return ssrfAllowedHosts.stream()
                .filter(h -> h != null && !h.isBlank())
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }

    private ResolvedSmtpEndpoint resolveSmtpEndpoint(
            EmailConnectionRequest request,
            EmailConnection existing,
            EmailConnectionDirection direction) {
        if (isOutboundCapable(direction)) {
            return resolveSystemSmtpEndpoint();
        }
        // Inbound-only monitor connections do not use SMTP; host/port are unused at runtime.
        return new ResolvedSmtpEndpoint(null, 587, true);
    }

    private ResolvedSmtpEndpoint resolveRuntimeSmtpEndpoint(EmailConnection connection) {
        EmailConnectionDirection direction = connection.getDirection() != null
                ? connection.getDirection() : EmailConnectionDirection.OUTBOUND;
        if (!isOutboundCapable(direction)) {
            throw new DeveloperBusinessException("VALIDATION_OUTBOUND_REQUIRED_FOR_TEST",
                    i18nService.getMessage("email.connection.outbound_required_for_test"));
        }
        return resolveSystemSmtpEndpoint();
    }

    private ResolvedSmtpEndpoint resolveSystemSmtpEndpoint() {
        try {
            AdminCenterSystemSmtpClient.SystemSmtpEndpoint endpoint =
                    adminCenterSystemSmtpClient.fetchSystemSmtpEndpoint();
            validateSmtpHost(endpoint.host());
            return new ResolvedSmtpEndpoint(endpoint.host(), endpoint.port(), endpoint.useTls());
        } catch (IllegalStateException ex) {
            throw new DeveloperBusinessException(
                    "VALIDATION_SYSTEM_SMTP_REQUIRED",
                    i18nService.getMessage("email.connection.system_smtp_required",
                            ex.getMessage() != null ? ex.getMessage() : ""));
        }
    }

    private static boolean isOutboundCapable(EmailConnectionDirection direction) {
        return direction == null
                || direction == EmailConnectionDirection.OUTBOUND
                || direction == EmailConnectionDirection.BOTH;
    }

    /**
     * Resolves inbound IMAP endpoint from Admin Center system config (required for {@code INBOUND}).
     */
    private ResolvedImapEndpoint resolveImapEndpoint(EmailConnectionRequest request,
                                                     EmailConnection existing,
                                                     EmailConnectionDirection direction) {
        if (!isInboundCapable(direction)) {
            return new ResolvedImapEndpoint(null, null, null);
        }
        return resolveSystemImapEndpoint();
    }

    private void rejectBothDirection(EmailConnectionDirection direction) {
        if (direction == EmailConnectionDirection.BOTH) {
            throw new DeveloperBusinessException(
                    "VALIDATION_DIRECTION_BOTH_REMOVED",
                    i18nService.getMessage("email.connection.direction_both_removed"));
        }
    }

    private void assertNameUniqueForDirection(
            Long functionUnitId,
            String name,
            EmailConnectionDirection direction,
            Long excludeConnectionId) {
        boolean conflict = excludeConnectionId == null
                ? emailConnectionRepository.existsByFunctionUnitIdAndNameAndDirection(
                functionUnitId, name, direction)
                : emailConnectionRepository.existsByFunctionUnitIdAndNameAndDirectionAndIdNot(
                functionUnitId, name, direction, excludeConnectionId);
        if (conflict) {
            String directionLabel = i18nService.getMessage(
                    "email.connection.direction_label." + direction.name());
            throw new DeveloperBusinessException(
                    "CONFLICT_CONNECTION_NAME",
                    i18nService.getMessage("email.connection.name_conflict", directionLabel, name));
        }
    }

    /** Read path: existing BOTH rows still resolve IMAP until the designer saves a new direction. */
    private static boolean isInboundCapable(EmailConnectionDirection direction) {
        return direction == EmailConnectionDirection.INBOUND
                || direction == EmailConnectionDirection.BOTH;
    }

    private ResolvedImapEndpoint resolveSystemImapEndpoint() {
        try {
            AdminCenterSystemImapClient.SystemImapEndpoint endpoint =
                    adminCenterSystemImapClient.fetchSystemImapEndpoint();
            // Admin already SSRF-validates imap.host (including intranet FQDNs). Do not
            // re-apply SMTP allowlist here — company IMAP typically resolves to RFC1918.
            return new ResolvedImapEndpoint(endpoint.host(), endpoint.port(), endpoint.useSsl());
        } catch (IllegalStateException ex) {
            throw new DeveloperBusinessException(
                    "VALIDATION_SYSTEM_IMAP_REQUIRED",
                    i18nService.getMessage("email.connection.system_imap_required",
                            ex.getMessage() != null ? ex.getMessage() : ""));
        }
    }

    private void ensureFunctionUnitExists(Long functionUnitId) {
        if (!functionUnitRepository.existsById(functionUnitId)) {
            throw new ResourceNotFoundException("FunctionUnit", functionUnitId);
        }
    }

    private EmailConnection getEntity(Long functionUnitId, Long connectionId) {
        EmailConnection connection = emailConnectionRepository.findById(connectionId)
                .orElseThrow(() -> new ResourceNotFoundException("EmailConnection", connectionId));
        if (!connection.getFunctionUnit().getId().equals(functionUnitId)) {
            throw new ResourceNotFoundException("EmailConnection", connectionId);
        }
        return connection;
    }

    private record ResolvedSmtpEndpoint(String host, int port, boolean useTls) {}

    private record ResolvedImapEndpoint(String host, Integer port, Boolean useSsl) {}

    private SmtpMailSender.SmtpConfig toSmtpConfig(EmailConnection connection, ResolvedSmtpEndpoint endpoint) {
        String password = null;
        if (connection.getCredentialEncrypted() != null) {
            password = encryptionService.decrypt(connection.getCredentialEncrypted());
        }
        return new SmtpMailSender.SmtpConfig(
                endpoint.host(),
                endpoint.port(),
                connection.getUsername(),
                password,
                connection.getFromEmail(),
                connection.getFromName(),
                endpoint.useTls()
        );
    }
}

package com.workflow.email.inbound;

import com.platform.security.encryption.EncryptionService;
import com.workflow.client.AdminCenterSystemImapClient;
import com.workflow.email.extract.EmailMessage;
import com.workflow.email.inbound.entity.ProcessedEmailMessage;
import com.workflow.email.inbound.entity.SysEmailConnection;
import com.workflow.email.inbound.entity.SysEmailMonitorRule;
import com.workflow.email.inbound.repository.SysEmailConnectionRepository;
import com.workflow.email.inbound.repository.SysEmailMonitorRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;

/**
 * Polls each enabled email monitor rule's mailbox for new messages (Power Automate-style
 * "when a new email arrives") and dispatches them to {@link EmailMonitorProcessor}.
 *
 * <p>The mailbox provider and credentials are entirely connection-determined: IMAP host comes
 * from the connection type preset, and the app password is decrypted from the synced connection.
 * Per-rule poll cadence is throttled by {@code pollIntervalSeconds}; IMAP/config failures use
 * exponential backoff so a down mailbox is not hit on every scheduler tick. Idempotency is
 * guaranteed by the processor's ledger, and multi-replica polling is serialized by
 * {@code @SchedulerLock}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmailMonitorScheduler {

    private static final int MAX_PER_POLL = 20;

    private final SysEmailMonitorRuleRepository ruleRepository;
    private final SysEmailConnectionRepository connectionRepository;
    private final InboundMailClient imapClient;
    private final EmailMonitorProcessor processor;
    private final EncryptionService encryptionService;
    private final AdminCenterSystemImapClient adminCenterSystemImapClient;
    private final EmailMonitorPollBackoff pollBackoff = new EmailMonitorPollBackoff();

    /** Throttle "0 enabled rules" so a missing Deploy does not WARN every 30s tick. */
    private static final int EMPTY_RULES_WARN_SECONDS = 300;
    private volatile Instant lastEmptyRulesWarnAt;

    @Value("${workflow.email.monitor.enabled:true}")
    private boolean enabled;

    /**
     * 多副本下只让抢到锁的节点轮询邮箱。
     *
     * <p>重复处理本身已被 {@code we_email_processed_messages} 的 {@code UNIQUE(rule_uid, message_id)}
     * 挡住（见 {@link EmailMonitorProcessor}），所以这把锁不是为了正确性，而是为了避免
     * N 个副本对同一邮箱并发建 IMAP 连接（Gmail/Exchange 会限流甚至封连接），
     * 以及 {@code lastSyncedAt} 游标的后写覆盖。
     *
     * <p>{@code lockAtMostFor} 取 5 分钟：远大于一次轮询的正常耗时（20 封 × IMAP fetch），
     * 又能保证节点崩溃后锁最迟 5 分钟自动释放。{@code lockAtLeastFor} 取 10 秒，
     * 防止轮询很快返回时锁瞬间释放、另一副本立刻重复轮询。
     */
    @Scheduled(fixedDelayString = "${workflow.email.monitor.poll-delay-ms:30000}")
    @SchedulerLock(name = "EmailMonitor_poll", lockAtMostFor = "PT5M", lockAtLeastFor = "PT10S")
    public void poll() {
        if (!enabled) {
            return;
        }
        List<SysEmailMonitorRule> rules;
        try {
            rules = ruleRepository.findByEnabledTrue();
        } catch (Exception e) {
            log.warn("Email monitor poll skipped (rules unavailable): {}", e.getMessage());
            return;
        }
        Instant now = Instant.now();
        if (rules.isEmpty()) {
            warnNoEnabledRules(now);
            return;
        }
        for (SysEmailMonitorRule rule : rules) {
            if (pollBackoff.shouldPoll(
                    rule.getId(), now, rule.getPollIntervalSeconds(), rule.getLastSyncedAt())) {
                pollRule(rule, now);
            }
        }
    }

    private void warnNoEnabledRules(Instant now) {
        Instant previous = lastEmptyRulesWarnAt;
        if (previous != null && now.isBefore(previous.plusSeconds(EMPTY_RULES_WARN_SECONDS))) {
            return;
        }
        lastEmptyRulesWarnAt = now;
        log.warn("[EMAIL-MONITOR] poll found 0 enabled rules in sys_email_monitor_rules; "
                + "bind a monitor to a Start Event and deploy the Function Unit");
    }

    private void pollRule(SysEmailMonitorRule rule, Instant now) {
        MailboxAccess access = resolveAccess(rule);
        if (access == null) {
            onPollFailure(rule, now, "mailbox access unresolved");
            return;
        }
        FetchResult result;
        try {
            result = imapClient.fetchNew(
                    access, rule.getFolderLabel(), rule.getLastSyncCursor(), MAX_PER_POLL);
        } catch (Exception e) {
            onPollFailure(rule, now, e.getMessage());
            return;
        }
        boolean processFailed = false;
        for (EmailMessage email : result.messages()) {
            if (!processFetched(rule, email)) {
                processFailed = true;
            }
        }
        pollBackoff.recordSuccess(rule.getId());
        if (processFailed) {
            // IMAP succeeded; do not advance the UID cursor or this batch is skipped forever
            // if process() threw before the processed-message ledger write.
            markPollAttempt(rule);
            return;
        }
        persistCursor(rule, result.nextCursor());
    }

    /**
     * @return {@code false} when a matching message threw before it could be recorded
     */
    private boolean processFetched(SysEmailMonitorRule rule, EmailMessage email) {
        if (!matchesFilters(rule, email)) {
            // Filter miss is not a monitored event: no process, no log.
            // Still consume the UID so the same mail is not fetched every poll.
            return true;
        }
        try {
            String status = processor.process(rule, email);
            logProcessOutcome(rule, email, status);
            return true;
        } catch (Exception e) {
            log.error("[EMAIL-MONITOR] rule {} failed to process messageId={}",
                    rule.getId(), email.messageId(), e);
            return false;
        }
    }

    private void logProcessOutcome(SysEmailMonitorRule rule, EmailMessage email, String status) {
        if (status == null) {
            log.info("[EMAIL-MONITOR] rule {} skipped messageId={} (duplicate or missing messageId)",
                    rule.getId(), email.messageId());
            return;
        }
        if (ProcessedEmailMessage.STATUS_STARTED.equals(status)) {
            log.info("[EMAIL-MONITOR] rule {} messageId={} started process",
                    rule.getId(), email.messageId());
            return;
        }
        log.warn("[EMAIL-MONITOR] rule {} messageId={} recorded status={} (no new process instance)",
                rule.getId(), email.messageId(), status);
    }

    private void markPollAttempt(SysEmailMonitorRule rule) {
        rule.setLastSyncedAt(Instant.now());
        ruleRepository.save(rule);
    }

    private void onPollFailure(SysEmailMonitorRule rule, Instant now, String reason) {
        EmailMonitorPollBackoff.FailureState state =
                pollBackoff.recordFailure(rule.getId(), now, rule.getPollIntervalSeconds());
        log.error("[EMAIL-MONITOR] poll failed ruleId={} name={} consecutiveFailures={} retryAfter={} cap={} reason={}",
                rule.getId(),
                rule.getName(),
                state.consecutiveFailures(),
                state.retryAfter(),
                state.atCap(),
                reason);
    }

    private MailboxAccess resolveAccess(SysEmailMonitorRule rule) {
        if (!StringUtils.hasText(rule.getConnectionUid())) {
            return null;
        }
        SysEmailConnection connection = connectionRepository.findById(rule.getConnectionUid()).orElse(null);
        if (connection == null || Boolean.FALSE.equals(connection.getEnabled())) {
            log.warn("[EMAIL-MONITOR] rule {} skipped: connection {} missing/disabled",
                    rule.getId(), rule.getConnectionUid());
            return null;
        }
        if (!isInbound(connection)) {
            log.warn("[EMAIL-MONITOR] rule {} skipped: connection {} direction={} is not inbound-capable",
                    rule.getId(), rule.getConnectionUid(), connection.getDirection());
            return null;
        }

        // Global IMAP endpoint from Admin Center system config (not per-connection fields).
        AdminCenterSystemImapClient.SystemImapEndpoint systemImap;
        try {
            systemImap = adminCenterSystemImapClient.fetchSystemImapEndpoint();
        } catch (IllegalStateException ex) {
            log.warn("[EMAIL-MONITOR] rule {} skipped: system IMAP not configured: {}",
                    rule.getId(), ex.getMessage());
            return null;
        }
        String host = systemImap.host();
        int port = systemImap.port();
        boolean ssl = systemImap.useSsl();
        log.info("[EMAIL-MONITOR] rule {} using system IMAP endpoint: host={} port={} ssl={}",
                rule.getId(), host, port, ssl);

        String username = StringUtils.hasText(connection.getMailboxAddress())
                ? connection.getMailboxAddress() : connection.getUsername();
        String password = decrypt(rule.getId(), connection.getCredentialEncrypted());
        if (!StringUtils.hasText(username) || password == null) {
            log.warn("[EMAIL-MONITOR] rule {} skipped: connection {} missing IMAP credentials (username/password)",
                    rule.getId(), rule.getConnectionUid());
            return null;
        }
        return new MailboxAccess(host, port, ssl, username, password);
    }

    /**
     * Runtime still accepts legacy BOTH so already-deployed packages keep polling.
     * DW no longer lets designers create or save BOTH; new monitors must be INBOUND.
     */
    private boolean isInbound(SysEmailConnection connection) {
        String direction = connection.getDirection();
        return "INBOUND".equalsIgnoreCase(direction) || "BOTH".equalsIgnoreCase(direction);
    }

    private boolean matchesFilters(SysEmailMonitorRule rule, EmailMessage email) {
        if (StringUtils.hasText(rule.getFilterFrom())) {
            String from = email.from() != null ? email.from().toLowerCase() : "";
            if (!from.contains(rule.getFilterFrom().toLowerCase())) {
                return false;
            }
        }
        if (StringUtils.hasText(rule.getFilterSubject())) {
            String subject = email.subject() != null ? email.subject().toLowerCase() : "";
            return subject.contains(rule.getFilterSubject().toLowerCase());
        }
        return true;
    }

    private void persistCursor(SysEmailMonitorRule rule, String nextCursor) {
        rule.setLastSyncCursor(nextCursor);
        rule.setLastSyncedAt(Instant.now());
        ruleRepository.save(rule);
    }

    private String decrypt(String ruleId, String encrypted) {
        if (!StringUtils.hasText(encrypted)) {
            return null;
        }
        try {
            return encryptionService.decrypt(encrypted);
        } catch (Exception e) {
            log.warn("[EMAIL-MONITOR] rule {} skipped: mailbox password decrypt failed: {}",
                    ruleId, e.getMessage());
            return null;
        }
    }
}

package com.admin.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "sys_email_connections")
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EmailConnection {

    @Id
    @Column(length = 64)
    private String id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "function_unit_id", nullable = false)
    private FunctionUnit functionUnit;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "connection_type", nullable = false, length = 30)
    @Builder.Default
    private String connectionType = "SMTP";

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    private Integer port;

    @Column(name = "username")
    private String username;

    @JsonIgnore
    @Column(name = "credential_encrypted", columnDefinition = "TEXT")
    private String credentialEncrypted;

    @Column(name = "from_email", nullable = false)
    private String fromEmail;

    @Column(name = "from_name", length = 100)
    private String fromName;

    @Column(name = "use_tls")
    @Builder.Default
    private Boolean useTls = true;

    @Column(name = "enabled")
    @Builder.Default
    private Boolean enabled = true;

    @Column(name = "direction", length = 20)
    @Builder.Default
    private String direction = "OUTBOUND";

    @Column(name = "oauth_provider", length = 20)
    private String oauthProvider;

    @JsonIgnore
    @Column(name = "oauth_refresh_token_encrypted", columnDefinition = "TEXT")
    private String oauthRefreshTokenEncrypted;

    @JsonIgnore
    @Column(name = "oauth_access_token_encrypted", columnDefinition = "TEXT")
    private String oauthAccessTokenEncrypted;

    @Column(name = "token_expires_at")
    private Instant tokenExpiresAt;

    @Column(name = "mailbox_address")
    private String mailboxAddress;

    @Column(name = "imap_host")
    private String imapHost;

    @Column(name = "imap_port")
    private Integer imapPort;

    @Column(name = "imap_use_ssl")
    private Boolean imapUseSsl;

    @Column(name = "oauth_scopes", columnDefinition = "TEXT")
    private String oauthScopes;

    @Column(name = "synced_at")
    private Instant syncedAt;
}

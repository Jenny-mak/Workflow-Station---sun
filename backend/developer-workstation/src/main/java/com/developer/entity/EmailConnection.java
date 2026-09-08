package com.developer.entity;

import com.developer.enums.ConnectionType;
import com.developer.enums.EmailConnectionDirection;
import com.developer.enums.OAuthProvider;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "dw_email_connections")
@EntityListeners(AuditingEntityListener.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EmailConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_uid", nullable = false, unique = true, length = 64)
    private String connectionUid;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "function_unit_id", nullable = false)
    private FunctionUnit functionUnit;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_type", nullable = false, length = 30)
    @Builder.Default
    private ConnectionType connectionType = ConnectionType.SMTP;

    @Column(name = "host", nullable = false)
    private String host;

    @Column(name = "port", nullable = false)
    @Builder.Default
    private Integer port = 587;

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

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", length = 20)
    @Builder.Default
    private EmailConnectionDirection direction = EmailConnectionDirection.OUTBOUND;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", length = 20)
    private OAuthProvider oauthProvider;

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

    /** Inbound IMAP host (used when direction is INBOUND/BOTH). */
    @Column(name = "imap_host")
    private String imapHost;

    /** Inbound IMAP port (typically 993 for SSL). */
    @Column(name = "imap_port")
    private Integer imapPort;

    /** Whether inbound IMAP uses implicit SSL (imaps). */
    @Column(name = "imap_use_ssl")
    private Boolean imapUseSsl;

    @Column(name = "oauth_scopes", columnDefinition = "TEXT")
    private String oauthScopes;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}

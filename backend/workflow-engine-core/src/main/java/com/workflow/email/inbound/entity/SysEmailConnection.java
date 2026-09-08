package com.workflow.email.inbound.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Read view of {@code sys_email_connections} (synced from developer-workstation) for inbound
 * mailbox credential resolution. Only the columns needed by the monitor scheduler are mapped.
 */
@Entity
@Table(name = "sys_email_connections")
@Getter
@Setter
public class SysEmailConnection {

    /** Equals the connection UID (sync uses the UID as the primary key). */
    @Id
    @Column(name = "id", length = 64)
    private String id;

    @Column(name = "connection_type", length = 30)
    private String connectionType;

    @Column(name = "username")
    private String username;

    @Column(name = "credential_encrypted", columnDefinition = "TEXT")
    private String credentialEncrypted;

    @Column(name = "direction", length = 20)
    private String direction;

    @Column(name = "oauth_provider", length = 20)
    private String oauthProvider;

    @Column(name = "mailbox_address")
    private String mailboxAddress;

    @Column(name = "imap_host")
    private String imapHost;

    @Column(name = "imap_port")
    private Integer imapPort;

    @Column(name = "imap_use_ssl")
    private Boolean imapUseSsl;

    @Column(name = "enabled")
    private Boolean enabled;
}

package com.developer.dto;

import com.developer.entity.EmailConnection;
import com.developer.enums.ConnectionType;
import com.developer.enums.EmailConnectionDirection;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EmailConnectionResponse {

    private Long id;
    private String connectionUid;
    private String name;
    private ConnectionType connectionType;
    private String host;
    private Integer port;
    private String username;
    private String fromEmail;
    private String fromName;
    private Boolean useTls;
    private Boolean enabled;
    private boolean hasPassword;
    private EmailConnectionDirection direction;
    private String mailboxAddress;
    private String imapHost;
    private Integer imapPort;
    private Boolean imapUseSsl;

    public static EmailConnectionResponse fromEntity(EmailConnection entity) {
        return EmailConnectionResponse.builder()
                .id(entity.getId())
                .connectionUid(entity.getConnectionUid())
                .name(entity.getName())
                .connectionType(entity.getConnectionType())
                .host(entity.getHost())
                .port(entity.getPort())
                .username(entity.getUsername())
                .fromEmail(entity.getFromEmail())
                .fromName(entity.getFromName())
                .useTls(entity.getUseTls())
                .enabled(entity.getEnabled())
                .hasPassword(entity.getCredentialEncrypted() != null && !entity.getCredentialEncrypted().isBlank())
                .direction(entity.getDirection())
                .mailboxAddress(entity.getMailboxAddress())
                .imapHost(entity.getImapHost())
                .imapPort(entity.getImapPort())
                .imapUseSsl(entity.getImapUseSsl())
                .build();
    }
}

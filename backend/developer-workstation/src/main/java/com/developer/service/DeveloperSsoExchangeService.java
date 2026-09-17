package com.developer.service;

import com.developer.dto.LoginResponse;
import com.developer.entity.User;
import com.platform.security.config.JwtProperties;
import com.platform.security.dto.UserEffectiveRole;
import com.platform.security.service.UserRoleService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import com.developer.security.LegacyVirtualGroupRoleLookup;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DEV-only: issues a JWT for the designer after SSO exchange, matching password login.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeveloperSsoExchangeService {

    private final LegacyVirtualGroupRoleLookup legacyVirtualGroupRoleLookup;
    private final UserRoleService userRoleService;
    private final JwtProperties jwtProperties;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @Value("${jwt.expiration:2592000000}")
    private long jwtExpiration;

    public LoginResponse issueSession(User user, HttpServletResponse response) {
        List<UserEffectiveRole> effectiveRoles = userRoleService.getEffectiveRolesForUser(user.getId().toString());
        List<String> roles = effectiveRoles.stream()
                .map(UserEffectiveRole::getRoleCode)
                .distinct()
                .collect(Collectors.toList());
        if (roles.isEmpty()) {
            roles = legacyVirtualGroupRoleLookup.findRoleCodes(user.getId());
        }
        List<String> permissions = getPermissionsForRoles(roles);
        List<LoginResponse.RoleWithSource> rolesWithSources = buildRolesWithSources(effectiveRoles);
        String accessToken = generateToken(user, roles, permissions);
        String refreshToken = generateRefreshToken(user.getId());
        
        // Set httpOnly cookies for access token and refresh token
        // Service-specific cookie name (e.g. dw_access_token) so three apps on same origin do not overwrite each other. See JwtProperties#cookieNames.
        Cookie accessTokenCookie = new Cookie(jwtProperties.getPrimaryCookieName(), accessToken);
        accessTokenCookie.setHttpOnly(true);
        accessTokenCookie.setSecure(false);
        accessTokenCookie.setPath("/");
        accessTokenCookie.setMaxAge((int)(jwtExpiration / 1000));
        accessTokenCookie.setAttribute("SameSite", "Lax");
        response.addCookie(accessTokenCookie);

        Cookie refreshTokenCookie = new Cookie(jwtProperties.getRefreshCookieName(), refreshToken);
        refreshTokenCookie.setHttpOnly(true);
        refreshTokenCookie.setSecure(false);
        refreshTokenCookie.setPath("/");
        refreshTokenCookie.setMaxAge(7 * 24 * 60 * 60);
        refreshTokenCookie.setAttribute("SameSite", "Lax");
        response.addCookie(refreshTokenCookie);
        
        return LoginResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .expiresIn(jwtExpiration / 1000)
                .user(LoginResponse.UserLoginInfo.builder()
                        .userId(user.getId())
                        .username(user.getUsername())
                        .displayName(user.getFullName() != null && !user.getFullName().isEmpty()
                                ? user.getFullName()
                                : (user.getDisplayName() != null && !user.getDisplayName().isEmpty()
                                ? user.getDisplayName()
                                : user.getUsername()))
                        .email(user.getEmail())
                        .roles(roles)
                        .permissions(permissions)
                        .rolesWithSources(rolesWithSources)
                        .language(user.getLanguage())
                        .hasAvatar(user.getAvatar() != null && user.getAvatar().length > 0)
                        .build())
                .build();
    }

    private List<LoginResponse.RoleWithSource> buildRolesWithSources(List<UserEffectiveRole> effectiveRoles) {
        List<LoginResponse.RoleWithSource> result = new ArrayList<>();
        for (UserEffectiveRole role : effectiveRoles) {
            for (var source : role.getSources()) {
                result.add(LoginResponse.RoleWithSource.builder()
                        .roleCode(role.getRoleCode())
                        .roleName(role.getRoleName())
                        .sourceType(source.getSourceType())
                        .sourceId(source.getSourceId())
                        .sourceName(source.getSourceName())
                        .build());
            }
        }
        return result;
    }

    private String generateToken(User user, List<String> roles, List<String> permissions) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + jwtExpiration);
        return Jwts.builder()
                .subject(user.getId().toString())
                .claim("username", user.getUsername())
                // platform-security 的 UserPrincipal 从此 claim 取显示名；缺失时下游
                // (如 AP per-user provisioning 的影子用户)只能落 username
                .claim("displayName", resolveDisplayName(user))
                .claim("roles", roles)
                .claim("permissions", permissions)
                .claim("language", user.getLanguage())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    private static String resolveDisplayName(User user) {
        if (user.getFullName() != null && !user.getFullName().isEmpty()) {
            return user.getFullName();
        }
        if (user.getDisplayName() != null && !user.getDisplayName().isEmpty()) {
            return user.getDisplayName();
        }
        return user.getUsername();
    }

    private String generateRefreshToken(String userId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + 604800000);
        return Jwts.builder()
                .subject(userId)
                .claim("type", "refresh")
                .issuedAt(now)
                .expiration(expiry)
                .signWith(getSigningKey())
                .compact();
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            keyBytes = Arrays.copyOf(keyBytes, 32);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    private List<String> getPermissionsForRoles(List<String> roles) {
        List<String> permissions = userRoleService.getPermissionsForRoleCodes(roles);
        if (permissions.isEmpty()) {
            return List.of("basic:access");
        }
        return permissions;
    }
}

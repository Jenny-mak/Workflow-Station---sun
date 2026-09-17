package com.developer.controller;

import com.developer.dto.ChangePasswordRequest;
import com.developer.dto.LoginRequest;
import com.developer.dto.LoginResponse;
import com.developer.entity.User;
import com.developer.repository.UserRepository;
import com.developer.security.LegacyVirtualGroupRoleLookup;
import com.platform.security.config.JwtProperties;
import com.platform.security.dto.UserEffectiveRole;
import com.platform.security.service.JwtTokenService;
import com.platform.security.service.UserRoleService;
import com.platform.common.i18n.I18nService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LegacyVirtualGroupRoleLookup legacyVirtualGroupRoleLookup;
    private final UserRoleService userRoleService;
    private final I18nService i18nService;
    private final JwtTokenService jwtTokenService;
    private final JwtProperties jwtProperties;
    
    @Value("${jwt.secret}")
    private String jwtSecret;
    
    @Value("${jwt.expiration:2592000000}")
    private long jwtExpiration;

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest, HttpServletResponse response) {
        String ipAddress = getClientIpAddress(httpRequest);
        log.debug("Login attempt for user: {} from {}", request.getUsername(), ipAddress);
        
        try {
            User user = userRepository.findByUsername(request.getUsername())
                    .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.invalid_credentials")));
            
            if (user.isLocked()) {
                throw new RuntimeException(i18nService.getMessage("auth.account_locked"));
            }
            
            if ("DISABLED".equals(user.getStatus())) {
                throw new RuntimeException(i18nService.getMessage("auth.account_disabled"));
            }
            
            if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
                user.incrementFailedLoginCount();
                if (user.getFailedLoginCount() >= 5) {
                    user.setStatus("LOCKED");
                    user.setLockedUntil(LocalDateTime.now().plusMinutes(30));
                }
                userRepository.save(user);
                throw new RuntimeException(i18nService.getMessage("auth.invalid_credentials"));
            }
            
            user.resetFailedLoginCount();
            user.setLastLoginAt(LocalDateTime.now());
            user.setLastLoginIp(ipAddress);
            userRepository.save(user);
            
            // Resolve effective roles via UserRoleService
            List<UserEffectiveRole> effectiveRoles = userRoleService.getEffectiveRolesForUser(user.getId().toString());
            List<String> roles = effectiveRoles.stream()
                    .map(UserEffectiveRole::getRoleCode)
                    .distinct()
                    .collect(Collectors.toList());
            
            // Fall back to legacy role lookup when the new system returns none
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
            
            log.info("User {} logged in successfully", request.getUsername());
            
            return ResponseEntity.ok(LoginResponse.builder()
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
                    .build());
        } catch (RuntimeException e) {
            log.warn("Login failed: {}", e.getMessage());
            return ResponseEntity.badRequest().body(LoginResponse.builder().build());
        }
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        String token = resolveAccessToken(authHeader, request);
        if (token != null) {
            jwtTokenService.blacklistToken(token);
        }
        return ResponseEntity.ok().build();
    }
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refresh(
            @RequestBody(required = false) Map<String, String> request,
            HttpServletRequest httpRequest,
            HttpServletResponse response) {
        String refreshToken = request != null ? request.get("refreshToken") : null;
        if (refreshToken == null || refreshToken.isBlank()) {
            refreshToken = resolveRefreshToken(httpRequest);
        }
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        try {
            if (jwtTokenService.isBlacklisted(refreshToken)) {
                return ResponseEntity.status(401).build();
            }
            Claims claims = parseToken(refreshToken);
            if (claims.getExpiration().before(new Date())) {
                return ResponseEntity.status(401).build();
            }
            jwtTokenService.blacklistToken(refreshToken);
            String userId = claims.getSubject();
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException("User not found"));
            
            List<UserEffectiveRole> effectiveRoles = userRoleService.getEffectiveRolesForUser(userId);
            List<String> roles = effectiveRoles.stream()
                    .map(UserEffectiveRole::getRoleCode).distinct().collect(java.util.stream.Collectors.toList());
            if (roles.isEmpty()) { roles = legacyVirtualGroupRoleLookup.findRoleCodes(user.getId()); }
            List<String> permissions = getPermissionsForRoles(roles);
            
            String newAccessToken = generateToken(user, roles, permissions);
            String newRefreshToken = generateRefreshToken(user.getId());
            
            setAuthCookie(response, jwtProperties.getPrimaryCookieName(), newAccessToken, (int)(jwtExpiration / 1000));
            setAuthCookie(response, jwtProperties.getRefreshCookieName(), newRefreshToken, 7 * 24 * 60 * 60);
            
            return ResponseEntity.ok(Map.of(
                    "accessToken", newAccessToken,
                    "refreshToken", newRefreshToken,
                    "expiresIn", jwtExpiration / 1000
            ));
        } catch (Exception e) {
            return ResponseEntity.status(401).build();
        }
    }

    @PostMapping("/change-password")
    @Transactional
    public ResponseEntity<Void> changePassword(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request,
            @Valid @RequestBody ChangePasswordRequest body) {
        String token = resolveAccessToken(authHeader, request);
        if (token == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            Claims claims = parseToken(token);
            String userId = claims.getSubject();
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.user_not_found")));
            if (!passwordEncoder.matches(body.getOldPassword(), user.getPasswordHash())) {
                return ResponseEntity.status(400).build();
            }
            user.setPasswordHash(passwordEncoder.encode(body.getNewPassword()));
            userRepository.save(user);
            jwtTokenService.blacklistToken(token);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.status(401).build();
        }
    }
    @GetMapping("/me/avatar")
    public ResponseEntity<byte[]> getAvatar(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        String token = resolveAccessToken(authHeader, request);
        if (token == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            Claims claims = parseToken(token);
            String userId = claims.getSubject();
            User user = userRepository.findById(userId).orElse(null);
            if (user == null || user.getAvatar() == null || user.getAvatar().length == 0) {
                return ResponseEntity.notFound().build();
            }
            return ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.IMAGE_JPEG)
                    .cacheControl(org.springframework.http.CacheControl.maxAge(1, java.util.concurrent.TimeUnit.HOURS).cachePrivate())
                    .body(user.getAvatar());
        } catch (Exception e) {
            log.debug("Avatar resolve failed: {}", e.getMessage());
            return ResponseEntity.status(401).build();
        }
    }

    @GetMapping("/me")
    public ResponseEntity<LoginResponse.UserLoginInfo> getCurrentUser(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        String token = resolveAccessToken(authHeader, request);
        if (token == null) {
            return ResponseEntity.status(401).build();
        }
        try {
            Claims claims = parseToken(token);
            String userId = claims.getSubject();
            
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new RuntimeException(i18nService.getMessage("auth.user_not_found")));
            
            List<UserEffectiveRole> effectiveRoles = userRoleService.getEffectiveRolesForUser(userId);
            List<String> roles = effectiveRoles.stream()
                    .map(UserEffectiveRole::getRoleCode)
                    .distinct()
                    .collect(Collectors.toList());
            
            if (roles.isEmpty()) {
                roles = legacyVirtualGroupRoleLookup.findRoleCodes(user.getId());
            }
            
            List<LoginResponse.RoleWithSource> rolesWithSources = buildRolesWithSources(effectiveRoles);
            
            return ResponseEntity.ok(LoginResponse.UserLoginInfo.builder()
                    .userId(user.getId())
                    .username(user.getUsername())
                    .displayName(user.getFullName() != null && !user.getFullName().isEmpty() 
                        ? user.getFullName() 
                        : (user.getDisplayName() != null && !user.getDisplayName().isEmpty() 
                            ? user.getDisplayName() 
                            : user.getUsername()))
                    .email(user.getEmail())
                    .roles(roles)
                    .permissions(getPermissionsForRoles(roles))
                    .rolesWithSources(rolesWithSources)
                    .language(user.getLanguage())
                    .hasAvatar(user.getAvatar() != null && user.getAvatar().length > 0)
                    .build());
        } catch (Exception e) {
            return ResponseEntity.status(401).build();
        }
    }
    @GetMapping("/validate")
    public ResponseEntity<Boolean> validateToken(
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            HttpServletRequest request) {
        String token = resolveAccessToken(authHeader, request);
        if (token == null) {
            return ResponseEntity.ok(false);
        }
        try {
            parseToken(token);
            return ResponseEntity.ok(true);
        } catch (Exception e) {
            return ResponseEntity.ok(false);
        }
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

    private Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private String resolveAccessToken(String authHeader, HttpServletRequest request) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        return resolveCookie(request, jwtProperties.getCookieNames());
    }
    private String resolveRefreshToken(HttpServletRequest request) {
        return resolveCookie(request, List.of(jwtProperties.getRefreshCookieName()));
    }
    private String resolveCookie(HttpServletRequest request, List<String> names) {
        Cookie[] cookies = request != null ? request.getCookies() : null;
        if (cookies == null || names == null || names.isEmpty()) {
            return null;
        }
        for (String name : names) {
            for (Cookie cookie : cookies) {
                if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
    private void setAuthCookie(HttpServletResponse response, String name, String value, int maxAge) {
        Cookie cookie = new Cookie(name, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(false);
        cookie.setPath("/");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Lax");
        response.addCookie(cookie);
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

    private String getClientIpAddress(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

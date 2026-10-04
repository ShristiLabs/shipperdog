package com.shipperdog.api.identity;

import com.shipperdog.api.identity.internal.AuthException;
import com.shipperdog.api.identity.internal.AuthenticatedPrincipal;
import com.shipperdog.api.identity.internal.entity.PersonalAccessTokenEntity;
import com.shipperdog.api.identity.internal.entity.UserEntity;
import com.shipperdog.api.identity.internal.repository.UserRepository;
import com.shipperdog.api.identity.internal.service.AuthService;
import com.shipperdog.api.identity.internal.service.PasswordResetService;
import com.shipperdog.api.identity.internal.service.RegistrationService;
import com.shipperdog.api.identity.internal.service.TokenService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * First-party authentication API (issue #3): invitation-only registration,
 * email/password login with rotating refresh tokens, password reset, and
 * personal access tokens for CLI/CI. Stateless by design; the security
 * rules live in {@link IdentitySecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final RegistrationService registration;
    private final PasswordResetService passwordReset;
    private final TokenService tokens;
    private final UserRepository users;

    AuthController(
            AuthService auth,
            RegistrationService registration,
            PasswordResetService passwordReset,
            TokenService tokens,
            UserRepository users) {
        this.auth = auth;
        this.registration = registration;
        this.passwordReset = passwordReset;
        this.tokens = tokens;
        this.users = users;
    }

    @PostMapping("/login")
    TokenPairResponse login(@RequestBody LoginRequest request) {
        TokenService.IssuedTokens issued = auth.login(request.email(), request.password());
        return new TokenPairResponse(issued.accessToken(), issued.refreshToken(), "Bearer", issued.expiresInSeconds());
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    UserResponse register(@RequestBody RegisterRequest request) {
        UserEntity user = registration.register(
                request.invitationToken(), request.email(), request.password(), request.fullName());
        return UserResponse.from(user);
    }

    @PostMapping("/refresh")
    TokenPairResponse refresh(@RequestBody RefreshRequest request) {
        TokenService.IssuedTokens issued = tokens.refresh(request.refreshToken());
        return new TokenPairResponse(issued.accessToken(), issued.refreshToken(), "Bearer", issued.expiresInSeconds());
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestBody LogoutRequest request) {
        auth.logout(request.refreshToken());
    }

    @GetMapping("/me")
    MeResponse me(@AuthenticationPrincipal Object principal) {
        UUID userId;
        UUID orgId = null;
        UUID tenantId = null;
        String via;
        if (principal instanceof Jwt jwt) {
            userId = UUID.fromString(jwt.getSubject());
            orgId = parseClaim(jwt, TokenService.CLAIM_ORG);
            tenantId = parseClaim(jwt, TokenService.CLAIM_TENANT);
            via = "jwt";
        } else if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            userId = patPrincipal.userId();
            via = "pat";
        } else {
            throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "no authenticated principal");
        }
        UserEntity user = users.findById(userId)
                .orElseThrow(() -> new AuthException(AuthException.Code.INVALID_CREDENTIALS, "user no longer exists"));
        return new MeResponse(
                user.getId(),
                user.getEmail(),
                user.getFullName(),
                user.getStatus().name(),
                orgId,
                tenantId,
                via);
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    InvitationResponse createInvitation(
            @AuthenticationPrincipal Object principal, @RequestBody CreateInvitationRequest request) {
        RegistrationService.Invitation invitation =
                registration.createInvitation(requireUserId(principal), request.email());
        return new InvitationResponse(invitation.email(), invitation.rawToken(), invitation.expiresAt());
    }

    @PostMapping("/password-reset/request")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void requestPasswordReset(@RequestBody PasswordResetRequest request) {
        passwordReset.requestReset(request.email());
    }

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void confirmPasswordReset(@RequestBody PasswordResetConfirmRequest request) {
        passwordReset.confirmReset(request.token(), request.newPassword());
    }

    @PostMapping("/tokens")
    @ResponseStatus(HttpStatus.CREATED)
    CreatedPatResponse createToken(@AuthenticationPrincipal Object principal, @RequestBody CreatePatRequest request) {
        if (request.name() == null
                || request.name().isBlank()
                || request.name().trim().length() > 100) {
            throw new AuthException(AuthException.Code.INVALID_REQUEST, "name is required (max 100 characters)");
        }
        Duration expiresIn = null;
        if (request.expiresInSeconds() != null) {
            if (request.expiresInSeconds() <= 0) {
                throw new AuthException(
                        AuthException.Code.INVALID_REQUEST, "expiresInSeconds must be positive when provided");
            }
            expiresIn = Duration.ofSeconds(request.expiresInSeconds());
        }
        TokenService.CreatedPat created = tokens.createPat(requireUserId(principal), request.name(), expiresIn);
        return new CreatedPatResponse(
                created.id(), created.name(), created.tokenPrefix(), created.rawToken(), created.expiresAt());
    }

    @GetMapping("/tokens")
    List<PatResponse> listTokens(@AuthenticationPrincipal Object principal) {
        return tokens.listPats(requireUserId(principal)).stream()
                .map(PatResponse::from)
                .toList();
    }

    @DeleteMapping("/tokens/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    ResponseEntity<Void> revokeToken(@AuthenticationPrincipal Object principal, @PathVariable UUID id) {
        tokens.revokePat(requireUserId(principal), id);
        return ResponseEntity.noContent().build();
    }

    private static UUID requireUserId(Object principal) {
        if (principal instanceof Jwt jwt) {
            return UUID.fromString(jwt.getSubject());
        }
        if (principal instanceof AuthenticatedPrincipal patPrincipal) {
            return patPrincipal.userId();
        }
        throw new AuthException(AuthException.Code.INVALID_CREDENTIALS, "no authenticated principal");
    }

    private static UUID parseClaim(Jwt jwt, String claim) {
        String value = jwt.getClaimAsString(claim);
        return value == null ? null : UUID.fromString(value);
    }

    private record LoginRequest(String email, String password) {}

    private record RegisterRequest(String invitationToken, String email, String password, String fullName) {}

    private record RefreshRequest(String refreshToken) {}

    private record LogoutRequest(String refreshToken) {}

    private record PasswordResetRequest(String email) {}

    private record PasswordResetConfirmRequest(String token, String newPassword) {}

    public record CreateInvitationRequest(String email) {}

    public record CreatePatRequest(String name, Long expiresInSeconds) {}

    /** Login/refresh response carrying both tokens (issue #3). */
    public record TokenPairResponse(String accessToken, String refreshToken, String tokenType, long expiresInSeconds) {}

    /** Registration result; credentials are never echoed. */
    public record UserResponse(UUID id, String email, String fullName, String status) {

        static UserResponse from(UserEntity user) {
            return new UserResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getFullName(),
                    user.getStatus().name());
        }
    }

    /** Current-actor view; org/tenant appear once membership exists. */
    public record MeResponse(
            UUID id,
            String email,
            String fullName,
            String status,
            UUID orgId,
            UUID tenantId,
            String authenticatedVia) {}

    /** Created invitation; the token is shown exactly once. */
    public record InvitationResponse(String email, String invitationToken, Instant expiresAt) {}

    /** PAT metadata for listings; the secret never appears here. */
    public record PatResponse(
            UUID id,
            String name,
            String tokenPrefix,
            Instant createdAt,
            Instant lastUsedAt,
            Instant expiresAt,
            Instant revokedAt) {

        static PatResponse from(PersonalAccessTokenEntity pat) {
            return new PatResponse(
                    pat.getId(),
                    pat.getName(),
                    pat.getTokenPrefix(),
                    pat.getCreatedAt(),
                    pat.getLastUsedAt(),
                    pat.getExpiresAt(),
                    pat.getRevokedAt());
        }
    }

    /** PAT creation result; {@code token} is shown exactly once. */
    public record CreatedPatResponse(UUID id, String name, String tokenPrefix, String token, Instant expiresAt) {}
}

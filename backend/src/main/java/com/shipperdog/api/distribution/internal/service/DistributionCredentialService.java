package com.shipperdog.api.distribution.internal.service;

import com.shipperdog.api.application.ApplicationAccess;
import com.shipperdog.api.audit.AuditEvent;
import com.shipperdog.api.audit.AuditEventType;
import com.shipperdog.api.audit.AuditLogger;
import com.shipperdog.api.distribution.DistributionException;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.entity.StoreAccountEntity;
import com.shipperdog.api.distribution.internal.repository.StoreAccountRepository;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.environment.EnvironmentAccess;
import com.shipperdog.api.organization.OrganizationAccess;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * The credential-delegation chain's core (issue #61, S13-01, AC2/AC3,
 * PRD 25): store accounts bound at the organization level, inherited
 * downward, overridable at the application and environment levels --
 * resolution walks most-specific-first and the first defined row
 * wins, so Production credentials differ from QA exactly because they
 * are different rows under different environments.
 *
 * <p><strong>The never-material doctrine (AC3):</strong> binding
 * accepts material ONCE, forwards it to the selected {@link
 * ChannelSecretStore}, and keeps only the returned reference (the V27
 * environment_credentials rule, now the chain's shape); the row, the
 * audit event, and every API answer carry the reference, never the
 * material. Rotation re-points the reference in place (the slot
 * doctrine: one account per provider per level, a replacement never a
 * merge -- the unique constraint is the authority behind the
 * first-defined-wins resolution).
 *
 * <p>Organization-grain configuration (the environments/rings
 * standing): explicit organization validation, no isolation seam --
 * an organization the caller cannot see conceals exactly like an
 * unknown one through the permission check.
 */
@Service
public class DistributionCredentialService {

    /** The provider vocabulary's shape: lowercase kebab, 1-32 chars (an open vocabulary, but not an open anything). */
    private static final Pattern PROVIDER_SHAPE = Pattern.compile("^[a-z][a-z0-9-]{0,31}$");

    private final StoreAccountRepository accounts;
    private final ChannelSecretStore secrets;
    private final OrganizationAccess organizations;
    private final ApplicationAccess applications;
    private final EnvironmentAccess environments;
    private final AuditLogger audit;
    private final Clock clock;

    public DistributionCredentialService(
            StoreAccountRepository accounts,
            ChannelSecretStore secrets,
            OrganizationAccess organizations,
            ApplicationAccess applications,
            EnvironmentAccess environments,
            AuditLogger audit,
            Clock clock) {
        this.accounts = accounts;
        this.secrets = secrets;
        this.organizations = organizations;
        this.applications = applications;
        this.environments = environments;
        this.audit = audit;
        this.clock = clock;
    }

    /** The binding write (create or rotation): one account per provider per level, material stored once. */
    @Transactional
    public StoreAccountView bind(BindingCommand command, UUID actorId) {
        if (!organizations.organizationExists(command.organizationId())) {
            throw new DistributionException(DistributionException.Code.ORGANIZATION_NOT_FOUND, "unknown organization");
        }
        if (command.provider() == null
                || !PROVIDER_SHAPE.matcher(command.provider()).matches()) {
            throw new DistributionException(
                    DistributionException.Code.INVALID_REQUEST,
                    "provider must be a lowercase kebab id of at most 32 characters");
        }
        if (command.name() == null || command.name().isBlank()) {
            throw new DistributionException(DistributionException.Code.INVALID_REQUEST, "name is required");
        }
        if (command.material() == null || !command.material().present()) {
            throw new DistributionException(DistributionException.Code.INVALID_REQUEST, "secret material is required");
        }
        if (command.environmentId() != null && command.applicationId() == null) {
            throw new DistributionException(
                    DistributionException.Code.DISTRIBUTION_CREDENTIAL_LEVEL_INVALID,
                    "an environment-level binding must name its application");
        }
        if (command.applicationId() != null) {
            var ownership = applications
                    .ownershipOf(command.applicationId())
                    .orElseThrow(() -> new DistributionException(
                            DistributionException.Code.APPLICATION_NOT_FOUND, "unknown application"));
            if (!command.organizationId().equals(ownership.organizationId())) {
                throw new DistributionException(
                        DistributionException.Code.APPLICATION_NOT_FOUND, "unknown application");
            }
        }
        if (command.environmentId() != null) {
            var state = environments
                    .stateOf(command.environmentId())
                    .orElseThrow(() -> new DistributionException(
                            DistributionException.Code.ENVIRONMENT_NOT_FOUND, "unknown environment"));
            if (!command.organizationId().equals(state.organizationId())) {
                throw new DistributionException(
                        DistributionException.Code.ENVIRONMENT_NOT_FOUND, "unknown environment");
            }
        }

        String reference = secrets.store(command.organizationId(), command.provider(), command.material());
        String metadata = command.metadata() == null ? "{}" : command.metadata().toString();
        StoreAccountEntity bound = accounts.findByOrganizationIdAndApplicationIdAndEnvironmentIdAndProvider(
                        command.organizationId(), command.applicationId(), command.environmentId(), command.provider())
                .map(existing -> {
                    String replaced = existing.getSecretReference();
                    existing.rotate(command.name(), metadata, reference);
                    StoreAccountEntity saved = accounts.save(existing);
                    // Rotation hygiene: the replaced material's stored row
                    // goes when its reference does (the store owns its
                    // namespace; the GCP posture keeps one reference per
                    // slot and answers nothing to delete).
                    if (!replaced.equals(reference)) {
                        secrets.delete(replaced);
                    }
                    return saved;
                })
                .orElseGet(() -> accounts.save(new StoreAccountEntity(
                        command.organizationId(),
                        command.applicationId(),
                        command.environmentId(),
                        command.provider(),
                        command.name(),
                        metadata,
                        reference)));

        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_CREDENTIAL_BOUND)
                .userId(actorId)
                .orgId(command.organizationId())
                .detail(Map.of(
                        "credentialId", bound.getId().toString(),
                        "provider", bound.getProvider(),
                        "level", bound.level().name().toLowerCase(),
                        "secretReference", bound.getSecretReference()))
                .success(true)
                .build());
        return viewOf(bound);
    }

    /** The organization's every binding, provider-major for the stable chain view. */
    @Transactional(readOnly = true)
    public List<StoreAccountView> list(UUID organizationId) {
        return accounts.findByOrganizationIdOrderByProviderAsc(organizationId).stream()
                .map(this::viewOf)
                .toList();
    }

    /** Deletes one binding (the chain falls back to the next level's row, or none). */
    @Transactional
    public void delete(UUID credentialId, UUID organizationId, UUID actorId) {
        StoreAccountEntity existing = accounts.findByIdAndOrganizationId(credentialId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND, "unknown credential"));
        accounts.delete(existing);
        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_CREDENTIAL_DELETED)
                .userId(actorId)
                .orgId(organizationId)
                .detail(Map.of(
                        "credentialId", existing.getId().toString(),
                        "provider", existing.getProvider(),
                        "level", existing.level().name().toLowerCase()))
                .success(true)
                .build());
    }

    /**
     * The resolution probe (the environment module's verify doctrine):
     * answers whether the binding's reference RESOLVES through the
     * selected store -- never what it resolves to.
     */
    @Transactional(readOnly = true)
    public boolean verify(UUID credentialId, UUID organizationId, UUID actorId) {
        StoreAccountEntity existing = accounts.findByIdAndOrganizationId(credentialId, organizationId)
                .orElseThrow(() -> new DistributionException(
                        DistributionException.Code.DISTRIBUTION_CREDENTIAL_NOT_FOUND, "unknown credential"));
        boolean resolved = secrets.resolve(existing.getSecretReference()).isPresent();
        audit.record(AuditEvent.builder(AuditEventType.DISTRIBUTION_CREDENTIAL_VERIFIED)
                .userId(actorId)
                .orgId(organizationId)
                .detail(Map.of(
                        "credentialId", existing.getId().toString(),
                        "provider", existing.getProvider(),
                        "resolved", String.valueOf(resolved)))
                .success(true)
                .build());
        return resolved;
    }

    /**
     * The delegation chain's effective view (AC2's tested matrix): for
     * every provider the organization binds anywhere, the row that
     * wins at the addressed scope (environment beats application beats
     * organization, first-defined-wins) and the level it won at.
     */
    @Transactional(readOnly = true)
    public List<EffectiveBinding> effectiveChain(UUID organizationId, UUID applicationId, UUID environmentId) {
        TreeSet<String> providers = new TreeSet<>();
        for (StoreAccountEntity account : accounts.findByOrganizationIdOrderByProviderAsc(organizationId)) {
            providers.add(account.getProvider());
        }
        List<EffectiveBinding> chain = new ArrayList<>();
        for (String provider : providers) {
            chain.add(new EffectiveBinding(
                    provider,
                    resolve(organizationId, applicationId, environmentId, provider)
                            .map(this::viewOf)
                            .orElse(null)));
        }
        return chain;
    }

    /**
     * The chain's resolution (PRD 25: first-defined-wins walking
     * environment, application, organization): dispatch's credential
     * answer, reference-only until the dispatch resolves material.
     */
    @Transactional(readOnly = true)
    public Optional<StoreAccountEntity> resolve(
            UUID organizationId, UUID applicationId, UUID environmentId, String provider) {
        if (environmentId != null) {
            Optional<StoreAccountEntity> atEnvironment =
                    accounts.findByOrganizationIdAndApplicationIdAndEnvironmentIdAndProvider(
                            organizationId, applicationId, environmentId, provider);
            if (atEnvironment.isPresent()) {
                return atEnvironment;
            }
        }
        if (applicationId != null) {
            Optional<StoreAccountEntity> atApplication =
                    accounts.findByOrganizationIdAndApplicationIdAndEnvironmentIdAndProvider(
                            organizationId, applicationId, null, provider);
            if (atApplication.isPresent()) {
                return atApplication;
            }
        }
        return accounts.findByOrganizationIdAndApplicationIdAndEnvironmentIdAndProvider(
                organizationId, null, null, provider);
    }

    private StoreAccountView viewOf(StoreAccountEntity entity) {
        return new StoreAccountView(
                entity.getId(),
                entity.level().name().toLowerCase(),
                entity.getOrganizationId(),
                entity.getApplicationId(),
                entity.getEnvironmentId(),
                entity.getProvider(),
                entity.getName(),
                entity.getMetadata(),
                entity.getSecretReference(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    /** The binding write's command: the level's parents, the provider, the label, the material, and non-secret metadata. */
    public record BindingCommand(
            UUID organizationId,
            UUID applicationId,
            UUID environmentId,
            String provider,
            String name,
            JsonNode metadata,
            SecretMaterial material) {}

    /** The binding's served view: everything EXCEPT material (which no surface ever sees). */
    public record StoreAccountView(
            UUID id,
            String level,
            UUID organizationId,
            UUID applicationId,
            UUID environmentId,
            String provider,
            String name,
            String metadata,
            String secretReference,
            java.time.Instant createdAt,
            java.time.Instant updatedAt) {}

    /** One provider's chain answer: the winning row (null when the scope binds none) -- AC2's matrix row. */
    public record EffectiveBinding(String provider, StoreAccountView binding) {}
}

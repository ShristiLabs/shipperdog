package com.shipperdog.api.distribution.internal.apple;

import com.shipperdog.api.distribution.DistributionCommand;
import com.shipperdog.api.distribution.SecretMaterial;
import com.shipperdog.api.distribution.internal.secret.ChannelSecretStore;
import com.shipperdog.api.distribution.internal.service.AdapterCallSandbox;
import com.shipperdog.api.distribution.internal.service.DistributionCredentialService;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The staged rollout's TestFlight arms (issue #66, S13-06, AC3/AC4):
 * the provider calls a staged stage's widening and the orchestrated
 * rollback's revert drive — the build assigned to the newly reached
 * groups, the build removed from the reached groups — run under the
 * same sandbox every ASC conversation runs under, with the credential
 * chain resolved at the release's scope exactly as the dispatch
 * resolves it. The arms live HERE (beside the client and the
 * credential shape they speak) and answer a plain outcome vocabulary
 * the staged engine records on its timeline; an arm's failure is the
 * outcome, never a throw — the stage's platform state is not the
 * store's to veto.
 */
@Component
public class TestFlightRolloutArms {

    private static final String CHANNEL = "testflight";

    private final AppStoreConnectClient asc;
    private final DistributionCredentialService credentials;
    private final ChannelSecretStore secrets;
    private final AdapterCallSandbox sandbox;

    public TestFlightRolloutArms(
            AppStoreConnectClient asc,
            DistributionCredentialService credentials,
            ChannelSecretStore secrets,
            AdapterCallSandbox sandbox) {
        this.asc = asc;
        this.credentials = credentials;
        this.secrets = secrets;
        this.sandbox = sandbox;
    }

    /**
     * The advance arm: the release's build(s) assigned to the mapped
     * groups of the stage's newly reached tenants — the dispatch's own
     * provider call, driven by the plan's widening.
     *
     * @return assigned:N, credential-unresolved, no-builds, no-groups,
     *         or arm-failed:{@code CODE}
     */
    @Transactional
    public String assignBuildToGroups(
            UUID organizationId,
            UUID applicationId,
            UUID environmentId,
            Collection<String> buildExternalIds,
            Collection<String> groupExternalIds) {
        if (buildExternalIds.isEmpty()) {
            return "no-builds";
        }
        if (groupExternalIds.isEmpty()) {
            return "no-groups";
        }
        AppleCredentials credential = credentialOf(organizationId, applicationId, environmentId);
        if (credential == null) {
            return "credential-unresolved";
        }
        int applied = 0;
        for (String buildId : buildExternalIds) {
            for (String groupId : groupExternalIds) {
                AdapterCallSandbox.Result<String> assigned = sandbox.run(CHANNEL, "staged-assign", attempt -> {
                    asc.assignBuildToGroup(credential, groupId, buildId);
                    return "assigned";
                });
                if (assigned.failure() != null) {
                    return "arm-failed:" + AdapterCallSandbox.taxonomyOf(assigned.failure());
                }
                applied++;
            }
        }
        return "assigned:" + applied;
    }

    /**
     * The rollback arm (AC4): the release's build(s) REMOVED from the
     * mapped groups of the reached cohort — the assignments the
     * rollout made, reverted (idempotent on ASC's side).
     *
     * @return reverted:N, credential-unresolved, no-builds, no-groups,
     *         or arm-failed:{@code CODE}
     */
    @Transactional
    public String removeBuildFromGroups(
            UUID organizationId,
            UUID applicationId,
            UUID environmentId,
            Collection<String> buildExternalIds,
            Collection<String> groupExternalIds) {
        if (buildExternalIds.isEmpty()) {
            return "no-builds";
        }
        if (groupExternalIds.isEmpty()) {
            return "no-groups";
        }
        AppleCredentials credential = credentialOf(organizationId, applicationId, environmentId);
        if (credential == null) {
            return "credential-unresolved";
        }
        int reverted = 0;
        for (String buildId : buildExternalIds) {
            for (String groupId : groupExternalIds) {
                AdapterCallSandbox.Result<String> removed = sandbox.run(CHANNEL, "staged-revert", attempt -> {
                    asc.removeBuildFromGroup(credential, groupId, buildId);
                    return "reverted";
                });
                if (removed.failure() != null) {
                    return "arm-failed:" + AdapterCallSandbox.taxonomyOf(removed.failure());
                }
                reverted++;
            }
        }
        return "reverted:" + reverted;
    }

    /** The apple credential chain resolved at the release's scope; null when unresolved or incomplete. */
    private AppleCredentials credentialOf(UUID organizationId, UUID applicationId, UUID environmentId) {
        return credentials
                .resolve(organizationId, applicationId, environmentId, "apple")
                .map(account -> {
                    SecretMaterial material =
                            secrets.resolve(account.getSecretReference()).orElse(new SecretMaterial(""));
                    return AppleCredentials.of(new DistributionCommand.ResolvedCredential(
                            account.getProvider(),
                            account.getName(),
                            account.getSecretReference(),
                            material,
                            account.getMetadata()));
                })
                .filter(AppleCredentials::complete)
                .orElse(null);
    }
}

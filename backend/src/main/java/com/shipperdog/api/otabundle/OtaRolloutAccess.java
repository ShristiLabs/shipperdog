package com.shipperdog.api.otabundle;

import java.util.Optional;
import java.util.UUID;

/**
 * The rollout-resolution seam (S16-06, issue #168): the standing the
 * S16-07 device-check route composes with {@link OtaDeviceBucketing}
 * to answer, for one device, whether the release's rollout serves it
 * -- one composable check, exactly the {@code OtaBundleAccess} note's
 * doctrine: this port answers the plan's facts, the caller owns the
 * policy ordering (application standing first, then release, then
 * THIS standing, then the bucket arithmetic).
 *
 * <p>The composition, spelled out for S16-07: a HALTED or PAUSED
 * standing yields the no-update 204 BEFORE any bucketing runs; a
 * RUNNING/COMPLETE standing serves the device when
 * {@code OtaDeviceBucketing.servesDevice(deviceId, planSalt,
 * currentBasisPoints)} -- and a release with no plan at all is the
 * caller's own judgment (documented there, not smuggled into facts).
 *
 * <p><strong>Tuples, never rows</strong> (the {@code ReleaseAccess}
 * discipline): everything crosses as the public record below. The
 * read takes the application beside the release because the plan rows
 * are ORG-grain (V56 carries no tenant column) -- the application
 * resolution provides the owning organization the seam binds, and the
 * port refuses (empty) any release that is not the named
 * application's own or not yet carrying its plan.
 */
public interface OtaRolloutAccess {

    /**
     * The release's rollout standing: the plan's status, the serving
     * stage's CUMULATIVE basis-point threshold, and the plan's salt
     * (public-by-design, D6). Empty when the release is unknown, not
     * the named application's, or carries no plan.
     */
    Optional<RolloutStanding> rolloutStandingOf(UUID applicationId, UUID releaseId);

    /**
     * One plan's device-facing facts. {@code status} is the stable
     * string vocabulary (RUNNING/HALTED/PAUSED/COMPLETE); {@code
     * currentBasisPoints} is the serving stage's cumulative threshold
     * (the final stage's 10000, a COMPLETE plan's full space).
     */
    record RolloutStanding(
            UUID releaseId, String status, Integer currentStage, int currentBasisPoints, String planSalt) {}
}

package com.shipperdog.api.gate.internal.repository;

import com.shipperdog.api.gate.internal.entity.ReleaseGateResultEntity;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Storage for the append-only gate result history (V31, issue #42,
 * S10-03). Rows are never mutated and never deleted (PRD P5); the
 * current state mirrors the latest row per (release, gate) in
 * {@code release_gates}. A pure child of the seam-served release (the
 * V25 doctrine).
 */
public interface ReleaseGateResultRepository extends JpaRepository<ReleaseGateResultEntity, UUID> {

    /** One gate's full history, newest first (the audit view behind "latest wins"). */
    List<ReleaseGateResultEntity> findByReleaseIdAndGateTypeOrderByRecordedAtDescIdDesc(
            UUID releaseId, String gateType);

    /** The release's complete history, newest first (the dashboard's evidence trail). */
    List<ReleaseGateResultEntity> findByReleaseIdOrderByRecordedAtDescIdDesc(UUID releaseId);
}

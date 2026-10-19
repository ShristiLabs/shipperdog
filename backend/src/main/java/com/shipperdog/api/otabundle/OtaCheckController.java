package com.shipperdog.api.otabundle;

import com.shipperdog.api.otabundle.internal.service.OtaCheckService;
import com.shipperdog.api.otabundle.internal.service.OtaCheckService.CheckQuery;
import com.shipperdog.api.otabundle.internal.service.OtaCheckService.Manifest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The device check API (S16-07, issue #169, ADR-0010 workstream 4 —
 * the OTA milestone's keystone device-facing endpoint): {@code GET
 * /api/v1/ota/check}, authenticated by the shipped app's {@code
 * sdc1.} app token (the {@code OtaAppTokenAuthenticationFilter}'s one
 * authority, honored here for the first time). The answer is the OTA
 * manifest of the update the device should take — its download URL
 * and {@code sdt1.} ingest grant minted at check time (D4/D5) — or
 * 204 No Content for every no-update standing: up-to-date, not
 * eligible, a halted or paused rollout, a release without its plan, a
 * stage below the device's bucket, no live release on the channel.
 * The refusals are indistinguishable on the wire; the funnel's split
 * lives in the server-side meters (D9).
 *
 * <p><strong>Anonymous at the chain, authenticated by the
 * filter</strong> — the download-proxy doctrine: the route is
 * {@code permitAll} in the security chain (the {@code sdc1.} prefix
 * is the credential, and the filter's registry lookup is the entire
 * authority), and the handler carries method security's own
 * {@code hasAuthority} so the posture is visible at the route. A
 * presented but invalid, tampered, or revoked {@code sdc1.} answers
 * the FILTER's uniform 401 — never a reason; a request with no
 * credential (or a console credential) answers the method-security
 * denial's guaranteed 403 envelope — the platform's uniform shape for
 * every {@code @PreAuthorize} refusal, carrying no hint of what
 * exists either way. The route sits on the isolation registry's
 * EXCLUDED list (the unauthenticated device surface, mirroring {@code
 * /api/v1/downloads/{token}}).
 */
@RestController
@RequestMapping("/api/v1/ota")
public class OtaCheckController {

    private final OtaCheckService checks;

    OtaCheckController(OtaCheckService checks) {
        this.checks = checks;
    }

    /**
     * Resolves one device's update standing. The query carries the
     * device's own identifier (the rollout bucketing's raw input),
     * its platform tag and channel (the ring vocabulary), the app
     * version it runs, and the optional bundle version label it
     * already carries (a first check has none). Honors the
     * correlation-id convention like every route.
     */
    @GetMapping("/check")
    @PreAuthorize("hasAuthority('" + OtaAppTokenAuthenticationFilter.CHECK_AUTHORITY + "')")
    ResponseEntity<Manifest> check(
            @AuthenticationPrincipal Object principal,
            @RequestParam("deviceId") String deviceId,
            @RequestParam("platform") String platform,
            @RequestParam("appVersion") String appVersion,
            @RequestParam(value = "bundleVersion", required = false) String bundleVersion,
            @RequestParam("channel") String channel) {
        if (!(principal instanceof OtaAppTokenPrincipal appToken)) {
            // Unreachable behind the authority guard; kept so the
            // route's own standing never depends on the filter's.
            return ResponseEntity.status(401).build();
        }
        CheckQuery query = OtaCheckService.validate(deviceId, platform, appVersion, bundleVersion, channel);
        return checks.check(appToken, query)
                .<ResponseEntity<Manifest>>map(manifest -> ResponseEntity.ok(manifest))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}

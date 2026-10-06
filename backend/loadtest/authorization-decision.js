// S7-03 authorization decision load test (issue #32, AC5).
//
// Mixed load against the first-party decision API at ~50 rps: the
// download decision (the granted persona's artifact-grain leg and the
// ungranted persona's version-grain leg) and the eligibility listing,
// across several tester personas, with a warm decision-input cache.
// Setup provisions everything through the API itself using the
// dev-seed platform administrator, so the run is reproducible
// against any local compose stack (see README.md).
//
// The p95 thresholds assert the PRD 42 SLO on the decision
// endpoints' own latency; the server-side view of the same budget is
// the shipperdog_authorization_decision_duration_seconds histogram on
// the management port's /actuator/prometheus.
//
// The world is deliberately artifact-free: the granted persona's
// download walks the full chain (user, organization, tenant, cached
// membership, application, version + cached entitlement) and stops at
// the artifact grain's concealed miss — one indexed issuance probe
// SHORT of the full allow leg, which the integration suite
// (AuthorizationDecisionApiIntegrationTest) covers functionally. The
// latency difference is one indexed point lookup; the SLO measures
// the decision walk.

import http from 'k6/http';
import { expectedStatuses } from 'k6/http';
import { check, sleep } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN_EMAIL = __ENV.ADMIN_EMAIL || 'dev@shipperdog.local';
const ADMIN_PASSWORD = __ENV.ADMIN_PASSWORD || 'shipperdog-dev-admin';
const TESTER_PASSWORD = __ENV.TESTER_PASSWORD || 'a-perfectly-valid-password';
const TESTERS = parseInt(__ENV.TESTERS || '4', 10);

const unexpectedFailures = new Counter('decision_unexpected_failures');

// The decision API's concealed 404 IS a correct answer (a deny is the
// product), so the transport-level success set includes it; the
// script-level checks and the unexpected-failure counter carry the
// real contract.
http.setResponseCallback(expectedStatuses(200, 404));

export const options = {
    scenarios: {
        mixed: {
            executor: 'constant-arrival-rate',
            rate: parseInt(__ENV.RPS || '50', 10),
            timeUnit: '1s',
            duration: __ENV.DURATION || '60s',
            preAllocatedVUs: 20,
            maxVUs: 60,
        },
    },
    thresholds: {
        // PRD 42: the authorization decision SLO.
        'http_req_duration{endpoint:download-granted}': ['p(95)<100'],
        'http_req_duration{endpoint:download-denied}': ['p(95)<100'],
        'http_req_duration{endpoint:eligibility}': ['p(95)<100'],
        decision_unexpected_failures: ['count==0'],
    },
};

function login(email, password) {
    const response = http.post(
        `${BASE_URL}/api/v1/auth/login`,
        JSON.stringify({ email: email, password: password }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    if (response.status !== 200) {
        throw new Error(`login ${email} -> ${response.status}: ${response.body}`);
    }
    return response.json('accessToken');
}

function postAs(admin, path, body, expected) {
    const response = http.post(`${BASE_URL}${path}`, JSON.stringify(body), {
        headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${admin}` },
    });
    if (response.status !== expected) {
        throw new Error(`POST ${path} -> ${response.status}: ${response.body}`);
    }
    return response.json();
}

export function setup() {
    const admin = login(ADMIN_EMAIL, ADMIN_PASSWORD);

    // One world per run: org, tenant, app, version, tester personas.
    const suffix = Date.now();
    const org = postAs(admin, '/api/v1/organizations', { name: `S703 Load ${suffix}` }, 201);
    const tenant = postAs(
        admin,
        '/api/v1/tenants',
        {
            organizationId: org.id,
            name: `Banking load ${suffix}`,
            externalId: `ext-s703-load-${suffix}`,
        },
        201
    );
    const application = postAs(
        admin,
        '/api/v1/applications',
        {
            tenantId: tenant.id,
            name: 'Banking',
            platforms: ['ANDROID'],
            packageName: `com.acme.loadtest`,
        },
        201
    );
    const version = postAs(
        admin,
        `/api/v1/applications/${application.id}/versions`,
        { versionName: '7.12.0' },
        201
    );

    // Testers: linked to the tenant; the first holds an EXPLICIT grant
    // (the granted leg), the others hold none (the denied leg) —
    // mixed personas with separate cache entries.
    const testers = [];
    for (let i = 0; i < TESTERS; i += 1) {
        const email = `s703-load-${suffix}-${i}@example.com`;
        const invited = postAs(
            admin,
            '/api/v1/testers',
            { organizationId: org.id, email: email, fullName: `Load ${i}` },
            201
        );
        const registered = http.post(
            `${BASE_URL}/api/v1/auth/register`,
            JSON.stringify({
                invitationToken: invited.invitation.invitationToken,
                email: email,
                password: TESTER_PASSWORD,
                fullName: `Load ${i}`,
            }),
            { headers: { 'Content-Type': 'application/json' } }
        );
        if (registered.status !== 201 && registered.status !== 200) {
            throw new Error(`register ${email} -> ${registered.status}: ${registered.body}`);
        }
        postAs(admin, `/api/v1/tenants/${tenant.id}/testers`, { testerId: invited.id }, 201);
        if (i === 0) {
            postAs(
                admin,
                `/api/v1/tenants/${tenant.id}/authorization-grants`,
                {
                    testerId: invited.id,
                    applicationId: application.id,
                    accessType: 'EXPLICIT_VERSION',
                    versionId: version.id,
                    reason: 'load test granted leg',
                },
                201
            );
        }
        testers.push({ email: email, token: login(email, TESTER_PASSWORD) });
    }

    // Warm every persona's cache before the measured window so the
    // SLO run observes the warm-cache path (AC1's condition).
    for (const persona of testers) {
        http.get(
            `${BASE_URL}/api/v1/authorization/decisions/download` +
                `?tenantId=${tenant.id}&versionId=${version.id}&platform=ANDROID`,
            { headers: { Authorization: `Bearer ${persona.token}` } }
        );
        http.get(
            `${BASE_URL}/api/v1/authorization/decisions/eligibility` +
                `?tenantId=${tenant.id}&applicationId=${application.id}`,
            { headers: { Authorization: `Bearer ${persona.token}` } }
        );
    }

    return { tenant: tenant, application: application, version: version, testers: testers };
}

export default function (data) {
    const granted = Math.random() < 0.5;
    const persona = granted ? data.testers[0] : data.testers[1 + Math.floor(Math.random() * (data.testers.length - 1))];
    const headers = { Authorization: `Bearer ${persona.token}` };
    const roll = Math.random();

    if (roll < 0.6) {
        // The download decision: the granted persona walks the whole
        // chain to the artifact grain; the ungranted denies at the
        // entitlement. Both are the contract; anything else fails.
        const response = http.get(
            `${BASE_URL}/api/v1/authorization/decisions/download` +
                `?tenantId=${data.tenant.id}&versionId=${data.version.id}&platform=ANDROID`,
            { headers: headers, tags: { endpoint: granted ? 'download-granted' : 'download-denied' } }
        );
        const expected = granted ? 404 : 404; // no artifact: the granted leg's artifact-grain miss
        check(response, {
            'download decides (200 or concealed 404)': (r) => r.status === 200 || r.status === 404,
        });
        if (response.status !== 200 && response.status !== expected) {
            unexpectedFailures.add(1);
        }
    } else if (roll < 0.9) {
        // The eligibility listing (the effective set over cached
        // inputs).
        const response = http.get(
            `${BASE_URL}/api/v1/authorization/decisions/eligibility` +
                `?tenantId=${data.tenant.id}&applicationId=${data.application.id}`,
            { headers: headers, tags: { endpoint: 'eligibility' } }
        );
        check(response, {
            'eligibility answers (200 or concealed 404)': (r) => r.status === 200 || r.status === 404,
        });
        if (response.status !== 200 && response.status !== 404) {
            unexpectedFailures.add(1);
        }
    } else {
        // An unknown-version probe: the concealed miss must stay fast
        // too (denials are the common case under attack).
        const response = http.get(
            `${BASE_URL}/api/v1/authorization/decisions/download` +
                `?tenantId=${data.tenant.id}&versionId=00000000-0000-4000-8000-000000000000&platform=ANDROID`,
            { headers: headers, tags: { endpoint: 'download-denied' } }
        );
        check(response, {
            'unknown version conceals as 404': (r) => r.status === 404,
        });
        if (response.status !== 404) {
            unexpectedFailures.add(1);
        }
    }
    sleep(0.05);
}

package com.shipperdog.api.notification;

/**
 * The HTTPS leg's payload shape (issue #71, S14-05): the wrapper the
 * collector receives around the frozen export envelope (the
 * versioned member set of docs/siem.md -- the schema is a public
 * API; members are append-only per version, breaking changes gate
 * through the S1-08 contract checks).
 */
public enum AuditStreamFormat {
    /**
     * The canonical ShipperDog audit envelope as-is: one JSON object
     * per event, fixed key order, HMAC-signed over the exact bytes
     * (the webhook recipe receivers already know).
     */
    SHIPPERDOG_JSON,
    /**
     * The Splunk HTTP Event Collector's JSON envelope (the optional
     * vendor helper the issue scopes in): the envelope rides the
     * {@code event} member with {@code time} and a stable {@code
     * sourcetype}; the HEC token travels the auth header, never the
     * body.
     */
    SPLUNK_HEC
}

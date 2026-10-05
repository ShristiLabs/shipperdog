package com.shipperdog.api.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a POST handler as honoring the {@code Idempotency-Key} header
 * (issue #8): a request carrying the header is executed once and replayed
 * from the stored response on retries within the retention window.
 * Requests without the header are unaffected — idempotency is opt-in per
 * request, and only handlers whose side effects make replay valuable
 * (resource creation, and from S5-01 on, artifact upload) carry this
 * marker.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {}

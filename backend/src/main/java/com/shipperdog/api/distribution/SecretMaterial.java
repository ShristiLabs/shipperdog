package com.shipperdog.api.distribution;

/**
 * Secret material in flight (issue #61, S13-01, PRD 25): the one
 * wrapper credential material moves behind, from the binding write to
 * the adapter's provider call. {@code toString()} answers the constant
 * redaction marker -- a record's or a map's accidental {@code
 * toString()} leaks nothing -- and there is no accessor that hands
 * back a loggable form. Material exists in JVM memory for the length
 * of one use and is never persisted, audited, or echoed by any
 * surface.
 *
 * <p>Deliberately NOT a record: records synthesize an {@code
 * equals}/{@code hashCode}/{code toString} over components; this class
 * overrides the lot so the value's shape can never regress into a
 * leak.
 */
public final class SecretMaterial {

    /** What any log line, dump, or error message sees instead of the material. */
    public static final String REDACTED = "<redacted>";

    private final char[] value;

    /** Wraps material for one flight. */
    public SecretMaterial(String value) {
        this.value = value == null ? new char[0] : value.toCharArray();
    }

    /** The material, for the one call that consumes it. */
    public String value() {
        return new String(value);
    }

    /** Whether any material is present (an empty binding resolves nothing). */
    public boolean present() {
        return value.length > 0;
    }

    @Override
    public String toString() {
        return REDACTED;
    }

    @Override
    public boolean equals(Object other) {
        return this == other;
    }

    @Override
    public int hashCode() {
        return SecretMaterial.class.hashCode();
    }
}

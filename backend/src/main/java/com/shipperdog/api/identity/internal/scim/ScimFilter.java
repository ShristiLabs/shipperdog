package com.shipperdog.api.identity.internal.scim;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The SCIM filter subset this server answers (S14-03, RFC 7644
 * section 3.4.2): the two attribute comparisons IdP provisioning
 * clients actually send — {@code userName eq "value"} and {@code
 * externalId eq "value"} — with single or double quoted values.
 * Everything else (other attributes, other operators, and/or
 * composition, grouping, caseIgnore, unquoted or trailing junk) is
 * rejected LOUDLY with 400 invalidFilter per the RFC's own guidance
 * for unsupported filters, so a mis-wired connector fails visibly
 * instead of silently matching everything.
 */
public final class ScimFilter {

    /** The whole-expression grammar of the supported subset. */
    private static final Pattern COMPARISON = Pattern.compile("^([\\w.]+)\\s+eq\\s+(?:\"([^\"]*)\"|'([^']*)')$");

    /** The filterable attribute. */
    public enum Attribute {
        USER_NAME,
        EXTERNAL_ID
    }

    private final Attribute attribute;

    private final String value;

    private ScimFilter(Attribute attribute, String value) {
        this.attribute = attribute;
        this.value = value;
    }

    public Attribute attribute() {
        return attribute;
    }

    public String value() {
        return value;
    }

    /**
     * Parses a filter expression. Null/blank means no filter (the
     * unfiltered listing); anything the subset does not cover is an
     * invalidFilter refusal.
     */
    public static ScimFilter parse(String filter) {
        if (filter == null || filter.isBlank()) {
            return null;
        }
        Matcher matcher = COMPARISON.matcher(filter.trim());
        if (!matcher.matches()) {
            throw ScimException.invalidFilter(
                    "unsupported filter: only 'userName eq \"value\"' and 'externalId eq \"value\"' are supported"
                            + " (a single quoted string, nothing else)");
        }
        String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        if (value.isEmpty()) {
            throw ScimException.invalidFilter("the comparison value must be a non-empty quoted string");
        }
        return new ScimFilter(attributeOf(matcher.group(1)), value);
    }

    private static Attribute attributeOf(String attribute) {
        return switch (attribute.toLowerCase(Locale.ROOT)) {
            case "username" -> Attribute.USER_NAME;
            case "externalid" -> Attribute.EXTERNAL_ID;
            default ->
                throw ScimException.invalidFilter("unsupported filter attribute '" + attribute
                        + "': only userName and externalId are filterable");
        };
    }
}

package com.pricingsim.validation;

/** Validation categories (PLAN.md §10). Every category except BUSINESS_RULE is structural: a violation is a FAIL. */
public enum CheckCategory {
    PRIMARY_KEY("PK"),
    FOREIGN_KEY("FK"),
    ENUM("EN"),
    ID_FORMAT("ID"),
    DATE_ORDER("DT"),
    NO_LOOK_AHEAD("LA"),
    RECONCILIATION("RC"),
    LIFECYCLE("LC"),
    DUPLICATE("DU"),
    COVERAGE("CV"),
    BUSINESS_RULE("BR");

    private final String prefix;

    CheckCategory(String prefix) {
        this.prefix = prefix;
    }

    public String prefix() {
        return prefix;
    }

    public boolean structural() {
        return this != BUSINESS_RULE;
    }
}

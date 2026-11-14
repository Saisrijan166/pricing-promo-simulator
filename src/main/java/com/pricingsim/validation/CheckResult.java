package com.pricingsim.validation;

import java.util.List;

/**
 * The outcome of one check.
 *
 * @param status     PASS, FAIL (structural violation) or WARN (business-rule findings, reconciled by evaluate)
 * @param sampleIds  up to 5 violating IDs, sorted
 */
public record CheckResult(String checkId, CheckCategory category, String table, String description, long rowsChecked,
                          long violations, Status status, List<String> sampleIds) {

    public enum Status {
        PASS, FAIL, WARN
    }

    public CheckResult {
        sampleIds = List.copyOf(sampleIds);
    }
}

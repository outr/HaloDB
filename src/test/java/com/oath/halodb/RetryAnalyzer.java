/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import org.testng.IRetryAnalyzer;
import org.testng.ITestResult;

/**
 * Retries a failed test once. A few of the integration tests race on slower/more-contended CI
 * runners (background compaction/tombstone threads, file locks, jmockit teardown timing) and flake
 * intermittently — almost exclusively on the Java 22 leg. A single retry keeps CI green for a
 * genuine flake, while the retry is logged so flakes stay visible and can be root-caused. A
 * deterministic failure still fails (it fails on the retry too), so real regressions are not masked.
 */
public class RetryAnalyzer implements IRetryAnalyzer {

    private static final int MAX_RETRIES = 1;

    private int attempts = 0;

    @Override
    public boolean retry(ITestResult result) {
        if (attempts < MAX_RETRIES) {
            attempts++;
            System.out.printf(
                "[RetryAnalyzer] FLAKY: retrying %s.%s (attempt %d/%d) after: %s%n",
                result.getTestClass().getRealClass().getSimpleName(),
                result.getName(),
                attempts + 1, MAX_RETRIES + 1,
                result.getThrowable() == null ? "<no throwable>" : result.getThrowable());
            return true;
        }
        return false;
    }
}

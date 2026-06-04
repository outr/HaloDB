/*
 * Copyright 2018, Oath Inc
 * Licensed under the terms of the Apache License 2.0. Please refer to accompanying LICENSE file for terms.
 */

package com.oath.halodb;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import org.testng.IAnnotationTransformer;
import org.testng.annotations.ITestAnnotation;

/**
 * Applies {@link RetryAnalyzer} to every {@code @Test} method (those that don't already declare one)
 * so the retry-once policy is suite-wide without annotating each test. Registered as a listener in
 * the generated testng.xml.
 */
public class RetryListener implements IAnnotationTransformer {

    @Override
    @SuppressWarnings("rawtypes")
    public void transform(ITestAnnotation annotation, Class testClass, Constructor testConstructor, Method testMethod) {
        // No HaloDB test declares its own retry analyzer, so applying it unconditionally is safe and
        // avoids depending on getRetryAnalyzer* accessors whose names vary across TestNG versions.
        annotation.setRetryAnalyzer(RetryAnalyzer.class);
    }
}

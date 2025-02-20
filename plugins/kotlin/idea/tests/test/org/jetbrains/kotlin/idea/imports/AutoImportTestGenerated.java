// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.

package org.jetbrains.kotlin.idea.imports;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.test.TestRoot;
import org.junit.runner.RunWith;

/**
 * This class is generated by {@link org.jetbrains.kotlin.testGenerator.generator.TestGenerator}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("idea/tests")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
public abstract class AutoImportTestGenerated extends AbstractAutoImportTest {
    @RunWith(JUnit3RunnerWithInners.class)
    @TestMetadata("testData/editor/autoImport")
    public static class WithAutoImport extends AbstractAutoImportTest {
        private void runTest(String testDataFilePath) throws Exception {
            KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
        }

        @TestMetadata("javaClass")
        public void testJavaClass() throws Exception {
            runTest("testData/editor/autoImport/javaClass/");
        }

        @TestMetadata("method")
        public void testMethod() throws Exception {
            runTest("testData/editor/autoImport/method/");
        }

        @TestMetadata("property")
        public void testProperty() throws Exception {
            runTest("testData/editor/autoImport/property/");
        }
    }

    @RunWith(JUnit3RunnerWithInners.class)
    @TestMetadata("testData/editor/autoImport")
    public static class WithoutAutoImport extends AbstractAutoImportTest {
        private void runTest(String testDataFilePath) throws Exception {
            KotlinTestUtils.runTest(this::doTestWithoutAutoImport, this, testDataFilePath);
        }

        @TestMetadata("javaClass")
        public void testJavaClass() throws Exception {
            runTest("testData/editor/autoImport/javaClass/");
        }

        @TestMetadata("method")
        public void testMethod() throws Exception {
            runTest("testData/editor/autoImport/method/");
        }

        @TestMetadata("property")
        public void testProperty() throws Exception {
            runTest("testData/editor/autoImport/property/");
        }
    }
}

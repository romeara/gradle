/*
 * Copyright 2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.testing.jacoco.plugins

import org.gradle.integtests.fixtures.AbstractTaskRelocationIntegrationTest
import org.gradle.util.Requires

import static org.gradle.util.TestPrecondition.FIX_TO_WORK_ON_JAVA9

@Requires(FIX_TO_WORK_ON_JAVA9)
class JacocoReportRelocationIntegrationTest extends AbstractTaskRelocationIntegrationTest {
    @Override
    protected String getTaskName() {
        return ":jacocoTestReport"
    }

    @Override
    protected void setupProjectInOriginalLocation() {
        file("src/main/java/org/gradle/Class1.java") <<
            "package org.gradle; public class Class1 { public boolean isFoo(Object arg) { return true; } }"
        file("src/test/java/org/gradle/Class1Test.java") <<
            "package org.gradle; import org.junit.Test; public class Class1Test { @Test public void someTest() { new Class1().isFoo(\"test\"); } }"

        buildFile << """
            apply plugin: "java"
            apply plugin: "jacoco"

            repositories {
                mavenCentral()
            }
            dependencies {
                testCompile 'junit:junit:4.12'
            }

            sourceSets.test.output.classesDir = file("build/classes/test")
        """

        succeeds "test"
    }

    @Override
    protected void moveFilesAround() {
        buildFile << """
            sourceSets.test.output.classesDir = file("build/test-classes")
            jacocoTestReport.executionData = files("build/jacoco.exec")
        """
        file("build/classes/test").assertIsDir().renameTo(file("build/test-classes"))
        file("build/jacoco/test.exec").assertIsFile().renameTo(file("build/jacoco.exec"))
    }

    @Override
    protected void removeResults() {
        file("build/reports/jacoco").assertIsDir().deleteDir()
    }

    @Override
    protected extractResults() {
        file("build/reports/jacoco/test/html/index.html").text
    }
}

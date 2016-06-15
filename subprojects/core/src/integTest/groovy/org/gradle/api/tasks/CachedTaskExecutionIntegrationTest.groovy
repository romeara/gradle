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

package org.gradle.api.tasks

import org.gradle.integtests.fixtures.AbstractIntegrationSpec

import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

class CachedTaskExecutionIntegrationTest extends AbstractIntegrationSpec {
    public static final String ORIGINAL_HELLO_WORLD = """
            public class Hello {
                public static void main(String... args) {
                    System.out.println("Hello World!");
                }
            }
        """
    public static final String CHANGED_HELLO_WORLD = """
            public class Hello {
                public static void main(String... args) {
                    System.out.println("Hello World with Changes!");
                }
            }
        """
    def cacheDir = testDirectoryProvider.createDir("task-cache")

    def setup() {
        buildFile << """
            apply plugin: "java"
        """

        file("src/main/java/Hello.java") << ORIGINAL_HELLO_WORLD
        file("src/main/resources/resource.properties") << """
            test=true
        """
    }

    def "no task is re-executed when inputs are unchanged"() {
        expect:
        succeedsWithCache "assemble"
        skippedTasks.empty

        succeedsWithCache "clean"

        succeedsWithCache "assemble"
        nonSkippedTasks.empty
    }

    def "tasks get cached when source code changes without changing the compiled output"() {
        expect:
        succeedsWithCache "assemble"
        skippedTasks.empty

        file("src/main/java/Hello.java") << """
            // Change to source file without compiled result change
        """
        succeedsWithCache "clean"

        succeedsWithCache "assemble"
        nonSkippedTasks.containsAll ":compileJava"
        skippedTasks.containsAll ":processResources", ":jar"
    }

    def "tasks get cached when source code changes back to previous state"() {
        expect:
        succeedsWithCache "compileJava" assertTaskNotSkipped ":compileJava"

        println "\n\n\n-----------------------------------------\n\n\n"

        when:
        file("src/main/java/Hello.java").text = CHANGED_HELLO_WORLD
        then:
        succeedsWithCache "compileJava" assertTaskNotSkipped ":compileJava"

        println "\n\n\n-----------------------------------------\n\n\n"

        when:
        file("src/main/java/Hello.java").text = ORIGINAL_HELLO_WORLD
        then:
        succeedsWithCache "compileJava" assertTaskSkipped ":compileJava"
    }

    def "jar tasks get cached even when output file is changed"() {
        file("settings.gradle") << "rootProject.name = 'test'"
        buildFile << """
            if (file("toggle.txt").exists()) {
                jar {
                    destinationDir = file("\$buildDir/other-jar")
                    baseName = "other-jar"
                }
            }
        """

        expect:
        succeedsWithCache "assemble"
        skippedTasks.empty
        file("build/libs/test.jar").isFile()

        succeedsWithCache "clean"
        !file("build/libs/test.jar").isFile()

        file("toggle.txt").touch()

        succeedsWithCache "assemble"
        skippedTasks.contains ":jar"
        !file("build/libs/test.jar").isFile()
        file("build/other-jar/other-jar.jar").isFile()
    }

    def "nothing gets cached when build script changes"() {
        file("settings.gradle") << "rootProject.name = 'test'"

        expect:
        succeedsWithCache "assemble"
        skippedTasks.empty
        file("build/libs/test.jar").isFile()

        succeedsWithCache "clean"
        !file("build/libs/test.jar").isFile()

        buildFile << """
            // Some comment
        """

        succeedsWithCache "assemble"
        skippedTasks.empty
        file("build/libs/test.jar").isFile()
    }

    def "nothing gets cached when a build script dependency changes"() {
        file("settings.gradle") << "rootProject.name = 'test'"

        buildFile << """
            buildscript {
                dependencies {
                    classpath files("lib.jar")
                }
            }
        """

        when:
        file("lib.jar").bytes = jarWithContents(["test.txt": "original contents"])
        succeedsWithCache "assemble"

        then:
        skippedTasks.empty
        file("build/libs/test.jar").isFile()

        when:
        succeedsWithCache "clean"

        then:
        !file("build/libs/test.jar").isFile()

        when:
        // File system may not see change without this wait
        Thread.sleep(1000L);
        file("lib.jar").bytes = jarWithContents(["test.txt": "modified contents"])

        succeedsWithCache "assemble"

        then:
        skippedTasks.empty
        file("build/libs/test.jar").isFile()
    }

    def jarWithContents(Map<String, String> contents) {
        def out = new ByteArrayOutputStream()
        def jarOut = new JarOutputStream(out)
        try {
            contents.each { file, fileContents ->
                def zipEntry = new ZipEntry(file)
                zipEntry.setTime(0)
                jarOut.putNextEntry(zipEntry)
                jarOut << fileContents
            }
        } finally {
            jarOut.close()
        }
        return out.toByteArray()
    }

    def "clean doesn't get cached"() {
        expect:
        succeedsWithCache "assemble"
        succeedsWithCache "clean"
        succeedsWithCache "assemble"
        succeedsWithCache "clean"
        nonSkippedTasks.contains ":clean"
    }

    def "task with cache disabled doesn't get cached"() {
        buildFile << """
            compileJava.outputs.cacheIf { false }
        """

        expect:
        succeedsWithCache "assemble"
        succeedsWithCache "clean"
        succeedsWithCache "assemble"
        // :compileJava is not cached, but :jar is still cached as its inputs haven't changed
        nonSkippedTasks.contains ":compileJava"
        skippedTasks.contains ":jar"
    }

    def succeedsWithCache(String... tasks) {
        executer.withArguments "-Dorg.gradle.cache.tasks=true", "-Dorg.gradle.cache.tasks.directory=" + cacheDir
        succeeds tasks
    }
}

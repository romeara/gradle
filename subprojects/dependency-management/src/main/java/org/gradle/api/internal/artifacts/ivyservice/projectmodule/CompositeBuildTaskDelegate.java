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

package org.gradle.api.internal.artifacts.ivyservice.projectmodule;

import org.gradle.api.DefaultTask;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.TaskAction;
import org.gradle.internal.component.local.model.DefaultProjectComponentIdentifier;

import java.util.Collections;

public class CompositeBuildTaskDelegate extends DefaultTask {
    private String delegateTo;

    @Input
    public String getDelegateTo() {
        return delegateTo;
    }

    public void setDelegateTo(String delegateTo) {
        this.delegateTo = delegateTo;
    }

    @TaskAction
    public void executeTaskInOtherBuild() {
        ProjectArtifactBuilder builder = getServices().get(ProjectArtifactBuilder.class);
        String[] split = delegateTo.split("::", 2);
        String buildName = split[0];
        String taskToExecute = ":" + split[1];
        ProjectComponentIdentifier id = DefaultProjectComponentIdentifier.newId(buildName + "::");
        builder.build(id, Collections.singleton(taskToExecute));
    }
}

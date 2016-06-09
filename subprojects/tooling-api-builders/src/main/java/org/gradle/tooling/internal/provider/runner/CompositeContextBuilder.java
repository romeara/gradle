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

package org.gradle.tooling.internal.provider.runner;

import org.gradle.StartParameter;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeContextBuildActionRunner;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logging;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.initialization.DefaultGradleLauncher;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.internal.composite.GradleParticipantBuild;
import org.gradle.internal.service.ServiceRegistry;

import java.util.List;

public class CompositeContextBuilder {
    private static final org.gradle.api.logging.Logger LOGGER = Logging.getLogger(CompositeContextBuilder.class);

    private final GradleLauncherFactory gradleLauncherFactory;

    public CompositeContextBuilder(GradleLauncherFactory gradleLauncherFactory) {
        this.gradleLauncherFactory = gradleLauncherFactory;
    }

    public void buildCompositeContext(StartParameter actionStartParameter, BuildRequestContext buildRequestContext,
                                      List<GradleParticipantBuild> participants, ServiceRegistry sharedServices) {
        CompositeBuildContext context = sharedServices.get(CompositeBuildContext.class);
        CompositeContextBuildActionRunner builder = new CompositeContextBuildActionRunner(context, true);

        for (GradleParticipantBuild participant : participants) {
            StartParameter startParameter = actionStartParameter.newInstance();
            startParameter.setProjectDir(participant.getProjectDir());
            startParameter.setConfigureOnDemand(false);
            if (startParameter.getLogLevel() == LogLevel.LIFECYCLE) {
                startParameter.setLogLevel(LogLevel.QUIET);
                LOGGER.lifecycle("[composite-build] Configuring participant: " + participant.getProjectDir());
            }

            execute(builder, buildRequestContext, sharedServices, startParameter);
        }
    }

    private void execute(CompositeContextBuildActionRunner buildActionRunner, BuildRequestContext buildRequestContext, ServiceRegistry contextServices, StartParameter startParameter) {
        DefaultGradleLauncher gradleLauncher = (DefaultGradleLauncher) gradleLauncherFactory.newInstance(startParameter, buildRequestContext, contextServices);
        try {
            gradleLauncher.addStandardOutputListener(buildRequestContext.getOutputListener());
            gradleLauncher.addStandardErrorListener(buildRequestContext.getErrorListener());
            buildActionRunner.run(configure(gradleLauncher));
        } finally {
            gradleLauncher.stop();
        }
    }

    public GradleInternal configure(DefaultGradleLauncher launcher) {
        return (GradleInternal) launcher.getBuildAnalysis().getGradle();
    }

}

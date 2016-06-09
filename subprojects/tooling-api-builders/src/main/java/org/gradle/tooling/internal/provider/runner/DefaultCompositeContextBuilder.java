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

import com.google.common.collect.Lists;
import org.apache.commons.io.FileUtils;
import org.gradle.StartParameter;
import org.gradle.api.Transformer;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.internal.GradleInternal;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeContextBuildActionRunner;
import org.gradle.api.logging.LogLevel;
import org.gradle.api.logging.Logging;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.initialization.DefaultGradleLauncher;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.internal.UncheckedException;
import org.gradle.internal.composite.CompositeContextBuilder;
import org.gradle.internal.composite.DefaultGradleParticipantBuild;
import org.gradle.internal.composite.GradleParticipantBuild;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildScopeServices;
import org.gradle.internal.service.scopes.BuildSessionScopeServices;
import org.gradle.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class DefaultCompositeContextBuilder implements CompositeContextBuilder {
    private static final org.gradle.api.logging.Logger LOGGER = Logging.getLogger(DefaultCompositeContextBuilder.class);

    private final GradleLauncherFactory gradleLauncherFactory;

    public DefaultCompositeContextBuilder(GradleLauncherFactory gradleLauncherFactory) {
        this.gradleLauncherFactory = gradleLauncherFactory;
    }

    @Override
    public void buildCompositeContext(StartParameter actionStartParameter, BuildRequestContext buildRequestContext,
                                      ServiceRegistry sharedServices, File compositeDefinitionFile) {
        CompositeBuildContext context = sharedServices.get(CompositeBuildContext.class);
        CompositeContextBuildActionRunner builder = new CompositeContextBuildActionRunner(context, true);

        for (GradleParticipantBuild participant : determineCompositeParticipants(compositeDefinitionFile)) {
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

    @Override
    public void printContext(ServiceRegistry sharedServices) {
        CompositeBuildContext context = sharedServices.get(CompositeBuildContext.class);
        for (ProjectComponentIdentifier projectComponentIdentifier : context.getAllProjects()) {
            System.out.println("Found participant: " + projectComponentIdentifier);
        }
    }

    private void execute(CompositeContextBuildActionRunner buildActionRunner, BuildRequestContext buildRequestContext, ServiceRegistry contextServices, StartParameter startParameter) {
        BuildSessionScopeServices sessionScopeServices = ((BuildScopeServices) contextServices).getSessionServices();
        DefaultGradleLauncher gradleLauncher = (DefaultGradleLauncher) gradleLauncherFactory.newInstance(startParameter, buildRequestContext, sessionScopeServices);
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

    private List<GradleParticipantBuild> determineCompositeParticipants(final File compositeDefinition) {
        List<File> participantPaths = Lists.newArrayList();
        try {
            participantPaths.add(new File(compositeDefinition.getParent(), ".").getCanonicalFile());
            for (String path : FileUtils.readLines(compositeDefinition)) {
                path = path.trim();
                if (path.isEmpty() || path.startsWith("#")) {
                    continue;
                }
                participantPaths.add(new File(compositeDefinition.getParent(), path).getCanonicalFile());
            }
        } catch (IOException e) {
            throw UncheckedException.throwAsUncheckedException(e);
        }

        return CollectionUtils.collect(participantPaths, new Transformer<GradleParticipantBuild, File>() {
            @Override
            public GradleParticipantBuild transform(File participantPath) {
                return new DefaultGradleParticipantBuild(participantPath, null, null, null);
            }
        });
    }


}

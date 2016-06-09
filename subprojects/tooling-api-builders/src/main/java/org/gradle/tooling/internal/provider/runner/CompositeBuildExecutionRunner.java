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
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeBuildContext;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.CompositeScopeServices;
import org.gradle.api.internal.artifacts.ivyservice.projectmodule.DefaultBuildableCompositeBuildContext;
import org.gradle.initialization.BuildRequestContext;
import org.gradle.initialization.DefaultGradleLauncher;
import org.gradle.initialization.GradleLauncher;
import org.gradle.initialization.GradleLauncherFactory;
import org.gradle.internal.classpath.ClassPath;
import org.gradle.internal.composite.CompositeBuildActionParameters;
import org.gradle.internal.composite.CompositeBuildActionRunner;
import org.gradle.internal.composite.CompositeBuildController;
import org.gradle.internal.composite.CompositeContextBuilder;
import org.gradle.internal.invocation.BuildAction;
import org.gradle.internal.invocation.BuildActionRunner;
import org.gradle.internal.service.DefaultServiceRegistry;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.internal.service.scopes.BuildSessionScopeServices;
import org.gradle.launcher.cli.ExecuteBuildAction;
import org.gradle.launcher.exec.BuildActionExecuter;
import org.gradle.launcher.exec.BuildActionParameters;
import org.gradle.launcher.exec.InProcessBuildActionExecuter;
import org.gradle.tooling.internal.provider.ExecuteBuildActionRunner;

import java.util.Collections;

public class CompositeBuildExecutionRunner implements CompositeBuildActionRunner {

    public void run(BuildAction action, BuildRequestContext requestContext, CompositeBuildActionParameters actionParameters, CompositeBuildController buildController) {
        if (!(action instanceof ExecuteBuildAction)) {
            return;
        }
        executeTasksInProcess(action.getStartParameter(), requestContext, buildController.getBuildScopeServices());
        buildController.setResult(null);
    }

    private void executeTasksInProcess(StartParameter actionStartParameter, BuildRequestContext buildRequestContext, ServiceRegistry sharedServices) {
        GradleLauncherFactory gradleLauncherFactory = sharedServices.get(GradleLauncherFactory.class);

        DefaultServiceRegistry compositeServices = createCompositeAwareServices(actionStartParameter, sharedServices);

        StartParameter startParameter = actionStartParameter.newInstance();
        startParameter.setSearchUpwards(false);
        startParameter.setSystemPropertiesArgs(Collections.singletonMap("org.gradle.resolution.assumeFluidDependencies", "true"));

        // Use a ModelActionRunner to ensure that model events are emitted
        BuildActionRunner runner = new ExecuteBuildActionRunner();
        BuildActionExecuter<BuildActionParameters> buildActionExecuter = new InProcessBuildActionExecuter(new ComposingGradleLauncherFactory(gradleLauncherFactory), runner);
        ServiceRegistry buildScopedServices = new BuildSessionScopeServices(compositeServices, startParameter, ClassPath.EMPTY);

        buildActionExecuter.execute(new ExecuteBuildAction(startParameter), buildRequestContext, null, buildScopedServices);
    }

    private DefaultServiceRegistry createCompositeAwareServices(StartParameter actionStartParameter, ServiceRegistry sharedServices) {
        GradleLauncherFactory gradleLauncherFactory = sharedServices.get(GradleLauncherFactory.class);
        DefaultServiceRegistry compositeServices = new BuildSessionScopeServices(sharedServices, actionStartParameter, ClassPath.EMPTY);
        compositeServices.add(CompositeBuildContext.class, new DefaultBuildableCompositeBuildContext());
        compositeServices.add(CompositeContextBuilder.class, new DefaultCompositeContextBuilder(gradleLauncherFactory));
        compositeServices.addProvider(new CompositeScopeServices(actionStartParameter, compositeServices));

        return compositeServices;
    }

    private static class ComposingGradleLauncherFactory implements GradleLauncherFactory {
        private final GradleLauncherFactory delegate;

        private ComposingGradleLauncherFactory(GradleLauncherFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public GradleLauncher newInstance(StartParameter startParameter) {
            return enable(delegate.newInstance(startParameter));
        }

        @Override
        public GradleLauncher newInstance(StartParameter startParameter, ServiceRegistry parent) {
            return enable(delegate.newInstance(startParameter, parent));
        }

        @Override
        public GradleLauncher newInstance(StartParameter startParameter, BuildRequestContext requestContext, ServiceRegistry parent) {
            return enable(delegate.newInstance(startParameter, requestContext, parent));
        }

        private GradleLauncher enable(GradleLauncher gradleLauncher) {
            ((DefaultGradleLauncher) gradleLauncher).buildComposite = true;
            return gradleLauncher;
        }
    }

}

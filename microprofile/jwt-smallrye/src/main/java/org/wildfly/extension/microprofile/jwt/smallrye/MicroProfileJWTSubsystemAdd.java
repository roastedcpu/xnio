/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.wildfly.extension.microprofile.jwt.smallrye;

import org.jboss.as.controller.AbstractBoottimeAddStepHandler;
import org.jboss.as.controller.OperationContext;
import org.jboss.dmr.ModelNode;
import org.wildfly.extension.microprofile.jwt.smallrye._private.MicroProfileJWTLogger;

/**
 * Add handler for the MicroProfile JWT subsystem.
 *
 * <a href="mailto:darran.lofthouse@jboss.com">Darran Lofthouse</a>
 */
class MicroProfileJWTSubsystemAdd extends AbstractBoottimeAddStepHandler {

    MicroProfileJWTSubsystemAdd() {
    }

    @Override
    public void performBoottime(OperationContext context, ModelNode operation, ModelNode model) {
        MicroProfileJWTLogger.ROOT_LOGGER.activatingSubsystem();

        // context.addStep(new AbstractDeploymentChainStep() {
        // public void execute(DeploymentProcessorTarget processorTarget) {
        // processorTarget.addDeploymentProcessor(MicroProfileJWTExtension.SUBSYSTEM_NAME, Phase.DEPENDENCIES,
        // Phase.DEPENDENCIES_MICROPROFILE_CONFIG, new DependencyProcessor());
        // processorTarget.addDeploymentProcessor(MicroProfileJWTExtension.SUBSYSTEM_NAME, Phase.POST_MODULE,
        // Phase.POST_MODULE_MICROPROFILE_CONFIG, new SubsystemDeploymentProcessor());
        //
        // }
        // }, OperationContext.Stage.RUNTIME);

    }
}

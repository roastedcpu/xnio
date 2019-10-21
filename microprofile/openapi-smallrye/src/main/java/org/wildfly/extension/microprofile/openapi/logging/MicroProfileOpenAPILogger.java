/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2019, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.extension.microprofile.openapi.logging;

import static org.jboss.logging.Logger.Level.INFO;
import static org.jboss.logging.Logger.Level.WARN;

import java.io.IOException;

import org.jboss.as.server.deployment.DeploymentUnitProcessingException;
import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.annotations.Cause;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

import io.smallrye.openapi.runtime.io.OpenApiSerializer.Format;

/**
 * Log messages for WildFly microprofile-openapi-smallrye subsystem.
 *
 * @author Michael Edgar
 */
@MessageLogger(projectCode = "WFLYMPOAI", length = 4)
public interface MicroProfileOpenAPILogger extends BasicLogger {
    MicroProfileOpenAPILogger LOGGER = Logger.getMessageLogger(MicroProfileOpenAPILogger.class, "org.wildfly.extension.microprofile.openapi.smallrye");

    @LogMessage(level = INFO)
    @Message(id = 1, value = "Activating Eclipse MicroProfile OpenAPI Subsystem")
    void activatingSubsystem();

    @Message(id = 2, value = "Failed to load OpenAPI static file from deployment")
    DeploymentUnitProcessingException staticFileLoadException(@Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 3, value = "Unable to serialize OpenAPI in %s format")
    void serializationException(Format format, @Cause IOException e);

    @LogMessage(level = WARN)
    @Message(id = 4, value = "MicroProfile OpenAPI endpoint already registered for host '%s'.  Skipping OpenAPI documentation of '%s'.")
    void endpointAlreadyRegistered(String hostName, String deployment);

    @LogMessage(level = INFO)
    @Message(id = 5, value = "Registered MicroProfile OpenAPI endpoint for host '%s'")
    void endpointRegistered(String hostName);

    @LogMessage(level = INFO)
    @Message(id = 6, value = "Unregistered MicroProfile OpenAPI endpoint for host '%s'")
    void endpointUnregistered(String hostName);
}

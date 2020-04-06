/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.wildfly.test.integration.microprofile.expansion.pack;

import java.util.Collection;
import java.util.Collections;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.test.shared.ServerReload;
import org.jboss.as.test.shared.TestLogHandlerSetupTask;
import org.jboss.as.test.shared.util.LoggingUtil;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Smoke test that the DEBUG log message from ExpansionPackDependencyVerifier is found in the log
 */
@RunWith(Arquillian.class)
@ServerSetup(ExpansionPackDependencyVerifierTestCase.TestLogHandlerSetup.class)
@RunAsClient
public class ExpansionPackDependencyVerifierTestCase {

    private static final String TEST_HANDLER_NAME = "test-" + ExpansionPackDependencyVerifierTestCase.class.getSimpleName();
    private static final String TEST_LOG_FILE_NAME = TEST_HANDLER_NAME + ".log";
    private static final String LOG_WARN_TAG = "JBEAPXP0001";
    private static final String LOG_DEBUG_TAG = "JBEAPXP0002";

    public static class TestLogHandlerSetup extends TestLogHandlerSetupTask {

        @Override
        public Collection<String> getCategories() {
            return Collections.singletonList("org.jboss.eap.expansion.pack.verifier");
        }

        @Override
        public String getLevel() {
            return "DEBUG";
        }
        @Override
        public String getHandlerName() {
            return TEST_HANDLER_NAME;
        }
        @Override
        public String getLogFileName() {
            return TEST_LOG_FILE_NAME;
        }
    }

    // Dummy deployment without which our ServerSetupTask doesn't get run
    @Deployment
    public static Archive<?> getDeployment() {
        return ShrinkWrap.create(JavaArchive.class, "expansion-pack-verifier.jar");
    }

    @ContainerResource
    private ManagementClient managementClient;

    @Test
    public void testVerifierMessage() throws Exception {
        Assert.assertFalse("ExpansionPackDependencyVerifier WARN incorrectly found", LoggingUtil.hasLogMessage(managementClient, TEST_HANDLER_NAME, LOG_WARN_TAG));
        ServerReload.executeReloadAndWaitForCompletion(managementClient);
        Assert.assertFalse("ExpansionPackDependencyVerifier WARN incorrectly found", LoggingUtil.hasLogMessage(managementClient, TEST_HANDLER_NAME, LOG_WARN_TAG));
        Assert.assertTrue("ExpansionPackDependencyVerifier status not found", LoggingUtil.hasLogMessage(managementClient, TEST_HANDLER_NAME, LOG_DEBUG_TAG, line -> line.contains("DEBUG")));
    }
}

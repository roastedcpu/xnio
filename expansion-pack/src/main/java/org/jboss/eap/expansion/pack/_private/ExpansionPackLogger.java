/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2020 Red Hat, Inc., and individual contributors
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
package org.jboss.eap.expansion.pack._private;

import org.jboss.logging.BasicLogger;
import org.jboss.logging.Logger;
import org.jboss.logging.Logger.Level;
import org.jboss.logging.annotations.LogMessage;
import org.jboss.logging.annotations.Message;
import org.jboss.logging.annotations.MessageLogger;

/**
 * Log messages for EAP expansion pack module
 */
@MessageLogger(projectCode = "JBEAPXP", length = 4)
public interface ExpansionPackLogger extends BasicLogger {

    ExpansionPackLogger VERIFY_LOGGER = Logger.getMessageLogger(ExpansionPackLogger.class, "org.jboss.eap.expansion.pack.verifier");

    @LogMessage(level = Level.WARN)
    @Message(id = 1, value = "Incorrect base version for JBoss EAP expansion pack. %s %s requires %s %s but %s is installed. " +
            "Unexpected results may occur. Please update this installation to the compatible base EAP version.")
    void incorrectBaseVersion(String xpName, String xpVersion, String baseName, String requiredBaseVersion, String actualBaseVersion);

    // This is DEBUG but leave it here as we check for the message id in some tests
    @LogMessage(level = Level.DEBUG)
    @Message(id = 2, value = "Expansion pack's base dependency is correct; required %s and found %s")
    void correctBaseVersion(String requiredBaseVersion, String actualBaseVersion);
}

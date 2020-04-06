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

package org.jboss.eap.expansion.pack;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.jboss.as.version.ProductConfig;
import org.jboss.eap.expansion.pack._private.ExpansionPackLogger;
import org.jboss.msc.Service;

import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceRegistryException;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;

public final class ExpansionPackDependencyVerifier implements Service {

    private static final AtomicBoolean installed = new AtomicBoolean();

    public static void installVerifier(ServiceTarget target) {

        synchronized (installed) {
            if (!installed.get()) {
                ServiceName svcName = ServiceName.JBOSS.append("eap", "expansion", "pack", "verifier");

                try {
                    ServiceBuilder<?> builder = target.addService(svcName);
                    Supplier<ProductConfig> supplier = builder.requires(ServiceName.JBOSS.append("as", "product-config")); // TODO use a capability
                    builder.setInstance(new ExpansionPackDependencyVerifier(supplier)).install();
                    installed.set(true);
                } catch (ServiceRegistryException e) {
                    // ignore. Just means this check doesn't happen which isn't critical
                }
            }
        }
    }

    private final Supplier<ProductConfig> supplier;

    private ExpansionPackDependencyVerifier(final Supplier<ProductConfig> supplier) {
        this.supplier = supplier;
    }

    @Override
    public void start(StartContext startContext) {
        InputStream is = getClass().getClassLoader().getResourceAsStream("validation.properties");
        if (is != null) {
            try {
                try {
                    Properties properties = new Properties();
                    properties.load(is);
                    String requiredBaseVersion = properties.getProperty("required.base.version");
                    if (requiredBaseVersion != null && !requiredBaseVersion.equals(supplier.get().getProductVersion())) {
                       String xpName = properties.getProperty("expansion.pack.release.name");
                       String xpVer = properties.getProperty("expansion.pack.release.version");
                       ExpansionPackLogger.VERIFY_LOGGER.incorrectBaseVersion(
                                xpName == null ? "JBoss EAP XP": xpName,
                                xpVer == null ? "" : xpVer,
                                supplier.get().getProductName(),
                                requiredBaseVersion,
                                supplier.get().getProductVersion()
                        );
                    } else {
                        ExpansionPackLogger.VERIFY_LOGGER.correctBaseVersion(requiredBaseVersion, supplier.get().getProductVersion());
                    }

                } finally {
                    is.close();
                }
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    @Override
    public void stop(StopContext stopContext) {
        installed.set(false);
    }
}

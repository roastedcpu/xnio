/*
 * JBoss, Home of Professional Open Source
 * Copyright 2012 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @author tags. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */

package org.jboss.as.clustering.infinispan.subsystem;

import java.util.function.Function;

import org.infinispan.Cache;
import org.infinispan.manager.EmbeddedCacheManager;
import org.jboss.as.clustering.controller.BinaryCapabilityNameResolver;
import org.jboss.as.clustering.controller.Metric;
import org.jboss.as.clustering.controller.MetricExecutor;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.dmr.ModelNode;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceNotFoundException;
import org.wildfly.clustering.infinispan.spi.InfinispanCacheRequirement;
import org.wildfly.clustering.infinispan.spi.InfinispanRequirement;
import org.wildfly.clustering.service.PassiveServiceSupplier;

/**
 * @author Paul Ferraro
 */
public abstract class CacheMetricExecutor<C> implements MetricExecutor<C>, Function<Cache<?, ?>, C> {

    private final BinaryCapabilityNameResolver resolver;

    protected CacheMetricExecutor() {
        this(BinaryCapabilityNameResolver.PARENT_CHILD);
    }

    protected CacheMetricExecutor(BinaryCapabilityNameResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public ModelNode execute(OperationContext context, Metric<C> metric) throws OperationFailedException {
        Cache<?, ?> cache = this.getCache(context);
        C metricContext = (cache != null) ? this.apply(cache) : null;
        return (metricContext != null) ? metric.execute(metricContext) : null;
    }

    private Cache<?, ?> getCache(OperationContext context) {
        ServiceName cacheName = InfinispanCacheRequirement.CACHE.getServiceName(context, this.resolver);
        try {
            return new PassiveServiceSupplier<Cache<?, ?>>(context.getServiceRegistry(false), cacheName).get();
        } catch (ServiceNotFoundException e) {
            // If cache was created programmatically, no service will exist for this cache
            // Obtain cache from cache manager service instead
            ServiceName containerName = InfinispanRequirement.CONTAINER.getServiceName(context, cacheName.getParent().getSimpleName());
            EmbeddedCacheManager manager = new PassiveServiceSupplier<EmbeddedCacheManager>(context.getServiceRegistry(false), containerName).get();
            if (!manager.cacheExists(cacheName.getSimpleName())) {
                // If cache does not exist, throw exception, lest we inadvertently create it.
                throw e;
            }
            return manager.getCache(cacheName.getSimpleName());
        }
    }
}

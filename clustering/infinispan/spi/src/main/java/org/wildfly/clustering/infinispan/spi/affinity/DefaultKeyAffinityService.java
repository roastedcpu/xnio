/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2021, Red Hat, Inc., and individual contributors
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

package org.wildfly.clustering.infinispan.spi.affinity;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.infinispan.Cache;
import org.infinispan.affinity.KeyAffinityService;
import org.infinispan.affinity.KeyGenerator;
import org.infinispan.distribution.DistributionManager;
import org.infinispan.distribution.ch.ConsistentHash;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.cachelistener.annotation.TopologyChanged;
import org.infinispan.notifications.cachelistener.event.TopologyChangedEvent;
import org.infinispan.notifications.cachemanagerlistener.event.CacheStoppedEvent;
import org.infinispan.remoting.transport.Address;
import org.infinispan.util.concurrent.ReclosableLatch;
import org.infinispan.util.logging.Log;
import org.infinispan.util.logging.LogFactory;

import net.jcip.annotations.GuardedBy;
import net.jcip.annotations.ThreadSafe;

/**
 * @author Paul Ferraro
 */
@ThreadSafe
@Listener
public class DefaultKeyAffinityService<K> implements KeyAffinityService<K> {

    // TODO During state transfer, we should try to assign keys to a node only if they are owners in both CHs
    private static final float THRESHOLD = 0.5f;

    //interval between key/queue poll
    private static final int POLL_INTERVAL_MILLIS = 50;

    private static final Log log = LogFactory.getLog(DefaultKeyAffinityService.class);

    private final Set<Address> filter;

    @GuardedBy("maxNumberInvariant")
    private final Map<Address, BlockingQueue<K>> address2key = new ConcurrentHashMap<>();
    private final Executor executor;
    private final Cache<? extends K, ?> cache;
    private final KeyGenerator<? extends K> keyGenerator;
    private final int bufferSize;
    private final AtomicInteger maxNumberOfKeys = new AtomicInteger(); //(nr. of addresses) * bufferSize;
    final AtomicInteger existingKeyCount = new AtomicInteger();

    private volatile boolean started;

    /**
     * Guards and make sure the following invariant stands:  maxNumberOfKeys ==  address2key.keys().size() * bufferSize
     */
    private final ReadWriteLock maxNumberInvariant = new ReentrantReadWriteLock();

    /**
     * Used for coordinating between the KeyGeneratorWorker and consumers.
     */
    private final ReclosableLatch keyProducerStartLatch = new ReclosableLatch();
    private volatile KeyGeneratorWorker keyGenWorker;

    public DefaultKeyAffinityService(Executor executor, Cache<? extends K, ?> cache, KeyGenerator<? extends K> keyGenerator, int bufferSize, Collection<Address> filter) {
        this.executor = executor;
        this.cache = cache;
        this.keyGenerator = keyGenerator;
        this.bufferSize = bufferSize;
        if (filter != null) {
            this.filter = ConcurrentHashMap.newKeySet();
            for (Address address : filter) {
                this.filter.add(address);
            }
        } else {
            this.filter = null;
        }
    }

    @Override
    public K getCollocatedKey(K otherKey) {
        Address address = getAddressForKey(otherKey);
        return getKeyForAddress(address);
    }

    @Override
    public K getKeyForAddress(Address address) {
        if (!started) {
            throw new IllegalStateException("You have to start the service first!");
        }
        if (address == null)
            throw new NullPointerException("Null address not supported!");

        BlockingQueue<K> queue = null;
        try {
            K result = null;
            while (result == null && !keyGenWorker.isStopped()) {
                // obtain the read lock inside the loop, otherwise a topology change will never be able
                // to obtain the write lock
                maxNumberInvariant.readLock().lock();
                try {
                    queue = address2key.get(address);
                    if (queue == null) {
                        return this.keyGenerator.getKey();
                    }

                    // first try to take an element without waiting
                    result = queue.poll();
                    if (result == null) {
                        // there are no elements in the queue, make sure the producer is started
                        keyProducerStartLatch.open();

                        // If address is not a member of the cluster, or the member does not own any segments, return a random key
                        ConsistentHash hash = this.cache.getAdvancedCache().getDistributionManager().getCacheTopology().getCurrentCH();
                        if (!hash.getMembers().contains(address) || hash.getPrimarySegmentsForOwner(address).isEmpty()) {
                            return this.keyGenerator.getKey();
                        }
                    }
                } finally {
                    maxNumberInvariant.readLock().unlock();
                }

                if (result == null) {
                    // Now wait for a new key. If there's a topology change, poll() will time out and we'll retry
                    // on the new queue.
                    try {
                        result = queue.poll(POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException e) {
                        // Ignore and restore interruption status
                        Thread.currentThread().interrupt();
                    }
                }
            }
            existingKeyCount.decrementAndGet();
            log.tracef("Returning key %s for address %s", result, address);
            return result;
        } finally {
            if (queue != null && queue.size() < bufferSize * THRESHOLD + 1) {
                keyProducerStartLatch.open();
            }
        }
    }

    @Override
    public void start() {
        if (started) {
            log.debug("Service already started, ignoring call to start!");
            return;
        }
        maxNumberInvariant.writeLock().lock();
        try {
            addQueuesForAddresses(cache.getAdvancedCache().getDistributionManager().getCacheTopology().getCurrentCH());
            resetNumberOfKeys();
        } finally {
            maxNumberInvariant.writeLock().unlock();
        }
        keyGenWorker = new KeyGeneratorWorker();
        executor.execute(keyGenWorker);
        cache.addListener(this);
        keyProducerStartLatch.open();
        started = true;
    }

    @Override
    public void stop() {
        if (!started) {
            log.debug("Ignoring call to stop as service is not started.");
            return;
        }
        started = false;
        EmbeddedCacheManager cacheManager = cache.getCacheManager();
        cache.removeListener(this);
        keyGenWorker.stop();
    }

    @TopologyChanged
    public void handleViewChange(TopologyChangedEvent<?, ?> event) {
        if (!event.isPre()) {
            log.tracef("TopologyChangedEvent received: %s", event);
            maxNumberInvariant.writeLock().lock();
            try {
                address2key.clear(); //we need to drop everything as key-mapping data is stale due to view change
                addQueuesForAddresses(event.getConsistentHashAtEnd());
                resetNumberOfKeys();
                keyProducerStartLatch.open();
            } finally {
                maxNumberInvariant.writeLock().unlock();
            }
        }
    }

    public boolean isKeyGeneratorThreadAlive() {
        return !keyGenWorker.isStopped();
    }

    public void handleCacheStopped(CacheStoppedEvent cse) {
        if (this.cache.getName().equals(cse.getCacheName())) {
            log.tracef("Cache stopped, stopping the service: %s", cse);
            stop();
        }
    }

    private class KeyGeneratorWorker implements Runnable {

        private volatile boolean isActive;
        private volatile boolean isStopped = false;

        @Override
        public void run() {
            try {
                while (!isStopped) {
                    if (keyProducerStartLatch.await(10, TimeUnit.SECONDS)) {
                        if (!isStopped) {
                            isActive = true;
                            log.trace("KeyGeneratorWorker marked as ACTIVE");
                            generateKeys();

                            isActive = false;
                            log.trace("KeyGeneratorWorker marked as INACTIVE");
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                log.debugf("Shutting down KeyAffinity service for key set: %s", filter);
            }
        }

        public boolean isStopped() {
             return isStopped;
        }

        private void generateKeys() {
            maxNumberInvariant.readLock().lock();
            try {
                // if there's a topology change, some queues will stop receiving keys
                // so we want to establish an upper bound on how many extra keys to generate
                // in order to fill all the queues
                int maxMisses = maxNumberOfKeys.get();
                int missCount = 0;
                while (existingKeyCount.get() < maxNumberOfKeys.get() && missCount <= maxMisses) {
                    K key = keyGenerator.getKey();
                    Address addressForKey = getAddressForKey(key);
                    boolean added = false;
                    if (interestedInAddress(addressForKey)) {
                        added = tryAddKey(addressForKey, key);
                    }
                    if (!added) missCount++;
                }

                // if we had too many misses, just release the lock and try again
                if (missCount <= maxMisses) {
                    keyProducerStartLatch.close();
                }
            } finally {
                maxNumberInvariant.readLock().unlock();
            }
        }

        @GuardedBy("maxNumberInvariant")
        private boolean tryAddKey(Address address, K key) {
            BlockingQueue<K> queue = address2key.get(address);
            // on node stop the distribution manager might still return the dead server for a while after we have already removed its queue
            if (queue == null)
                return false;

            boolean added = queue.offer(key);
            if (added) {
                existingKeyCount.incrementAndGet();
                log.tracef("Added key %s for address %s", key, address);
            }
            return added;
        }

        public boolean isActive() {
            return isActive;
        }

        public void stop() {
            isStopped = true;
            keyProducerStartLatch.open();
        }
    }

    /**
     * Important: this *MUST* be called with WL on {@link #address2key}.
     */
    @GuardedBy("maxNumberInvariant")
    private void resetNumberOfKeys() {
        maxNumberOfKeys.set(address2key.keySet().size() * bufferSize);
        existingKeyCount.set(0);
        if (log.isTraceEnabled()) {
            log.tracef("resetNumberOfKeys ends with: maxNumberOfKeys=%s, existingKeyCount=%s", maxNumberOfKeys.get(), existingKeyCount.get());
        }
    }

    /**
     * Important: this *MUST* be called with WL on {@link #address2key}.
     */
    @GuardedBy("maxNumberInvariant")
    private void addQueuesForAddresses(ConsistentHash hash) {
        for (Address address : hash.getMembers()) {
            if (interestedInAddress(address) && !hash.getPrimarySegmentsForOwner(address).isEmpty()) {
                address2key.put(address, new ArrayBlockingQueue<>(bufferSize));
            } else {
                log.tracef("Skipping address: %s", address);
            }
        }
    }

    private boolean interestedInAddress(Address address) {
        return filter == null || filter.contains(address);
    }

    private Address getAddressForKey(Object key) {
        DistributionManager distributionManager = getDistributionManager();
        return distributionManager.getCacheTopology().getDistribution(key).primary();
    }

    private DistributionManager getDistributionManager() {
        DistributionManager distributionManager = cache.getAdvancedCache().getDistributionManager();
        if (distributionManager == null) {
            throw new IllegalStateException("Null distribution manager. Is this an distributed(v.s. replicated) cache?");
        }
        return distributionManager;
    }

    public Map<Address, BlockingQueue<K>> getAddress2KeysMapping() {
        return Collections.unmodifiableMap(address2key);
    }

    public int getMaxNumberOfKeys() {
        return maxNumberOfKeys.intValue();
    }

    public boolean isKeyGeneratorThreadActive() {
        return keyGenWorker.isActive();
    }

    @Override
    public boolean isStarted() {
        return started;
    }
}
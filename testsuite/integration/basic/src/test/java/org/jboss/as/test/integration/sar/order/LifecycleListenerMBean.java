/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2021 Red Hat, Inc., and individual contributors
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

package org.jboss.as.test.integration.sar.order;

import java.util.List;

public interface LifecycleListenerMBean {

    void mbeanCreated(String id);

    void mbeanStarted(String id);

    void mbeanStopped(String id);

    void mbeanDestroyed(String id);

    List<String> getCreates();

    List<String> getStarts();

    List<String> getStops();

    List<String> getDestroys();

}
/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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
package org.wildfly.clustering.web.undertow.session;

import io.undertow.security.api.AuthenticatedSessionManager.AuthenticatedSession;
import io.undertow.websockets.core.WebSocketChannel;

import java.util.List;

import org.wildfly.clustering.web.LocalContextFactory;

public enum LocalSessionContextFactory implements LocalContextFactory<LocalSessionContext> {
    INSTANCE;

    @Override
    public LocalSessionContext createLocalContext() {
        return new LocalSessionContext() {
            private volatile AuthenticatedSession authenticatedSession;
            private volatile List<WebSocketChannel> channels;

            @Override
            public AuthenticatedSession getAuthenticatedSession() {
                return this.authenticatedSession;
            }

            @Override
            public void setAuthenticatedSession(AuthenticatedSession authenticatedSession) {
                this.authenticatedSession = authenticatedSession;
            }

            @Override
            public List<WebSocketChannel> getWebSocketChannels() {
                return this.channels;
            }

            @Override
            public void setWebSocketChannels(List<WebSocketChannel> channels) {
                this.channels = channels;
            }
        };
    }
}

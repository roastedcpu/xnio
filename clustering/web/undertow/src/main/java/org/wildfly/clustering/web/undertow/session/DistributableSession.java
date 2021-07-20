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
import io.undertow.server.HttpServerExchange;
import io.undertow.server.session.SessionConfig;
import io.undertow.server.session.SessionListener.SessionDestroyedReason;
import io.undertow.servlet.UndertowServletMessages;
import io.undertow.servlet.handlers.security.CachedAuthenticatedSessionHandler;
import io.undertow.websockets.core.WebSocketChannel;

import java.time.Duration;
import java.util.AbstractMap.SimpleImmutableEntry;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import javax.servlet.http.HttpServletRequest;

import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.BatchContext;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.web.session.ImmutableSessionAttributes;
import org.wildfly.clustering.web.session.Session;
import org.wildfly.clustering.web.session.SessionManager;
import org.wildfly.clustering.web.session.oob.OOBSession;
import org.wildfly.clustering.web.undertow.logging.UndertowClusteringLogger;

/**
 * Adapts a distributable {@link Session} to an Undertow {@link io.undertow.server.session.Session}.
 * @author Paul Ferraro
 */
public class DistributableSession implements io.undertow.server.session.Session {
    // These mechanisms can auto-reauthenticate and thus use local context (instead of replicating)
    private static final Set<String> AUTO_REAUTHENTICATING_MECHANISMS = new HashSet<>(Arrays.asList(HttpServletRequest.BASIC_AUTH, HttpServletRequest.DIGEST_AUTH, HttpServletRequest.CLIENT_CERT_AUTH));
    static final String WEB_SOCKET_CHANNELS_ATTRIBUTE = "io.undertow.websocket.current-connections";

    private final UndertowSessionManager manager;
    private final Batch batch;
    private final Consumer<HttpServerExchange> closeTask;

    private volatile Map.Entry<Session<LocalSessionContext>, SessionConfig> entry;
    // The following references are only used to create an OOB session
    private volatile String id = null;
    private volatile LocalSessionContext localContext = null;

    public DistributableSession(UndertowSessionManager manager, Session<LocalSessionContext> session, SessionConfig config, Batch batch, Consumer<HttpServerExchange> closeTask) {
        this.manager = manager;
        this.id = session.getId();
        this.entry = new SimpleImmutableEntry<>(session, config);
        this.batch = batch;
        this.closeTask = closeTask;
    }

    private Map.Entry<Session<LocalSessionContext>, SessionConfig> getSessionEntry() {
        Map.Entry<Session<LocalSessionContext>, SessionConfig> entry = this.entry;
        // If entry is null, we are outside of the context of a request
        if (entry == null) {
            // Only allow a single thread to lazily create OOB session
            synchronized (this) {
                if (this.entry == null) {
                    // N.B. If entry is null, id and localContext will already have been set
                    this.entry = new SimpleImmutableEntry<>(new OOBSession<>(this.manager.getSessionManager(), this.id, this.localContext), new SimpleSessionConfig(this.id));
                }
                entry = this.entry;
            }
        }
        return entry;
    }

    @Override
    public io.undertow.server.session.SessionManager getSessionManager() {
        return this.manager;
    }

    @Override
    public void requestDone(HttpServerExchange exchange) {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        Batcher<Batch> batcher = this.manager.getSessionManager().getBatcher();
        try (BatchContext context = batcher.resumeBatch(this.batch)) {
            // If batch was discarded, close it
            if (this.batch.getState() == Batch.State.DISCARDED) {
                this.batch.close();
            }
            // If batch is closed, close valid session in a new batch
            try (Batch batch = (this.batch.getState() == Batch.State.CLOSED) && session.isValid() ? batcher.createBatch() : this.batch) {
                // Ensure session is closed, even if invalid
                session.close();
            }
        } catch (Throwable e) {
            // Don't propagate exceptions at the stage, since response was already committed
            UndertowClusteringLogger.ROOT_LOGGER.warn(e.getLocalizedMessage(), e);
        } finally {
            // Dereference the distributed session, but retain reference to session identifier and local context
            // If session is accessed after this method, getSessionEntry() will lazily create an OOB session
            this.id = session.getId();
            this.localContext = session.getLocalContext();
            this.entry = null;
            this.closeTask.accept(exchange);
        }
    }

    @Override
    public String getId() {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        return session.getId();
    }

    @Override
    public long getCreationTime() {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            return session.getMetaData().getCreationTime().toEpochMilli();
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public long getLastAccessedTime() {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            return session.getMetaData().getLastAccessedTime().toEpochMilli();
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public int getMaxInactiveInterval() {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            return (int) session.getMetaData().getMaxInactiveInterval().getSeconds();
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            session.getMetaData().setMaxInactiveInterval(Duration.ofSeconds(interval));
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public Set<String> getAttributeNames() {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            return session.getAttributes().getAttributeNames();
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public Object getAttribute(String name) {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            if (CachedAuthenticatedSessionHandler.ATTRIBUTE_NAME.equals(name)) {
                AuthenticatedSession auth = (AuthenticatedSession) session.getAttributes().getAttribute(name);
                return (auth != null) ? auth : session.getLocalContext().getAuthenticatedSession();
            }
            if (WEB_SOCKET_CHANNELS_ATTRIBUTE.equals(name)) {
                return session.getLocalContext().getWebSocketChannels();
            }
            return session.getAttributes().getAttribute(name);
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public Object setAttribute(String name, Object value) {
        if (value == null) {
            return this.removeAttribute(name);
        }
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            if (CachedAuthenticatedSessionHandler.ATTRIBUTE_NAME.equals(name)) {
                AuthenticatedSession auth = (AuthenticatedSession) value;
                return AUTO_REAUTHENTICATING_MECHANISMS.contains(auth.getMechanism()) ? this.setAuthenticatedSession(auth) : session.getAttributes().setAttribute(name, auth);
            }
            if (WEB_SOCKET_CHANNELS_ATTRIBUTE.equals(name)) {
                @SuppressWarnings("unchecked")
                List<WebSocketChannel> channels = (List<WebSocketChannel>) value;
                return this.setWebSocketChannels(channels);
            }
            Object old = session.getAttributes().setAttribute(name, value);
            if (old == null) {
                this.manager.getSessionListeners().attributeAdded(this, name, value);
            } else if (old != value) {
                this.manager.getSessionListeners().attributeUpdated(this, name, value, old);
            }
            return old;
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public Object removeAttribute(String name) {
        Session<LocalSessionContext> session = this.getSessionEntry().getKey();
        try (BatchContext context = this.resumeBatch()) {
            if (CachedAuthenticatedSessionHandler.ATTRIBUTE_NAME.equals(name)) {
                AuthenticatedSession auth = (AuthenticatedSession) session.getAttributes().removeAttribute(name);
                return (auth != null) ? auth : this.setAuthenticatedSession(null);
            }
            if (WEB_SOCKET_CHANNELS_ATTRIBUTE.equals(name)) {
                return this.setWebSocketChannels(null);
            }
            Object old = session.getAttributes().removeAttribute(name);
            if (old != null) {
                this.manager.getSessionListeners().attributeRemoved(this, name, old);
            }
            return old;
        } catch (IllegalStateException e) {
            this.closeIfInvalid(null, session);
            throw e;
        }
    }

    @Override
    public void invalidate(HttpServerExchange exchange) {
        Map.Entry<Session<LocalSessionContext>, SessionConfig> entry = this.getSessionEntry();
        @SuppressWarnings("resource")
        Session<LocalSessionContext> session = entry.getKey();
        if (session.isValid()) {
            // Invoke listeners outside of the context of the batch associated with this session
            // Trigger attribute listeners
            ImmutableSessionAttributes attributes = session.getAttributes();
            for (String name : attributes.getAttributeNames()) {
                Object value = attributes.getAttribute(name);
                this.manager.getSessionListeners().attributeRemoved(this, name, value);
            }
            this.manager.getSessionListeners().sessionDestroyed(this, exchange, SessionDestroyedReason.INVALIDATED);
        }
        try (BatchContext context = this.resumeBatch()) {
            session.invalidate();
            if (exchange != null) {
                String id = session.getId();
                entry.getValue().clearSession(exchange, id);
            }
            // An OOB session has no batch
            if (this.batch != null) {
                this.batch.close();
            }
        } catch (IllegalStateException e) {
            // If Session.invalidate() fails due to concurrent invalidation, close this session
            if (!session.isValid()) {
                session.close();
            }
            throw e;
        } finally {
            this.closeTask.accept(exchange);
        }
    }

    @Override
    public String changeSessionId(HttpServerExchange exchange, SessionConfig config) {
        // Workaround for UNDERTOW-1902
        if (exchange.isResponseStarted()) { // Should match the condition in io.undertow.servlet.spec.HttpServletResponseImpl#isCommitted()
            throw UndertowServletMessages.MESSAGES.responseAlreadyCommited();
        }
        Session<LocalSessionContext> oldSession = this.getSessionEntry().getKey();
        SessionManager<LocalSessionContext, Batch> manager = this.manager.getSessionManager();
        String id = manager.createIdentifier();
        try (BatchContext context = this.resumeBatch()) {
            Session<LocalSessionContext> newSession = manager.createSession(id);
            try {
                for (String name: oldSession.getAttributes().getAttributeNames()) {
                    newSession.getAttributes().setAttribute(name, oldSession.getAttributes().getAttribute(name));
                }
                newSession.getMetaData().setMaxInactiveInterval(oldSession.getMetaData().getMaxInactiveInterval());
                newSession.getMetaData().setLastAccessedTime(oldSession.getMetaData().getLastAccessedTime());
                newSession.getLocalContext().setAuthenticatedSession(oldSession.getLocalContext().getAuthenticatedSession());
                newSession.getLocalContext().setWebSocketChannels(oldSession.getLocalContext().getWebSocketChannels());
                oldSession.invalidate();
                config.setSessionId(exchange, id);
                this.entry = new SimpleImmutableEntry<>(newSession, config);
            } catch (IllegalStateException e) {
                this.closeIfInvalid(exchange, oldSession);
                newSession.invalidate();
                throw e;
            }
        }
        if (!oldSession.isValid()) {
            // Invoke listeners outside of the context of the batch associated with this session
            this.manager.getSessionListeners().sessionIdChanged(this, oldSession.getId());
        }
        return id;
    }

    private AuthenticatedSession setAuthenticatedSession(AuthenticatedSession auth) {
        LocalSessionContext localContext = this.entry.getKey().getLocalContext();
        AuthenticatedSession old = localContext.getAuthenticatedSession();
        localContext.setAuthenticatedSession(auth);
        return old;
    }

    private List<WebSocketChannel> setWebSocketChannels(List<WebSocketChannel> channels) {
        LocalSessionContext localContext = this.entry.getKey().getLocalContext();
        List<WebSocketChannel> old = localContext.getWebSocketChannels();
        localContext.setWebSocketChannels(channels);
        return old;
    }

    private BatchContext resumeBatch() {
        Batch batch = (this.batch != null) && (this.batch.getState() != Batch.State.CLOSED) ? this.batch : null;
        return this.manager.getSessionManager().getBatcher().resumeBatch(batch);
    }

    private void closeIfInvalid(HttpServerExchange exchange, Session<LocalSessionContext> session) {
        if (!session.isValid()) {
            // If session was invalidated by a concurrent request, Undertow will not trigger Session.requestDone(...), so we need to close the session here
            try {
                session.close();
            } finally {
                // Ensure close task is run
                this.closeTask.accept(exchange);
            }
        }
    }
}

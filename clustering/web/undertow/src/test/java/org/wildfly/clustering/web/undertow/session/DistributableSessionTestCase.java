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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSessionActivationListener;

import io.undertow.UndertowOptions;
import io.undertow.connector.ByteBufferPool;
import io.undertow.security.api.AuthenticatedSessionManager.AuthenticatedSession;
import io.undertow.security.idm.Account;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.protocol.http.HttpServerConnection;
import io.undertow.server.session.SessionConfig;
import io.undertow.server.session.SessionListener;
import io.undertow.server.session.SessionListener.SessionDestroyedReason;
import io.undertow.server.session.SessionListeners;
import io.undertow.servlet.handlers.security.CachedAuthenticatedSessionHandler;
import io.undertow.websockets.core.WebSocketChannel;
import io.undertow.util.Protocols;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.ArgumentCaptor;
import org.wildfly.clustering.ee.Batch;
import org.wildfly.clustering.ee.BatchContext;
import org.wildfly.clustering.ee.Batcher;
import org.wildfly.clustering.web.session.Session;
import org.wildfly.clustering.web.session.SessionAttributes;
import org.wildfly.clustering.web.session.SessionManager;
import org.wildfly.clustering.web.session.SessionMetaData;
import org.xnio.OptionMap;
import org.xnio.StreamConnection;
import org.xnio.channels.Configurable;
import org.xnio.conduits.ConduitStreamSinkChannel;
import org.xnio.conduits.ConduitStreamSourceChannel;
import org.xnio.conduits.StreamSinkConduit;
import org.xnio.conduits.StreamSourceConduit;

/**
 * Unit test for {@link DistributableSession}.
 *
 * @author Paul Ferraro
 */
public class DistributableSessionTestCase {
    private final UndertowSessionManager manager = mock(UndertowSessionManager.class);
    private final SessionConfig config = mock(SessionConfig.class);
    private final Session<LocalSessionContext> session = mock(Session.class);
    private final Batch batch = mock(Batch.class);
    private final Consumer<HttpServerExchange> closeTask = mock(Consumer.class);

    @Rule
    public ExpectedException exception = ExpectedException.none();

    private final io.undertow.server.session.Session adapter = new DistributableSession(this.manager, this.session, this.config, this.batch, this.closeTask);

    @Test
    public void getId() {
        String id = "id";
        when(this.session.getId()).thenReturn(id);

        String result = this.adapter.getId();

        assertSame(id, result);
    }

    @Test
    public void requestDone() {
        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        HttpServerExchange exchange = new HttpServerExchange(null);

        when(this.session.isValid()).thenReturn(true);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        this.adapter.requestDone(exchange);

        verify(this.session).close();
        verify(this.batch).close();
        verify(context).close();
        verify(this.closeTask).accept(exchange);
    }

    @Test
    public void requestDoneInvalidSession() {
        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        HttpServerExchange exchange = new HttpServerExchange(null);

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.isValid()).thenReturn(false);
        when(this.batch.getState()).thenReturn(Batch.State.CLOSED);

        this.adapter.requestDone(exchange);

        verify(this.session).close();
        verify(this.batch).close();
        verify(context).close();
        verify(this.closeTask).accept(exchange);
    }

    @Test
    public void getCreationTime() {
        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionMetaData metaData = mock(SessionMetaData.class);
        Instant now = Instant.now();

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getMetaData()).thenReturn(metaData);
        when(metaData.getCreationTime()).thenReturn(now);

        long result = this.adapter.getCreationTime();

        assertEquals(now.toEpochMilli(), result);

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getMetaData();

        try {
            this.adapter.getCreationTime();
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void getLastAccessedTime() {
        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionMetaData metaData = mock(SessionMetaData.class);
        Instant now = Instant.now();

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getMetaData()).thenReturn(metaData);
        when(metaData.getLastAccessedTime()).thenReturn(now);

        long result = this.adapter.getLastAccessedTime();

        assertEquals(now.toEpochMilli(), result);

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getMetaData();

        try {
            this.adapter.getLastAccessedTime();
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void getMaxInactiveInterval() {
        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionMetaData metaData = mock(SessionMetaData.class);
        long expected = 3600L;

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getMetaData()).thenReturn(metaData);
        when(metaData.getMaxInactiveInterval()).thenReturn(Duration.ofSeconds(expected));

        long result = this.adapter.getMaxInactiveInterval();

        assertEquals(expected, result);

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getMetaData();

        try {
            this.adapter.getMaxInactiveInterval();
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void setMaxInactiveInterval() {
        int interval = 3600;

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionMetaData metaData = mock(SessionMetaData.class);

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getMetaData()).thenReturn(metaData);

        this.adapter.setMaxInactiveInterval(interval);

        verify(metaData).setMaxInactiveInterval(Duration.ofSeconds(interval));

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getMetaData();

        try {
            this.adapter.setMaxInactiveInterval(interval);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void getAttributeNames() {
        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        Set<String> expected = Collections.singleton("name");

        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.getAttributeNames()).thenReturn(expected);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        Object result = this.adapter.getAttributeNames();

        assertSame(expected, result);

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getAttributes();

        try {
            this.adapter.getAttributeNames();
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void getAttribute() {
        String name = "name";

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        Object expected = new Object();

        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.getAttribute(name)).thenReturn(expected);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        Object result = this.adapter.getAttribute(name);

        assertSame(expected, result);

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getAttributes();

        try {
            this.adapter.getAttribute(name);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void getAuthenticatedSessionAttribute() {
        String name = CachedAuthenticatedSessionHandler.class.getName() + ".AuthenticatedSession";

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        Account account = mock(Account.class);
        AuthenticatedSession auth = new AuthenticatedSession(account, HttpServletRequest.FORM_AUTH);

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.getAttribute(name)).thenReturn(auth);

        AuthenticatedSession result = (AuthenticatedSession) this.adapter.getAttribute(name);

        assertSame(account, result.getAccount());
        assertSame(HttpServletRequest.FORM_AUTH, result.getMechanism());

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        LocalSessionContext localContext = mock(LocalSessionContext.class);
        AuthenticatedSession expected = new AuthenticatedSession(account, HttpServletRequest.BASIC_AUTH);

        when(attributes.getAttribute(name)).thenReturn(null);
        when(this.session.getLocalContext()).thenReturn(localContext);
        when(localContext.getAuthenticatedSession()).thenReturn(expected);

        result = (AuthenticatedSession) this.adapter.getAttribute(name);

        assertSame(expected, result);

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getAttributes();

        try {
            this.adapter.getAttribute(name);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void getWebSocketChannelsSessionAttribute() {
        this.getLocalContextSessionAttribute(DistributableSession.WEB_SOCKET_CHANNELS_ATTRIBUTE);
    }

    private void getLocalContextSessionAttribute(String name) {
        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        LocalSessionContext localContext = mock(LocalSessionContext.class);
        List<WebSocketChannel> expected = Collections.singletonList(mock(WebSocketChannel.class));

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getLocalContext()).thenReturn(localContext);
        when(localContext.getWebSocketChannels()).thenReturn(expected);

        @SuppressWarnings("unchecked")
        List<WebSocketChannel> result = (List<WebSocketChannel>) this.adapter.getAttribute(name);

        assertSame(expected, result);

        verify(context).close();
    }

    @Test
    public void setAttribute() {
        String name = "name";
        Integer value = Integer.valueOf(1);

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);

        Object expected = new Object();

        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.setAttribute(name, value)).thenReturn(expected);
        when(this.manager.getSessionListeners()).thenReturn(listeners);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        Object result = this.adapter.setAttribute(name, value);

        assertSame(expected, result);

        verify(listener, never()).attributeAdded(this.adapter, name, value);
        verify(listener).attributeUpdated(this.adapter, name, value, expected);
        verify(listener, never()).attributeRemoved(same(this.adapter), same(name), any());
        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getAttributes();

        try {
            this.adapter.setAttribute(name, value);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void setNewAttribute() {
        String name = "name";
        Integer value = Integer.valueOf(1);

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);
        Object expected = null;

        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.setAttribute(name, value)).thenReturn(expected);
        when(this.manager.getSessionListeners()).thenReturn(listeners);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        Object result = this.adapter.setAttribute(name, value);

        assertSame(expected, result);

        verify(listener).attributeAdded(this.adapter, name, value);
        verify(listener, never()).attributeUpdated(same(this.adapter), same(name), same(value), any());
        verify(listener, never()).attributeRemoved(same(this.adapter), same(name), any());
        verify(context).close();
    }

    @Test
    public void setNullAttribute() {
        String name = "name";
        Object value = null;

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);
        Object expected = new Object();

        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.removeAttribute(name)).thenReturn(expected);
        when(this.manager.getSessionListeners()).thenReturn(listeners);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        Object result = this.adapter.setAttribute(name, value);

        assertSame(expected, result);

        verify(listener, never()).attributeAdded(this.adapter, name, value);
        verify(listener, never()).attributeUpdated(same(this.adapter), same(name), same(value), any());
        verify(listener).attributeRemoved(this.adapter, name, expected);
        verify(context).close();
    }

    @Test
    public void setSameAttribute() {
        String name = "name";
        Integer value = Integer.valueOf(1);

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);
        Object expected = value;

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.setAttribute(name, value)).thenReturn(expected);
        when(this.manager.getSessionListeners()).thenReturn(listeners);

        Object result = this.adapter.setAttribute(name, value);

        assertSame(expected, result);

        verify(listener, never()).attributeAdded(this.adapter, name, value);
        verify(listener, never()).attributeUpdated(same(this.adapter), same(name), same(value), any());
        verify(listener, never()).attributeRemoved(same(this.adapter), same(name), any());
        verify(context).close();
    }

    @Test
    public void setAuthenticatedSessionAttribute() {
        String name = CachedAuthenticatedSessionHandler.class.getName() + ".AuthenticatedSession";
        Account account = mock(Account.class);
        AuthenticatedSession auth = new AuthenticatedSession(account, HttpServletRequest.FORM_AUTH);

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        Account oldAccount = mock(Account.class);
        AuthenticatedSession oldAuth = new AuthenticatedSession(oldAccount, HttpServletRequest.FORM_AUTH);
        ArgumentCaptor<AuthenticatedSession> capturedAuth = ArgumentCaptor.forClass(AuthenticatedSession.class);

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.setAttribute(same(name), capturedAuth.capture())).thenReturn(oldAuth);

        AuthenticatedSession result = (AuthenticatedSession) this.adapter.setAttribute(name, auth);

        assertSame(auth.getAccount(), capturedAuth.getValue().getAccount());
        assertSame(auth.getMechanism(), capturedAuth.getValue().getMechanism());

        assertSame(oldAccount, result.getAccount());
        assertSame(HttpServletRequest.FORM_AUTH, result.getMechanism());

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context, attributes);

        capturedAuth = ArgumentCaptor.forClass(AuthenticatedSession.class);

        when(attributes.setAttribute(same(name), capturedAuth.capture())).thenReturn(null);

        result = (AuthenticatedSession) this.adapter.setAttribute(name, auth);

        assertSame(auth.getAccount(), capturedAuth.getValue().getAccount());
        assertSame(auth.getMechanism(), capturedAuth.getValue().getMechanism());

        assertNull(result);

        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context, attributes);

        auth = new AuthenticatedSession(account, HttpServletRequest.BASIC_AUTH);
        AuthenticatedSession oldSession = new AuthenticatedSession(oldAccount, HttpServletRequest.BASIC_AUTH);

        LocalSessionContext localContext = mock(LocalSessionContext.class);

        when(this.session.getLocalContext()).thenReturn(localContext);
        when(localContext.getAuthenticatedSession()).thenReturn(oldSession);

        result = (AuthenticatedSession) this.adapter.setAttribute(name, auth);

        verify(localContext).setAuthenticatedSession(same(auth));
        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getAttributes();

        try {
            this.adapter.setAttribute(name, oldAuth);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    public void setWebSocketChannelsSessionAttribute() {
        String name = DistributableSession.WEB_SOCKET_CHANNELS_ATTRIBUTE;
        List<WebSocketChannel> channels = Collections.singletonList(mock(WebSocketChannel.class));
        List<WebSocketChannel> oldChannels = Collections.singletonList(mock(WebSocketChannel.class));

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        LocalSessionContext localContext = mock(LocalSessionContext.class);

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getLocalContext()).thenReturn(localContext);
        when(localContext.getWebSocketChannels()).thenReturn(oldChannels);

        List<WebSocketChannel> result = (List<WebSocketChannel>) this.adapter.setAttribute(name, channels);

        assertSame(oldChannels, result);

        verify(localContext).setWebSocketChannels(same(channels));
        verify(context).close();
    }

    @Test
    public void removeAttribute() {
        String name = "name";

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);
        Object expected = new Object();

        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.removeAttribute(name)).thenReturn(expected);
        when(this.manager.getSessionListeners()).thenReturn(listeners);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        Object result = this.adapter.removeAttribute(name);

        assertSame(expected, result);

        verify(listener).attributeRemoved(this.adapter, name, expected);
        verify(context).close();
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getAttributes();

        try {
            this.adapter.removeAttribute(name);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void removeNonExistingAttribute() {
        String name = "name";

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);

        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.removeAttribute(name)).thenReturn(null);
        when(this.manager.getSessionListeners()).thenReturn(listeners);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);

        Object result = this.adapter.removeAttribute(name);

        assertNull(result);

        verify(listener, never()).attributeRemoved(same(this.adapter), same(name), any());
        verify(context).close();
    }

    @Test
    public void removeAuthenticatedSessionAttribute() {
        String name = CachedAuthenticatedSessionHandler.class.getName() + ".AuthenticatedSession";

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        Account oldAccount = mock(Account.class);
        AuthenticatedSession oldAuth = new AuthenticatedSession(oldAccount, HttpServletRequest.FORM_AUTH);

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.removeAttribute(same(name))).thenReturn(oldAuth);

        AuthenticatedSession result = (AuthenticatedSession) this.adapter.removeAttribute(name);

        assertSame(oldAccount, result.getAccount());
        assertSame(HttpServletRequest.FORM_AUTH, result.getMechanism());

        verify(context).close();

        reset(context, attributes);

        LocalSessionContext localContext = mock(LocalSessionContext.class);
        AuthenticatedSession oldSession = new AuthenticatedSession(oldAccount, HttpServletRequest.BASIC_AUTH);
        when(localContext.getAuthenticatedSession()).thenReturn(oldSession);
        when(attributes.removeAttribute(same(name))).thenReturn(null);
        when(this.session.getLocalContext()).thenReturn(localContext);

        result = (AuthenticatedSession) this.adapter.removeAttribute(name);

        assertSame(result, oldSession);
        verify(localContext).setAuthenticatedSession(null);
        verify(context).close();

        reset(localContext, context, attributes);

        when(localContext.getAuthenticatedSession()).thenReturn(null);

        result = (AuthenticatedSession) this.adapter.removeAttribute(name);

        assertNull(result);

        verify(context).close();
        verify(localContext).setAuthenticatedSession(null);
        verify(this.session, never()).close();
        verify(this.closeTask, never()).accept(null);

        reset(context);

        doThrow(IllegalStateException.class).when(this.session).getAttributes();

        try {
            this.adapter.removeAttribute(name);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(null);
        }
    }

    @Test
    public void removeWebSocketChannelsSessionAttribute() {
        String name = DistributableSession.WEB_SOCKET_CHANNELS_ATTRIBUTE;
        List<WebSocketChannel> oldValue = Collections.emptyList();

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        LocalSessionContext localContext = mock(LocalSessionContext.class);

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.session.getLocalContext()).thenReturn(localContext);
        when(localContext.getWebSocketChannels()).thenReturn(oldValue);

        Object result = this.adapter.removeAttribute(name);

        assertSame(oldValue, result);

        verify(localContext).setWebSocketChannels(null);
        verify(attributes, never()).removeAttribute(name);
        verify(context).close();
    }

    @Test
    public void invalidate() {
        HttpServerExchange exchange = new HttpServerExchange(null);

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        SessionListener listener = mock(SessionListener.class);
        SessionAttributes attributes = mock(SessionAttributes.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);
        String sessionId = "session";
        String attributeName = "attribute";
        Object attributeValue = mock(HttpSessionActivationListener.class);

        when(this.manager.getSessionListeners()).thenReturn(listeners);
        when(this.session.isValid()).thenReturn(true);
        when(this.session.getId()).thenReturn(sessionId);
        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(this.batch.getState()).thenReturn(Batch.State.ACTIVE);
        when(this.session.getAttributes()).thenReturn(attributes);
        when(attributes.getAttributeNames()).thenReturn(Collections.singleton("attribute"));
        when(attributes.getAttribute(attributeName)).thenReturn(attributeValue);

        this.adapter.invalidate(exchange);

        verify(this.session).invalidate();
        verify(this.config).clearSession(exchange, sessionId);
        verify(listener).sessionDestroyed(this.adapter, exchange, SessionDestroyedReason.INVALIDATED);
        verify(listener).attributeRemoved(this.adapter, attributeName, attributeValue);
        verify(this.batch).close();
        verify(context).close();
        verify(this.closeTask).accept(exchange);

        reset(context, this.session, this.closeTask);

        doThrow(IllegalStateException.class).when(this.session).invalidate();

        try {
            this.adapter.invalidate(exchange);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(this.session).close();
            verify(this.closeTask).accept(exchange);
        }
    }

    @Test
    public void getSessionManager() {
        assertSame(this.manager, this.adapter.getSessionManager());
    }

    @Test
    public void changeSessionId() {
        HttpServerExchange exchange = new HttpServerExchange(null);

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        Session<LocalSessionContext> session = mock(Session.class);
        SessionAttributes oldAttributes = mock(SessionAttributes.class);
        SessionAttributes newAttributes = mock(SessionAttributes.class);
        SessionMetaData oldMetaData = mock(SessionMetaData.class);
        SessionMetaData newMetaData = mock(SessionMetaData.class);
        LocalSessionContext oldContext = mock(LocalSessionContext.class);
        LocalSessionContext newContext = mock(LocalSessionContext.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);
        String oldSessionId = "old";
        String newSessionId = "new";
        String name = "name";
        Object value = new Object();
        Instant now = Instant.now();
        Duration interval = Duration.ofSeconds(10L);
        AuthenticatedSession authenticatedSession = new AuthenticatedSession(null, null);
        List<WebSocketChannel> webSocketChannels = Collections.emptyList();

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(manager.createIdentifier()).thenReturn(newSessionId);
        when(manager.createSession(newSessionId)).thenReturn(session);
        when(this.session.getAttributes()).thenReturn(oldAttributes);
        when(this.session.getMetaData()).thenReturn(oldMetaData);
        when(session.getAttributes()).thenReturn(newAttributes);
        when(session.getMetaData()).thenReturn(newMetaData);
        when(oldAttributes.getAttributeNames()).thenReturn(Collections.singleton(name));
        when(oldAttributes.getAttribute(name)).thenReturn(value);
        when(newAttributes.setAttribute(name, value)).thenReturn(null);
        when(oldMetaData.getLastAccessedTime()).thenReturn(now);
        when(oldMetaData.getMaxInactiveInterval()).thenReturn(interval);
        when(this.session.getId()).thenReturn(oldSessionId);
        when(session.getId()).thenReturn(newSessionId);
        when(this.session.getLocalContext()).thenReturn(oldContext);
        when(session.getLocalContext()).thenReturn(newContext);
        when(oldContext.getAuthenticatedSession()).thenReturn(authenticatedSession);
        when(oldContext.getWebSocketChannels()).thenReturn(webSocketChannels);
        when(this.manager.getSessionListeners()).thenReturn(listeners);

        String result = this.adapter.changeSessionId(exchange, this.config);

        assertSame(newSessionId, result);

        verify(newMetaData).setLastAccessedTime(now);
        verify(newMetaData).setMaxInactiveInterval(interval);
        verify(this.config).setSessionId(exchange, newSessionId);
        verify(newContext).setAuthenticatedSession(same(authenticatedSession));
        verify(newContext).setWebSocketChannels(same(webSocketChannels));
        verify(listener).sessionIdChanged(this.adapter, oldSessionId);

        verify(this.session).invalidate();
        verify(session, never()).invalidate();
        verify(context).close();
    }

    @Test
    public void changeSessionIdConcurrentInvalidate() {
        HttpServerExchange exchange = new HttpServerExchange(null);

        SessionManager<LocalSessionContext, Batch> manager = mock(SessionManager.class);
        Batcher<Batch> batcher = mock(Batcher.class);
        BatchContext context = mock(BatchContext.class);
        Session<LocalSessionContext> newSession = mock(Session.class);
        SessionAttributes oldAttributes = mock(SessionAttributes.class);
        SessionAttributes newAttributes = mock(SessionAttributes.class);
        SessionMetaData oldMetaData = mock(SessionMetaData.class);
        SessionMetaData newMetaData = mock(SessionMetaData.class);
        LocalSessionContext oldContext = mock(LocalSessionContext.class);
        LocalSessionContext newContext = mock(LocalSessionContext.class);
        SessionListener listener = mock(SessionListener.class);
        SessionListeners listeners = new SessionListeners();
        listeners.addSessionListener(listener);
        String oldSessionId = "old";
        String newSessionId = "new";
        String name = "name";
        Object value = new Object();
        Instant now = Instant.now();
        Duration interval = Duration.ofSeconds(10L);
        AuthenticatedSession authenticatedSession = new AuthenticatedSession(null, null);
        List<WebSocketChannel> webSocketChannels = Collections.emptyList();

        when(this.manager.getSessionManager()).thenReturn(manager);
        when(manager.getBatcher()).thenReturn(batcher);
        when(batcher.resumeBatch(this.batch)).thenReturn(context);
        when(manager.createIdentifier()).thenReturn(newSessionId);
        when(manager.createSession(newSessionId)).thenReturn(newSession);
        when(this.session.getAttributes()).thenReturn(oldAttributes);
        when(this.session.getMetaData()).thenReturn(oldMetaData);
        when(newSession.getAttributes()).thenReturn(newAttributes);
        when(newSession.getMetaData()).thenReturn(newMetaData);
        when(oldAttributes.getAttributeNames()).thenReturn(Collections.singleton(name));
        when(oldAttributes.getAttribute(name)).thenReturn(value);
        when(newAttributes.setAttribute(name, value)).thenReturn(null);
        when(oldMetaData.getLastAccessedTime()).thenReturn(now);
        when(oldMetaData.getMaxInactiveInterval()).thenReturn(interval);
        when(this.session.getId()).thenReturn(oldSessionId);
        when(newSession.getId()).thenReturn(newSessionId);
        when(this.session.getLocalContext()).thenReturn(oldContext);
        when(newSession.getLocalContext()).thenReturn(newContext);
        when(oldContext.getAuthenticatedSession()).thenReturn(authenticatedSession);
        when(oldContext.getWebSocketChannels()).thenReturn(webSocketChannels);

        doThrow(IllegalStateException.class).when(this.session).invalidate();

        try {
            this.adapter.changeSessionId(exchange, this.config);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            verify(context).close();
            verify(listener, never()).sessionIdChanged(this.adapter, oldSessionId);
            verify(this.session).close();
            verify(this.closeTask).accept(exchange);
            verify(newSession).invalidate();
        }
    }

    public void changeSessionIdResponseCommitted() {
        SessionMetaData metaData = mock(SessionMetaData.class);
        when(this.session.getMetaData()).thenReturn(metaData);
        when(metaData.isNew()).thenReturn(false);
        when(this.session.isValid()).thenReturn(true);

        io.undertow.server.session.Session session = new DistributableSession(this.manager, this.session, this.config, this.batch, this.closeTask);

        // Ugh - all this, just to get HttpServerExchange.isResponseStarted() to return true
        Configurable configurable = mock(Configurable.class);
        StreamSourceConduit sourceConduit = mock(StreamSourceConduit.class);
        ConduitStreamSourceChannel sourceChannel = new ConduitStreamSourceChannel(configurable, sourceConduit);
        StreamSinkConduit sinkConduit = mock(StreamSinkConduit.class);
        ConduitStreamSinkChannel sinkChannel = new ConduitStreamSinkChannel(configurable, sinkConduit);
        StreamConnection stream = mock(StreamConnection.class);

        when(stream.getSourceChannel()).thenReturn(sourceChannel);
        when(stream.getSinkChannel()).thenReturn(sinkChannel);

        ByteBufferPool bufferPool = mock(ByteBufferPool.class);
        HttpHandler handler = mock(HttpHandler.class);
        HttpServerConnection connection = new HttpServerConnection(stream, bufferPool, handler, OptionMap.create(UndertowOptions.ALWAYS_SET_DATE, false), 0, null);
        HttpServerExchange exchange = new HttpServerExchange(connection);
        exchange.setProtocol(Protocols.HTTP_1_1);
        exchange.getResponseChannel();

        SessionConfig config = mock(SessionConfig.class);

        try {
            session.changeSessionId(exchange, config);
            fail("ISE expected");
        } catch (IllegalStateException e) {
            // Expected
        }
    }
}

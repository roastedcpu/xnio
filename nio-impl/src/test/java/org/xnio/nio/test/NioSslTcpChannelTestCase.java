/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013 Red Hat, Inc., and individual contributors
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

package org.xnio.nio.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;

import org.junit.BeforeClass;
import org.xnio.ChannelListener;
import org.xnio.IoFuture;
import org.xnio.OptionMap;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import org.xnio.channels.AcceptingChannel;
import org.xnio.channels.BoundChannel;
import org.xnio.channels.ConnectedSslStreamChannel;
import org.xnio.ssl.XnioSsl;

/**
 * Test for {@code XnioSsl} channels.
 * 
 * @author <a href="mailto:frainone@redhat.com">Flavia Rainone</a>
 *
 */
public class NioSslTcpChannelTestCase extends AbstractNioSslTcpTest<ConnectedSslStreamChannel, ConnectedSslStreamChannel, ConnectedSslStreamChannel> {

    private XnioSsl xnioSsl;
    private static final String KEY_STORE_PROPERTY = "javax.net.ssl.keyStore";
    private static final String KEY_STORE_PASSWORD_PROPERTY = "javax.net.ssl.keyStorePassword";
    private static final String TRUST_STORE_PROPERTY = "javax.net.ssl.trustStore";
    private static final String TRUST_STORE_PASSWORD_PROPERTY = "javax.net.ssl.trustStorePassword";
    private static final String DEFAULT_KEY_STORE = "keystore.jks";
    private static final String DEFAULT_KEY_STORE_PASSWORD = "jboss-remoting-test";

    @BeforeClass
    public static void setKeyStoreAndTrustStore() {
        final URL storePath = NioSslTcpChannelTestCase.class.getClassLoader().getResource(DEFAULT_KEY_STORE);
        if (System.getProperty(KEY_STORE_PROPERTY) == null) {
            System.setProperty(KEY_STORE_PROPERTY, storePath.getFile());
        }
        if (System.getProperty(KEY_STORE_PASSWORD_PROPERTY) == null) {
            System.setProperty(KEY_STORE_PASSWORD_PROPERTY, DEFAULT_KEY_STORE_PASSWORD);
        }
        if (System.getProperty(TRUST_STORE_PROPERTY) == null) {
            System.setProperty(TRUST_STORE_PROPERTY, storePath.getFile());
        }
        if (System.getProperty(TRUST_STORE_PASSWORD_PROPERTY) == null) {
            System.setProperty(TRUST_STORE_PASSWORD_PROPERTY, DEFAULT_KEY_STORE_PASSWORD);
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected AcceptingChannel<? extends ConnectedSslStreamChannel> createServer(XnioWorker worker, InetSocketAddress address,
            ChannelListener<AcceptingChannel<ConnectedSslStreamChannel>> openListener, OptionMap optionMap) throws IOException {
        return xnioSsl.createSslTcpServer(worker, address,  openListener,  optionMap);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected IoFuture<? extends ConnectedSslStreamChannel> connect(XnioWorker worker, InetSocketAddress address,
            ChannelListener<ConnectedSslStreamChannel> openListener, ChannelListener<? super BoundChannel> bindListener,
            OptionMap optionMap) {
        return xnioSsl.connectSsl(worker, address,  openListener, bindListener, optionMap);
    }

    @Override
    protected void setReadListener(ConnectedSslStreamChannel channel, ChannelListener<ConnectedSslStreamChannel> readListener) {
        channel.getReadSetter().set(readListener);
    }

    @Override
    protected void setWriteListener(ConnectedSslStreamChannel channel, ChannelListener<ConnectedSslStreamChannel> writeListener) {
        channel.getWriteSetter().set(writeListener);
    }

    @Override
    protected void resumeReads(ConnectedSslStreamChannel channel) {
        channel.resumeReads();
    }

    @Override
    protected void resumeWrites(ConnectedSslStreamChannel channel) {
        channel.resumeWrites();
    }

    @Override
    protected void shutdownReads(ConnectedSslStreamChannel channel) throws IOException {
        channel.shutdownReads();
    }

    @Override
    protected void shutdownWrites(ConnectedSslStreamChannel channel) throws IOException {
        channel.shutdownWrites();
    }

    @Override
    protected void doConnectionTest(final Runnable body, final ChannelListener<? super ConnectedSslStreamChannel> clientHandler, final ChannelListener<? super ConnectedSslStreamChannel> serverHandler) throws Exception {
        xnioSsl = Xnio.getInstance("nio", NioSslTcpChannelTestCase.class.getClassLoader()).getSslProvider(OptionMap.EMPTY);
        super.doConnectionTest(body,  clientHandler, serverHandler);
    }
}
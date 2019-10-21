/*
 * JBoss, Home of Professional Open Source. Copyright 2019, Red Hat, Inc., and
 * individual contributors as indicated by the @author tags. See the
 * copyright.txt file in the distribution for a full listing of individual
 * contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package org.wildfly.extension.microprofile.openapi.deployment;

import java.io.IOException;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jboss.msc.Service;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StopContext;
import org.wildfly.extension.microprofile.openapi.MicroProfileOpenAPIExtension;
import org.wildfly.extension.microprofile.openapi.logging.MicroProfileOpenAPILogger;
import org.wildfly.extension.undertow.Host;
import org.wildfly.extension.undertow.UndertowService;

import io.smallrye.openapi.api.OpenApiDocument;
import io.smallrye.openapi.runtime.io.OpenApiSerializer;
import io.smallrye.openapi.runtime.io.OpenApiSerializer.Format;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.util.HeaderMap;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.util.StatusCodes;

/**
 * @author Michael Edgar
 */
public class OpenAPIContextService implements Service {

    static final String OPENAPI_CONTEXT_SERVICE_SC_ATTR = OpenAPIContextService.class.getName();

    private static final String OAI = "/openapi";
    private static final String ALLOWED_METHODS = "GET, HEAD, OPTIONS";
    private static final String QUERY_PARAM_FORMAT = "format";

    private final Supplier<UndertowService> undertowService;
    private final Supplier<DeploymentInfo> deploymentInfoService;
    private final Map<Format, String> cachedModels = new ConcurrentHashMap<>();
    private final OpenAPIDocumentBuilder documentBuilder = new OpenAPIDocumentBuilder.create();
    private OpenAPI document = null;

    OpenAPIContextService(Supplier<UndertowService> undertowService, Supplier<DeploymentInfo> deploymentInfoService) {
        this.undertowService = undertowService;
        this.deploymentInfoService = deploymentInfoService;
    }

    @Override
    public void start(StartContext context) {
        runWithHost(host -> {
            host.registerHandler(OAI, new OpenAPIHandler());
            MicroProfileOpenAPILogger.LOGGER.endpointRegistered(host.getName());
        });
    }

    @Override
    public void stop(StopContext context) {
        runWithHost(host -> {
            host.unregisterHandler(OAI);
            MicroProfileOpenAPILogger.LOGGER.endpointUnregistered(host.getName());
        });
    }

    private void runWithHost(Consumer<Host> function) {
        final DeploymentInfo info = deploymentInfoService.get();
        final String hostName = info.getHostName();

        undertowService.get()
                       .getServers()
                       .stream()
                       .flatMap(server -> server.getHosts().stream())
                       .filter(host -> hostName.equals(host.getName()))
                       .findFirst()
                       .ifPresent(function);
    }

    private class OpenAPIHandler implements HttpHandler {
        @Override
        public void handleRequest(HttpServerExchange exchange) {
            if (OAI.equalsIgnoreCase(exchange.getRequestPath()) && OpenApiDocument.INSTANCE.isSet()) {
                if (exchange.getRequestMethod().equals(Methods.GET)) {
                    sendOai(exchange);
                } else if (exchange.getRequestMethod().equals(Methods.OPTIONS))  {
                    sendPreflight(exchange);
                } else {
                    exchange.setStatusCode(StatusCodes.NOT_FOUND);
                }
            } else {
                exchange.setStatusCode(StatusCodes.NOT_FOUND);
            }
        }

        private void sendPreflight(HttpServerExchange exchange) {
            addCorsResponseHeaders(exchange);
            exchange.getResponseSender().send(ALLOWED_METHODS);
        }


        private void sendOai(HttpServerExchange exchange) {
            String accept = exchange.getRequestHeaders().getFirst(Headers.ACCEPT);
            Deque<String> formatQueryParams = exchange.getQueryParameters().get(QUERY_PARAM_FORMAT);
            String formatParam = null;
            if (formatQueryParams != null) {
                formatParam = formatQueryParams.getFirst();
            }

            // Default content type is YAML
            Format format = Format.YAML;

            // Check Accept, then query parameter "format" for JSON; else use YAML.
            if ((accept != null && accept.contains(Format.JSON.getMimeType()))
                    || Format.JSON.name().equalsIgnoreCase(formatParam)
                    || Format.JSON.getMimeType().equalsIgnoreCase(formatParam)) {
                format = Format.JSON;
            }

            String oai = getCachedOaiString(format);

            addCorsResponseHeaders(exchange);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, format.getMimeType());
            exchange.getResponseSender().send(oai);
        }

        private String getCachedOaiString(Format format) {
            return cachedModels.computeIfAbsent(format, this::getModel);
        }

        private String getModel(Format format) {
            try {
                return OpenApiSerializer.serialize(OpenApiDocument.INSTANCE.get(), format);
            } catch (IOException e) {
                MicroProfileOpenAPILogger.LOGGER.serializationException(format, e);
                return null;
            }
        }

        private void addCorsResponseHeaders(HttpServerExchange exchange) {
            HeaderMap headerMap = exchange.getResponseHeaders();
            headerMap.put(new HttpString("Access-Control-Allow-Origin"), "*");
            headerMap.put(new HttpString("Access-Control-Allow-Credentials"), "true");
            headerMap.put(new HttpString("Access-Control-Allow-Methods"), ALLOWED_METHODS);
            headerMap.put(new HttpString("Access-Control-Allow-Headers"), "Content-Type, Authorization");
            headerMap.put(new HttpString("Access-Control-Max-Age"), "86400");
        }
    }
}

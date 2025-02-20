/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.eventmesh.runtime.admin.handler;

import org.apache.eventmesh.common.Constants;
import org.apache.eventmesh.common.utils.JsonUtils;
import org.apache.eventmesh.runtime.admin.controller.HttpHandlerManager;
import org.apache.eventmesh.runtime.admin.response.Error;
import org.apache.eventmesh.runtime.admin.response.GetConfigurationResponse;
import org.apache.eventmesh.runtime.common.EventHttpHandler;
import org.apache.eventmesh.runtime.configuration.EventMeshGrpcConfiguration;
import org.apache.eventmesh.runtime.configuration.EventMeshHTTPConfiguration;
import org.apache.eventmesh.runtime.configuration.EventMeshTCPConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;


import com.sun.net.httpserver.HttpExchange;

import lombok.extern.slf4j.Slf4j;

/**
 * The config handler
 */
@Slf4j
@EventHttpHandler(path = "/configuration")
public class ConfigurationHandler extends AbstractHttpHandler {

    private final EventMeshTCPConfiguration eventMeshTCPConfiguration;
    private final EventMeshHTTPConfiguration eventMeshHTTPConfiguration;
    private final EventMeshGrpcConfiguration eventMeshGrpcConfiguration;

    public ConfigurationHandler(
        EventMeshTCPConfiguration eventMeshTCPConfiguration,
        EventMeshHTTPConfiguration eventMeshHTTPConfiguration,
        EventMeshGrpcConfiguration eventMeshGrpcConfiguration,
        HttpHandlerManager httpHandlerManager
    ) {
        super(httpHandlerManager);
        this.eventMeshTCPConfiguration = eventMeshTCPConfiguration;
        this.eventMeshHTTPConfiguration = eventMeshHTTPConfiguration;
        this.eventMeshGrpcConfiguration = eventMeshGrpcConfiguration;
    }

    /**
     * OPTIONS /configuration
     */
    void preflight(HttpExchange httpExchange) throws IOException {
        httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        httpExchange.getResponseHeaders().add("Access-Control-Allow-Methods", "*");
        httpExchange.getResponseHeaders().add("Access-Control-Allow-Headers", "*");
        httpExchange.getResponseHeaders().add("Access-Control-Max-Age", "86400");
        httpExchange.sendResponseHeaders(200, 0);
        OutputStream out = httpExchange.getResponseBody();
        out.close();
    }

    /**
     * GET /config Return a response that contains the EventMesh configuration
     */
    void get(HttpExchange httpExchange) throws IOException {
        httpExchange.getResponseHeaders().add("Content-Type", "application/json");
        httpExchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        try (OutputStream out = httpExchange.getResponseBody()) {
            try {
                GetConfigurationResponse getConfigurationResponse = new GetConfigurationResponse(
                    eventMeshTCPConfiguration.getSysID(),
                    eventMeshTCPConfiguration.getNamesrvAddr(),
                    eventMeshTCPConfiguration.getEventMeshEnv(),
                    eventMeshTCPConfiguration.getEventMeshIDC(),
                    eventMeshTCPConfiguration.getEventMeshCluster(),
                    eventMeshTCPConfiguration.getEventMeshServerIp(),
                    eventMeshTCPConfiguration.getEventMeshName(),
                    eventMeshTCPConfiguration.getEventMeshWebhookOrigin(),
                    eventMeshTCPConfiguration.isEventMeshServerSecurityEnable(),
                    eventMeshTCPConfiguration.isEventMeshServerRegistryEnable(),
                    // TCP Configuration
                    eventMeshTCPConfiguration.getEventMeshTcpServerPort(),
                    // HTTP Configuration
                    eventMeshHTTPConfiguration.getHttpServerPort(),
                    eventMeshHTTPConfiguration.isEventMeshServerUseTls(),
                    // gRPC Configuration
                    eventMeshGrpcConfiguration.getGrpcServerPort(),
                    eventMeshGrpcConfiguration.isEventMeshServerUseTls()
                );

                String result = JsonUtils.toJSONString(getConfigurationResponse);
                httpExchange.sendResponseHeaders(200, result.getBytes(Constants.DEFAULT_CHARSET).length);
                out.write(result.getBytes(Constants.DEFAULT_CHARSET));
            } catch (Exception e) {
                StringWriter writer = new StringWriter();
                PrintWriter printWriter = new PrintWriter(writer);
                e.printStackTrace(printWriter);
                printWriter.flush();
                String stackTrace = writer.toString();

                Error error = new Error(e.toString(), stackTrace);
                String result = JsonUtils.toJSONString(error);
                httpExchange.sendResponseHeaders(500, result.getBytes(Constants.DEFAULT_CHARSET).length);
                out.write(result.getBytes(Constants.DEFAULT_CHARSET));
            }
        }
    }

    @Override
    public void handle(HttpExchange httpExchange) throws IOException {
        if (httpExchange.getRequestMethod().equals("OPTIONS")) {
            preflight(httpExchange);
        }
        if (httpExchange.getRequestMethod().equals("GET")) {
            get(httpExchange);
        }
    }
}

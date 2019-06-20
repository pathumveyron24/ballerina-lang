/*
 *  Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 *  WSO2 Inc. licenses this file to you under the Apache License,
 *  Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 *
 */

package org.ballerinalang.net.http;

import org.ballerinalang.connector.api.Annotation;
import org.ballerinalang.connector.api.BLangConnectorSPIUtil;
import org.ballerinalang.connector.api.Service;
import org.ballerinalang.connector.api.Struct;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.util.codegen.ProgramFile;
import org.ballerinalang.util.exceptions.BallerinaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.ballerinalang.net.http.HttpConstants.DEFAULT_HOST;

/**
 * This services registry holds all the services of HTTP + WebSocket. This is a singleton class where all HTTP +
 * WebSocket Dispatchers can access.
 *
 * @since 0.8
 */
public class BHTTPServicesRegistry {

    private static final Logger logger = LoggerFactory.getLogger(HTTPServicesRegistry.class);

    protected Map<String, ServicesMapHolder> servicesMapByHost = new ConcurrentHashMap<>();
    protected Map<String, BHttpService> servicesByBasePath;
    protected List<String> sortedServiceURIs;
    private final BWebSocketServicesRegistry webSocketServicesRegistry;

    public BHTTPServicesRegistry(BWebSocketServicesRegistry webSocketServicesRegistry) {
        this.webSocketServicesRegistry = webSocketServicesRegistry;
    }

    /**
     * Get ServiceInfo instance for given interface and base path.
     *
     * @param basepath basePath of the service.
     * @return the {@link BHttpService} instance if exist else null
     */
    public BHttpService getServiceInfo(String basepath) {
        return servicesByBasePath.get(basepath);
    }

    /**
     * Get ServicesMapHolder for given host name.
     *
     * @param hostName of the service
     * @return the servicesMapHolder if exists else null
     */
    public ServicesMapHolder getServicesMapHolder(String hostName) {
        return servicesMapByHost.get(hostName);
    }

    /**
     * Get Services map for given host name.
     *
     * @param hostName of the service
     * @return the serviceHost map if exists else null
     */
    public Map<String, BHttpService> getServicesByHost(String hostName) {
        return servicesMapByHost.get(hostName).servicesByBasePath;
    }

    /**
     * Get sortedServiceURIs list for given host name.
     *
     * @param hostName of the service
     * @return the sorted basePath list if exists else null
     */
    public List<String> getSortedServiceURIsByHost(String hostName) {
        return servicesMapByHost.get(hostName).sortedServiceURIs;
    }

    /**
     * Register a service into the map.
     *
     * @param service requested serviceInfo to be registered.
     */
    public void registerService(Service service) {
        List<BHttpService> httpServices = BHttpService.buildHttpService(service);

        for (BHttpService httpService : httpServices) {
            String hostName = httpService.getHostName();
            if (servicesMapByHost.get(hostName) == null) {
                servicesByBasePath = new ConcurrentHashMap<>();
                sortedServiceURIs = new CopyOnWriteArrayList<>();
                servicesMapByHost.put(hostName, new ServicesMapHolder(servicesByBasePath, sortedServiceURIs));
            } else {
                servicesByBasePath = getServicesByHost(hostName);
                sortedServiceURIs = getSortedServiceURIsByHost(hostName);
            }

            String basePath = httpService.getBasePath();
            if (servicesByBasePath.containsKey(basePath)) {
                String errorMessage = hostName.equals(DEFAULT_HOST) ? "'" : "' under host name : '" + hostName + "'";
                throw new BallerinaException("Service registration failed: two services have the same basePath : '" +
                                                     basePath + errorMessage);
            }
            servicesByBasePath.put(basePath, httpService);
            String errLog = String.format("Service deployed : %s with context %s", service.getName(), basePath);
            logger.info(errLog);

            //basePath will get cached after registering service
            sortedServiceURIs.add(basePath);
            sortedServiceURIs.sort((basePath1, basePath2) -> basePath2.length() - basePath1.length());
            registerUpgradableWebSocketService(httpService);
        }
    }

    private void registerUpgradableWebSocketService(BHttpService httpService) {
        httpService.getUpgradeToWebSocketResources().forEach(upgradeToWebSocketResource -> {
            ProgramFile programFile = BWebSocketUtil.getProgramFile(upgradeToWebSocketResource.getBalResource());
            Annotation resourceConfigAnnotation =
                    BHttpUtil.getResourceConfigAnnotation(upgradeToWebSocketResource.getBalResource(),
                                                         HttpConstants.HTTP_PACKAGE_PATH);
            if (resourceConfigAnnotation == null) {
                throw new BallerinaException("Cannot register WebSocket service without resource config " +
                                                     "annotation in resource " + upgradeToWebSocketResource.getName());
            }
            Struct webSocketConfig =
                    resourceConfigAnnotation.getValue().getStructField(HttpConstants.ANN_CONFIG_ATTR_WEBSOCKET_UPGRADE);
            BMap serviceField =
                    (BMap) webSocketConfig.getServiceField(WebSocketConstants.WEBSOCKET_UPGRADE_SERVICE_CONFIG);
            Service webSocketTypeService = BLangConnectorSPIUtil.getService(programFile, serviceField);
            BWebSocketService webSocketService = new BWebSocketService(sanitizeBasePath(httpService.getBasePath()),
                                                                     upgradeToWebSocketResource, webSocketTypeService);
            webSocketServicesRegistry.registerService(webSocketService);
        });
    }

    private String sanitizeBasePath(String basePath) {
        basePath = basePath.trim();
        if (!basePath.startsWith(HttpConstants.DEFAULT_BASE_PATH)) {
            basePath = HttpConstants.DEFAULT_BASE_PATH.concat(basePath);
        }
        if (basePath.endsWith(HttpConstants.DEFAULT_BASE_PATH) && basePath.length() != 1) {
            basePath = basePath.substring(0, basePath.length() - 1);
        }
        return basePath;
    }

    public String findTheMostSpecificBasePath(String requestURIPath, Map<String, BHttpService> services,
                                              List<String> sortedServiceURIs) {
        for (Object key : sortedServiceURIs) {
            if (!requestURIPath.toLowerCase().contains(key.toString().toLowerCase())) {
                continue;
            }
            if (requestURIPath.length() <= key.toString().length()) {
                return key.toString();
            }
            if (requestURIPath.startsWith(key.toString().concat("/"))) {
                return key.toString();
            }
        }
        if (services.containsKey(HttpConstants.DEFAULT_BASE_PATH)) {
            return HttpConstants.DEFAULT_BASE_PATH;
        }
        return null;
    }

    /**
     * Holds both serviceByBasePath map and sorted Service basePath list.
     */
    protected class ServicesMapHolder {
        private Map<String, BHttpService> servicesByBasePath;
        private List<String> sortedServiceURIs;

        public ServicesMapHolder(Map<String, BHttpService> servicesByBasePath, List<String> sortedServiceURIs) {
            this.servicesByBasePath = servicesByBasePath;
            this.sortedServiceURIs = sortedServiceURIs;
        }
    }
}

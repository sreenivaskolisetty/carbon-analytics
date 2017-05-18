/*
 * Copyright (c)  2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.carbon.stream.processor.core.internal.util.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wso2.carbon.kernel.configprovider.CarbonConfigurationException;
import org.wso2.carbon.kernel.configprovider.ConfigProvider;
import org.wso2.siddhi.core.util.config.ConfigManager;
import org.wso2.siddhi.core.util.config.ConfigReader;

/**
 * Siddhi File Configuration Manager.
 */
public class FileConfigManager implements ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(FileConfigManager.class);

    private ConfigProvider configProvider;

    public FileConfigManager(ConfigProvider configProvider) {
        this.configProvider = configProvider;
    }

    @Override
    public ConfigReader generateConfigReader(String namespace, String name) {
        if (configProvider != null) {
            try {
                RootConfiguration rootConfiguration = configProvider.getConfigurationObject(RootConfiguration.class);
                if (null != rootConfiguration && null != rootConfiguration.extensions) {
                    for (Extension extension : rootConfiguration.extensions) {
                        ChildConfiguration childConfiguration = extension.getExtension();
                        if (null != childConfiguration && null != childConfiguration.getName() && childConfiguration
                                .getName().equals(name) && null != childConfiguration.getNamespace() &&
                                childConfiguration.getNamespace().equals(namespace)
                                && null != childConfiguration.getProperties()) {
                            return new FileConfigReader(childConfiguration.getProperties());
                        }
                    }
                }
            } catch (CarbonConfigurationException e) {
                LOGGER.error(e.getMessage(), e);
            }
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Couldn't find a matching configuration for name: "+
                    name + "and namespace: " + namespace + "!");
        }
        return null;
    }
}

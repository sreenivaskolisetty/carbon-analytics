/*
 * Copyright (c) 2018, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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

package org.wso2.carbon.siddhi.editor.core.util.eventflow.constants;

/**
 * Used to define the different types of nodes that are available when generating a graph.
 */
public enum NodeType {
    AGGREGATION("aggregation"),
    FUNCTION("function"),
    PARTITION("partition"),
    PARTITION_TYPE("partition-type"),
    QUERY("query"),
    STREAM("stream"),
    TABLE("table"),
    TRIGGER("trigger"),
    WINDOW("window");

    private String type;

    NodeType(String type) {
        this.type = type;
    }

    public String getTypeAsString() {
        return type;
    }

}

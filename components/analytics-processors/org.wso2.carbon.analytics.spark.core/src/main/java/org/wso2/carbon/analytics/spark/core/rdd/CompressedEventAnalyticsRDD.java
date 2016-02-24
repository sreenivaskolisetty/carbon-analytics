/*
 * Copyright (c) 2016, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
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
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.analytics.spark.core.rdd;

import static scala.collection.JavaConversions.asScalaIterator;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.spark.Dependency;
import org.apache.spark.InterruptibleIterator;
import org.apache.spark.Partition;
import org.apache.spark.SparkContext;
import org.apache.spark.TaskContext;
import org.apache.spark.rdd.RDD;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wso2.carbon.analytics.dataservice.commons.AnalyticsDataResponse;
import org.wso2.carbon.analytics.datasource.commons.Record;
import org.wso2.carbon.analytics.datasource.commons.RecordGroup;
import org.wso2.carbon.analytics.datasource.commons.exception.AnalyticsException;
import org.wso2.carbon.analytics.spark.core.internal.ServiceHolder;
import org.wso2.carbon.analytics.spark.core.sources.AnalyticsPartition;
import org.wso2.carbon.analytics.spark.core.util.AnalyticsConstants;

import scala.collection.JavaConversions;
import scala.collection.Seq;
import scala.reflect.ClassTag;

/**
 * This class represents Spark analytics RDD implementation.
 */
public class CompressedEventAnalyticsRDD extends RDD<Row> implements Serializable {

    private static final Log log = LogFactory.getLog(CompressedEventAnalyticsRDD.class);
    private static final long serialVersionUID = 5948588299500227997L;
    private List<String> allColumns;
    private List<String> outputColumns;
    private int tenantId;
    private String tableName;

    public CompressedEventAnalyticsRDD() {
        super(null, null, null);
    }

    /**
     * Create a Compressed Event Analytics RDD.
     * 
     * @param tenantId      Tenant ID
     * @param tableName     Name of the associated table
     * @param allColumns       List of allColumns to include in the rdd as fields
     * @param mergeSchema   Flag to merge the existing schema and the defined schema
     * @param sc            Spark Context
     * @param deps          Scala Sequence
     * @param evidence      Class Tag
     */
    public CompressedEventAnalyticsRDD(int tenantId, String tableName, List<String> columns, 
            boolean mergeSchema, SparkContext sc, Seq<Dependency<?>> deps, ClassTag<Row> evidence) {
        super(sc, deps, evidence);
        this.tenantId = tenantId;
        this.tableName = tableName;
        this.allColumns = columns;
        this.outputColumns = new ArrayList<String>(columns);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public scala.collection.Iterator<Row> compute(Partition split, TaskContext context) {
        AnalyticsPartition partition = (AnalyticsPartition) split;
        try {
            Iterator<Record> recordsItr = ServiceHolder.getAnalyticsDataService().readRecords(partition
                .getRecordStoreName(), partition.getRecordGroup());
            return new InterruptibleIterator(context, asScalaIterator(new RowRecordIteratorAdaptor(recordsItr)));
        } catch (AnalyticsException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    @Override
    public Seq<String> getPreferredLocations(Partition split) {
        if (split instanceof AnalyticsPartition) {
            AnalyticsPartition ap = (AnalyticsPartition) split;
            try {
                return JavaConversions.asScalaBuffer(Arrays.asList(ap.getRecordGroup().getLocations())).toList();
            } catch (AnalyticsException e) {
                log.error("Error in getting preffered location: " + e.getMessage() + " falling back to default impl."
                    , e);
                return super.getPreferredLocations(split);
            }
        } else {
            return super.getPreferredLocations(split);
        }
    }

    @Override
    public Partition[] getPartitions() {
        AnalyticsDataResponse resp;
        if (!this.allColumns.contains(AnalyticsConstants.DATA_COLUMN)) {
            this.allColumns.add(AnalyticsConstants.DATA_COLUMN);
        }
        try {
            resp = ServiceHolder.getAnalyticsDataService().get(this.tenantId, this.tableName,
                computePartitions(), this.allColumns, Long.MIN_VALUE, Long.MAX_VALUE, 0, -1);
        } catch (AnalyticsException e) {
            throw new RuntimeException(e.getMessage(), e);
        }
        RecordGroup[] rgs = resp.getRecordGroups();
        Partition[] result = new Partition[rgs.length];
        for (int i = 0; i < result.length; i++) {
            result[i] = new AnalyticsPartition(resp.getRecordStoreName(), rgs[i], i);
        }
        return result;
    }

    private int computePartitions() throws AnalyticsException {
        if (ServiceHolder.getAnalyticskExecutor() != null) {
            return ServiceHolder.getAnalyticskExecutor().getNumPartitionsHint();
        }
        return AnalyticsConstants.SPARK_DEFAULT_PARTITION_COUNT;
    }

    /**
     * Row iterator implementation to act as an adaptor for a record iterator.
     */
    private class RowRecordIteratorAdaptor implements Iterator<Row>, Serializable {
        private static final long serialVersionUID = -8866801517386445810L;
        private Iterator<Record> recordItr;
        private Iterator<Row> rows;

        public RowRecordIteratorAdaptor(Iterator<Record> recordItr) {
            this.recordItr = recordItr;
        }

        @Override
        public boolean hasNext() {
            if (this.rows == null && this.recordItr.hasNext()) {
                this.rows = this.recordToRows(this.recordItr.next());
            }
            if (this.rows == null) {
                return false;
            }
            if (this.rows.hasNext()) {
                return true;
            } else {
                this.rows = null;
                return this.hasNext();
            }
        }

        @Override
        public Row next() {
            if (this.hasNext()) {
                return this.rows.next();
            } else {
                return null;
            }
        }

        /**
         * Converts a DB record to Spark Row(s). Create one ore more rows from a single record.
         * 
         * @param record    Record to be converted to row(s)
         */
        private Iterator<Row> recordToRows(Record record) {
            List<Row> tempRows = new ArrayList<Row>();
            Map<String, Object> recordVals = record.getValues();
            try {
                if (recordVals.get(AnalyticsConstants.DATA_COLUMN) != null) {
                    JSONObject eventsAggregated = new JSONObject(recordVals.get(AnalyticsConstants.DATA_COLUMN)
                        .toString());
                    JSONArray eventsArray = eventsAggregated.getJSONArray(AnalyticsConstants.JSON_FIELD_EVENTS);
                    Map<Integer, Map<String, String>> payloadsMap = null;
                    if (eventsAggregated.has(AnalyticsConstants.JSON_FIELD_PAYLOADS)) {
                        JSONArray payloadsArray = eventsAggregated.getJSONArray(AnalyticsConstants.JSON_FIELD_PAYLOADS);
                        payloadsMap = getPayloadsAsMap(payloadsArray);
                    }
                    String messageFlowId = eventsAggregated.getString(AnalyticsConstants.JSON_FIELD_MESSAGE_FLOW_ID);

                    // Iterate over the array of events
                    for (int j = 0; j < eventsArray.length(); j++) {
                        // Create a row with extended fields
                        tempRows.add(RowFactory.create(getFieldValues(messageFlowId, eventsArray.getJSONObject(j),
                            payloadsMap, j)));
                    }
                } else {
                    Map<String, Object> rowVals = new LinkedHashMap<String, Object>();
                    tempRows.add(RowFactory.create(rowVals.values().toArray()));
                }
            } catch (JSONException e) {
                throw new RuntimeException("Error occured while splitting the record to rows: " + e.getMessage(), e);
            }
            return tempRows.iterator();
        }
        
        /**
         * Get the values of each field of an event, as an Array.
         * 
         * @param messageFlowId ID of the message flow
         * @param event         Current event 
         * @param payloadsMap   Payloads Map
         * @param eventIndex    Index of the current event
         * @return              Array of values of the fields in the event
         */
        private Object[] getFieldValues(String messageFlowId, JSONObject event,
                Map<Integer, Map<String, String>> payloadsMap, int eventIndex) {
            Map<String, Object> extendedRowVals = new LinkedHashMap<String, Object>();
            // Iterate over new (split) fields and add them
            try {
                for (int k = 0; k < outputColumns.size(); k++) {
                    // Add the component index
                    if (outputColumns.get(k).equalsIgnoreCase(AnalyticsConstants.COMPONENT_INDEX)) {
                        extendedRowVals.put(outputColumns.get(k), eventIndex);
                    } else if (outputColumns.get(k).equalsIgnoreCase(AnalyticsConstants.JSON_FIELD_MESSAGE_FLOW_ID)) {
                        // Add the event flow ID
                        extendedRowVals.put(outputColumns.get(k), messageFlowId);
                    } else if (event.has(outputColumns.get(k))) {
                        String fieldValue = event.getString(outputColumns.get(k));
                        if (fieldValue == null || "null".equalsIgnoreCase(fieldValue)) {
                            if (payloadsMap != null && payloadsMap.containsKey(eventIndex)) {
                                extendedRowVals.put(outputColumns.get(k), payloadsMap.get(eventIndex)
                                    .get(outputColumns.get(k)));
                            } else {
                                extendedRowVals.put(outputColumns.get(k), null);
                            }
                        } else {
                            extendedRowVals.put(outputColumns.get(k), fieldValue);
                        }
                    } else {
                        extendedRowVals.put(outputColumns.get(k), null);
                    }
                }
                return extendedRowVals.values().toArray();
            } catch (JSONException e) {
                throw new RuntimeException("Error occured while splitting the record to rows: " + e.getMessage(), e);
            }
        }
        
        /**
         * Convert json payload to map.
         * 
         * @param payloadsArray     JSON Array containing payload details
         * @return                  map of payloads
         */
        private Map<Integer, Map<String, String>> getPayloadsAsMap(JSONArray payloadsArray) {
            Map<Integer, Map<String, String>> payloadsMap = new HashMap<Integer, Map<String, String>>();
            for (int i = 0; i < payloadsArray.length(); i++) {
                try {
                    String payload = payloadsArray.getJSONObject(i).getString(AnalyticsConstants.JSON_FIELD_PAYLOAD);
                    JSONArray eventRefs = payloadsArray.getJSONObject(i).getJSONArray(AnalyticsConstants.JSON_FIELD_EVENTS);
                    for (int j = 0; j < eventRefs.length(); j++) {
                        int eventIndex = eventRefs.getJSONObject(j).getInt(AnalyticsConstants.JSON_FIELD_EVENT_INDEX);
                        Map<String, String> existingPayloadMap = payloadsMap.get(eventIndex);
                        if (existingPayloadMap == null) {
                            Map<String, String> attributesMap = new HashMap<String, String>();
                            attributesMap.put(eventRefs.getJSONObject(j).getString(AnalyticsConstants.
                                JSON_FIELD_ATTRIBUTE), payload);
                            payloadsMap.put(eventIndex, attributesMap);
                        } else {
                            existingPayloadMap.put(eventRefs.getJSONObject(j).getString(AnalyticsConstants.
                                JSON_FIELD_ATTRIBUTE), payload);
                        }
                    }
                } catch (JSONException e) {
                    throw new RuntimeException("Error occured while generating payload map: " + e.getMessage(), e);
                }
            }
            return payloadsMap;
        }
        
        @Override
        public void remove() {
            this.recordItr.remove();
        }
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.flink.streaming.connectors.kudu.connector;

import com.stumbleupon.async.Callback;
import org.apache.commons.collections.CollectionUtils;
import org.apache.kudu.client.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

public class KuduConnector implements AutoCloseable {

    private final Logger LOG = LoggerFactory.getLogger(this.getClass());

    public enum Consistency {EVENTUAL, STRONG};
    public enum WriteMode {INSERT,UPDATE,UPSERT}

    private AsyncKuduClient client;
    private KuduTable table;
    private AsyncKuduSession asyncSession;
    private KuduSession session;
    private long counter = 0;
    private SessionConfiguration.FlushMode flushMode = SessionConfiguration.FlushMode.MANUAL_FLUSH;
    private int flushInterval = 1000;
    private int mutationBufferSpace = 1000;

    public KuduConnector(String kuduMasters, KuduTableInfo tableInfo) throws IOException {
        client = client(kuduMasters);
        table = table(tableInfo);
    }

    private AsyncKuduClient client(String kuduMasters) {
        return new AsyncKuduClient.AsyncKuduClientBuilder(kuduMasters).build();
    }

    private KuduTable table(KuduTableInfo infoTable) throws IOException {
        KuduClient syncClient = client.syncClient();

        String tableName = infoTable.getName();
        if (syncClient.tableExists(tableName)) {
            return syncClient.openTable(tableName);
        }
        if (infoTable.createIfNotExist()) {
            return syncClient.createTable(tableName, infoTable.getSchema(), infoTable.getCreateTableOptions());
        }
        throw new UnsupportedOperationException("table not exists and is marketed to not be created");
    }

    public boolean deleteTable() throws IOException {
        String tableName = table.getName();
        client.syncClient().deleteTable(tableName);
        return true;
    }

    public KuduScanner scanner(byte[] token) throws IOException {
        return KuduScanToken.deserializeIntoScanner(token, client.syncClient());
    }

    public List<KuduScanToken> scanTokens(List<KuduFilterInfo> tableFilters, List<String> tableProjections, Long rowLimit) {
        KuduScanToken.KuduScanTokenBuilder tokenBuilder = client.syncClient().newScanTokenBuilder(table);

        if (CollectionUtils.isNotEmpty(tableProjections)) {
            tokenBuilder.setProjectedColumnNames(tableProjections);
        }

        if (CollectionUtils.isNotEmpty(tableFilters)) {
            tableFilters.stream()
                    .map(filter -> filter.toPredicate(table.getSchema()))
                    .forEach(tokenBuilder::addPredicate);
        }

        if (rowLimit !=null && rowLimit > 0) {
            tokenBuilder.limit(rowLimit);
            // FIXME: https://issues.apache.org/jira/browse/KUDU-16
            // Server side limit() operator for java-based scanners are not implemented yet
        }

        return tokenBuilder.build();
    }

    public void writeRow(KuduRow row, Consistency consistency, WriteMode writeMode) throws Exception {
        final Operation operation = KuduMapper.toOperation(table, writeMode, row);

        if (Consistency.EVENTUAL.equals(consistency)) {
            if (asyncSession == null || asyncSession.isClosed()) {
                asyncSession = client.newSession();
                asyncSession.setFlushMode(flushMode);
                asyncSession.setMutationBufferSpace(mutationBufferSpace);
                asyncSession.setFlushInterval(flushInterval);
            }
            asyncSession.apply(operation);
            counter++;

            if (counter == mutationBufferSpace) {
                asyncSession.close().addCallback(new ResponseCallback()).join();
                counter = 0;
            }
        } else {
            if (session == null || session.isClosed()) {
                session = client.syncClient().newSession();
                session.setFlushMode(flushMode);
                session.setMutationBufferSpace(mutationBufferSpace);
                session.setFlushInterval(flushInterval);
            }
            session.apply(operation);

            counter++;

            if (counter == mutationBufferSpace) {
                processResponse(session.close());
                counter = 0;
            }
        }
    }

    @Override
    public void close() throws Exception {
        LOG.warn("kudu client closing");
        if (client == null) return;

        if (asyncSession != null && !asyncSession.isClosed()) {
            asyncSession.close().addCallback(new ResponseCallback()).join();
        }

        if (session != null && !session.isClosed()) {
            processResponse(session.close());
        }

        client.close();
        LOG.warn("kudu client close finished");
    }

    private Boolean processResponse(List<OperationResponse> operationResponses) {
        Boolean isOk = operationResponses.isEmpty();
        for(OperationResponse operationResponse : operationResponses) {
            if (operationResponse.getRowError() != null) {
                logResponseError(operationResponse.getRowError());
            }
        }
        return isOk;
    }

    private void logResponseError(RowError error) {
        LOG.error("Error {} on {}: {} ", error.getErrorStatus(), error.getOperation(), error.toString());
    }

    private class ResponseCallback implements Callback<Boolean, List<OperationResponse>> {
        @Override
        public Boolean call(List<OperationResponse> operationResponses) {
            return processResponse(operationResponses);
        }
    }

    public KuduConnector setFlushInterval(int flunshInterval) {
        this.flushInterval = flunshInterval;
        return this;
    }

    public KuduConnector setMutationBufferSpace(int mutationBufferSpace) {
        this.mutationBufferSpace = mutationBufferSpace;
        return this;
    }

    public KuduConnector setFlushMode(SessionConfiguration.FlushMode flushMode) {
        this.flushMode = flushMode;
        return this;
    }
}

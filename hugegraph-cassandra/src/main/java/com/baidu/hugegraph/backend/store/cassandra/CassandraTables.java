/*
 * Copyright 2017 HugeGraph Authors
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.baidu.hugegraph.backend.store.cassandra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.tinkerpop.gremlin.structure.Direction;

import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.id.IdGenerator;
import com.baidu.hugegraph.backend.id.SplicingIdGenerator;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.exceptions.DriverException;
import com.datastax.driver.core.querybuilder.Delete;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

public class CassandraTables {

    /***************************** Table defines *****************************/

    public static class VertexLabel extends CassandraTable {

        public static final String TABLE = "vertex_labels";

        public VertexLabel() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.NAME, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of();
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap.of(
                    HugeKeys.ID_STRATEGY, DataType.text(),
                    HugeKeys.PRIMARY_KEYS, DataType.text(),
                    HugeKeys.NULLABLE_KEYS, DataType.text(),
                    HugeKeys.INDEX_NAMES, DataType.text(),
                    HugeKeys.PROPERTIES, DataType.text()
            );

            this.createTable(session, pkeys, ckeys, columns);
        }
    }

    public static class EdgeLabel extends CassandraTable {

        public static final String TABLE = "edge_labels";

        public EdgeLabel() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.NAME, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of();
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap
                    .<HugeKeys, DataType>builder()
                    .put(HugeKeys.SOURCE_LABEL, DataType.text())
                    .put(HugeKeys.TARGET_LABEL, DataType.text())
                    .put(HugeKeys.FREQUENCY, DataType.text())
                    .put(HugeKeys.SORT_KEYS, DataType.text())
                    .put(HugeKeys.NULLABLE_KEYS, DataType.text())
                    .put(HugeKeys.INDEX_NAMES, DataType.text())
                    .put(HugeKeys.PROPERTIES, DataType.text())
                    .build();

            this.createTable(session, pkeys, ckeys, columns);
        }
    }

    public static class PropertyKey extends CassandraTable {

        public static final String TABLE = "property_keys";

        public PropertyKey() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.NAME, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of();
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap.of(
                    HugeKeys.DATA_TYPE, DataType.text(),
                    HugeKeys.CARDINALITY, DataType.text(),
                    HugeKeys.PROPERTIES, DataType.text()
            );

            this.createTable(session, pkeys, ckeys, columns);
        }
    }

    public static class IndexLabel extends CassandraTable {

        public static final String TABLE = "index_labels";

        public IndexLabel() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.NAME, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of(
                    HugeKeys.BASE_TYPE, DataType.text(),
                    HugeKeys.BASE_VALUE, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap.of(
                    HugeKeys.INDEX_TYPE, DataType.text(),
                    HugeKeys.FIELDS, DataType.text()
            );

            this.createTable(session, pkeys, ckeys, columns);
        }
    }

    public static class Vertex extends CassandraTable {

        public static final String TABLE = "vertices";

        public Vertex() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.ID, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of();
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap.of(
                    HugeKeys.LABEL, DataType.text(),
                    HugeKeys.PROPERTIES, DataType.map(DataType.text(),
                                                      DataType.text())
            );

            this.createTable(session, pkeys, ckeys, columns);
            this.createIndex(session, "vertex_label_index", HugeKeys.LABEL);
        }

        @Override
        protected List<HugeKeys> idColumnName() {
            return ImmutableList.of(HugeKeys.ID);
        }

        @Override
        protected List<BackendEntry> mergeEntries(List<BackendEntry> entries) {
            // Set id for entries
            for (BackendEntry i : entries) {
                CassandraBackendEntry entry = (CassandraBackendEntry) i;
                entry.id(IdGenerator.of(entry.<String>column(HugeKeys.ID)));
            }
            return entries;
        }
    }

    public static class Edge extends CassandraTable {

        public static final String TABLE = "edges";

        private static final HugeKeys[] KEYS = new HugeKeys[] {
                HugeKeys.OWNER_VERTEX,
                HugeKeys.DIRECTION,
                HugeKeys.LABEL,
                HugeKeys.SORT_VALUES,
                HugeKeys.OTHER_VERTEX
        };

        public Edge() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.OWNER_VERTEX, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of(
                    HugeKeys.DIRECTION, DataType.text(),
                    HugeKeys.LABEL, DataType.text(),
                    HugeKeys.SORT_VALUES, DataType.text(),
                    HugeKeys.OTHER_VERTEX, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap.of(
                    HugeKeys.PROPERTIES, DataType.map(DataType.text(),
                                                      DataType.text())
            );

            this.createTable(session, pkeys, ckeys, columns);
            this.createIndex(session, "edge_label_index", HugeKeys.LABEL);
        }

        @Override
        protected List<HugeKeys> pkColumnName() {
            return ImmutableList.of(HugeKeys.OWNER_VERTEX);
        }

        @Override
        protected List<HugeKeys> idColumnName() {
            return Arrays.asList(KEYS);
        }

        @Override
        protected List<String> idColumnValue(Id id) {
            return idColumnValue(id, Direction.OUT);
        }

        protected List<String> idColumnValue(Id id, Direction dir) {
            // TODO: improve Id split()
            String[] idParts = SplicingIdGenerator.split(id);

            // Ensure edge id with Direction
            // NOTE: we assume the id without Direction if it contains 4 parts
            // TODO: should move to Serializer
            if (idParts.length == 4) {
                if (dir == Direction.IN) {
                    // Swap source-vertex and target-vertex
                    String tmp = idParts[0];
                    idParts[0] = idParts[3];
                    idParts[3] = tmp;
                }
                List<String> list = new ArrayList<>(Arrays.asList(idParts));
                list.add(1, dir.name());
                return list;
            }

            return Arrays.asList(idParts);
        }

        @Override
        public void delete(CassandraSessionPool.Session session,
                           CassandraBackendEntry.Row entry) {
            /*
             * TODO: Delete edge by label
             * Need to implement the framework that can delete with query
             * which contains id or condition.
             */

            // Let super class do delete if not deleting edge by label
            List<String> idParts = this.idColumnValue(entry.id());
            if (idParts.size() > 1 || entry.columns().size() > 0) {
                super.delete(session, entry);
                return;
            }

            // The only element is label
            String label = idParts.get(0);
            this.deleteEdgesByLabel(session, label);
        }

        protected void deleteEdgesByLabel(CassandraSessionPool.Session session,
                                          String label) {
            final String LABEL = formatKey(HugeKeys.LABEL);
            final String OWNER_VERTEX = formatKey(HugeKeys.OWNER_VERTEX);
            final String DIRECTION = formatKey(HugeKeys.DIRECTION);

            // Query edges by label index
            Select select = QueryBuilder.select().from(this.table());
            select.where(formatEQ(HugeKeys.LABEL, label));

            ResultSet rs;
            try {
                rs = session.execute(select);
            } catch (DriverException e) {
                throw new BackendException("Failed to query edges with " +
                          "label '%s' for deleting", label, e);
            }

            // Delete edges
            for (Iterator<Row> it = rs.iterator(); it.hasNext();) {
                Row row = it.next();

                assert label.equals(row.get(LABEL, String.class));
                String ownerVertex = row.get(OWNER_VERTEX, String.class);
                String direction = row.get(DIRECTION, String.class);

                Delete delete = QueryBuilder.delete().from(this.table());
                delete.where(formatEQ(HugeKeys.OWNER_VERTEX, ownerVertex));
                delete.where(formatEQ(HugeKeys.DIRECTION, direction));
                delete.where(formatEQ(HugeKeys.LABEL, label));

                session.add(delete);
            }
        }

        @Override
        protected List<BackendEntry> mergeEntries(List<BackendEntry> entries) {
            // TODO: merge rows before calling result2Entry()

            // Merge edges into vertex
            Map<Id, CassandraBackendEntry> vertices = new HashMap<>();

            for (BackendEntry i : entries) {
                CassandraBackendEntry entry = (CassandraBackendEntry) i;
                Id srcVertexId = IdGenerator.of(
                                 entry.<String>column(HugeKeys.OWNER_VERTEX));
                if (!vertices.containsKey(srcVertexId)) {
                    CassandraBackendEntry vertex = new CassandraBackendEntry(
                            HugeType.VERTEX, srcVertexId);

                    vertex.column(HugeKeys.ID,
                                  entry.column(HugeKeys.OWNER_VERTEX));
                    vertex.column(HugeKeys.PROPERTIES, ImmutableMap.of());

                    vertices.put(srcVertexId, vertex);
                }
                // Add edge into vertex as a sub row
                vertices.get(srcVertexId).subRow(entry.row());
            }

            return ImmutableList.copyOf(vertices.values());
        }
    }

    public static class SecondaryIndex extends CassandraTable {

        public static final String TABLE = "secondary_indexes";

        public SecondaryIndex() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.FIELD_VALUES, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of(
                    HugeKeys.INDEX_LABEL_NAME, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap.of(
                    HugeKeys.ELEMENT_IDS, DataType.set(DataType.text())
            );

            this.createTable(session, pkeys, ckeys, columns);
        }

        @Override
        protected List<HugeKeys> idColumnName() {
            return ImmutableList.of(HugeKeys.FIELD_VALUES,
                                    HugeKeys.INDEX_LABEL_NAME);
        }

        @Override
        protected List<HugeKeys> modifiableColumnName() {
            return ImmutableList.of(HugeKeys.ELEMENT_IDS);
        }

        @Override
        public void delete(CassandraSessionPool.Session session,
                           CassandraBackendEntry.Row entry) {
            String fieldValues = entry.column(HugeKeys.FIELD_VALUES);
            if (fieldValues != null) {
                throw new BackendException("SecondaryIndex deletion " +
                          "should just have INDEX_LABEL_NAME, " +
                          "but FIELD_VALUES(%s) is provided.", fieldValues);
            }

            String indexLabel = entry.column(HugeKeys.INDEX_LABEL_NAME);
            if (indexLabel == null || indexLabel.isEmpty()) {
                throw new BackendException("SecondaryIndex deletion " +
                          "needs INDEX_LABEL_NAME, but not provided.");
            }

            Select select = QueryBuilder.select().from(this.table());
            select.where(formatEQ(HugeKeys.INDEX_LABEL_NAME, indexLabel));
            select.allowFiltering();

            ResultSet rs;
            try {
                rs = session.execute(select);
            } catch (DriverException e) {
                throw new BackendException("Failed to query secondary " +
                          "indexes with index label '%s' for deleting",
                          indexLabel, e);
            }

            final String FIELD_VALUES = formatKey(HugeKeys.FIELD_VALUES);
            for (Iterator<Row> it = rs.iterator(); it.hasNext();) {
                fieldValues = it.next().get(FIELD_VALUES, String.class);
                Delete delete = QueryBuilder.delete().from(this.table());
                delete.where(formatEQ(HugeKeys.INDEX_LABEL_NAME, indexLabel));
                delete.where(formatEQ(HugeKeys.FIELD_VALUES, fieldValues));
                session.add(delete);
            }
        }

        @Override
        public void insert(CassandraSessionPool.Session session,
                           CassandraBackendEntry.Row entry) {
            throw new BackendException(
                      "SecondaryIndex insertion is not supported.");
        }
    }

    public static class SearchIndex extends CassandraTable {

        public static final String TABLE = "search_indexes";

        public SearchIndex() {
            super(TABLE);
        }

        @Override
        public void init(CassandraSessionPool.Session session) {
            ImmutableMap<HugeKeys, DataType> pkeys = ImmutableMap.of(
                    HugeKeys.INDEX_LABEL_NAME, DataType.text()
            );
            ImmutableMap<HugeKeys, DataType> ckeys = ImmutableMap.of(
                    HugeKeys.FIELD_VALUES, DataType.decimal()
            );
            ImmutableMap<HugeKeys, DataType> columns = ImmutableMap.of(
                    HugeKeys.ELEMENT_IDS, DataType.set(DataType.text())
            );

            this.createTable(session, pkeys, ckeys, columns);
        }

        @Override
        protected List<HugeKeys> idColumnName() {
            return ImmutableList.of(HugeKeys.INDEX_LABEL_NAME,
                                    HugeKeys.FIELD_VALUES);
        }

        @Override
        protected List<HugeKeys> modifiableColumnName() {
            return ImmutableList.of(HugeKeys.ELEMENT_IDS);
        }

        @Override
        public void delete(CassandraSessionPool.Session session,
                                        CassandraBackendEntry.Row entry) {
            String fieldValues = entry.column(HugeKeys.FIELD_VALUES);
            if (fieldValues != null) {
                throw new BackendException("SearchIndex deletion " +
                          "should just have INDEX_LABEL_NAME, " +
                          "but PROPERTY_VALUES(%s) is provided.", fieldValues);
            }

            String indexLabel = entry.column(HugeKeys.INDEX_LABEL_NAME);
            if (indexLabel == null || indexLabel.isEmpty()) {
                throw new BackendException("SearchIndex deletion " +
                          "needs INDEX_LABEL_NAME, but not provided.");
            }

            Delete delete = QueryBuilder.delete().from(this.table());
            delete.where(formatEQ(HugeKeys.INDEX_LABEL_NAME, indexLabel));
            session.add(delete);
        }

        @Override
        public void insert(CassandraSessionPool.Session session,
                           CassandraBackendEntry.Row entry) {
            throw new BackendException(
                      "SearchIndex insertion is not supported.");
        }
    }
}

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
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.baidu.hugegraph.backend.store.cassandra;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.tinkerpop.gremlin.structure.Direction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baidu.hugegraph.backend.BackendException;
import com.baidu.hugegraph.backend.id.Id;
import com.baidu.hugegraph.backend.query.Condition;
import com.baidu.hugegraph.backend.query.Condition.Relation;
import com.baidu.hugegraph.backend.query.Query;
import com.baidu.hugegraph.backend.query.Query.Order;
import com.baidu.hugegraph.backend.store.BackendEntry;
import com.baidu.hugegraph.type.HugeType;
import com.baidu.hugegraph.type.define.HugeKeys;
import com.baidu.hugegraph.util.CopyUtil;
import com.baidu.hugegraph.util.E;
import com.datastax.driver.core.ColumnDefinitions.Definition;
import com.datastax.driver.core.DataType;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.querybuilder.Clause;
import com.datastax.driver.core.querybuilder.Clauses;
import com.datastax.driver.core.querybuilder.Delete;
import com.datastax.driver.core.querybuilder.Insert;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.datastax.driver.core.querybuilder.Update;
import com.datastax.driver.core.schemabuilder.SchemaBuilder;
import com.google.common.collect.ImmutableList;

public abstract class CassandraTable {

    private static final Logger logger =
            LoggerFactory.getLogger(CassandraStore.class);

    private interface MetaHandler {
        public Object handle(CassandraSessionPool.Session session,
                             String meta, Object... args);
    }

    private final String table;

    private final Map<String, MetaHandler> metaHandlers;

    public CassandraTable(String table) {
        this.table = table;
        this.metaHandlers = new ConcurrentHashMap<>();

        this.registerMetaHandlers();
    }

    public String table() {
        return this.table;
    }

    public Object metadata(CassandraSessionPool.Session session,
                           String meta, Object... args) {
        if (!this.metaHandlers.containsKey(meta)) {
            throw new BackendException("Invalid metadata name '%s'", meta);
        }
        return this.metaHandlers.get(meta).handle(session, meta, args);
    }

    private void registerMetaHandlers() {
        this.metaHandlers.put("splits", (session, meta, args) -> {
            E.checkArgument(args.length == 1,
                            "The args count of %s must be 1", meta);
            long splitSize = (long) args[0];
            CassandraShard spliter = new CassandraShard(session,
                                                        session.keyspace(),
                                                        table());
            return spliter.getSplits(0, splitSize);
        });
    }

    public Iterable<BackendEntry> query(CassandraSessionPool.Session session,
                                        Query query) {
        List<BackendEntry> rs = new ArrayList<>();

        if (query.limit() == 0 && query.limit() != Query.NO_LIMIT) {
            logger.debug("Return empty result(limit=0) for query {}", query);
            return rs;
        }

        try {
            List<Select> selections = query2Select(query);
            for (Select selection : selections) {
                ResultSet results = session.execute(selection);
                rs.addAll(this.results2Entries(query.resultType(), results));
            }
        } catch (Exception e) {
            throw new BackendException("Failed to query [%s]", e, query);
        }

        logger.debug("Return {} for query {}", rs, query);
        return rs;
    }

    protected List<Select> query2Select(Query query) {
        // Set table
        Select select = QueryBuilder.select().from(this.table);

        // Set limit
        if (query.limit() != Query.NO_LIMIT) {
            select.limit((int) query.limit());
        }

        // NOTE: Cassandra does not support query.offset()
        if (query.offset() != 0) {
            logger.warn("Query offset is not supported currently " +
                        "on Cassandra strore, it will be ignored");
        }

        // Set order-by
        for (Map.Entry<HugeKeys, Order> order : query.orders().entrySet()) {
            String name = formatKey(order.getKey());
            if (order.getValue() == Order.ASC) {
                select.orderBy(QueryBuilder.asc(name));
            } else {
                assert order.getValue() == Order.DESC;
                select.orderBy(QueryBuilder.desc(name));
            }
        }

        // Is query by id?
        List<Select> ids = this.queryId2Select(query, select);

        if (query.conditions().isEmpty()) {
            // Query only by id
            logger.debug("Query only by id(s): {}", ids);
            return ids;
        } else {
            List<Select> conds = new ArrayList<Select>(ids.size());
            for (Select selection : ids) {
                // Query by condition
                conds.addAll(this.queryCondition2Select(query, selection));
            }
            logger.debug("Query by conditions: {}", conds);
            return conds;
        }
    }

    protected List<Select> queryId2Select(Query query, Select select) {
        // Query by id(s)
        if (query.ids().isEmpty()) {
            return ImmutableList.of(select);
        }

        List<String> nameParts = this.idColumnName();

        List<List<String>> ids = new ArrayList<>(query.ids().size());
        for (Id id : query.ids()) {
            List<String> idParts = this.idColumnValue(id);
            if (nameParts.size() != idParts.size()) {
                throw new BackendException(
                          "Unsupported ID format: '%s' (should contain %s)",
                          id, nameParts);
            }
            ids.add(idParts);
        }

        // Query only by partition-key
        if (nameParts.size() == 1) {
            List<String> idList = new ArrayList<>(ids.size());
            for (List<String> id : ids) {
                assert id.size() == 1;
                idList.add(id.get(0));
            }
            select.where(QueryBuilder.in(nameParts.get(0), idList));
            return ImmutableList.of(select);
        }

        /*
         * Query by partition-key + clustering-key
         * NOTE: Error if multi-column IN clause include partition key:
         * error: multi-column relations can only be applied to clustering
         * columns when using: select.where(QueryBuilder.in(names, idList));
         * So we use multi-query instead of IN
         */
        List<Select> selections = new ArrayList<Select>(ids.size());
        for (List<String> id : ids) {
            assert nameParts.size() == id.size();
            // NOTE: there is no Select.clone(), just use copy instead
            Select idSelection = CopyUtil.copy(select,
                                 QueryBuilder.select().from(this.table));
            /*
             * NOTE: concat with AND relation, like:
             * "pk = id and ck1 = v1 and ck2 = v2"
             */
            for (int i = 0, n = nameParts.size(); i < n; i++) {
                idSelection.where(QueryBuilder.eq(nameParts.get(i),
                                                  id.get(i)));
            }
            selections.add(idSelection);
        }
        return selections;
    }

    protected Collection<Select> queryCondition2Select(Query query,
                                                       Select select) {
        // Query by conditions
        Set<Condition> conditions = query.conditions();
        for (Condition condition : conditions) {
            Clause clause = condition2Cql(condition);
            select.where(clause);
            if (Clauses.needAllowFiltering(clause)) {
                select.allowFiltering();
            }
        }
        return ImmutableList.of(select);
    }

    protected Clause condition2Cql(Condition condition) {
        switch (condition.type()) {
            case AND:
                Condition.And and = (Condition.And) condition;
                Clause left = condition2Cql(and.left());
                Clause right = condition2Cql(and.right());
                return Clauses.and(left, right);
            case OR:
                throw new BackendException("Not support OR currently");
            case RELATION:
                Condition.Relation r = (Condition.Relation) condition;
                return relation2Cql(r);
            default:
                final String msg = "Unsupported condition: " + condition;
                throw new AssertionError(msg);
        }
    }

    protected Clause relation2Cql(Relation relation) {
        String key = relation.key().toString();
        Object value = relation.value();

        // Serialize value (TODO: should move to Serializer)
        value = serializeValue(value);

        switch (relation.relation()) {
            case EQ:
                return QueryBuilder.eq(key, value);
            case GT:
                return QueryBuilder.gt(key, value);
            case GTE:
                return QueryBuilder.gte(key, value);
            case LT:
                return QueryBuilder.lt(key, value);
            case LTE:
                return QueryBuilder.lte(key, value);
            case IN:
                List<?> values = (List<?>) value;
                List<Object> serializedValues = new ArrayList<>(values.size());
                for (Object v : values) {
                    serializedValues.add(serializeValue(v));
                }
                return QueryBuilder.in(key, serializedValues);
            case CONTAINS_KEY:
                return QueryBuilder.containsKey(key, value);
            case SCAN:
                String[] col = pkColumnName().toArray(new String[0]);
                Object start = QueryBuilder.raw(key);
                Object end = QueryBuilder.raw((String) value);
                return Clauses.and(
                        QueryBuilder.gte(QueryBuilder.token(col), start),
                        QueryBuilder.lt(QueryBuilder.token(col), end));
            /*
             * Currently we can't sypport LIKE due to error:
             * "cassandra no viable alternative at input 'like'..."
             */
            // case LIKE:
            //    return QueryBuilder.like(key, value);
            case NEQ:
            default:
                throw new AssertionError("Unsupported relation: " + relation);
        }
    }

    protected static Object serializeValue(Object value) {
        // Serialize value (TODO: should move to Serializer)
        if (value instanceof Id) {
            value = ((Id) value).asString();
        } else if (value instanceof Direction) {
            value = ((Direction) value).name();
        }

        return value;
    }

    protected List<BackendEntry> results2Entries(HugeType resultType,
                                                 ResultSet results) {
        List<BackendEntry> entries = new ArrayList<>();

        for (Iterator<Row> iter = results.iterator(); iter.hasNext();) {
            Row row = iter.next();
            entries.add(result2Entry(resultType, row));
        }

        return this.mergeEntries(entries);
    }

    protected CassandraBackendEntry result2Entry(HugeType type, Row row) {
        CassandraBackendEntry entry = new CassandraBackendEntry(type);

        List<Definition> cols = row.getColumnDefinitions().asList();
        for (Definition col : cols) {
            String name = col.getName();
            Object value = row.getObject(name);
            entry.column(parseKey(name), value);
        }

        return entry;
    }

    protected List<String> pkColumnName() {
        return idColumnName();
    }

    protected List<String> idColumnName() {
        return ImmutableList.of(HugeKeys.NAME.name());
    }

    protected List<String> idColumnValue(Id id) {
        return ImmutableList.of(id.asString());
    }

    protected List<BackendEntry> mergeEntries(List<BackendEntry> entries) {
        return entries;
    }

    protected final String formatKey(HugeKeys key) {
        return key.name();
    }

    protected final HugeKeys parseKey(String name) {
        return HugeKeys.valueOf(name.toUpperCase());
    }

    /**
     * Insert an entire row
     */
    public void insert(CassandraSessionPool.Session session,
                       CassandraBackendEntry.Row entry) {
        assert entry.columns().size() > 0;
        Insert insert = QueryBuilder.insertInto(this.table);

        for (Map.Entry<HugeKeys, Object> c : entry.columns().entrySet()) {
            insert.value(this.formatKey(c.getKey()), c.getValue());
        }

        session.add(insert);
    }

    /**
     * Append several elements to the collection column of a row
     */
    public void append(CassandraSessionPool.Session session,
                       CassandraBackendEntry.Row entry) {

        List<String> idNames = this.idColumnName();
        List<String> idValues = this.idColumnValue(entry.id());
        assert idNames.size() == idValues.size();

        Update update = QueryBuilder.update(table());

        for (Map.Entry<HugeKeys, Object> column : entry.columns().entrySet()) {
            String key = this.formatKey(column.getKey());
            if (!idNames.contains(key)) {
                update.with(QueryBuilder.append(key, column.getValue()));
            }
        }

        for (int i = 0, n = idNames.size(); i < n; i++) {
            update.where(QueryBuilder.eq(idNames.get(i), idValues.get(i)));
        }

        session.add(update);
    }

    /**
     * Eliminate several elements from the collection column of a row
     */
    public void eliminate(CassandraSessionPool.Session session,
                          CassandraBackendEntry.Row entry) {

        List<String> idNames = this.idColumnName();
        List<String> idValues = this.idColumnValue(entry.id());
        assert idNames.size() == idValues.size();

        // Update by id
        Update update = QueryBuilder.update(table());

        for (Map.Entry<HugeKeys, Object> column : entry.columns().entrySet()) {
            String key = this.formatKey(column.getKey());
            if (!idNames.contains(key)) {
                /*
                 * NOTE: eliminate from map<text, text> should just pass key,
                 * if use the following statement:
                 * UPDATE vertices SET PROPERTIES=PROPERTIES-{'city':'"Wuhan"'}
                 * WHERE LABEL='person' AND PRIMARY_VALUES='josh';
                 * it will throw a cassandra exception:
                 * Invalid map literal for properties of typefrozen<set<text>>
                 */
                Object value = column.getValue();
                if (value instanceof Map) {
                    @SuppressWarnings("rawtypes")
                    Set<?> keySet = ((Map) value).keySet();
                    update.with(QueryBuilder.removeAll(key, keySet));
                } else if (value instanceof Set) {
                    update.with(QueryBuilder.removeAll(key, (Set<?>) value));
                } else if (value instanceof List) {
                    Set<?> keySet = new HashSet<>((List<?>) value);
                    update.with(QueryBuilder.removeAll(key, keySet));
                } else {
                    update.with(QueryBuilder.remove(key, value));
                }
            }
        }

        for (int i = 0, n = idNames.size(); i < n; i++) {
            update.where(QueryBuilder.eq(idNames.get(i), idValues.get(i)));
        }

        session.add(update);
    }

    /**
     * Delete an entire row
     */
    public void delete(CassandraSessionPool.Session session,
                       CassandraBackendEntry.Row entry) {
        if (entry.columns().isEmpty()) {
            // Delete just by id
            List<String> idNames = this.idColumnName();
            List<String> idValues = this.idColumnValue(entry.id());
            assert idNames.size() == idValues.size();

            Delete delete = QueryBuilder.delete().from(this.table);
            for (int i = 0, n = idNames.size(); i < n; i++) {
                delete.where(QueryBuilder.eq(idNames.get(i), idValues.get(i)));
            }

            session.add(delete);
        } else {
            // Delete just by column keys
            // TODO: delete by id + keys(like index element-ids))

            // NOTE: there are multi deletions if delete by id + keys
            Delete delete = QueryBuilder.delete().from(this.table);
            for (Map.Entry<HugeKeys, Object> c : entry.columns().entrySet()) {
                // TODO: should support other filters (like containsKey)
                delete.where(QueryBuilder.eq(formatKey(c.getKey()),
                                             c.getValue()));
            }

            session.add(delete);
        }
    }

    protected void createTable(CassandraSessionPool.Session session,
                               HugeKeys[] columns,
                               HugeKeys[] primaryKeys) {
        DataType[] columnTypes = new DataType[columns.length];
        for (int i = 0; i < columns.length; i++) {
            columnTypes[i] = DataType.text();
        }
        this.createTable(session, columns, columnTypes, primaryKeys);
    }

    protected void createTable(CassandraSessionPool.Session session,
                               HugeKeys[] columns,
                               DataType[] columnTypes,
                               HugeKeys[] primaryKeys) {
        // TODO: to make it more clear.
        assert (primaryKeys.length > 0);
        HugeKeys[] partitionKeys = new HugeKeys[] {primaryKeys[0]};
        HugeKeys[] clusterKeys = null;
        if (primaryKeys.length > 1) {
            clusterKeys = Arrays.copyOfRange(
                          primaryKeys, 1, primaryKeys.length);
        } else {
            clusterKeys = new HugeKeys[] {};
        }
        this.createTable(session, columns, columnTypes,
                         partitionKeys, clusterKeys);
    }

    protected void createTable(CassandraSessionPool.Session session,
                               HugeKeys[] columns,
                               HugeKeys[] pKeys,
                               HugeKeys[] cKeys) {
        DataType[] columnTypes = new DataType[columns.length];
        for (int i = 0; i < columns.length; i++) {
            columnTypes[i] = DataType.text();
        }
        this.createTable(session, columns, columnTypes, pKeys, cKeys);
    }

    protected void createTable(CassandraSessionPool.Session session,
                               HugeKeys[] columns,
                               DataType[] columnTypes,
                               HugeKeys[] pKeys,
                               HugeKeys[] cKeys) {

        assert (columns.length == columnTypes.length);

        StringBuilder sb = new StringBuilder(128 + columns.length * 64);

        // Append table
        sb.append("CREATE TABLE IF NOT EXISTS ");
        sb.append(this.table);
        sb.append("(");

        // Append columns
        for (int i = 0; i < columns.length; i++) {
            // Append column name
            sb.append(formatKey(columns[i]));
            sb.append(" ");
            // Append column type
            sb.append(columnTypes[i].asFunctionParameterString());
            sb.append(", ");
        }

        // Append primary keys
        sb.append("PRIMARY KEY (");

        // Append partition keys
        sb.append("(");
        for (HugeKeys i : pKeys) {
            if (i != pKeys[0]) {
                sb.append(", ");
            }
            sb.append(formatKey(i));
        }
        sb.append(")");

        // Append clustering keys
        for (HugeKeys i : cKeys) {
            sb.append(", ");
            sb.append(formatKey(i));
        }

        // Append the end of primary keys
        sb.append(")");

        // Append the end of table declare
        sb.append(");");

        logger.info("Create table: {}", sb);
        session.execute(sb.toString());
    }

    protected void dropTable(CassandraSessionPool.Session session) {
        logger.info("Drop table: {}", this.table);
        session.execute(SchemaBuilder.dropTable(this.table).ifExists());
    }

    protected void createIndex(CassandraSessionPool.Session session,
                               String indexName,
                               HugeKeys column) {

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE INDEX IF NOT EXISTS ");
        sb.append(indexName);
        sb.append(" ON ");
        sb.append(this.table);
        sb.append("(");
        sb.append(formatKey(column));
        sb.append(");");

        logger.info("create index: {}", sb);
        session.execute(sb.toString());
    }

    /*************************** abstract methods ***************************/

    public abstract void init(CassandraSessionPool.Session session);

    public void clear(CassandraSessionPool.Session session) {
        this.dropTable(session);
    }
}

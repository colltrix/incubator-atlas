/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.hive.hook;


import com.google.common.annotations.VisibleForTesting;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.atlas.AtlasClient;
import org.apache.atlas.AtlasConstants;
import org.apache.atlas.hive.bridge.HiveMetaStoreBridge;
import org.apache.atlas.hive.model.HiveDataModelGenerator;
import org.apache.atlas.hive.model.HiveDataTypes;
import org.apache.atlas.hive.rewrite.HiveASTRewriter;
import org.apache.atlas.hook.AtlasHook;
import org.apache.atlas.notification.hook.HookNotification;
import org.apache.atlas.typesystem.Referenceable;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.TableType;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.ql.QueryPlan;
import org.apache.hadoop.hive.ql.exec.ExplainTask;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.hooks.Entity;
import org.apache.hadoop.hive.ql.hooks.Entity.Type;
import org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext;
import org.apache.hadoop.hive.ql.hooks.HookContext;
import org.apache.hadoop.hive.ql.hooks.ReadEntity;
import org.apache.hadoop.hive.ql.hooks.WriteEntity;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.plan.HiveOperation;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.log4j.LogManager;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scala.tools.cmd.gen.AnyVals;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * AtlasHook sends lineage information to the AtlasSever.
 */
public class HiveHook extends AtlasHook implements ExecuteWithHookContext {
    private static final Logger LOG = LoggerFactory.getLogger(HiveHook.class);


    public static final String CONF_PREFIX = "atlas.hook.hive.";
    private static final String MIN_THREADS = CONF_PREFIX + "minThreads";
    private static final String MAX_THREADS = CONF_PREFIX + "maxThreads";
    private static final String KEEP_ALIVE_TIME = CONF_PREFIX + "keepAliveTime";
    public static final String CONF_SYNC = CONF_PREFIX + "synchronous";
    public static final String QUEUE_SIZE = CONF_PREFIX + "queueSize";

    public static final String HOOK_NUM_RETRIES = CONF_PREFIX + "numRetries";

    private static final Map<String, HiveOperation> OPERATION_MAP = new HashMap<>();

    // wait time determines how long we wait before we exit the jvm on
    // shutdown. Pending requests after that will not be sent.
    private static final int WAIT_TIME = 3;
    private static ExecutorService executor;

    private static final int minThreadsDefault = 1;
    private static final int maxThreadsDefault = 5;
    private static final long keepAliveTimeDefault = 10;
    private static final int queueSizeDefault = 10000;

    private List<HookNotification.HookNotificationMessage> messages = new ArrayList<>();

    private static final HiveConf hiveConf;

    static {
        try {
            // initialize the async facility to process hook calls. We don't
            // want to do this inline since it adds plenty of overhead for the query.
            int minThreads = atlasProperties.getInt(MIN_THREADS, minThreadsDefault);
            int maxThreads = atlasProperties.getInt(MAX_THREADS, maxThreadsDefault);
            long keepAliveTime = atlasProperties.getLong(KEEP_ALIVE_TIME, keepAliveTimeDefault);
            int queueSize = atlasProperties.getInt(QUEUE_SIZE, queueSizeDefault);

            executor = new ThreadPoolExecutor(minThreads, maxThreads, keepAliveTime, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(queueSize),
                new ThreadFactoryBuilder().setNameFormat("Atlas Logger %d").build());

            Runtime.getRuntime().addShutdownHook(new Thread() {
                @Override
                public void run() {
                    try {
                        executor.shutdown();
                        executor.awaitTermination(WAIT_TIME, TimeUnit.SECONDS);
                        executor = null;
                    } catch (InterruptedException ie) {
                        LOG.info("Interrupt received in shutdown.");
                    }
                    // shutdown client
                }
            });

            setupOperationMap();
        } catch (Exception e) {
            LOG.info("Attempting to send msg while shutdown in progress.", e);
        }

        hiveConf = new HiveConf();

        LOG.info("Created Atlas Hook");
    }

    private static void setupOperationMap() {
        //Populate OPERATION_MAP - string to HiveOperation mapping
        for (HiveOperation hiveOperation : HiveOperation.values()) {
            OPERATION_MAP.put(hiveOperation.getOperationName(), hiveOperation);
        }
    }

    @Override
    protected String getNumberOfRetriesPropertyKey() {
        return HOOK_NUM_RETRIES;
    }

    @Override
    public void run(final HookContext hookContext) throws Exception {
        // clone to avoid concurrent access
        try {
            final HiveConf conf = new HiveConf(hookContext.getConf());

            final HiveEventContext event = new HiveEventContext();
            event.setInputs(hookContext.getInputs());
            event.setOutputs(hookContext.getOutputs());
            event.setJsonPlan(getQueryPlan(hookContext.getConf(), hookContext.getQueryPlan()));
            event.setHookType(hookContext.getHookType());
            event.setUgi(hookContext.getUgi());
            event.setUser(getUser(hookContext.getUserName()));
            event.setOperation(OPERATION_MAP.get(hookContext.getOperationName()));
            event.setQueryId(hookContext.getQueryPlan().getQueryId());
            event.setQueryStr(hookContext.getQueryPlan().getQueryStr());
            event.setQueryStartTime(hookContext.getQueryPlan().getQueryStartTime());
            event.setQueryType(hookContext.getQueryPlan().getQueryPlan().getQueryType());

            boolean sync = conf.get(CONF_SYNC, "false").equals("true");
            if (sync) {
                fireAndForget(event);
            } else {
                executor.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            fireAndForget(event);
                        } catch (Throwable e) {
                            LOG.error("Atlas hook failed due to error ", e);
                        }
                    }
                });
            }
        } catch(Throwable t) {
            LOG.error("Submitting to thread pool failed due to error ", t);
        }
    }

    private void fireAndForget(HiveEventContext event) throws Exception {
        assert event.getHookType() == HookContext.HookType.POST_EXEC_HOOK : "Non-POST_EXEC_HOOK not supported!";

        LOG.info("Entered Atlas hook for hook type {} operation {}", event.getHookType(), event.getOperation());

        HiveMetaStoreBridge dgiBridge = new HiveMetaStoreBridge(hiveConf);

        switch (event.getOperation()) {
        case CREATEDATABASE:
            handleEventOutputs(dgiBridge, event, Type.DATABASE);
            break;

        case CREATETABLE:
            LinkedHashMap<Type, Referenceable> tablesCreated = handleEventOutputs(dgiBridge, event, Type.TABLE);
            if (tablesCreated.size() > 0) {
                handleExternalTables(dgiBridge, event, tablesCreated);
            }
            break;

        case CREATETABLE_AS_SELECT:

        case CREATEVIEW:
        case ALTERVIEW_AS:
        case LOAD:
        case EXPORT:
        case IMPORT:
        case QUERY:
        case TRUNCATETABLE:
            registerProcess(dgiBridge, event);
            break;

        case ALTERTABLE_RENAME:
        case ALTERVIEW_RENAME:
            renameTable(dgiBridge, event);
            break;

        case ALTERTABLE_FILEFORMAT:
        case ALTERTABLE_CLUSTER_SORT:
        case ALTERTABLE_BUCKETNUM:
        case ALTERTABLE_PROPERTIES:
        case ALTERVIEW_PROPERTIES:
        case ALTERTABLE_SERDEPROPERTIES:
        case ALTERTABLE_SERIALIZER:
        case ALTERTABLE_ADDCOLS:
        case ALTERTABLE_REPLACECOLS:
        case ALTERTABLE_PARTCOLTYPE:
            handleEventOutputs(dgiBridge, event, Type.TABLE);
            break;
        case ALTERTABLE_RENAMECOL:
            renameColumn(dgiBridge, event);
            break;
        case ALTERTABLE_LOCATION:
            LinkedHashMap<Type, Referenceable> tablesUpdated = handleEventOutputs(dgiBridge, event, Type.TABLE);
            if (tablesUpdated != null && tablesUpdated.size() > 0) {
                //Track altered lineage in case of external tables
                handleExternalTables(dgiBridge, event, tablesUpdated);
            }
            break;
        case ALTERDATABASE:
        case ALTERDATABASE_OWNER:
            handleEventOutputs(dgiBridge, event, Type.DATABASE);
            break;

        case DROPTABLE:
        case DROPVIEW:
            deleteTable(dgiBridge, event);
            break;

        case DROPDATABASE:
            deleteDatabase(dgiBridge, event);
            break;

        default:
        }

        notifyEntities(messages);
    }

    private void deleteTable(HiveMetaStoreBridge dgiBridge, HiveEventContext event) {
        for (WriteEntity output : event.getOutputs()) {
            if (Type.TABLE.equals(output.getType())) {
                deleteTable(dgiBridge, event, output);
            }
        }
    }

    private void deleteTable(HiveMetaStoreBridge dgiBridge, HiveEventContext event, WriteEntity output) {
        final String tblQualifiedName = HiveMetaStoreBridge.getTableQualifiedName(dgiBridge.getClusterName(), output.getTable());
        LOG.info("Deleting table {} ", tblQualifiedName);
        messages.add(
            new HookNotification.EntityDeleteRequest(event.getUser(),
                HiveDataTypes.HIVE_TABLE.getName(),
                AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
                tblQualifiedName));
    }

    private void deleteDatabase(HiveMetaStoreBridge dgiBridge, HiveEventContext event) {
        if (event.getOutputs().size() > 1) {
            LOG.info("Starting deletion of tables and databases with cascade {} " , event.getQueryStr());
        }

        for (WriteEntity output : event.getOutputs()) {
            if (Type.TABLE.equals(output.getType())) {
                deleteTable(dgiBridge, event, output);
            } else if (Type.DATABASE.equals(output.getType())) {
                final String dbQualifiedName = HiveMetaStoreBridge.getDBQualifiedName(dgiBridge.getClusterName(), output.getDatabase().getName());
                messages.add(
                    new HookNotification.EntityDeleteRequest(event.getUser(),
                        HiveDataTypes.HIVE_DB.getName(),
                        AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
                        dbQualifiedName));
            }
        }
    }

    private Pair<String, String> findChangedColNames(List<FieldSchema> oldColList, List<FieldSchema> newColList){
        HashMap<FieldSchema, Integer> oldColHashMap = new HashMap<>();
        HashMap<FieldSchema, Integer> newColHashMap = new HashMap<>();
        for (int i = 0; i < oldColList.size(); i++){
            oldColHashMap.put(oldColList.get(i), i);
            newColHashMap.put(newColList.get(i), i);
        }

        String changedColStringOldName = oldColList.get(0).getName();
        String changedColStringNewName = changedColStringOldName;

        for(int i = 0; i < oldColList.size(); i++){
            if (!newColHashMap.containsKey(oldColList.get(i))){
                changedColStringOldName = oldColList.get(i).getName();
                break;
            }
        }

        for(int i = 0; i < newColList.size(); i++){
            if (!oldColHashMap.containsKey(newColList.get(i))){
                changedColStringNewName = newColList.get(i).getName();
                break;
            }
        }

        return Pair.of(changedColStringOldName, changedColStringNewName);
    }

    private void renameColumn(HiveMetaStoreBridge dgiBridge, HiveEventContext event) throws  Exception{
        assert event.getInputs() != null && event.getInputs().size() == 1;
        assert event.getOutputs() != null && event.getOutputs().size() > 0;
        Table oldTable = event.getInputs().iterator().next().getTable();
        List<FieldSchema> oldColList = oldTable.getAllCols();
        List<FieldSchema> newColList = dgiBridge.hiveClient.getTable(event.getOutputs().iterator().next().getTable().getTableName()).getAllCols();
        assert oldColList.size() == newColList.size();

        Pair<String, String> changedColNamePair = findChangedColNames(oldColList, newColList);
        String oldColName = changedColNamePair.getLeft();
        String newColName = changedColNamePair.getRight();
        for(WriteEntity writeEntity : event.getOutputs()){
            if (writeEntity.getType() == Type.TABLE){
                Table newTable = writeEntity.getTable();
                createOrUpdateEntities(dgiBridge, event.getUser(), writeEntity, true, oldTable);
                final String newQualifiedTableName = dgiBridge.getTableQualifiedName(dgiBridge.getClusterName(),
                        newTable);
                String oldColumnQFName = HiveMetaStoreBridge.getColumnQualifiedName(newQualifiedTableName, oldColName);
                String newColumnQFName = HiveMetaStoreBridge.getColumnQualifiedName(newQualifiedTableName, newColName);
                Referenceable newColEntity = new Referenceable(HiveDataTypes.HIVE_COLUMN.getName());
                newColEntity.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, newColumnQFName);

                messages.add(new HookNotification.EntityPartialUpdateRequest(event.getUser(),
                        HiveDataTypes.HIVE_COLUMN.getName(), AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
                        oldColumnQFName, newColEntity));
            }
        }
        handleEventOutputs(dgiBridge, event, Type.TABLE);
    }

    private void renameTable(HiveMetaStoreBridge dgiBridge, HiveEventContext event) throws Exception {
        //crappy, no easy of getting new name
        assert event.getInputs() != null && event.getInputs().size() == 1;
        assert event.getOutputs() != null && event.getOutputs().size() > 0;

        //Update entity if not exists
        ReadEntity oldEntity = event.getInputs().iterator().next();
        Table oldTable = oldEntity.getTable();

        for (WriteEntity writeEntity : event.getOutputs()) {
            if (writeEntity.getType() == Entity.Type.TABLE) {
                Table newTable = writeEntity.getTable();
                //Hive sends with both old and new table names in the outputs which is weird. So skipping that with the below check
                if (!newTable.getDbName().equals(oldTable.getDbName()) || !newTable.getTableName().equals(oldTable.getTableName())) {
                    final String oldQualifiedName = dgiBridge.getTableQualifiedName(dgiBridge.getClusterName(),
                        oldTable);
                    final String newQualifiedName = dgiBridge.getTableQualifiedName(dgiBridge.getClusterName(),
                        newTable);

                    //Create/update old table entity - create entity with oldQFNme and old tableName if it doesnt exist. If exists, will update
                    //We always use the new entity while creating the table since some flags, attributes of the table are not set in inputEntity and Hive.getTable(oldTableName) also fails since the table doesnt exist in hive anymore
                    final LinkedHashMap<Type, Referenceable> tables = createOrUpdateEntities(dgiBridge, event.getUser(), writeEntity, true);
                    Referenceable tableEntity = tables.get(Type.TABLE);

                    //Reset regular column QF Name to old Name and create a new partial notification request to replace old column QFName to newName to retain any existing traits
                    replaceColumnQFName(event, (List<Referenceable>) tableEntity.get(HiveDataModelGenerator.COLUMNS), oldQualifiedName, newQualifiedName);

                    //Reset partition key column QF Name to old Name and create a new partial notification request to replace old column QFName to newName to retain any existing traits
                    replaceColumnQFName(event, (List<Referenceable>) tableEntity.get(HiveDataModelGenerator.PART_COLS), oldQualifiedName, newQualifiedName);

                    //Reset SD QF Name to old Name and create a new partial notification request to replace old SD QFName to newName to retain any existing traits
                    replaceSDQFName(event, tableEntity, oldQualifiedName, newQualifiedName);

                    //Reset Table QF Name to old Name and create a new partial notification request to replace old Table QFName to newName
                    replaceTableQFName(dgiBridge, event, oldTable, newTable, tableEntity, oldQualifiedName, newQualifiedName);
                }
            }
        }
    }

    private Referenceable replaceTableQFName(HiveMetaStoreBridge dgiBridge, HiveEventContext event, Table oldTable, Table newTable, final Referenceable tableEntity, final String oldTableQFName, final String newTableQFName) throws HiveException {
        tableEntity.set(HiveDataModelGenerator.NAME,  oldTable.getTableName().toLowerCase());
        tableEntity.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, oldTableQFName);

        //Replace table entity with new name
        final Referenceable newEntity = new Referenceable(HiveDataTypes.HIVE_TABLE.getName());
        newEntity.set(HiveDataModelGenerator.NAME, newTable.getTableName().toLowerCase());
        newEntity.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, newTableQFName);

        ArrayList<String> alias_list = new ArrayList<>();
        alias_list.add(oldTable.getTableName().toLowerCase());
        newEntity.set(HiveDataModelGenerator.TABLE_ALIAS_LIST, alias_list);
        messages.add(new HookNotification.EntityPartialUpdateRequest(event.getUser(),
            HiveDataTypes.HIVE_TABLE.getName(), AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
            oldTableQFName, newEntity));

        return newEntity;
    }

    private List<Referenceable> replaceColumnQFName(final HiveEventContext event, final List<Referenceable> cols, final String oldTableQFName, final String newTableQFName) {
        List<Referenceable> newColEntities = new ArrayList<>();
        for (Referenceable col : cols) {
            final String colName = (String) col.get(HiveDataModelGenerator.NAME);
            String oldColumnQFName = HiveMetaStoreBridge.getColumnQualifiedName(oldTableQFName, colName);
            String newColumnQFName = HiveMetaStoreBridge.getColumnQualifiedName(newTableQFName, colName);
            col.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, oldColumnQFName);

            Referenceable newColEntity = new Referenceable(HiveDataTypes.HIVE_COLUMN.getName());
            ///Only QF Name changes
            newColEntity.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, newColumnQFName);
            messages.add(new HookNotification.EntityPartialUpdateRequest(event.getUser(),
                HiveDataTypes.HIVE_COLUMN.getName(), AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
                oldColumnQFName, newColEntity));
            newColEntities.add(newColEntity);
        }
        return newColEntities;
    }

    private Referenceable replaceSDQFName(final HiveEventContext event, Referenceable tableEntity, final String oldTblQFName, final String newTblQFName) {
        //Reset storage desc QF Name to old Name
        final Referenceable sdRef = ((Referenceable) tableEntity.get(HiveDataModelGenerator.STORAGE_DESC));
        sdRef.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, HiveMetaStoreBridge.getStorageDescQFName(oldTblQFName));

        //Replace SD QF name first to retain tags
        final String oldSDQFName = HiveMetaStoreBridge.getStorageDescQFName(oldTblQFName);
        final String newSDQFName = HiveMetaStoreBridge.getStorageDescQFName(newTblQFName);

        final Referenceable newSDEntity = new Referenceable(HiveDataTypes.HIVE_STORAGEDESC.getName());
        newSDEntity.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, newSDQFName);
        messages.add(new HookNotification.EntityPartialUpdateRequest(event.getUser(),
            HiveDataTypes.HIVE_STORAGEDESC.getName(), AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME,
            oldSDQFName, newSDEntity));

        return newSDEntity;
    }

    private LinkedHashMap<Type, Referenceable> createOrUpdateEntities(HiveMetaStoreBridge dgiBridge, String user, Entity entity, boolean skipTempTables, Table existTable) throws Exception {
        Database db = null;
        Table table = null;
        Partition partition = null;
        LinkedHashMap<Type, Referenceable> result = new LinkedHashMap<>();
        List<Referenceable> entities = new ArrayList<>();

        switch (entity.getType()) {
        case DATABASE:
            db = entity.getDatabase();
            break;

        case TABLE:
            table = entity.getTable();
            db = dgiBridge.hiveClient.getDatabase(table.getDbName());
            break;

        case PARTITION:
            partition = entity.getPartition();
            table = partition.getTable();
            db = dgiBridge.hiveClient.getDatabase(table.getDbName());
            break;
        }

        db = dgiBridge.hiveClient.getDatabase(db.getName());
        Referenceable dbEntity = dgiBridge.createDBInstance(db);

        entities.add(dbEntity);
        result.put(Type.DATABASE, dbEntity);

        Referenceable tableEntity = null;

        if (table != null) {
            if (existTable != null) {
                table = existTable;
            } else {
                table = dgiBridge.hiveClient.getTable(table.getDbName(), table.getTableName());
            }
            //If its an external table, even though the temp table skip flag is on,
            // we create the table since we need the HDFS path to temp table lineage.
            if (skipTempTables &&
                table.isTemporary() &&
                !TableType.EXTERNAL_TABLE.equals(table.getTableType())) {
               LOG.debug("Skipping temporary table registration {} since it is not an external table {} ", table.getTableName(), table.getTableType().name());

            } else {
                tableEntity = dgiBridge.createTableInstance(dbEntity, table);
                entities.add(tableEntity);
                result.put(Type.TABLE, tableEntity);
            }
        }


        messages.add(new HookNotification.EntityUpdateRequest(user, entities));
        return result;
    }

    private LinkedHashMap<Type, Referenceable> createOrUpdateEntities(HiveMetaStoreBridge dgiBridge, String user, Entity entity, boolean skipTempTables) throws Exception{
        return createOrUpdateEntities(dgiBridge, user, entity, skipTempTables, null);
    }

    private LinkedHashMap<Type, Referenceable> handleEventOutputs(HiveMetaStoreBridge dgiBridge, HiveEventContext event, Type entityType) throws Exception {
        for (Entity entity : event.getOutputs()) {
            if (entity.getType() == entityType) {
                return createOrUpdateEntities(dgiBridge, event.getUser(), entity, true);
            }
        }
        return null;
    }

    private Entity getEntityByType(Set<? extends Entity> entities, Type entityType) {
        for (Entity entity : entities) {
            if (entity.getType() == entityType) {
                return entity;
            }
        }
        return null;
    }


    public static String lower(String str) {
        if (StringUtils.isEmpty(str)) {
            return null;
        }
        return str.toLowerCase().trim();
    }

    public static String normalize(String queryStr) {
        String result = null;
        if (queryStr != null) {
            try {
                HiveASTRewriter rewriter = new HiveASTRewriter(hiveConf);
                result = rewriter.rewrite(queryStr);
            } catch (Exception e) {
                LOG.warn("Could not rewrite query due to error. Proceeding with original query {}", queryStr, e);
            }
        }

        result = lower(result);
        return result;
    }

    private void registerProcess(HiveMetaStoreBridge dgiBridge, HiveEventContext event) throws Exception {
        Set<ReadEntity> inputs = event.getInputs();
        Set<WriteEntity> outputs = event.getOutputs();

        //Even explain CTAS has operation name as CREATETABLE_AS_SELECT
        if (inputs.isEmpty() && outputs.isEmpty()) {
            LOG.info("Explain statement. Skipping...");
            return;
        }

        if (event.getQueryId() == null) {
            LOG.info("Query id/plan is missing for {}", event.getQueryStr());
        }

        final Map<String, Referenceable> source = new LinkedHashMap<>();
        final Map<String, Referenceable> target = new LinkedHashMap<>();
        final Set<Referenceable> entities = new LinkedHashSet<>();

        boolean isSelectQuery = isSelectQuery(event);

        // filter out select queries which do not modify data
        if (!isSelectQuery) {
            for (ReadEntity readEntity : event.getInputs()) {
                processHiveEntity(dgiBridge, event, readEntity, source, entities);
            }

            for (WriteEntity writeEntity : event.getOutputs()) {
                processHiveEntity(dgiBridge, event, writeEntity, target, entities);
            }

            if (source.size() > 0 || target.size() > 0) {
                Referenceable processReferenceable = getProcessReferenceable(dgiBridge, event,
                    new ArrayList<Referenceable>() {{
                        addAll(source.values());
                    }},
                    new ArrayList<Referenceable>() {{
                        addAll(target.values());
                    }});

                entities.add(processReferenceable);
                messages.add(new HookNotification.EntityUpdateRequest(event.getUser(), new ArrayList<Referenceable>(entities)));
            } else {
                LOG.info("Skipped query {} since it has no getInputs() or resulting getOutputs()", event.getQueryStr());
            }
        } else {
            LOG.info("Skipped query {} for processing since it is a select query ", event.getQueryStr());
        }
    }

    private void processHiveEntity(HiveMetaStoreBridge dgiBridge, HiveEventContext event, Entity entity, Map<String, Referenceable> dataSets, Set<Referenceable> entities) throws Exception {
        if (entity.getType() == Type.TABLE || entity.getType() == Type.PARTITION) {
            final String tblQFName = dgiBridge.getTableQualifiedName(dgiBridge.getClusterName(), entity.getTable());
            if (!dataSets.containsKey(tblQFName)) {
                LinkedHashMap<Type, Referenceable> result = createOrUpdateEntities(dgiBridge, event.getUser(), entity, false);
                dataSets.put(tblQFName, result.get(Type.TABLE));
                entities.addAll(result.values());
            }
        } else if (entity.getType() == Type.DFS_DIR) {
            final String pathUri = lower(new Path(entity.getLocation()).toString());
            LOG.info("Registering DFS Path {} ", pathUri);
            Referenceable hdfsPath = dgiBridge.fillHDFSDataSet(pathUri);
            dataSets.put(pathUri, hdfsPath);
            entities.add(hdfsPath);
        }
    }

    private JSONObject getQueryPlan(HiveConf hiveConf, QueryPlan queryPlan) throws Exception {
        try {
            ExplainTask explain = new ExplainTask();
            explain.initialize(hiveConf, queryPlan, null);
            List<Task<?>> rootTasks = queryPlan.getRootTasks();
            return explain.getJSONPlan(null, null, rootTasks, queryPlan.getFetchTask(), true, false, false);
        } catch (Throwable e) {
            LOG.info("Failed to get queryplan", e);
            return new JSONObject();
        }
    }

    private boolean isSelectQuery(HiveEventContext event) {
        if (event.getOperation() == HiveOperation.QUERY) {
            //Select query has only one output
            if (event.getOutputs().size() == 1) {
                WriteEntity output = event.getOutputs().iterator().next();
                /* Strangely select queries have DFS_DIR as the type which seems like a bug in hive. Filter out by checking if the path is a temporary URI
                 * Insert into/overwrite queries onto local or dfs paths have DFS_DIR or LOCAL_DIR as the type and WriteType.PATH_WRITE and tempUri = false
                 * Insert into a temporary table has isTempURI = false. So will not skip as expected
                 */
                if (output.getType() == Type.DFS_DIR || output.getType() == Type.LOCAL_DIR) {
                    if (output.getWriteType() == WriteEntity.WriteType.PATH_WRITE &&
                        output.isTempURI()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void handleExternalTables(final HiveMetaStoreBridge dgiBridge, final HiveEventContext event, final LinkedHashMap<Type, Referenceable> tables) throws HiveException, MalformedURLException {
        List<Referenceable> entities = new ArrayList<>();
        Table hiveTable = getEntityByType(event.getOutputs(), Type.TABLE).getTable();
        //Refresh to get the correct location
        hiveTable = dgiBridge.hiveClient.getTable(hiveTable.getDbName(), hiveTable.getTableName());

        final String location = lower(hiveTable.getDataLocation().toString());
        if (hiveTable != null && TableType.EXTERNAL_TABLE.equals(hiveTable.getTableType())) {
            LOG.info("Registering external table process {} ", event.getQueryStr());
            List<Referenceable> inputs = new ArrayList<Referenceable>() {{
                add(dgiBridge.fillHDFSDataSet(location));
            }};

            List<Referenceable> outputs = new ArrayList<Referenceable>() {{
                add(tables.get(Type.TABLE));
            }};

            Referenceable processReferenceable = getProcessReferenceable(dgiBridge, event, inputs, outputs);
            entities.addAll(tables.values());
            entities.add(processReferenceable);
            messages.add(new HookNotification.EntityUpdateRequest(event.getUser(), entities));
        }
    }

    private boolean isCreateOp(HiveEventContext hiveEvent) {
        if (HiveOperation.CREATETABLE.equals(hiveEvent.getOperation())
            || HiveOperation.CREATEVIEW.equals(hiveEvent.getOperation())
            || HiveOperation.ALTERVIEW_AS.equals(hiveEvent.getOperation())
            || HiveOperation.CREATETABLE_AS_SELECT.equals(hiveEvent.getOperation())) {
            return true;
        }
        return false;
    }

    private Referenceable getProcessReferenceable(HiveMetaStoreBridge dgiBridge, HiveEventContext hiveEvent, List<Referenceable> sourceList, List<Referenceable> targetList) {
        Referenceable processReferenceable = new Referenceable(HiveDataTypes.HIVE_PROCESS.getName());

        String queryStr = hiveEvent.getQueryStr();
        if (!isCreateOp(hiveEvent)) {
            queryStr = normalize(queryStr);
            processReferenceable.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, getProcessQualifiedName(queryStr, sourceList, targetList));
        } else {
            queryStr = lower(queryStr);
            processReferenceable.set(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME, queryStr);
        }

        LOG.debug("Registering query: {}", queryStr);

        //The serialization code expected a list
        if (sourceList != null || !sourceList.isEmpty()) {
            processReferenceable.set("inputs", sourceList);
        }
        if (targetList != null || !targetList.isEmpty()) {
            processReferenceable.set("outputs", targetList);
        }
        processReferenceable.set(AtlasClient.NAME, queryStr);

        processReferenceable.set("operationType", hiveEvent.getOperation().getOperationName());
        processReferenceable.set("startTime", new Date(hiveEvent.getQueryStartTime()));
        processReferenceable.set("userName", hiveEvent.getUser());
        processReferenceable.set("queryText", queryStr);
        processReferenceable.set("queryId", hiveEvent.getQueryId());
        processReferenceable.set("queryPlan", hiveEvent.getJsonPlan());
        processReferenceable.set(AtlasConstants.CLUSTER_NAME_ATTRIBUTE, dgiBridge.getClusterName());

        List<String> recentQueries = new ArrayList<>(1);
        recentQueries.add(hiveEvent.getQueryStr());
        processReferenceable.set("recentQueries", recentQueries);
        processReferenceable.set("endTime", new Date(System.currentTimeMillis()));
        //TODO set queryGraph
        return processReferenceable;
    }

    @VisibleForTesting
    static String getProcessQualifiedName(String normalizedQuery, List<Referenceable> inputs, List<Referenceable> outputs) {
        StringBuilder buffer = new StringBuilder(normalizedQuery);
        addDatasets(buffer, inputs);
        addDatasets(buffer, outputs);
        return buffer.toString();
    }

    private static void addDatasets(StringBuilder buffer, List<Referenceable> refs) {
        if (refs != null) {
            for (Referenceable input : refs) {
                //TODO - Change to qualifiedName later
                buffer.append(":");
                String dataSetQlfdName = (String) input.get(AtlasClient.REFERENCEABLE_ATTRIBUTE_NAME);
                buffer.append(dataSetQlfdName.toLowerCase().replaceAll("/", ""));
            }
        }
    }

    public static class HiveEventContext {
        private Set<ReadEntity> inputs;
        private Set<WriteEntity> outputs;

        private String user;
        private UserGroupInformation ugi;
        private HiveOperation operation;
        private HookContext.HookType hookType;
        private JSONObject jsonPlan;
        private String queryId;
        private String queryStr;
        private Long queryStartTime;

        private String queryType;

        public void setInputs(Set<ReadEntity> inputs) {
            this.inputs = inputs;
        }

        public void setOutputs(Set<WriteEntity> outputs) {
            this.outputs = outputs;
        }

        public void setUser(String user) {
            this.user = user;
        }

        public void setUgi(UserGroupInformation ugi) {
            this.ugi = ugi;
        }

        public void setOperation(HiveOperation operation) {
            this.operation = operation;
        }

        public void setHookType(HookContext.HookType hookType) {
            this.hookType = hookType;
        }

        public void setJsonPlan(JSONObject jsonPlan) {
            this.jsonPlan = jsonPlan;
        }

        public void setQueryId(String queryId) {
            this.queryId = queryId;
        }

        public void setQueryStr(String queryStr) {
            this.queryStr = queryStr;
        }

        public void setQueryStartTime(Long queryStartTime) {
            this.queryStartTime = queryStartTime;
        }

        public void setQueryType(String queryType) {
            this.queryType = queryType;
        }

        public Set<ReadEntity> getInputs() {
            return inputs;
        }

        public Set<WriteEntity> getOutputs() {
            return outputs;
        }

        public String getUser() {
            return user;
        }

        public UserGroupInformation getUgi() {
            return ugi;
        }

        public HiveOperation getOperation() {
            return operation;
        }

        public HookContext.HookType getHookType() {
            return hookType;
        }

        public JSONObject getJsonPlan() {
            return jsonPlan;
        }

        public String getQueryId() {
            return queryId;
        }

        public String getQueryStr() {
            return queryStr;
        }

        public Long getQueryStartTime() {
            return queryStartTime;
        }

        public String getQueryType() {
            return queryType;
        }

    }
}

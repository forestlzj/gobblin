/*
 * Copyright (C) 2014-2016 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */
package gobblin.data.management.conversion.hive;

import java.util.HashMap;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.api.AlreadyExistsException;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.SerDeInfo;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.joda.time.DateTime;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;

import gobblin.configuration.ConfigurationKeys;
import gobblin.configuration.SourceState;
import gobblin.configuration.WorkUnitState;
import gobblin.data.management.conversion.hive.entities.SerializableHivePartition;
import gobblin.data.management.conversion.hive.entities.SerializableHiveTable;
import gobblin.data.management.conversion.hive.provider.HiveMetastoreBasedUpdateProvider;
import gobblin.data.management.conversion.hive.provider.HiveUnitUpdateProvider;
import gobblin.data.management.conversion.hive.util.HiveSourceUtils;
import gobblin.hive.HiveMetastoreClientPool;
import gobblin.hive.avro.HiveAvroSerDeManager;
import gobblin.source.extractor.extract.LongWatermark;
import gobblin.source.workunit.WorkUnit;


@Slf4j
@Test(groups = { "gobblin.data.management.conversion" })
public class HiveSourceTest {

  private IMetaStoreClient localMetastoreClient;
  private HiveSource hiveSource;
  private HiveUnitUpdateProvider updateProvider;

  @BeforeClass
  public void setup() throws Exception {
    this.localMetastoreClient =
        HiveMetastoreClientPool.get(new Properties(), Optional.<String> absent()).getClient().get();
    this.hiveSource = new HiveSource();
    this.updateProvider = new HiveMetastoreBasedUpdateProvider();
  }

  @Test
  public void testGetWorkUnitsForTable() throws Exception {

    String dbName = "testdb2";
    String tableName = "testtable2";
    String tableSdLoc = "/tmp/testtable2";

    this.localMetastoreClient.dropDatabase(dbName, false, true, true);

    SourceState testState = getTestState(dbName);

    createTestTable(dbName, tableName, tableSdLoc, Optional.<String> absent());

    List<WorkUnit> workUnits = hiveSource.getWorkunits(testState);

    Assert.assertEquals(workUnits.size(), 1);
    WorkUnit wu = workUnits.get(0);

    SerializableHiveTable serializedHiveTable = HiveSourceUtils.deserializeTable(wu);

    Assert.assertEquals(serializedHiveTable.getDbName(), dbName);
    Assert.assertEquals(serializedHiveTable.getTableName(), tableName);
    Assert.assertEquals(serializedHiveTable.getSchemaUrl(), new Path("/tmp/dummy"));

  }

  @Test
  public void testGetWorkUnitsForPartitions() throws Exception {

    String dbName = "testdb3";
    String tableName = "testtable3";
    String tableSdLoc = "/tmp/testtable3";

    this.localMetastoreClient.dropDatabase(dbName, false, true, true);

    SourceState testState = getTestState(dbName);

    Table tbl = createTestTable(dbName, tableName, tableSdLoc, Optional.of("field"));

    addTestPartition(tbl, ImmutableList.of("f1"), (int) System.currentTimeMillis());

    List<WorkUnit> workUnits = this.hiveSource.getWorkunits(testState);

    Assert.assertEquals(workUnits.size(), 1);
    WorkUnit wu = workUnits.get(0);

    SerializableHiveTable serializedHiveTable = HiveSourceUtils.deserializeTable(wu);
    SerializableHivePartition serializedHivePartition = HiveSourceUtils.deserializePartition(wu);

    Assert.assertEquals(serializedHiveTable.getDbName(), dbName);
    Assert.assertEquals(serializedHiveTable.getTableName(), tableName);

    Assert.assertEquals(serializedHivePartition.getPartitionName(), "field=f1");

  }

  @Test
  public void testGetWorkunitsAfterWatermark() throws Exception {

    String dbName = "testdb4";
    String tableName1 = "testtable1";
    String tableSdLoc1 = "/tmp/testtable1";
    String tableName2 = "testtable2";
    String tableSdLoc2 = "/tmp/testtable2";

    this.localMetastoreClient.dropDatabase(dbName, false, true, true);

    createTestTable(dbName, tableName1, tableSdLoc1, Optional.<String> absent());
    createTestTable(dbName, tableName2, tableSdLoc2, Optional.<String> absent(), true);

    List<WorkUnitState> previousWorkUnitStates = Lists.newArrayList();

    Table table1 = this.localMetastoreClient.getTable(dbName, tableName1);

    previousWorkUnitStates.add(createPreviousWus(dbName, tableName1,
        TimeUnit.MILLISECONDS.convert(table1.getCreateTime(), TimeUnit.SECONDS)));

    SourceState testState = new SourceState(getTestState(dbName), previousWorkUnitStates);

    List<WorkUnit> workUnits = this.hiveSource.getWorkunits(testState);

    Assert.assertEquals(workUnits.size(), 1);
    WorkUnit wu = workUnits.get(0);

    SerializableHiveTable serializedHiveTable = HiveSourceUtils.deserializeTable(wu);

    Assert.assertEquals(serializedHiveTable.getDbName(), dbName);
    Assert.assertEquals(serializedHiveTable.getTableName(), tableName2);

  }

  @Test
  public void testShouldCreateWorkunitsOlderThanLookback() throws Exception {

    long currentTime = System.currentTimeMillis();
    long watermarkTime = new DateTime(currentTime).minusDays(50).getMillis();
    long partitionCreateTime = new DateTime(currentTime).minusDays(35).getMillis();

    LongWatermark watermark = new LongWatermark(watermarkTime);

    org.apache.hadoop.hive.ql.metadata.Partition partition = createDummyPartition(partitionCreateTime);

    SourceState testState = getTestState("testDb6");
    HiveSource source = new HiveSource();
    source.initialize(testState);

    boolean shouldCreate =
        source.shouldCreateWorkunitForPartition(partition, updateProvider.getUpdateTime(partition), watermark);

    Assert.assertEquals(shouldCreate, false, "Should not create workunits older than lookback");

  }

  @Test
  public void testShouldCreateWorkunitsNewerThanLookback() throws Exception {

    long currentTime = System.currentTimeMillis();
    long watermarkTime = new DateTime(currentTime).minusDays(50).getMillis();
    long partitionCreateTime = new DateTime(currentTime).minusDays(25).getMillis();

    LongWatermark watermark = new LongWatermark(watermarkTime);

    org.apache.hadoop.hive.ql.metadata.Partition partition = createDummyPartition(partitionCreateTime);

    SourceState testState = getTestState("testDb7");
    HiveSource source = new HiveSource();
    source.initialize(testState);

    boolean shouldCreate =
        source.shouldCreateWorkunitForPartition(partition, updateProvider.getUpdateTime(partition), watermark);

    Assert.assertEquals(shouldCreate, true, "Should create workunits newer than lookback");

  }

  private static WorkUnitState createPreviousWus(String dbName, String tableName, long watermark) {

    WorkUnitState wus = new WorkUnitState();
    wus.setActualHighWatermark(new LongWatermark(watermark));
    wus.setProp(ConfigurationKeys.DATASET_URN_KEY, dbName + "@" + tableName);

    return wus;
  }

  private Table createTestTable(String dbName, String tableName, String tableSdLoc, Optional<String> partitionFieldName)
      throws Exception {
    return createTestTable(dbName, tableName, tableSdLoc, partitionFieldName, false);
  }

  private Table createTestTable(String dbName, String tableName, String tableSdLoc,
      Optional<String> partitionFieldName, boolean ignoreDbCreation) throws Exception {
    if (!ignoreDbCreation) {
      createTestDb(dbName);
    }

    Table tbl = org.apache.hadoop.hive.ql.metadata.Table.getEmptyTable(dbName, tableName);
    tbl.getSd().setLocation(tableSdLoc);
    tbl.getSd().getSerdeInfo().setParameters(ImmutableMap.of(HiveAvroSerDeManager.SCHEMA_URL, "/tmp/dummy"));

    if (partitionFieldName.isPresent()) {
      tbl.addToPartitionKeys(new FieldSchema(partitionFieldName.get(), "string", "some comment"));
    }

    this.localMetastoreClient.createTable(tbl);

    return tbl;
  }

  private void createTestDb(String dbName) throws Exception {

    Database db = new Database(dbName, "Some description", "/tmp/" + dbName, new HashMap<String, String>());
    try {
      this.localMetastoreClient.createDatabase(db);
    } catch (AlreadyExistsException e) {
      log.warn(dbName + " already exits");
    }

  }

  private static SourceState getTestState(String dbName) {
    SourceState testState = new SourceState();
    testState.setProp("hive.dataset.database", dbName);
    testState.setProp("hive.dataset.table.pattern", "*");
    testState.setProp(ConfigurationKeys.JOB_ID_KEY, "testJobId");
    return testState;
  }

  private Partition addTestPartition(Table tbl, List<String> values, int createTime) throws Exception {
    StorageDescriptor partitionSd = new StorageDescriptor();
    partitionSd.setLocation("/tmp/" + tbl.getTableName() + "/part1");
    partitionSd.setSerdeInfo(new SerDeInfo("name", "serializationLib", ImmutableMap.of(HiveAvroSerDeManager.SCHEMA_URL,
        "/tmp/dummy")));
    partitionSd.setCols(tbl.getPartitionKeys());
    Partition partition =
        new Partition(values, tbl.getDbName(), tbl.getTableName(), 1, 1, partitionSd, new HashMap<String, String>());
    partition.setCreateTime(createTime);
    return this.localMetastoreClient.add_partition(partition);

  }

  private org.apache.hadoop.hive.ql.metadata.Partition createDummyPartition(long createTime) {
    org.apache.hadoop.hive.ql.metadata.Partition partition = new org.apache.hadoop.hive.ql.metadata.Partition();
    Partition tPartition = new Partition();
    tPartition.setCreateTime((int) TimeUnit.SECONDS.convert(createTime, TimeUnit.MILLISECONDS));
    partition.setTPartition(tPartition);
    return partition;
  }

}

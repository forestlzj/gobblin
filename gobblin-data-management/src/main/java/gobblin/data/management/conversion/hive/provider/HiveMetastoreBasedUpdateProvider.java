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
package gobblin.data.management.conversion.hive.provider;

import java.util.concurrent.TimeUnit;

import lombok.NoArgsConstructor;

import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;

import gobblin.annotation.Alpha;

/**
 * An update provider that uses update metadata from Hive metastore
 */
@Alpha
@NoArgsConstructor
public class HiveMetastoreBasedUpdateProvider implements HiveUnitUpdateProvider {

  @Override
  public long getUpdateTime(Partition partition) throws UpdateNotFoundExecption {
    return TimeUnit.MILLISECONDS.convert(partition.getTPartition().getCreateTime(), TimeUnit.SECONDS);
  }

  @Override
  public long getUpdateTime(Table table) throws UpdateNotFoundExecption {
    return TimeUnit.MILLISECONDS.convert(table.getTTable().getCreateTime(), TimeUnit.SECONDS);
  }

}

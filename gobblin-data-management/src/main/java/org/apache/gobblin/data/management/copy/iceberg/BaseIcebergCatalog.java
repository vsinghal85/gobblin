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

package org.apache.gobblin.data.management.copy.iceberg;

import java.util.Map;

import org.apache.hadoop.conf.Configuration;
import org.apache.iceberg.CatalogUtil;
import org.apache.iceberg.Table;
import org.apache.iceberg.TableOperations;
import org.apache.iceberg.catalog.Catalog;
import org.apache.iceberg.catalog.TableIdentifier;
import org.apache.gobblin.dataset.DatasetConstants;
import org.apache.iceberg.exceptions.NoSuchTableException;

/**
 * Base implementation of {@link IcebergCatalog} to access {@link IcebergTable} and the
 * underlying concrete companion catalog e.g. {@link org.apache.iceberg.hive.HiveCatalog}
 */
public abstract class BaseIcebergCatalog implements IcebergCatalog {

  protected final String catalogName;
  protected final Class<? extends Catalog> companionCatalogClass;

  protected BaseIcebergCatalog(String catalogName, Class<? extends Catalog> companionCatalogClass) {
    this.catalogName = catalogName;
    this.companionCatalogClass = companionCatalogClass;
  }

  @Override
  public IcebergTable openTable(String dbName, String tableName) throws IcebergTable.TableNotFoundException {
    TableIdentifier tableId = TableIdentifier.of(dbName, tableName);
    try {
      return new IcebergTable(tableId, calcDatasetDescriptorName(tableId), getDatasetDescriptorPlatform(),
          createTableOperations(tableId), this.getCatalogUri(), loadTableInstance(tableId));
    } catch (NoSuchTableException ex) {
      // defend against `org.apache.iceberg.catalog.Catalog::loadTable` throwing inside some `@Override` of `loadTableInstance`
      throw new IcebergTable.TableNotFoundException(tableId);
    }
  }

  protected Catalog createCompanionCatalog(Map<String, String> properties, Configuration configuration) {
    return CatalogUtil.loadCatalog(this.companionCatalogClass.getName(), this.catalogName, properties, configuration);
  }

  /**
   * Enable catalog-specific qualification for charting lineage, etc.  This default impl is an identity pass-through that adds no qualification.
   * @return the name to use for the table identified by {@link TableIdentifier}
   */
  protected String calcDatasetDescriptorName(TableIdentifier tableId) {
    return tableId.toString(); // default to FQ ID with both table namespace and name
  }

  /**
   * Enable catalog-specific naming for charting lineage, etc.  This default impl gives {@link DatasetConstants#PLATFORM_ICEBERG}
   * @return the {@link org.apache.gobblin.dataset.DatasetDescriptor#getPlatform()} to use for tables from this catalog
   */
  protected String getDatasetDescriptorPlatform() {
    return DatasetConstants.PLATFORM_ICEBERG;
  }

  protected abstract TableOperations createTableOperations(TableIdentifier tableId);

  protected abstract Table loadTableInstance(TableIdentifier tableId);
}

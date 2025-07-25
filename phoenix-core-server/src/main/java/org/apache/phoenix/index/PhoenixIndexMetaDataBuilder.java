/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.index;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.apache.hadoop.hbase.client.Mutation;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.MiniBatchOperationInProgress;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.cache.IndexMetaDataCache;
import org.apache.phoenix.cache.ServerCacheClient;
import org.apache.phoenix.cache.TenantCache;
import org.apache.phoenix.coprocessorclient.BaseScannerRegionObserverConstants;
import org.apache.phoenix.exception.SQLExceptionCode;
import org.apache.phoenix.exception.SQLExceptionInfo;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.transaction.PhoenixTransactionContext;
import org.apache.phoenix.transaction.TransactionFactory;
import org.apache.phoenix.util.ClientUtil;
import org.apache.phoenix.util.PhoenixRuntime;
import org.apache.phoenix.util.ScanUtil;

public class PhoenixIndexMetaDataBuilder {
  private final RegionCoprocessorEnvironment env;

  PhoenixIndexMetaDataBuilder(RegionCoprocessorEnvironment env) {
    this.env = env;
  }

  public PhoenixIndexMetaData getIndexMetaData(MiniBatchOperationInProgress<Mutation> miniBatchOp)
    throws IOException {
    IndexMetaDataCache indexMetaDataCache =
      getIndexMetaDataCache(env, miniBatchOp.getOperation(0).getAttributesMap());
    return new PhoenixIndexMetaData(indexMetaDataCache,
      miniBatchOp.getOperation(0).getAttributesMap());
  }

  private static IndexMetaDataCache getIndexMetaDataCache(RegionCoprocessorEnvironment env,
    Map<String, byte[]> attributes) throws IOException {
    if (attributes == null) {
      return IndexMetaDataCache.EMPTY_INDEX_META_DATA_CACHE;
    }
    byte[] uuid = attributes.get(PhoenixIndexCodec.INDEX_UUID);
    if (uuid == null) {
      return IndexMetaDataCache.EMPTY_INDEX_META_DATA_CACHE;
    }
    byte[] md = attributes.get(PhoenixIndexCodec.INDEX_PROTO_MD);
    if (md == null) {
      md = attributes.get(PhoenixIndexCodec.INDEX_MD);
    }
    if (md != null) {
      boolean useProto = md != null;
      byte[] txState = attributes.get(BaseScannerRegionObserverConstants.TX_STATE);
      final List<IndexMaintainer> indexMaintainers = IndexMaintainer.deserialize(md, useProto);
      byte[] clientVersionBytes = attributes.get(BaseScannerRegionObserverConstants.CLIENT_VERSION);
      final int clientVersion = clientVersionBytes == null
        ? ScanUtil.UNKNOWN_CLIENT_VERSION
        : Bytes.toInt(clientVersionBytes);
      final PhoenixTransactionContext txnContext =
        TransactionFactory.getTransactionContext(txState, clientVersion);
      return new IndexMetaDataCache() {

        @Override
        public void close() throws IOException {
        }

        @Override
        public List<IndexMaintainer> getIndexMaintainers() {
          return indexMaintainers;
        }

        @Override
        public PhoenixTransactionContext getTransactionContext() {
          return txnContext;
        }

        @Override
        public int getClientVersion() {
          return clientVersion;
        }

      };
    } else {
      byte[] tenantIdBytes = attributes.get(PhoenixRuntime.TENANT_ID_ATTRIB);
      ImmutableBytesPtr tenantId =
        tenantIdBytes == null ? null : new ImmutableBytesPtr(tenantIdBytes);
      TenantCache cache = GlobalCache.getTenantCache(env, tenantId);
      IndexMetaDataCache indexCache =
        (IndexMetaDataCache) cache.getServerCache(new ImmutableBytesPtr(uuid));
      if (indexCache == null) {
        String msg = "key=" + ServerCacheClient.idToString(uuid) + " region=" + env.getRegion()
          + "host=" + env.getServerName().getServerName();
        SQLException e = new SQLExceptionInfo.Builder(SQLExceptionCode.INDEX_METADATA_NOT_FOUND)
          .setMessage(msg).build().buildException();
        ClientUtil.throwIOException("Index update failed", e); // will not return
      }
      return indexCache;
    }

  }
}

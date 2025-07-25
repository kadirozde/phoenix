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
package org.apache.phoenix.coprocessor;

import static org.apache.phoenix.util.ScanUtil.getDummyResult;
import static org.apache.phoenix.util.ScanUtil.getPageSizeMsForRegionScanner;
import static org.apache.phoenix.util.ScanUtil.isDummy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.client.RegionInfo;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.coprocessor.RegionCoprocessorEnvironment;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.regionserver.ScannerContext;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.cache.GlobalCache;
import org.apache.phoenix.cache.HashCache;
import org.apache.phoenix.cache.TenantCache;
import org.apache.phoenix.coprocessorclient.HashJoinCacheNotFoundException;
import org.apache.phoenix.execute.TupleProjector;
import org.apache.phoenix.execute.TupleProjector.ProjectedValueTuple;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.KeyValueColumnExpression;
import org.apache.phoenix.hbase.index.util.ImmutableBytesPtr;
import org.apache.phoenix.iterate.RegionScannerFactory;
import org.apache.phoenix.join.HashJoinInfo;
import org.apache.phoenix.parse.JoinTableNode.JoinType;
import org.apache.phoenix.schema.IllegalDataException;
import org.apache.phoenix.schema.KeyValueSchema;
import org.apache.phoenix.schema.ValueBitSet;
import org.apache.phoenix.schema.tuple.MultiKeyValueTuple;
import org.apache.phoenix.schema.tuple.PositionBasedResultTuple;
import org.apache.phoenix.schema.tuple.ResultTuple;
import org.apache.phoenix.schema.tuple.SingleKeyValueTuple;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.util.ClientUtil;
import org.apache.phoenix.util.EnvironmentEdgeManager;
import org.apache.phoenix.util.TupleUtil;

public class HashJoinRegionScanner implements RegionScanner {

  private final RegionScanner scanner;
  private final TupleProjector projector;
  private final HashJoinInfo joinInfo;
  private final RegionCoprocessorEnvironment env;
  private Queue<Tuple> resultQueue;
  private boolean hasMore;
  private long count;
  private long limit;
  private HashCache[] hashCaches;
  private List<Tuple>[] tempTuples;
  private ValueBitSet tempDestBitSet;
  private ValueBitSet[] tempSrcBitSet;
  private final boolean useQualifierAsListIndex;
  private final boolean useNewValueColumnQualifier;
  private final boolean addArrayCell;
  private final long pageSizeMs;

  @SuppressWarnings("unchecked")
  public HashJoinRegionScanner(RegionScanner scanner, Scan scan, TupleProjector projector,
    HashJoinInfo joinInfo, ImmutableBytesPtr tenantId, RegionCoprocessorEnvironment env,
    boolean useQualifierAsIndex, boolean useNewValueColumnQualifier) throws IOException {

    this(env, scanner, scan, null, null, projector, joinInfo, tenantId, useQualifierAsIndex,
      useNewValueColumnQualifier);
  }

  @SuppressWarnings("unchecked")
  public HashJoinRegionScanner(RegionCoprocessorEnvironment env, RegionScanner scanner, Scan scan,
    final Set<KeyValueColumnExpression> arrayKVRefs, final Expression[] arrayFuncRefs,
    TupleProjector projector, HashJoinInfo joinInfo, ImmutableBytesPtr tenantId,
    boolean useQualifierAsIndex, boolean useNewValueColumnQualifier) throws IOException {

    this.env = env;
    this.scanner = scanner;
    this.projector = projector;
    this.joinInfo = joinInfo;
    this.resultQueue = new LinkedList<Tuple>();
    this.hasMore = true;
    this.count = 0;
    this.limit = Long.MAX_VALUE;
    for (JoinType type : joinInfo.getJoinTypes()) {
      if (
        type != JoinType.Inner && type != JoinType.Left && type != JoinType.Semi
          && type != JoinType.Anti
      ) throw new DoNotRetryIOException(
        "Got join type '" + type + "'. Expect only INNER or LEFT with hash-joins.");
    }
    if (joinInfo.getLimit() != null) {
      this.limit = joinInfo.getLimit();
    }
    int count = joinInfo.getJoinIds().length;
    this.tempTuples = new List[count];
    this.hashCaches = new HashCache[count];
    this.tempSrcBitSet = new ValueBitSet[count];
    TenantCache cache = GlobalCache.getTenantCache(env, tenantId);
    for (int i = 0; i < count; i++) {
      ImmutableBytesPtr joinId = joinInfo.getJoinIds()[i];
      if (joinId.getLength() == 0) { // semi-join optimized into skip-scan
        hashCaches[i] = null;
        tempSrcBitSet[i] = null;
        tempTuples[i] = null;
        continue;
      }
      HashCache hashCache = (HashCache) cache.getServerCache(joinId);
      if (hashCache == null) {
        Exception cause = new HashJoinCacheNotFoundException(Bytes.toLong(joinId.get()));
        throw new DoNotRetryIOException(cause.getMessage(), cause);
      }

      hashCaches[i] = hashCache;
      tempSrcBitSet[i] = ValueBitSet.newInstance(joinInfo.getSchemas()[i]);
    }
    if (this.projector != null) {
      this.tempDestBitSet = ValueBitSet.newInstance(joinInfo.getJoinedSchema());
      this.projector.setValueBitSet(tempDestBitSet);
    }
    this.useQualifierAsListIndex = useQualifierAsIndex;
    this.useNewValueColumnQualifier = useNewValueColumnQualifier;
    this.addArrayCell = (arrayFuncRefs != null && arrayFuncRefs.length > 0 && arrayKVRefs != null
      && arrayKVRefs.size() > 0);
    this.pageSizeMs = getPageSizeMsForRegionScanner(scan);
  }

  private void processResults(List<Cell> result, boolean hasBatchLimit) throws IOException {
    if (result.isEmpty()) return;
    Tuple tuple = useQualifierAsListIndex
      ? new PositionBasedResultTuple(result)
      : new ResultTuple(Result.create(result));
    boolean projected = false;

    // For backward compatibility. In new versions, HashJoinInfo.forceProjection()
    // always returns true.
    if (joinInfo.forceProjection()) {
      tuple = projector.projectResults(tuple, useNewValueColumnQualifier);
      projected = true;
    }

    // TODO: fix below Scanner.next() and Scanner.nextRaw() methods as well.
    if (hasBatchLimit)
      throw new UnsupportedOperationException("Cannot support join operations in scans with limit");

    int count = joinInfo.getJoinIds().length;
    boolean cont = true;
    for (int i = 0; i < count; i++) {
      if (!(joinInfo.earlyEvaluation()[i]) || hashCaches[i] == null) continue;
      ImmutableBytesPtr key =
        TupleUtil.getConcatenatedValue(tuple, joinInfo.getJoinExpressions()[i]);
      tempTuples[i] = hashCaches[i].get(key);
      JoinType type = joinInfo.getJoinTypes()[i];
      if (
        ((type == JoinType.Inner || type == JoinType.Semi) && tempTuples[i] == null)
          || (type == JoinType.Anti && tempTuples[i] != null)
      ) {
        cont = false;
        break;
      }
    }
    if (cont) {
      if (projector == null) {
        int dup = 1;
        for (int i = 0; i < count; i++) {
          dup *= (tempTuples[i] == null ? 1 : tempTuples[i].size());
        }
        for (int i = 0; i < dup; i++) {
          offerResult(tuple, projected, result);
        }
      } else {
        KeyValueSchema schema = joinInfo.getJoinedSchema();
        if (!joinInfo.forceProjection()) { // backward compatibility
          tuple = projector.projectResults(tuple, useNewValueColumnQualifier);
          projected = true;
        }
        offerResult(tuple, projected, result);
        for (int i = 0; i < count; i++) {
          boolean earlyEvaluation = joinInfo.earlyEvaluation()[i];
          JoinType type = joinInfo.getJoinTypes()[i];
          if (earlyEvaluation && (type == JoinType.Semi || type == JoinType.Anti)) continue;
          int j = resultQueue.size();
          while (j-- > 0) {
            Tuple lhs = resultQueue.poll();
            if (!earlyEvaluation) {
              ImmutableBytesPtr key =
                TupleUtil.getConcatenatedValue(lhs, joinInfo.getJoinExpressions()[i]);
              tempTuples[i] = hashCaches[i].get(key);
              if (tempTuples[i] == null) {
                if (type == JoinType.Inner || type == JoinType.Semi) {
                  continue;
                } else if (type == JoinType.Anti) {
                  offerResult(lhs, projected, result);
                  continue;
                }
              }
            }
            if (tempTuples[i] == null) {
              Tuple joined = tempSrcBitSet[i] == ValueBitSet.EMPTY_VALUE_BITSET
                ? lhs
                : mergeProjectedValue(lhs, schema, tempDestBitSet, null, joinInfo.getSchemas()[i],
                  tempSrcBitSet[i], joinInfo.getFieldPositions()[i]);
              offerResult(joined, projected, result);
              continue;
            }
            for (Tuple t : tempTuples[i]) {
              Tuple joined = tempSrcBitSet[i] == ValueBitSet.EMPTY_VALUE_BITSET
                ? lhs
                : mergeProjectedValue(lhs, schema, tempDestBitSet, t, joinInfo.getSchemas()[i],
                  tempSrcBitSet[i], joinInfo.getFieldPositions()[i]);
              offerResult(joined, projected, result);
            }
          }
        }
      }
      // apply post-join filter
      Expression postFilter = joinInfo.getPostJoinFilterExpression();
      if (postFilter != null) {
        for (Iterator<Tuple> iter = resultQueue.iterator(); iter.hasNext();) {
          Tuple t = iter.next();
          postFilter.reset();
          ImmutableBytesPtr tempPtr = new ImmutableBytesPtr();
          try {
            if (!postFilter.evaluate(t, tempPtr) || tempPtr.getLength() == 0) {
              iter.remove();
              continue;
            }
          } catch (IllegalDataException e) {
            iter.remove();
            continue;
          }
          Boolean b = (Boolean) postFilter.getDataType().toObject(tempPtr);
          if (!Boolean.TRUE.equals(b)) {
            iter.remove();
          }
        }
      }
    }
  }

  private boolean shouldAdvance() {
    if (!resultQueue.isEmpty()) return false;

    return hasMore;
  }

  private boolean nextInQueue(List<Cell> results) {
    if (resultQueue.isEmpty()) {
      return false;
    }

    Tuple tuple = resultQueue.poll();
    for (int i = 0; i < tuple.size(); i++) {
      results.add(tuple.getValue(i));
    }
    return (count++ < limit) && (resultQueue.isEmpty() ? hasMore : true);
  }

  @Override
  public long getMvccReadPoint() {
    return scanner.getMvccReadPoint();
  }

  @Override
  public RegionInfo getRegionInfo() {
    return scanner.getRegionInfo();
  }

  @Override
  public boolean isFilterDone() throws IOException {
    return scanner.isFilterDone() && resultQueue.isEmpty();
  }

  @Override
  public boolean nextRaw(List<Cell> result) throws IOException {
    return next(result, true, null);
  }

  @Override
  public boolean nextRaw(List<Cell> result, ScannerContext scannerContext) throws IOException {
    return next(result, true, scannerContext);
  }

  private boolean next(List<Cell> result, boolean raw, ScannerContext scannerContext)
    throws IOException {
    try {
      long startTime = EnvironmentEdgeManager.currentTimeMillis();
      while (shouldAdvance()) {
        if (scannerContext != null) {
          hasMore =
            raw ? scanner.nextRaw(result, scannerContext) : scanner.next(result, scannerContext);
        } else {
          hasMore = raw ? scanner.nextRaw(result) : scanner.next(result);
        }
        if (isDummy(result)) {
          return true;
        }
        if (result.isEmpty()) {
          return hasMore;
        }
        Cell cell = result.get(0);
        processResults(result, false);
        if (EnvironmentEdgeManager.currentTimeMillis() - startTime >= pageSizeMs) {
          byte[] rowKey = CellUtil.cloneRow(cell);
          result.clear();
          getDummyResult(rowKey, result);
          return true;
        }
        result.clear();
      }

      return nextInQueue(result);
    } catch (Throwable t) {
      ClientUtil.throwIOException(env.getRegion().getRegionInfo().getRegionNameAsString(), t);
      return false; // impossible
    }
  }

  @Override
  public boolean reseek(byte[] row) throws IOException {
    return scanner.reseek(row);
  }

  @Override
  public void close() throws IOException {
    scanner.close();
  }

  @Override
  public boolean next(List<Cell> result) throws IOException {
    return next(result, false, null);
  }

  @Override
  public boolean next(List<Cell> result, ScannerContext scannerContext) throws IOException {
    return next(result, false, scannerContext);
  }

  @Override
  public long getMaxResultSize() {
    return this.scanner.getMaxResultSize();
  }

  @Override
  public int getBatch() {
    return this.scanner.getBatch();
  }

  // PHOENIX-4791 Propagate array element cell through hash join
  private void offerResult(Tuple tuple, boolean projected, List<Cell> result) {
    if (!projected || !addArrayCell) {
      resultQueue.offer(tuple);
      return;
    }

    Cell projectedCell = tuple.getValue(0);
    int arrayCellPosition = RegionScannerFactory.getArrayCellPosition(result);
    Cell arrayCell = result.get(arrayCellPosition);

    List<Cell> cells = new ArrayList<Cell>(2);
    cells.add(projectedCell);
    cells.add(arrayCell);
    MultiKeyValueTuple multi = new MultiKeyValueTuple(cells);
    resultQueue.offer(multi);
  }

  // PHOENIX-4917 Merge array element cell through hash join.
  // Merge into first cell, then reattach array cell.
  private Tuple mergeProjectedValue(Tuple dest, KeyValueSchema destSchema, ValueBitSet destBitSet,
    Tuple src, KeyValueSchema srcSchema, ValueBitSet srcBitSet, int offset) throws IOException {

    if (dest instanceof ProjectedValueTuple) {
      return TupleProjector.mergeProjectedValue((ProjectedValueTuple) dest, destBitSet, src,
        srcBitSet, offset, useNewValueColumnQualifier);
    }

    ProjectedValueTuple first = projector.projectResults(new SingleKeyValueTuple(dest.getValue(0)));
    ProjectedValueTuple merged = TupleProjector.mergeProjectedValue(first, destBitSet, src,
      srcBitSet, offset, useNewValueColumnQualifier);

    int size = dest.size();
    if (size == 1) {
      return merged;
    }

    List<Cell> cells = new ArrayList<Cell>(size);
    cells.add(merged.getValue(0));
    for (int i = 1; i < size; i++) {
      cells.add(dest.getValue(i));
    }
    MultiKeyValueTuple multi = new MultiKeyValueTuple(cells);
    return multi;
  }
}

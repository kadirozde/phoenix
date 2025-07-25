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
package org.apache.phoenix.filter;

import static org.apache.phoenix.schema.PTable.QualifierEncodingScheme.NON_ENCODED_QUALIFIERS;
import static org.apache.phoenix.thirdparty.com.google.common.base.Preconditions.checkArgument;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.IOException;
import java.util.BitSet;
import java.util.NoSuchElementException;
import java.util.TreeSet;
import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.DoNotRetryIOException;
import org.apache.hadoop.hbase.exceptions.DeserializationException;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Writables;
import org.apache.hadoop.io.WritableUtils;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.expression.KeyValueColumnExpression;
import org.apache.phoenix.expression.visitor.ExpressionVisitor;
import org.apache.phoenix.expression.visitor.StatelessTraverseAllExpressionVisitor;
import org.apache.phoenix.schema.PTable.QualifierEncodingScheme;
import org.apache.phoenix.schema.tuple.BaseTuple;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.ClientUtil;

/**
 * Filter used for tables that use number based column qualifiers generated by one of the encoding
 * schemes in {@link QualifierEncodingScheme}. Because the qualifiers are number based, instead of
 * using a map of cells to track the columns that have been found, we can use an array of cells
 * where the index into the array would be derived by the number based column qualifier. See
 * {@link EncodedCQIncrementalResultTuple}. Using this filter helps us to directly seek to the next
 * row when the column qualifier that we have encountered is greater than the maxQualifier that we
 * expect. This helps in speeding up the queries filtering on key value columns. TODO: derived this
 * from MultiKeyValueComparisonFilter to reduce the copy/paste from that class.
 */
public class MultiEncodedCQKeyValueComparisonFilter extends BooleanExpressionFilter {
  // Smallest qualifier for the columns that are being projected and filtered on
  private int minQualifier;

  // Largest qualifier for the columns that are being projected and filtered on
  private int maxQualifier;

  private QualifierEncodingScheme encodingScheme;

  // Smallest qualifier for the columns in where expression
  private int whereExpressionMinQualifier;

  // Largest qualifier for the columns in where expression
  private int whereExpressionMaxQualifier;

  private FilteredKeyValueHolder filteredKeyValues;

  // BitSet to track the qualifiers in where expression that we expect to find while filtering a row
  private BitSet whereExpressionQualifiers;

  // Set to track the column families of the columns in where expression
  private TreeSet<byte[]> cfSet;

  // Boolean that tells us whether the result of expression evaluation as and when we filter key
  // values in a row
  private Boolean matchedColumn;

  // Tuple used to store the relevant key values found while filtering a row
  private EncodedCQIncrementalResultTuple inputTuple = new EncodedCQIncrementalResultTuple();

  // Member variable to cache the size of whereExpressionQualifiers
  private int expectedCardinality;

  private byte[] essentialCF = ByteUtil.EMPTY_BYTE_ARRAY;
  private boolean allCFs;

  private static final byte[] UNITIALIZED_KEY_BUFFER = new byte[0];

  public MultiEncodedCQKeyValueComparisonFilter() {
  }

  public MultiEncodedCQKeyValueComparisonFilter(Expression expression,
    QualifierEncodingScheme scheme, boolean allCFs, byte[] essentialCF) {
    super(expression);
    checkArgument(scheme != NON_ENCODED_QUALIFIERS,
      "Filter can only be used for encoded qualifiers");
    this.encodingScheme = scheme;
    this.allCFs = allCFs;
    this.essentialCF = essentialCF == null ? ByteUtil.EMPTY_BYTE_ARRAY : essentialCF;
    initFilter(expression);
  }

  private final class FilteredKeyValueHolder {
    // Cell values corresponding to columns in where expression that were found while filtering a
    // row.
    private Cell[] filteredCells;

    // BitSet to track whether qualifiers in where expression were found when filtering a row
    private BitSet filteredQualifiers;

    // Using an explicit counter instead of relying on the cardinality of the bitset as computing
    // the
    // cardinality could be slightly more expensive than just incrementing an integer
    private int numKeyValues;

    private FilteredKeyValueHolder(int size) {
      filteredCells = new Cell[size];
      filteredQualifiers = new BitSet(size);
    }

    private void setCell(int qualifier, Cell c) {
      int index = qualifier - whereExpressionMinQualifier;
      filteredCells[index] = c;
      filteredQualifiers.set(index);
      numKeyValues++;
    }

    private Cell getCell(int qualifier) {
      int index = qualifier - whereExpressionMinQualifier;
      return filteredQualifiers.get(index) ? filteredCells[index] : null;
    }

    private void clear() {
      // Note here that we are only clearing out the filteredQualifiers bitset. We are not setting
      // all the
      // entries in filteredKeyValues to null or allocating a new Cell array as that would be
      // expensive.
      filteredQualifiers.clear();
      numKeyValues = 0;
    }

    /**
     * This method really shouldn't be the way for getting hold of cells. It was just added to keep
     * the tuple.get(index) method happy.
     */
    public Cell getCellAtIndex(int index) {
      int bitIndex;
      for (bitIndex = filteredQualifiers.nextSetBit(0); bitIndex >= 0 && index >= 0; bitIndex =
        filteredQualifiers.nextSetBit(bitIndex + 1)) {
        index--;
      }
      if (bitIndex < 0) {
        throw new NoSuchElementException();
      }
      return filteredCells[bitIndex];
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder(100);
      int length = filteredQualifiers.length();
      for (int i = 0; i < length; i++) {
        sb.append(filteredCells[i].toString());
      }
      return sb.toString();
    }

    private boolean allColumnsFound() {
      return numKeyValues == expectedCardinality;
    }

    private int numKeyValues() {
      return numKeyValues;
    }

  }

  private void initFilter(Expression expression) {
    cfSet = new TreeSet<byte[]>(Bytes.BYTES_COMPARATOR);
    final BitSet expressionQualifiers = new BitSet(20);
    final Pair<Integer, Integer> range = new Pair<>();
    ExpressionVisitor<Void> visitor = new StatelessTraverseAllExpressionVisitor<Void>() {
      @Override
      public Void visit(KeyValueColumnExpression expression) {
        int qualifier = encodingScheme.decode(expression.getColumnQualifier());
        if (range.getFirst() == null) {
          range.setFirst(qualifier);
          range.setSecond(qualifier);
        } else if (qualifier < range.getFirst()) {
          range.setFirst(qualifier);
        } else if (qualifier > range.getSecond()) {
          range.setSecond(qualifier);
        }
        cfSet.add(expression.getColumnFamily());
        expressionQualifiers.set(qualifier);
        return null;
      }
    };
    expression.accept(visitor);
    // Set min and max qualifiers for columns in the where expression
    whereExpressionMinQualifier = range.getFirst();
    whereExpressionMaxQualifier = range.getSecond();

    int size = whereExpressionMaxQualifier - whereExpressionMinQualifier + 1;
    filteredKeyValues = new FilteredKeyValueHolder(size);

    // Initialize the bitset and mark the qualifiers for columns in where expression
    whereExpressionQualifiers = new BitSet(size);
    for (int i = whereExpressionMinQualifier; i <= whereExpressionMaxQualifier; i++) {
      if (expressionQualifiers.get(i)) {
        whereExpressionQualifiers.set(i - whereExpressionMinQualifier);
      }
    }
    expectedCardinality = whereExpressionQualifiers.cardinality();
  }

  private boolean isQualifierForColumnInWhereExpression(int qualifier) {
    return qualifier >= whereExpressionMinQualifier
      ? whereExpressionQualifiers.get(qualifier - whereExpressionMinQualifier)
      : false;
  }

  // No @Override for HBase 3 compatibility
  public ReturnCode filterKeyValue(Cell cell) {
    return filterCell(cell);
  }

  @Override
  public ReturnCode filterCell(Cell cell) {
    if (Boolean.TRUE.equals(this.matchedColumn)) {
      // We already found and matched the single column, all keys now pass
      return ReturnCode.INCLUDE_AND_NEXT_COL;
    }
    if (Boolean.FALSE.equals(this.matchedColumn)) {
      // We found all the columns, but did not match the expression, so skip to next row
      return ReturnCode.NEXT_ROW;
    }
    inputTuple.setKey(cell);
    int qualifier = encodingScheme.decode(cell.getQualifierArray(), cell.getQualifierOffset(),
      cell.getQualifierLength());
    if (isQualifierForColumnInWhereExpression(qualifier)) {
      filteredKeyValues.setCell(qualifier, cell);
      // We found a new column, so we can re-evaluate
      this.matchedColumn = this.evaluate(inputTuple);
      if (this.matchedColumn == null) {
        if (inputTuple.isImmutable()) {
          this.matchedColumn = Boolean.FALSE;
        } else {
          return ReturnCode.INCLUDE_AND_NEXT_COL;
        }
      }
      return this.matchedColumn ? ReturnCode.INCLUDE_AND_NEXT_COL : ReturnCode.NEXT_ROW;
    }
    // The qualifier is not one of the qualifiers in the expression. So decide whether
    // we would need to include it in our result.
    if (qualifier < minQualifier) {
      // Qualifier is smaller than the minimum expected qualifier. Look at the next column.
      return ReturnCode.NEXT_COL;
    }
    // TODO: I don't think we would ever hit this case of encountering a greater than what we
    // expect.
    // Leaving the code commented out here for future reference.
    // if (qualifier > maxQualifier) {
    // Qualifier is larger than the max expected qualifier. We are done looking at columns in this
    // row.
    // return ReturnCode.NEXT_ROW;
    // }
    return ReturnCode.INCLUDE_AND_NEXT_COL;
  }

  @Override
  public boolean filterRow() {
    if (
      this.matchedColumn == null && !inputTuple.isImmutable()
        && expression.requiresFinalEvaluation()
    ) {
      inputTuple.setImmutable();
      this.matchedColumn = this.evaluate(inputTuple);
    }
    return !(Boolean.TRUE.equals(this.matchedColumn));
  }

  final class EncodedCQIncrementalResultTuple extends BaseTuple {
    private final ImmutableBytesWritable keyPtr =
      new ImmutableBytesWritable(UNITIALIZED_KEY_BUFFER);
    private boolean isImmutable;

    @Override
    public boolean isImmutable() {
      return isImmutable || filteredKeyValues.allColumnsFound();
    }

    public void setImmutable() {
      this.isImmutable = true;
    }

    private void setKey(Cell value) {
      keyPtr.set(value.getRowArray(), value.getRowOffset(), value.getRowLength());
    }

    @Override
    public void getKey(ImmutableBytesWritable ptr) {
      ptr.set(keyPtr.get(), keyPtr.getOffset(), keyPtr.getLength());
    }

    @Override
    public Cell getValue(byte[] cf, byte[] cq) {
      int qualifier = encodingScheme.decode(cq);
      return filteredKeyValues.getCell(qualifier);
    }

    @Override
    public String toString() {
      return filteredKeyValues.toString();
    }

    @Override
    public int size() {
      return filteredKeyValues.numKeyValues();
    }

    /**
     * This method doesn't perform well and shouldn't be the way of getting hold of elements in the
     * tuple.
     */
    @Override
    public Cell getValue(int index) {
      return filteredKeyValues.getCellAtIndex(index);
    }

    @Override
    public boolean getValue(byte[] family, byte[] qualifier, ImmutableBytesWritable ptr) {
      Cell cell = getValue(family, qualifier);
      if (cell == null) return false;
      ptr.set(cell.getValueArray(), cell.getValueOffset(), cell.getValueLength());
      return true;
    }

    void reset() {
      isImmutable = false;
      keyPtr.set(UNITIALIZED_KEY_BUFFER);
    }

    @Override
    public long getSerializedSize() {
      if (filteredKeyValues == null || filteredKeyValues.numKeyValues() == 0) {
        return 0;
      }
      long totalSize = 0;
      for (int i = 0; i < filteredKeyValues.numKeyValues(); i++) {
        Cell cell = filteredKeyValues.getCellAtIndex(i);
        if (cell != null) {
          totalSize += cell.getSerializedSize();
        }
      }
      return totalSize;
    }
  }

  @Override
  public void readFields(DataInput input) throws IOException {
    try {
      this.minQualifier = WritableUtils.readVInt(input);
      this.maxQualifier = WritableUtils.readVInt(input);
      this.whereExpressionMinQualifier = WritableUtils.readVInt(input);
      this.whereExpressionMaxQualifier = WritableUtils.readVInt(input);
      this.encodingScheme = QualifierEncodingScheme.values()[WritableUtils.readVInt(input)];
      super.readFields(input);
      try {
        allCFs = input.readBoolean();
        if (!allCFs) {
          essentialCF = Bytes.readByteArray(input);
        }
      } catch (EOFException e) { // Ignore as this will occur when a 4.10 client is used
      }
    } catch (DoNotRetryIOException e) {
      throw e;
    } catch (Throwable t) { // Catches incompatibilities during reading/writing and doesn't retry
      ClientUtil.throwIOException("MultiEncodedCQKeyValueComparisonFilter failed during writing",
        t);
    }
    initFilter(expression);
  }

  @Override
  public void write(DataOutput output) throws IOException {
    try {
      WritableUtils.writeVInt(output, minQualifier);
      WritableUtils.writeVInt(output, maxQualifier);
      WritableUtils.writeVInt(output, whereExpressionMinQualifier);
      WritableUtils.writeVInt(output, whereExpressionMaxQualifier);
      WritableUtils.writeVInt(output, encodingScheme.ordinal());
      super.write(output);
      output.writeBoolean(allCFs);
      if (!allCFs) {
        Bytes.writeByteArray(output, essentialCF);
      }
    } catch (DoNotRetryIOException e) {
      throw e;
    } catch (Throwable t) { // Catches incompatibilities during reading/writing and doesn't retry
      ClientUtil.throwIOException("MultiEncodedCQKeyValueComparisonFilter failed during writing",
        t);
    }
  }

  public void setMinMaxQualifierRange(Pair<Integer, Integer> minMaxQualifiers) {
    this.minQualifier = minMaxQualifiers.getFirst();
    this.maxQualifier = minMaxQualifiers.getSecond();
  }

  public void setMinQualifier(int minQualifier) {
    this.minQualifier = minQualifier;
  }

  public static MultiEncodedCQKeyValueComparisonFilter parseFrom(final byte[] pbBytes)
    throws DeserializationException {
    try {
      return (MultiEncodedCQKeyValueComparisonFilter) Writables.getWritable(pbBytes,
        new MultiEncodedCQKeyValueComparisonFilter());
    } catch (IOException e) {
      throw new DeserializationException(e);
    }
  }

  @Override
  public void reset() {
    filteredKeyValues.clear();
    matchedColumn = null;
    inputTuple.reset();
    super.reset();
  }

  @Override
  public boolean isFamilyEssential(byte[] name) {
    // Typically only the column families involved in the expression are essential.
    // The others are for columns projected in the select expression. However, depending
    // on the expression (i.e. IS NULL), we may need to include the column family
    // containing the empty key value or all column families in the case of a mapped
    // view (where we don't have an empty key value).
    return allCFs || Bytes.compareTo(name, essentialCF) == 0 || cfSet.contains(name);
  }

}

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
package org.apache.phoenix.expression;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.expression.visitor.ExpressionVisitor;
import org.apache.phoenix.query.QueryConstants;
import org.apache.phoenix.schema.SortOrder;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PVarbinary;
import org.apache.phoenix.schema.types.PVarbinaryEncoded;
import org.apache.phoenix.util.ByteUtil;
import org.apache.phoenix.util.SchemaUtil;
import org.apache.phoenix.util.TrustedByteArrayOutputStream;

public class RowValueConstructorExpression extends BaseCompoundExpression {

  private ImmutableBytesWritable ptrs[];
  private ImmutableBytesWritable literalExprPtr;
  private int partialEvalIndex = -1;
  private int estimatedByteSize;

  // The boolean field that indicated the object is a literal constant,
  // has been repurposed to a bitset and now holds additional information.
  // This is to facilitate b/w compat to 4.13 clients.
  // @see <a href="https://issues.apache.org/jira/browse/PHOENIX-5122">PHOENIX-5122</a>
  private BitSet extraFields;

  // Important : When you want to add new bits make sure to add those towards the end,
  // else will break b/w compat again.
  private enum ExtraFieldPosition {

    LITERAL_CONSTANT(0),
    STRIP_TRAILING_SEPARATOR_BYTE(1);

    private int bitPosition;

    private ExtraFieldPosition(int position) {
      bitPosition = position;
    }

    private int getBitPosition() {
      return bitPosition;
    }
  }

  public RowValueConstructorExpression() {
  }

  public RowValueConstructorExpression(List<Expression> children, boolean isConstant) {
    super(children);
    extraFields = new BitSet(8);
    extraFields.set(ExtraFieldPosition.STRIP_TRAILING_SEPARATOR_BYTE.getBitPosition());
    if (isConstant) {
      extraFields.set(ExtraFieldPosition.LITERAL_CONSTANT.getBitPosition());
    }
    estimatedByteSize = 0;
    init();
  }

  public RowValueConstructorExpression clone(List<Expression> children) {
    return new RowValueConstructorExpression(children, literalExprPtr != null);
  }

  public int getEstimatedSize() {
    return estimatedByteSize;
  }

  @Override
  public boolean isStateless() {
    return literalExprPtr != null;
  }

  @Override
  public final <T> T accept(ExpressionVisitor<T> visitor) {
    List<T> l = acceptChildren(visitor, visitor.visitEnter(this));
    T t = visitor.visitLeave(this, l);
    if (t == null) {
      t = visitor.defaultReturn(this, l);
    }
    return t;
  }

  @Override
  public void readFields(DataInput input) throws IOException {
    super.readFields(input);
    extraFields = BitSet.valueOf(new byte[] { input.readByte() });
    init();
  }

  @Override
  public void write(DataOutput output) throws IOException {
    super.write(output);
    byte[] b = extraFields.toByteArray();
    output.writeByte((b.length > 0 ? b[0] & 0xff : 0));
  }

  private void init() {
    this.ptrs = new ImmutableBytesWritable[children.size()];
    if (isConstant()) {
      ImmutableBytesWritable ptr = new ImmutableBytesWritable();
      this.evaluate(null, ptr);
      literalExprPtr = ptr;
    }
  }

  private boolean isConstant() {
    return extraFields.get(ExtraFieldPosition.LITERAL_CONSTANT.getBitPosition());
  }

  private boolean isStripTrailingSepByte() {
    return extraFields.get(ExtraFieldPosition.STRIP_TRAILING_SEPARATOR_BYTE.getBitPosition());
  }

  @Override
  public PDataType getDataType() {
    return PVarbinary.INSTANCE;
  }

  @Override
  public void reset() {
    partialEvalIndex = 0;
    estimatedByteSize = 0;
    Arrays.fill(ptrs, null);
    super.reset();
  }

  private static int getExpressionByteCount(Expression e) {
    PDataType<?> childType = e.getDataType();
    if (childType != null && !childType.isFixedWidth()) {
      return childType != PVarbinaryEncoded.INSTANCE ? 1 : 2;
    } else {
      // Write at least one null byte in the case of the child being null with a childType of null
      return childType == null ? 1 : SchemaUtil.getFixedByteSize(e);
    }
  }

  @Override
  public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
    if (literalExprPtr != null) {
      // if determined during construction that the row value constructor is just comprised of
      // literal expressions,
      // let's just return the ptr we have already computed and be done with evaluation.
      ptr.set(literalExprPtr.get(), literalExprPtr.getOffset(), literalExprPtr.getLength());
      return true;
    }
    try {
      boolean isPartialEval = this.partialEvalIndex >= 0;
      int evalIndex = isPartialEval ? this.partialEvalIndex : 0;
      int expressionCount = evalIndex;
      for (; evalIndex < ptrs.length; evalIndex++) {
        final Expression expression = children.get(evalIndex);
        // TODO: handle overflow and underflow
        if (expression.evaluate(tuple, ptr)) {
          if (ptr.getLength() == 0) {
            estimatedByteSize += getExpressionByteCount(expression);
          } else {
            expressionCount = evalIndex + 1;
            ptrs[evalIndex] = new ImmutableBytesWritable();
            ptrs[evalIndex].set(ptr.get(), ptr.getOffset(), ptr.getLength());
            estimatedByteSize += ptr.getLength()
              + (expression.getDataType().isFixedWidth() ? 0 : getSeparatorBytesLength(expression)); // 1
                                                                                                     // extra
                                                                                                     // for
                                                                                                     // the
                                                                                                     // separator
                                                                                                     // byte.
          }
        } else if (tuple == null || tuple.isImmutable()) {
          estimatedByteSize += getExpressionByteCount(expression);
        } else { // Cannot yet be evaluated
          return false;
        }
      }
      if (isPartialEval) {
        this.partialEvalIndex = evalIndex; // Move counter forward
      }

      if (evalIndex == ptrs.length) {
        if (expressionCount == 0) {
          ptr.set(ByteUtil.EMPTY_BYTE_ARRAY);
          return true;
        }
        if (expressionCount == 1) {
          ptr.set(ptrs[0].get(), ptrs[0].getOffset(), ptrs[0].getLength());
          return true;
        }
        TrustedByteArrayOutputStream output = new TrustedByteArrayOutputStream(estimatedByteSize);
        try {
          boolean previousCarryOver = false;
          for (int i = 0; i < expressionCount; i++) {
            Expression child = getChildren().get(i);
            PDataType childType = child.getDataType();
            ImmutableBytesWritable tempPtr = ptrs[i];
            if (tempPtr == null) {
              // Since we have a null and have no representation for null,
              // we must decrement the value of the current. Otherwise,
              // we'd have an ambiguity if this value happened to be the
              // min possible value.
              previousCarryOver = childType == null || childType.isFixedWidth();
              if (childType == PVarbinaryEncoded.INSTANCE) {
                output.write(QueryConstants.VARBINARY_ENCODED_SEPARATOR_BYTES);
              } else {
                int bytesToWrite = getExpressionByteCount(child);
                for (int m = 0; m < bytesToWrite; m++) {
                  output.write(QueryConstants.SEPARATOR_BYTE);
                }
              }
            } else {
              output.write(tempPtr.get(), tempPtr.getOffset(), tempPtr.getLength());
              if (!childType.isFixedWidth()) {
                output.write(
                  SchemaUtil.getSeparatorBytes(childType, true, false, child.getSortOrder()));
              }
              if (previousCarryOver) {
                previousCarryOver = !ByteUtil.previousKey(output.getBuffer(), output.size());
              }
            }
          }
          int outputSize = output.size();
          byte[] outputBytes = output.getBuffer();
          // Don't remove trailing separator byte unless it's the one for ASC
          // as otherwise we need it to ensure sort order is correct.
          // Additionally for b/w compat with clients older than 4.14.1 -
          // If SortOorder.ASC then always strip trailing separator byte (as before)
          // else only strip for >= 4.14 client (when STRIP_TRAILING_SEPARATOR_BYTE bit is set)
          for (int k = expressionCount - 1; k >= 0 && getChildren().get(k).getDataType() != null
            && !getChildren().get(k).getDataType().isFixedWidth()
            && hasSeparatorBytes(outputBytes, outputSize, k)
            && (getChildren().get(k).getSortOrder() == SortOrder.ASC
              || isStripTrailingSepByte()); k--) {
            outputSize--;
            if (getChildren().get(k).getDataType() == PVarbinaryEncoded.INSTANCE) {
              outputSize--;
            }
          }
          ptr.set(outputBytes, 0, outputSize);
          return true;
        } finally {
          output.close();
        }
      }
      return false;
    } catch (IOException e) {
      throw new RuntimeException(e); // Impossible.
    }
  }

  private boolean hasSeparatorBytes(byte[] outputBytes, int outputSize, int k) {
    if (getChildren().get(k).getDataType() != PVarbinaryEncoded.INSTANCE) {
      return outputBytes[outputSize - 1]
          == SchemaUtil.getSeparatorByte(true, false, getChildren().get(k));
    } else {
      byte[] sepBytes = SchemaUtil.getSeparatorBytesForVarBinaryEncoded(true, false,
        getChildren().get(k).getSortOrder());
      return outputSize >= 2 && outputBytes[outputSize - 1] == sepBytes[1]
        && outputBytes[outputSize - 2] == sepBytes[0];
    }
  }

  private static int getSeparatorBytesLength(Expression expression) {
    return expression.getDataType() != PVarbinaryEncoded.INSTANCE ? 1 : 2;
  }

  @Override
  public final String toString() {
    StringBuilder buf = new StringBuilder("(");
    for (int i = 0; i < children.size() - 1; i++) {
      buf.append(children.get(i) + ", ");
    }
    buf.append(children.get(children.size() - 1) + ")");
    return buf.toString();
  }

  @Override
  public boolean requiresFinalEvaluation() {
    return true;
  }
}

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
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.expression.visitor.ExpressionVisitor;
import org.apache.phoenix.schema.PColumn;
import org.apache.phoenix.schema.PDatum;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.tuple.ValueGetterTuple;
import org.apache.phoenix.util.SchemaUtil;

/**
 * Class to access a column value stored in a KeyValue
 * @since 0.1
 */
public class KeyValueColumnExpression extends ColumnExpression {
  private byte[] cf;
  private byte[] cq;
  private String displayName; // client-side only.

  public KeyValueColumnExpression() {
  }

  public KeyValueColumnExpression(final byte[] cf, final byte[] cq) {
    this.cf = cf;
    this.cq = cq;
  }

  public KeyValueColumnExpression(PColumn column) {
    super(column);
    this.cf = column.getFamilyName().getBytes();
    // for backward compatibility since older tables won't have columnQualifierBytes in their
    // metadata
    this.cq = column.getColumnQualifierBytes() != null
      ? column.getColumnQualifierBytes()
      : column.getName().getBytes();
    this.displayName = column.getName().getString();
  }

  public KeyValueColumnExpression(PColumn column, String displayName) {
    super(column);
    this.cf = column.getFamilyName().getBytes();
    // for backward compatibility since older tables won't have columnQualifierBytes in their
    // metadata
    this.cq = column.getColumnQualifierBytes() != null
      ? column.getColumnQualifierBytes()
      : column.getName().getBytes();
    this.displayName = displayName;
  }

  public KeyValueColumnExpression(PDatum column, byte[] cf, byte[] cq) {
    super(column);
    this.cf = cf;
    this.cq = cq;
  }

  public byte[] getColumnFamily() {
    return cf;
  }

  public byte[] getColumnQualifier() {
    return cq;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + Arrays.hashCode(cf);
    result = prime * result + Arrays.hashCode(cq);
    return result;
  }

  // TODO: assumes single table
  @Override
  public boolean equals(Object obj) {
    if (this == obj) return true;
    if (obj == null) return false;
    if (getClass() != obj.getClass()) return false;
    KeyValueColumnExpression other = (KeyValueColumnExpression) obj;
    if (!Arrays.equals(cf, other.cf)) return false;
    if (!Arrays.equals(cq, other.cq)) return false;
    return true;
  }

  @Override
  public String toString() {
    if (displayName == null) {
      displayName = SchemaUtil.getColumnDisplayName(cf, cq);
    }
    return displayName;
  }

  @Override
  public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {
    return tuple.getValue(cf, cq, ptr);
  }

  public boolean evaluateUnsafe(Tuple tuple, ImmutableBytesWritable ptr) {
    if (tuple instanceof ValueGetterTuple) {
      return ((ValueGetterTuple) tuple).getValueUnsafe(cf, cq, ptr);
    } else {
      return tuple.getValue(cf, cq, ptr);
    }
  }

  @Override
  public void readFields(DataInput input) throws IOException {
    super.readFields(input);
    cf = Bytes.readByteArray(input);
    cq = Bytes.readByteArray(input);
  }

  @Override
  public void write(DataOutput output) throws IOException {
    super.write(output);
    Bytes.writeByteArray(output, cf);
    Bytes.writeByteArray(output, cq);
  }

  @Override
  public <T> T accept(ExpressionVisitor<T> visitor) {
    return visitor.visit(this);
  }

}

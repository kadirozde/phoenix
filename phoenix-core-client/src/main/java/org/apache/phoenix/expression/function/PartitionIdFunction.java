/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.expression.function;

import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.phoenix.expression.Determinism;
import org.apache.phoenix.expression.Expression;
import org.apache.phoenix.parse.FunctionParseNode.BuiltInFunction;
import org.apache.phoenix.parse.PartitionIdParseNode;
import org.apache.phoenix.schema.tuple.Tuple;
import org.apache.phoenix.schema.types.PDataType;
import org.apache.phoenix.schema.types.PVarchar;

import java.util.List;

import static org.apache.phoenix.query.QueryConstants.SEPARATOR_BYTE;

/**
 * Function to return the partition id which is the encoded data table region name. This function
 * is used only with CDC Indexes
 */
@BuiltInFunction(name = PartitionIdFunction.NAME,
        nodeClass= PartitionIdParseNode.class,
        args = {})
public class PartitionIdFunction extends ScalarFunction {
    public static final String NAME = "PARTITION_ID";

    public PartitionIdFunction() {
    }

    /**
     *  @param children none
     *  {@link org.apache.phoenix.parse.PartitionIdParseNode#create create}
     *  will return the partition id of a given CDC index row.
     */
    public PartitionIdFunction(List<Expression> children) {
        super(children);
        if (!children.isEmpty()) {
            throw new IllegalArgumentException(
                    "PartitionIdFunction should not have any child expression"
            );
        }
    }

    @Override
    public String getName() {
        return NAME;
    }

    /**
     * The evaluate method is called under the following conditions -
     * 1. When PARTITION_ID() is evaluated in the projection list.
     *
     * 2. When PARTITION_ID() is evaluated in the backend as part of the where clause.
     *
     */
    @Override
    public boolean evaluate(Tuple tuple, ImmutableBytesWritable ptr) {

        if (tuple == null) {
            return false;
        }
        tuple.getKey(ptr);
        int length = 0;
        byte[] rowKey = ptr.get();
        for (; length < ptr.getLength(); length++) {
            if (rowKey[length] == SEPARATOR_BYTE) {
                break;
            }
        }
        ptr.set(ptr.get(), 0, length);
        return true;
    }

    @Override
    public PDataType getDataType() {
        return PVarchar.INSTANCE;
    }

    @Override
    public Integer getMaxLength() {
        return 32;
    }

    @Override
    public boolean isStateless() {
        return false;
    }

    @Override
    public Determinism getDeterminism() {
        return Determinism.PER_ROW;
    }

}
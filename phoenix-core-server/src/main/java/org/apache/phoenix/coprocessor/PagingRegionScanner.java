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
package org.apache.phoenix.coprocessor;

import java.io.IOException;
import java.util.List;

import org.apache.hadoop.hbase.Cell;
import org.apache.hadoop.hbase.CellUtil;
import org.apache.hadoop.hbase.client.PackagePrivateFieldAccessor;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.client.TableDescriptor;
import org.apache.hadoop.hbase.regionserver.BloomType;
import org.apache.hadoop.hbase.regionserver.Region;
import org.apache.hadoop.hbase.regionserver.RegionScanner;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.phoenix.filter.PagingFilter;
import org.apache.phoenix.filter.SkipScanFilter;
import org.apache.phoenix.query.KeyRange;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.util.ScanUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *  PagingRegionScanner works with PagingFilter to make sure that the time between two rows
 *  returned by the HBase region scanner should not exceed the configured page size in ms
 *  (on PagingFilter). When the page size is reached (because there are too many cells/rows
 *  to be filtered out), PagingFilter stops the HBase region scanner and sets its state
 *  to STOPPED. In this case, the HBase region scanner next() returns false and
 *  PagingFilter#isStopped() returns true. PagingRegionScanner is responsible for detecting
 *  PagingFilter has stopped the scanner, and returning a dummy result to signal to
 *  Phoenix client to resume the scan operation by skipping this dummy result and calling
 *  ResultScanner#next().
 */
public class PagingRegionScanner extends BaseRegionScanner {
    private static final Logger LOGGER = LoggerFactory.getLogger(PagingRegionScanner.class);
    private Region region;
    private Scan scan;
    private PagingFilter pagingFilter;
    private MultiKeyPointLookup multiKeyPointLookup = null;
    private boolean initialized = false;

    private class MultiKeyPointLookup {
        private SkipScanFilter skipScanFilter;
        private List<KeyRange> pointLookupRanges = null;
        private int lookupPosition = 0;
        private int initialLookupPosition = 0;
        private byte[] lookupKeyPrefix = null;
        private Cell lastDummyCell = null;

        private MultiKeyPointLookup(SkipScanFilter skipScanFilter) throws IOException {
            this.skipScanFilter = skipScanFilter;
            pointLookupRanges = skipScanFilter.getPointLookupKeyRanges();
            initialLookupPosition = findLookupPosition(scan.getStartRow());
            lookupPosition = initialLookupPosition;
            if (initialLookupPosition < 0) {
                throw new IOException(
                        "Unexpected start row key " + Bytes.toStringBinary(scan.getStartRow())
                                + " region " + region);
            }
            if (skipScanFilter.getOffset() > 0) {
                lookupKeyPrefix = new byte[skipScanFilter.getOffset()];
                System.arraycopy(scan.getStartRow(), 0, lookupKeyPrefix, 0,
                        skipScanFilter.getOffset());
            }

        }

        private int findLookupPosition(byte[] startRowKey) {
            for (int i = 0; i < pointLookupRanges.size(); i++) {
                byte[] rowKey = pointLookupRanges.get(i).getLowerRange();
                if (Bytes.compareTo(startRowKey, skipScanFilter.getOffset(),
                        startRowKey.length - skipScanFilter.getOffset(), rowKey, 0,
                        rowKey.length) <= 0) {
                    return i;
                }
            }
            return -1;
        }

        private void reset() {
            lookupPosition = initialLookupPosition + 1;
            lastDummyCell = null;
        }

        private void verifyStartRowKey(byte[] startRowKey) throws IOException {
            byte[] expectedRowKey = pointLookupRanges.get(initialLookupPosition).getLowerRange();
            if (Bytes.compareTo(startRowKey, skipScanFilter.getOffset(),
                    startRowKey.length - skipScanFilter.getOffset(), expectedRowKey, 0,
                    expectedRowKey.length) != 0) {
                byte[] adjustedExpectedRowKey = expectedRowKey;
                if (skipScanFilter.getOffset() != 0) {
                    int len = expectedRowKey.length + lookupKeyPrefix.length;
                    adjustedExpectedRowKey = new byte[len];
                    System.arraycopy(lookupKeyPrefix, 0, adjustedExpectedRowKey, 0,
                            lookupKeyPrefix.length);
                    System.arraycopy(expectedRowKey, 0, adjustedExpectedRowKey,
                            lookupKeyPrefix.length, expectedRowKey.length);
                }
                throw new IOException(
                        "Unexpected row key " + Bytes.toStringBinary(startRowKey) + " (expected "
                                + Bytes.toStringBinary(adjustedExpectedRowKey) + ") region "
                                + region);
            }
            if (skipScanFilter.getOffset() > 0) {
                System.arraycopy(startRowKey, 0, lookupKeyPrefix, 0,
                        skipScanFilter.getOffset());
            }
        }

        private RegionScanner getNewScanner() throws IOException {
            if (lookupPosition >= pointLookupRanges.size()) {
                return null;
            }
            byte[] rowKey = pointLookupRanges.get(lookupPosition++).getLowerRange();
            byte[] adjustedRowKey = rowKey;
            if (lookupKeyPrefix != null) {
                int len = rowKey.length + lookupKeyPrefix.length;
                adjustedRowKey = new byte[len];
                System.arraycopy(lookupKeyPrefix, 0, adjustedRowKey, 0,
                        lookupKeyPrefix.length);
                System.arraycopy(rowKey, 0, adjustedRowKey, lookupKeyPrefix.length,
                        rowKey.length);
            }
            if (lastDummyCell != null) {
                byte[] dummyRowKey = CellUtil.cloneRow(lastDummyCell);
                scan.withStartRow(dummyRowKey, false);
                lastDummyCell = null;
            } else {
                scan.withStartRow(adjustedRowKey, true);
            }
            scan.withStopRow(adjustedRowKey, true);
            return region.getScanner(scan);
        }

        private boolean hasMore() {
            return lookupPosition < pointLookupRanges.size();
        }
    }

    public PagingRegionScanner(Region region, RegionScanner scanner, Scan scan) {
        super(scanner);
        this.region = region;
        this.scan = scan;
        pagingFilter = ScanUtil.getPhoenixPagingFilter(scan);
        if (pagingFilter != null) {
            pagingFilter.init();
        }
    }

    void init() throws IOException {
        if (initialized) {
            return;
        }
        TableDescriptor tableDescriptor = region.getTableDescriptor();
        BloomType bloomFilterType = tableDescriptor.getColumnFamilies()[0].getBloomFilterType();
        if (bloomFilterType == BloomType.ROW) {
            // Check if the scan is a multi-point-lookup scan if so remove it from the scan
            SkipScanFilter skipScanFilter =
                    ScanUtil.removeSkipScanFilterWithMultiKeyPointLookup(scan);
            if (skipScanFilter != null) {
                multiKeyPointLookup = new MultiKeyPointLookup(skipScanFilter);
            }
        }
        initialized = true;
    }
    private boolean next(List<Cell> results, boolean raw) throws IOException {
        try {
            init();
            byte[] adjustedStartRowKey =
                    scan.getAttribute(QueryServices.PHOENIX_PAGING_NEW_SCAN_START_ROWKEY);
            byte[] adjustedStartRowKeyIncludeBytes =
                    scan.getAttribute(QueryServices.PHOENIX_PAGING_NEW_SCAN_START_ROWKEY_INCLUDE);
            // If scanners at higher level needs to re-scan the data that were already scanned
            // earlier, they can provide adjusted new start rowkey for the scan and whether to
            // include it.
            // If they are set as the scan attributes, close the scanner, reopen it with
            // updated start rowkey and whether to include it. Update mvcc read point from the
            // previous scanner and set it back to the new scanner to maintain the read
            // consistency for the given region.
            // Once done, continue the scan operation and reset the attributes.
            if (adjustedStartRowKey != null && adjustedStartRowKeyIncludeBytes != null) {
                long mvccReadPoint = delegate.getMvccReadPoint();
                delegate.close();
                scan.withStartRow(adjustedStartRowKey,
                        Bytes.toBoolean(adjustedStartRowKeyIncludeBytes));
                PackagePrivateFieldAccessor.setMvccReadPoint(scan, mvccReadPoint);
                if (multiKeyPointLookup != null) {
                    scan.withStopRow(adjustedStartRowKey, true);
                    // Reset multiKeyPointLookupPosition to start scanning from the first row
                    multiKeyPointLookup.reset();
                    multiKeyPointLookup.verifyStartRowKey(adjustedStartRowKey);
                }
                delegate = region.getScanner(scan);
                scan.setAttribute(QueryServices.PHOENIX_PAGING_NEW_SCAN_START_ROWKEY, null);
                scan.setAttribute(QueryServices.PHOENIX_PAGING_NEW_SCAN_START_ROWKEY_INCLUDE, null);
            } else {
                if (multiKeyPointLookup != null) {
                   RegionScanner regionScanner = multiKeyPointLookup.getNewScanner();
                   if (regionScanner == null) {
                       return false;
                   }
                   delegate = regionScanner;
                }
            }
            if (pagingFilter != null) {
                pagingFilter.init();
            }

            boolean hasMore = raw ? delegate.nextRaw(results) : delegate.next(results);
            if (pagingFilter == null) {
                return multiKeyPointLookup != null ? multiKeyPointLookup.hasMore() : hasMore;
            }
            if (!hasMore) {
                // There is no more row from the HBase region scanner. We need to check if
                // PagingFilter has stopped the region scanner
                if (pagingFilter.isStopped()) {
                    if (results.isEmpty()) {
                        byte[] rowKey = pagingFilter.getCurrentRowKeyToBeExcluded();
                        LOGGER.info("Page filter stopped, generating dummy key {} ",
                                Bytes.toStringBinary(rowKey));
                        ScanUtil.getDummyResult(rowKey, results);
                        if (multiKeyPointLookup != null) {
                            multiKeyPointLookup.lastDummyCell = results.get(0);
                            multiKeyPointLookup.lookupPosition--;
                        }
                        return true;
                    }
                    return multiKeyPointLookup != null ? multiKeyPointLookup.hasMore() : true;
                }
                return multiKeyPointLookup != null ? multiKeyPointLookup.hasMore() : false;
            } else {
                if (results.isEmpty() && multiKeyPointLookup != null) {
                    multiKeyPointLookup.lookupPosition--;
                }
                // We got a row from the HBase scanner within the configured time (i.e.,
                // the page size). We need to start a new page on the next next() call.
                return multiKeyPointLookup != null ? multiKeyPointLookup.hasMore() : true;
            }
        } catch (Exception e) {
            if (pagingFilter != null) {
                pagingFilter.init();
            }
            if (multiKeyPointLookup != null) {
                multiKeyPointLookup.lookupPosition--;
            }
            throw e;
        } finally {
            if (multiKeyPointLookup != null) {
                delegate.close();
            }
        }
    }

    @Override
    public boolean next(List<Cell> results) throws IOException {
        return next(results, false);
    }

    @Override
    public boolean nextRaw(List<Cell> results) throws IOException {
        return next(results, true);
    }

    @Override
    public RegionScanner getNewRegionScanner(Scan scan) throws IOException {
        return new PagingRegionScanner(region, region.getScanner(scan), scan);
    }
}

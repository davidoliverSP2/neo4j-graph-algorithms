/**
 * Copyright (c) 2017 "Neo4j, Inc." <http://neo4j.com>
 *
 * This file is part of Neo4j Graph Algorithms <http://github.com/neo4j-contrib/neo4j-graph-algorithms>.
 *
 * Neo4j Graph Algorithms is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.utils.paged;

import org.neo4j.graphalgo.core.write.PropertyTranslator;

import java.util.Arrays;
import java.util.function.LongUnaryOperator;

public final class FixedLongArray {

    private static final int PAGE_SHIFT = 12;
    private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
    private static final long PAGE_MASK = (long) (PAGE_SIZE - 1);

    public static FixedLongArray newArray(long size, AllocationTracker tracker) {
        int numPages = PageUtil.numPagesFor(size, PAGE_SHIFT, (int) PAGE_MASK);
        long[][] pages = new long[numPages][];

        long memoryUsed = MemoryUsage.sizeOfObjectArray(numPages);
        final long pageBytes = MemoryUsage.sizeOfLongArray(PAGE_SIZE);
        for (int i = 0; i < numPages - 1; i++) {
            memoryUsed += pageBytes;
            pages[i] = new long[PAGE_SIZE];
        }
        final int lastPageSize = indexInPage(size);
        pages[numPages - 1] = new long[lastPageSize];
        memoryUsed += MemoryUsage.sizeOfLongArray(lastPageSize);

        tracker.add(MemoryUsage.shallowSizeOfInstance(FixedLongArray.class));
        tracker.add(memoryUsed);

        return new FixedLongArray(size, pages, memoryUsed);
    }

    private final long size;
    private long[][] pages;
    private final long memoryUsed;

    private FixedLongArray(long size, long[][] pages, long memoryUsed) {
        this.size = size;
        this.pages = pages;
        this.memoryUsed = memoryUsed;
    }

    public long get(long index) {
        assert index < size;
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        return pages[pageIndex][indexInPage];
    }

    public void set(long index, long value) {
        assert index < size;
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        pages[pageIndex][indexInPage] = value;
    }

    public void or(long index, final long value) {
        assert index < size;
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        pages[pageIndex][indexInPage] |= value;
    }

    public void addTo(long index, long value) {
        assert index < size;
        final int pageIndex = pageIndex(index);
        final int indexInPage = indexInPage(index);
        pages[pageIndex][indexInPage] += value;
    }

    public void setAll(LongUnaryOperator gen) {
        for (int i = 0; i < pages.length; i++) {
            final long t = ((long) i) << PAGE_SHIFT;
            Arrays.setAll(pages[i], j -> gen.applyAsLong(t + j));
        }
    }

    public void fill(long value) {
        for (long[] page : pages) {
            Arrays.fill(page, value);
        }
    }

    public final long size() {
        return size;
    }

    public long release() {
        if (pages != null) {
            pages = null;
            return memoryUsed;
        }
        return 0L;
    }

    public Cursor newCursor() {
        return new Cursor(size, pages);
    }

    public Cursor cursor(long from, Cursor cursor) {
        cursor.init(from);
        return cursor;
    }

    private static int pageIndex(long index) {
        return (int) (index >>> PAGE_SHIFT);
    }

    private static int indexInPage(long index) {
        return (int) (index & PAGE_MASK);
    }

    public static final class Cursor {

        private final long[][] pages;

        public long[] array;
        public int offset;
        public int limit;

        private int page;
        private int fromPage;

        private final int maxPage;
        private final long capacity;

        private Cursor(final long capacity, final long[][] pages) {
            this.capacity = capacity;
            this.maxPage = pages.length - 1;
            this.pages = pages;
        }

        private void init(long fromIndex) {
            assert fromIndex < capacity;
            fromPage = pageIndex(fromIndex);
            array = pages[fromPage];
            offset = indexInPage(fromIndex);
            int length = (int) Math.min(PAGE_SIZE - offset, capacity);
            limit = offset + length;
            page = fromPage - 1;
        }

        public final boolean next() {
            int current = ++page;
            if (current == fromPage) {
                return true;
            }
            if (current > maxPage) {
                return false;
            }
            array = pages[current];
            offset = 0;
            limit = array.length;
            return true;
        }
    }

    public static class Translator implements PropertyTranslator.OfLong<FixedLongArray> {

        public static final Translator INSTANCE = new Translator();

        @Override
        public long toLong(final FixedLongArray data, final long nodeId) {
            return data.get(nodeId);
        }
    }

}

package org.neo4j.graphalgo.core.utils.paged;

import org.neo4j.graphalgo.core.write.PropertyTranslator;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.LongConsumer;
import java.util.function.LongUnaryOperator;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

public abstract class HugeLongArray {

    abstract public long get(long index);

    abstract public void set(long index, long value);

    abstract public void or(long index, final long value);

    abstract public void addTo(long index, long value);

    abstract public void setAll(LongUnaryOperator gen);

    abstract public void fill(long value);

    abstract public long size();

    abstract public long release();

    abstract public Cursor newCursor();

    abstract public Cursor cursor(long from, Cursor cursor);

    public static HugeLongArray newArray(long size, AllocationTracker tracker) {
        if (size <= SingleHugeLongArray.PAGE_SIZE) {
            try {
                return SingleHugeLongArray.of(size, tracker);
            } catch (OutOfMemoryError ignored) {
                // OOM is very likely because we just tried to create a single array that is too large
                // in which case we're just going the paged way. If the OOM had any other reason, we're
                // probably triggering it again in the construction of the paged array, where it will be thrown.
            }
        }
        return PagedHugeLongArray.of(size, tracker);
    }

    /* test-only */ static HugeLongArray newPagedArray(long size, AllocationTracker tracker) {
        return PagedHugeLongArray.of(size, tracker);
    }

    /* test-only */ static HugeLongArray newSingleArray(int size, AllocationTracker tracker) {
        return SingleHugeLongArray.of(size, tracker);
    }

    public final LongStream toStream() {
        final Spliterator.OfLong spliter = LongCursorSpliterator.of(this);
        return StreamSupport.longStream(spliter, false);
    }

    public static abstract class Cursor {

        public long[] array;
        public int offset;
        public int limit;

        Cursor() {
        }

        abstract public boolean next();

    }

    public static class Translator implements PropertyTranslator.OfLong<HugeLongArray> {

        public static final Translator INSTANCE = new Translator();

        @Override
        public long toLong(final HugeLongArray data, final long nodeId) {
            return data.get(nodeId);
        }
    }

    static final class LongCursorSpliterator implements Spliterator.OfLong {
        private final Cursor cursor;
        private final int characteristics;

        static Spliterator.OfLong of(HugeLongArray array) {
            Cursor cursor = array.cursor(0, array.newCursor());
            if (cursor.next()) {
                return new LongCursorSpliterator(cursor);
            }
            return Spliterators.emptyLongSpliterator();

        }

        private LongCursorSpliterator(Cursor cursor) {
            this.cursor = cursor;
            this.characteristics = Spliterator.ORDERED | Spliterator.IMMUTABLE;
        }

        @Override
        public OfLong trySplit() {
            final long[] array = cursor.array;
            final int offset = cursor.offset;
            final int limit = cursor.limit;
            if (cursor.next()) {
                return Spliterators.spliterator(array, offset, limit, characteristics);
            }
            return null;
        }

        @Override
        public void forEachRemaining(LongConsumer action) {
            do {
                final long[] array = cursor.array;
                final int offset = cursor.offset;
                final int limit = cursor.limit;
                for (int i = offset; i < limit; i++) {
                    action.accept(array[i]);
                }
            } while (cursor.next());
        }

        @Override
        public boolean tryAdvance(LongConsumer action) {
            do {
                final int index = cursor.offset++;
                if (index < cursor.limit) {
                    action.accept(cursor.array[index]);
                    return true;
                }
            } while (cursor.next());
            return false;
        }

        @Override
        public long estimateSize() {
            return Long.MAX_VALUE;
        }

        @Override
        public int characteristics() {
            return characteristics;
        }

        @Override
        public Comparator<? super Long> getComparator() {
            return null;
        }
    }

    private static final class SingleHugeLongArray extends HugeLongArray {

        private static final int PAGE_SHIFT = 30;
        private static final int PAGE_SIZE = 1 << PAGE_SHIFT;

        private static HugeLongArray of(long size, AllocationTracker tracker) {
            assert size <= PAGE_SIZE;
            final int intSize = (int) size;
            long[] page = new long[intSize];

            tracker.add(MemoryUsage.shallowSizeOfInstance(HugeLongArray.class));
            tracker.add(MemoryUsage.sizeOfLongArray(intSize));

            return new SingleHugeLongArray(intSize, page);
        }

        private final int size;
        private long[] page;

        private SingleHugeLongArray(int size, long[] page) {
            this.size = size;
            this.page = page;
        }

        @Override
        public long get(long index) {
            assert index < size;
            return page[(int) index];
        }

        @Override
        public void set(long index, long value) {
            assert index < size;
            page[(int) index] = value;
        }

        @Override
        public void or(long index, final long value) {
            assert index < size;
            page[(int) index] |= value;
        }

        @Override
        public void addTo(long index, long value) {
            assert index < size;
            page[(int) index] += value;
        }

        @Override
        public void setAll(LongUnaryOperator gen) {
            Arrays.setAll(page, gen::applyAsLong);
        }

        @Override
        public void fill(long value) {
            Arrays.fill(page, value);
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long release() {
            if (page != null) {
                page = null;
                return MemoryUsage.sizeOfLongArray(size);
            }
            return 0L;
        }

        @Override
        public Cursor newCursor() {
            return new SingleCursor(page);
        }

        @Override
        public Cursor cursor(final long from, final Cursor cursor) {
            assert cursor instanceof SingleCursor;
            ((SingleCursor) cursor).init(from);
            return cursor;
        }

        private static final class SingleCursor extends Cursor {

            private boolean exhausted;

            private SingleCursor(final long[] page) {
                super();
                this.array = page;
                this.limit = page.length;
            }

            private void init(long fromIndex) {
                assert fromIndex < limit;
                offset = (int) fromIndex;
                exhausted = false;
            }

            public final boolean next() {
                if (exhausted) {
                    return false;
                }
                exhausted = true;
                return true;
            }
        }
    }

    public static final class PagedHugeLongArray extends HugeLongArray {

        private static final int PAGE_SHIFT = 14;
        private static final int PAGE_SIZE = 1 << PAGE_SHIFT;
        private static final long PAGE_MASK = (long) (PAGE_SIZE - 1);

        private static HugeLongArray of(long size, AllocationTracker tracker) {
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

            tracker.add(MemoryUsage.shallowSizeOfInstance(HugeLongArray.class));
            tracker.add(memoryUsed);

            return new PagedHugeLongArray(size, pages, memoryUsed);
        }

        private final long size;
        private long[][] pages;
        private final long memoryUsed;

        private PagedHugeLongArray(long size, long[][] pages, long memoryUsed) {
            this.size = size;
            this.pages = pages;
            this.memoryUsed = memoryUsed;
        }

        @Override
        public long get(long index) {
            assert index < size;
            final int pageIndex = pageIndex(index);
            final int indexInPage = indexInPage(index);
            return pages[pageIndex][indexInPage];
        }

        @Override
        public void set(long index, long value) {
            assert index < size;
            final int pageIndex = pageIndex(index);
            final int indexInPage = indexInPage(index);
            pages[pageIndex][indexInPage] = value;
        }

        @Override
        public void or(long index, final long value) {
            assert index < size;
            final int pageIndex = pageIndex(index);
            final int indexInPage = indexInPage(index);
            pages[pageIndex][indexInPage] |= value;
        }

        @Override
        public void addTo(long index, long value) {
            assert index < size;
            final int pageIndex = pageIndex(index);
            final int indexInPage = indexInPage(index);
            pages[pageIndex][indexInPage] += value;
        }

        @Override
        public void setAll(LongUnaryOperator gen) {
            for (int i = 0; i < pages.length; i++) {
                final long t = ((long) i) << PAGE_SHIFT;
                Arrays.setAll(pages[i], j -> gen.applyAsLong(t + j));
            }
        }

        @Override
        public void fill(long value) {
            for (long[] page : pages) {
                Arrays.fill(page, value);
            }
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long release() {
            if (pages != null) {
                pages = null;
                return memoryUsed;
            }
            return 0L;
        }

        @Override
        public Cursor newCursor() {
            return new PagedCursor(size, pages);
        }

        @Override
        public Cursor cursor(final long from, final Cursor cursor) {
            assert cursor instanceof PagedCursor;
            ((PagedCursor) cursor).init(from);
            return cursor;
        }

        private static int pageIndex(long index) {
        return (int) (index >>> PAGE_SHIFT);
    }

        private static int indexInPage(long index) {
        return (int) (index & PAGE_MASK);
    }

        private static final class PagedCursor extends Cursor {

            private final long[][] pages;
            private final int maxPage;
            private final long capacity;

            private int page;
            private int fromPage;

            private PagedCursor(final long capacity, final long[][] pages) {
                super();
                this.capacity = capacity;
                this.maxPage = pages.length - 1;
                this.pages = pages;
            }

            private void init(long fromIndex) {
                assert fromIndex < capacity;
                fromPage = pageIndex(fromIndex);
                array = pages[fromPage];
                offset = indexInPage(fromIndex);
                limit = (int) Math.min(PAGE_SIZE, capacity);
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
    }
}

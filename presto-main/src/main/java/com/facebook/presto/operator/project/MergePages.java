/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.operator.project;

import com.facebook.presto.memory.context.AggregatedMemoryContext;
import com.facebook.presto.memory.context.LocalMemoryContext;
import com.facebook.presto.operator.WorkProcessor;
import com.facebook.presto.spi.Page;
import com.facebook.presto.spi.PageBuilder;
import com.facebook.presto.spi.block.PageBuilderStatus;
import com.facebook.presto.spi.type.Type;
import com.google.common.collect.ImmutableList;

import javax.annotation.concurrent.NotThreadSafe;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.operator.WorkProcessor.ProcessorState.finished;
import static com.facebook.presto.operator.WorkProcessor.ProcessorState.needsMoreData;
import static com.facebook.presto.operator.WorkProcessor.ProcessorState.ofResult;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * This class is intended to be used right after the PageProcessor to ensure
 * that the size of the pages returned by FilterAndProject and ScanFilterAndProject
 * is big enough so it does not introduce considerable synchronization overhead.
 * <p>
 * As long as the input page contains more than {@link MergePagesTransformation#minRowCount} rows
 * or is bigger than {@link MergePagesTransformation#minPageSizeInBytes} it is returned as is without
 * additional memory copy.
 * <p>
 * The page data that has been buffered so far before receiving a "big" page is being flushed
 * before transferring a "big" page.
 * <p>
 * Although it is still possible that the {@link MergePages} may return a tiny page,
 * this situation is considered to be rare due to the assumption that filter selectivity may not
 * vary a lot based on the particular input page.
 * <p>
 * Considering the CPU time required to process(filter, project) a full (~1MB) page returned by a
 * connector, the CPU cost of memory copying (< 50kb, < 1024 rows) is supposed to be negligible.
 */
@NotThreadSafe
public final class MergePages
{
    private static final int MAX_MIN_PAGE_SIZE = 1024 * 1024;

    private MergePages() {}

    public static WorkProcessor<Page> mergePages(
            Iterable<? extends Type> types,
            long minPageSizeInBytes,
            int minRowCount,
            WorkProcessor<Page> pages,
            AggregatedMemoryContext memoryContext)
    {
        return mergePages(types, minPageSizeInBytes, minRowCount, PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES, pages, memoryContext);
    }

    public static WorkProcessor<Page> mergePages(
            Iterable<? extends Type> types,
            long minPageSizeInBytes,
            int minRowCount,
            int maxPageSizeInBytes,
            WorkProcessor<Page> pages,
            AggregatedMemoryContext memoryContext)
    {
        return pages.transform(new MergePagesTransformation(types, minPageSizeInBytes, minRowCount, maxPageSizeInBytes, memoryContext.newLocalMemoryContext()));
    }

    private static class MergePagesTransformation
            implements WorkProcessor.Transformation<Page, Page>
    {
        private final List<Type> types;
        private final long minPageSizeInBytes;
        private final int minRowCount;
        private final LocalMemoryContext memoryContext;

        private PageBuilder pageBuilder;

        private MergePagesTransformation(Iterable<? extends Type> types, long minPageSizeInBytes, int minRowCount, int maxPageSizeInBytes, LocalMemoryContext memoryContext)
        {
            this.types = ImmutableList.copyOf(requireNonNull(types, "types is null"));
            checkArgument(minPageSizeInBytes >= 0, "minPageSizeInBytes must be greater or equal than zero");
            checkArgument(minRowCount >= 0, "minRowCount must be greater or equal than zero");
            checkArgument(maxPageSizeInBytes > 0, "maxPageSizeInBytes must be greater than zero");
            checkArgument(maxPageSizeInBytes >= minPageSizeInBytes, "maxPageSizeInBytes must be greater or equal than minPageSizeInBytes");
            checkArgument(minPageSizeInBytes <= MAX_MIN_PAGE_SIZE, "minPageSizeInBytes must be less or equal than %d", MAX_MIN_PAGE_SIZE);
            this.minPageSizeInBytes = minPageSizeInBytes;
            this.minRowCount = minRowCount;
            this.memoryContext = requireNonNull(memoryContext, "memoryContext is null");
            pageBuilder = PageBuilder.withMaxPageSize(maxPageSizeInBytes, this.types);
        }

        @Override
        public WorkProcessor.ProcessorState<Page> process(Optional<Page> inputPageOptional)
        {
            boolean inputFinished = !inputPageOptional.isPresent();
            if (inputFinished) {
                if (pageBuilder.isEmpty()) {
                    pageBuilder = null;
                    memoryContext.close();
                    return finished();
                }

                return ofResult(flush(), false);
            }

            Page inputPage = inputPageOptional.get();
            if (inputPage.getSizeInBytes() >= minPageSizeInBytes || inputPage.getPositionCount() >= minRowCount) {
                if (pageBuilder.isEmpty()) {
                    return ofResult(inputPage);
                }

                Page output = pageBuilder.build();
                pageBuilder.reset();
                // inputPage is preserved until next process(...) call
                memoryContext.setBytes(pageBuilder.getRetainedSizeInBytes() + inputPage.getRetainedSizeInBytes());
                return ofResult(output, false);
            }

            appendPage(inputPage);

            if (pageBuilder.isFull()) {
                return ofResult(flush());
            }

            return needsMoreData();
        }

        private void appendPage(Page page)
        {
            pageBuilder.declarePositions(page.getPositionCount());
            for (int channel = 0; channel < types.size(); channel++) {
                Type type = types.get(channel);
                for (int position = 0; position < page.getPositionCount(); position++) {
                    type.appendTo(page.getBlock(channel), position, pageBuilder.getBlockBuilder(channel));
                }
            }
            memoryContext.setBytes(pageBuilder.getRetainedSizeInBytes());
        }

        private Page flush()
        {
            Page output = pageBuilder.build();
            pageBuilder.reset();
            memoryContext.setBytes(pageBuilder.getRetainedSizeInBytes());
            return output;
        }
    }
}

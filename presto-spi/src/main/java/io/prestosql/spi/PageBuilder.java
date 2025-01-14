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
package io.prestosql.spi;

import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.block.PageBuilderStatus;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.Restorable;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.type.Type;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.prestosql.spi.block.PageBuilderStatus.DEFAULT_MAX_PAGE_SIZE_IN_BYTES;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Objects.requireNonNull;

@RestorableConfig(uncapturedFields = "types")
public class PageBuilder
        implements Restorable
{
    // We choose default initial size to be 8 for PageBuilder and BlockBuilder
    // so the underlying data is larger than the object overhead, and the size is power of 2.
    //
    // This could be any other small number.
    private static final int DEFAULT_INITIAL_EXPECTED_ENTRIES = 8;

    private final BlockBuilder[] blockBuilders;
    private final List<Type> types;
    private PageBuilderStatus pageBuilderStatus;
    private int declaredPositions;

    /**
     * Create a PageBuilder with given types.
     * <p>
     * A PageBuilder instance created with this constructor has no estimation about bytes per entry,
     * therefore it can resize frequently while appending new rows.
     * <p>
     * This constructor should only be used to get the initial PageBuilder.
     * Once the PageBuilder is full use reset() or createPageBuilderLike() to create a new
     * PageBuilder instance with its size estimated based on previous data.
     */
    public PageBuilder(List<? extends Type> types)
    {
        this(DEFAULT_INITIAL_EXPECTED_ENTRIES, types);
    }

    public PageBuilder(int initialExpectedEntries, List<? extends Type> types)
    {
        this(initialExpectedEntries, DEFAULT_MAX_PAGE_SIZE_IN_BYTES, types, Optional.empty());
    }

    public static PageBuilder withMaxPageSize(int maxPageBytes, List<? extends Type> types)
    {
        return new PageBuilder(DEFAULT_INITIAL_EXPECTED_ENTRIES, maxPageBytes, types, Optional.empty());
    }

    private PageBuilder(int initialExpectedEntries, int maxPageBytes, List<? extends Type> types, Optional<BlockBuilder[]> templateBlockBuilders)
    {
        this.types = unmodifiableList(new ArrayList<>(requireNonNull(types, "types is null")));

        pageBuilderStatus = new PageBuilderStatus(maxPageBytes);
        blockBuilders = new BlockBuilder[types.size()];

        if (templateBlockBuilders.isPresent()) {
            BlockBuilder[] templates = templateBlockBuilders.get();
            checkArgument(templates.length == types.size(), "Size of templates and types should match");
            for (int i = 0; i < blockBuilders.length; i++) {
                blockBuilders[i] = templates[i].newBlockBuilderLike(pageBuilderStatus.createBlockBuilderStatus());
            }
        }
        else {
            for (int i = 0; i < blockBuilders.length; i++) {
                blockBuilders[i] = types.get(i).createBlockBuilder(pageBuilderStatus.createBlockBuilderStatus(), initialExpectedEntries);
            }
        }
    }

    public void reset()
    {
        if (isEmpty()) {
            return;
        }
        pageBuilderStatus = new PageBuilderStatus(pageBuilderStatus.getMaxPageSizeInBytes());

        declaredPositions = 0;

        for (int i = 0; i < types.size(); i++) {
            blockBuilders[i] = blockBuilders[i].newBlockBuilderLike(pageBuilderStatus.createBlockBuilderStatus());
        }
    }

    public PageBuilder newPageBuilderLike()
    {
        return new PageBuilder(declaredPositions, pageBuilderStatus.getMaxPageSizeInBytes(), types, Optional.of(blockBuilders));
    }

    public BlockBuilder getBlockBuilder(int channel)
    {
        return blockBuilders[channel];
    }

    public Type getType(int channel)
    {
        return types.get(channel);
    }

    public void declarePosition()
    {
        declaredPositions++;
    }

    public void declarePositions(int positions)
    {
        declaredPositions += positions;
    }

    public boolean isFull()
    {
        return declaredPositions == Integer.MAX_VALUE || pageBuilderStatus.isFull();
    }

    public boolean isEmpty()
    {
        return declaredPositions == 0;
    }

    public int getPositionCount()
    {
        return declaredPositions;
    }

    public long getSizeInBytes()
    {
        return pageBuilderStatus.getSizeInBytes();
    }

    public long getRetainedSizeInBytes()
    {
        // We use a foreach loop instead of streams
        // as it has much better performance.
        long retainedSizeInBytes = 0;
        for (BlockBuilder blockBuilder : blockBuilders) {
            retainedSizeInBytes += blockBuilder.getRetainedSizeInBytes();
        }
        return retainedSizeInBytes;
    }

    public Page build()
    {
        if (blockBuilders.length == 0) {
            return new Page(declaredPositions);
        }

        Block[] blocks = new Block[blockBuilders.length];
        for (int i = 0; i < blocks.length; i++) {
            blocks[i] = blockBuilders[i].build();
            if (blocks[i].getPositionCount() != declaredPositions) {
                throw new IllegalStateException(format("Declared positions (%s) does not match block %s's number of entries (%s)", declaredPositions, i, blocks[i].getPositionCount()));
            }
        }

        return new Page(blocks);
    }

    private static void checkArgument(boolean expression, String errorMessage)
    {
        if (!expression) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        PageBuilderState myState = new PageBuilderState();
        Object[] blockBuildersSnapshot = new Object[blockBuilders.length];
        for (int i = 0; i < blockBuilders.length; i++) {
            blockBuildersSnapshot[i] = blockBuilders[i].capture(serdeProvider);
        }
        myState.blockBuilders = blockBuildersSnapshot;
        myState.pageBuilderStatus = pageBuilderStatus.capture(serdeProvider);
        myState.declaredPositions = declaredPositions;
        return myState;
    }

    @Override
    public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        PageBuilderState myState = (PageBuilderState) state;
        Object[] blockBuilderSnapshot = myState.blockBuilders;
        for (int i = 0; i < blockBuilderSnapshot.length; i++) {
            this.blockBuilders[i].restore(blockBuilderSnapshot[i], serdeProvider);
        }
        this.pageBuilderStatus.restore(myState.pageBuilderStatus, serdeProvider);
        this.declaredPositions = myState.declaredPositions;
    }

    private static class PageBuilderState
            implements Serializable
    {
        private Object[] blockBuilders;
        private Object pageBuilderStatus;
        private int declaredPositions;
    }
}

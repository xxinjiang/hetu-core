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
package io.prestosql.sql.planner.optimizations;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.prestosql.Session;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.connector.ConstantProperty;
import io.prestosql.spi.connector.LocalProperty;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.predicate.NullableValue;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.relation.VariableReferenceExpression;
import io.prestosql.sql.planner.Partitioning;
import io.prestosql.sql.planner.PartitioningHandle;
import io.prestosql.sql.planner.SymbolUtils;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.SymbolReference;

import javax.annotation.concurrent.Immutable;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Iterables.transform;
import static io.prestosql.sql.planner.Partitioning.ArgumentBinding.constantBinding;
import static io.prestosql.sql.planner.Partitioning.ArgumentBinding.expressionBinding;
import static io.prestosql.sql.planner.SystemPartitioningHandle.COORDINATOR_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SOURCE_DISTRIBUTION;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToExpression;
import static io.prestosql.sql.relational.OriginalExpressionUtils.isExpression;
import static io.prestosql.util.MoreLists.filteredCopy;
import static java.util.Objects.requireNonNull;

public class ActualProperties
{
    private final Global global;
    private final List<LocalProperty<Symbol>> localProperties;
    private final Map<Symbol, NullableValue> constants;

    private ActualProperties(
            Global global,
            List<? extends LocalProperty<Symbol>> inputLocalProperties,
            Map<Symbol, NullableValue> constants)
    {
        requireNonNull(global, "globalProperties is null");
        requireNonNull(inputLocalProperties, "inputLocalProperties is null");
        requireNonNull(constants, "constants is null");

        this.global = global;

        // The constants field implies a ConstantProperty in localProperties (but not vice versa).
        // Let's make sure to include the constants into the local constant properties.
        List<? extends LocalProperty<Symbol>> localPropertieList = inputLocalProperties;
        Set<Symbol> localConstants = LocalProperties.extractLeadingConstants(localPropertieList);
        localPropertieList = LocalProperties.stripLeadingConstants(localPropertieList);

        Set<Symbol> updatedLocalConstants = ImmutableSet.<Symbol>builder()
                .addAll(localConstants)
                .addAll(constants.keySet())
                .build();

        List<LocalProperty<Symbol>> updatedLocalProperties = LocalProperties.normalizeAndPrune(ImmutableList.<LocalProperty<Symbol>>builder()
                .addAll(transform(updatedLocalConstants, ConstantProperty::new))
                .addAll(localPropertieList)
                .build());

        this.localProperties = ImmutableList.copyOf(updatedLocalProperties);
        this.constants = ImmutableMap.copyOf(constants);
    }

    public boolean isCoordinatorOnly()
    {
        return global.isCoordinatorOnly();
    }

    /**
     * @return true if the plan will only execute on a single node
     */
    public boolean isSingleNode()
    {
        return global.isSingleNode();
    }

    public boolean isNullsAndAnyReplicated()
    {
        return global.isNullsAndAnyReplicated();
    }

    public boolean isStreamPartitionedOn(Collection<Symbol> columns, boolean exactly)
    {
        return isStreamPartitionedOn(columns, false, exactly);
    }

    public boolean isStreamPartitionedOn(Collection<Symbol> columns, boolean nullsAndAnyReplicated, boolean exactly)
    {
        if (exactly) {
            return global.isStreamPartitionedOnExactly(columns, constants.keySet(), nullsAndAnyReplicated);
        }
        return global.isStreamPartitionedOn(columns, constants.keySet(), nullsAndAnyReplicated);
    }

    public boolean isNodePartitionedOn(Collection<Symbol> columns, boolean exactly)
    {
        return isNodePartitionedOn(columns, false, exactly);
    }

    public boolean isNodePartitionedOn(Collection<Symbol> columns, boolean nullsAndAnyReplicated, boolean exactly)
    {
        if (exactly) {
            return global.isNodePartitionedOnExactly(columns, constants.keySet(), nullsAndAnyReplicated);
        }
        return global.isNodePartitionedOn(columns, constants.keySet(), nullsAndAnyReplicated);
    }

    public boolean isCompatibleTablePartitioningWith(Partitioning partitioning, boolean nullsAndAnyReplicated, Metadata metadata, Session session)
    {
        return global.isCompatibleTablePartitioningWith(partitioning, nullsAndAnyReplicated, metadata, session);
    }

    public boolean isCompatibleTablePartitioningWith(ActualProperties other, Function<Symbol, Set<Symbol>> symbolMappings, Metadata metadata, Session session)
    {
        return global.isCompatibleTablePartitioningWith(
                other.global,
                symbolMappings,
                symbol -> Optional.ofNullable(constants.get(symbol)),
                symbol -> Optional.ofNullable(other.constants.get(symbol)),
                metadata,
                session);
    }

    /**
     * @return true if all the data will effectively land in a single stream
     */
    public boolean isEffectivelySingleStream()
    {
        return global.isEffectivelySingleStream(constants.keySet());
    }

    /**
     * @return true if repartitioning on the keys will yield some difference
     */
    public boolean isStreamRepartitionEffective(Collection<Symbol> keys)
    {
        return global.isStreamRepartitionEffective(keys, constants.keySet());
    }

    public ActualProperties translate(Function<Symbol, Optional<Symbol>> translator)
    {
        return builder()
                .global(global.translate(new Partitioning.Translator(translator, symbol -> Optional.ofNullable(constants.get(symbol)), expression -> Optional.empty())))
                .local(LocalProperties.translate(localProperties, translator))
                .constants(translateConstants(translator))
                .build();
    }

    public ActualProperties translate(
            Function<Symbol, Optional<Symbol>> translator,
            Function<Expression, Optional<Symbol>> expressionTranslator)
    {
        return builder()
                .global(global.translate(new Partitioning.Translator(translator, symbol -> Optional.ofNullable(constants.get(symbol)), expressionTranslator)))
                .local(LocalProperties.translate(localProperties, translator))
                .constants(translateConstants(translator))
                .build();
    }

    public Optional<Partitioning> getNodePartitioning()
    {
        return global.getNodePartitioning();
    }

    public Map<Symbol, NullableValue> getConstants()
    {
        return constants;
    }

    public List<LocalProperty<Symbol>> getLocalProperties()
    {
        return localProperties;
    }

    public ActualProperties withReplicatedNulls(boolean replicatedNulls)
    {
        return builderFrom(this)
                .global(global.withReplicatedNulls(replicatedNulls))
                .build();
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static Builder builderFrom(ActualProperties properties)
    {
        return new Builder(properties.global, properties.localProperties, properties.constants);
    }

    private Map<Symbol, NullableValue> translateConstants(Function<Symbol, Optional<Symbol>> translator)
    {
        Map<Symbol, NullableValue> translatedConstants = new HashMap<>();
        for (Map.Entry<Symbol, NullableValue> entry : constants.entrySet()) {
            Optional<Symbol> translatedKey = translator.apply(entry.getKey());
            if (translatedKey.isPresent()) {
                translatedConstants.put(translatedKey.get(), entry.getValue());
            }
        }
        return translatedConstants;
    }

    public ActualProperties translateRowExpression(Map<Symbol, RowExpression> assignments, TypeProvider types)
    {
        Map<Symbol, SymbolReference> inputToOutputSymbol = new HashMap<>();
        for (Map.Entry<Symbol, RowExpression> assignment : assignments.entrySet()) {
            RowExpression expression = assignment.getValue();
            SymbolReference symbolReference = SymbolUtils.toSymbolReference(assignment.getKey());
            if (isExpression(expression)) {
                if (castToExpression(expression) instanceof SymbolReference) {
                    inputToOutputSymbol.put(SymbolUtils.from(castToExpression(expression)), symbolReference);
                }
            }
            else {
                if (expression instanceof VariableReferenceExpression) {
                    inputToOutputSymbol.put(new Symbol(((VariableReferenceExpression) expression).getName()), symbolReference);
                }
            }
        }

        Map<Symbol, Partitioning.ArgumentBinding> inputToOutputMappings =
                inputToOutputSymbol.entrySet().stream().collect(Collectors.toMap(v -> v.getKey(), v -> expressionBinding(v.getValue())));
        Map<Symbol, NullableValue> translatedConstants = new HashMap<>();
        for (Map.Entry<Symbol, NullableValue> entry : constants.entrySet()) {
            if (inputToOutputSymbol.containsKey(entry.getKey())) {
                Symbol symbol = SymbolUtils.from(inputToOutputSymbol.get(entry.getKey()));
                translatedConstants.put(symbol, entry.getValue());
            }
            else {
                inputToOutputMappings.put(entry.getKey(), constantBinding(entry.getValue()));
            }
        }

        return builder()
                .global(global.translateRowExpression(inputToOutputMappings, assignments, types))
                .local(LocalProperties.translate(localProperties,
                        symbol -> inputToOutputSymbol.containsKey(symbol) ? Optional.of(SymbolUtils.from(inputToOutputSymbol.get(symbol))) : Optional.empty()))
                .constants(translatedConstants)
                .build();
    }

    public static class Builder
    {
        private Global global;
        private List<LocalProperty<Symbol>> localProperties;
        private Map<Symbol, NullableValue> constants;
        private boolean unordered;

        public Builder()
        {
            this(Global.arbitraryPartition(), ImmutableList.of(), ImmutableMap.of());
        }

        public Builder(Global global, List<LocalProperty<Symbol>> localProperties, Map<Symbol, NullableValue> constants)
        {
            this.global = requireNonNull(global, "global is null");
            this.localProperties = ImmutableList.copyOf(localProperties);
            this.constants = ImmutableMap.copyOf(constants);
        }

        public Builder global(Global global)
        {
            this.global = global;
            return this;
        }

        public Builder global(ActualProperties other)
        {
            this.global = other.global;
            return this;
        }

        public Builder local(List<? extends LocalProperty<Symbol>> localProperties)
        {
            this.localProperties = ImmutableList.copyOf(localProperties);
            return this;
        }

        public Builder constants(Map<Symbol, NullableValue> constants)
        {
            this.constants = ImmutableMap.copyOf(constants);
            return this;
        }

        public Builder unordered(boolean unordered)
        {
            this.unordered = unordered;
            return this;
        }

        public ActualProperties build()
        {
            List<LocalProperty<Symbol>> properties = this.localProperties;
            if (unordered) {
                properties = filteredCopy(this.localProperties, property -> !property.isOrderSensitive());
            }
            return new ActualProperties(global, properties, constants);
        }
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(global, localProperties, constants.keySet());
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        final ActualProperties other = (ActualProperties) obj;
        return Objects.equals(this.global, other.global)
                && Objects.equals(this.localProperties, other.localProperties)
                && Objects.equals(this.constants.keySet(), other.constants.keySet());
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("globalProperties", global)
                .add("localProperties", localProperties)
                .add("constants", constants)
                .toString();
    }

    @Immutable
    public static final class Global
    {
        // Description of the partitioning of the data across nodes
        private final Optional<Partitioning> nodePartitioning; // if missing => partitioned with some unknown scheme
        // Description of the partitioning of the data across streams (splits)
        private final Optional<Partitioning> streamPartitioning; // if missing => partitioned with some unknown scheme

        // NOTE: Partitioning on zero columns (or effectively zero columns if the columns are constant) indicates that all
        // the rows will be partitioned into a single node or stream. However, this can still be a partitioned plan in that the plan
        // will be executed on multiple servers, but only one server will get all the data.

        // Description of whether rows with nulls in partitioning columns or some arbitrary rows have been replicated to all *nodes*
        private final boolean nullsAndAnyReplicated;

        private Global(Optional<Partitioning> nodePartitioning, Optional<Partitioning> streamPartitioning, boolean nullsAndAnyReplicated)
        {
            checkArgument(!nodePartitioning.isPresent()
                            || !streamPartitioning.isPresent()
                            || nodePartitioning.get().getColumns().containsAll(streamPartitioning.get().getColumns())
                            || streamPartitioning.get().getColumns().containsAll(nodePartitioning.get().getColumns()),
                    "Global stream partitioning columns should match node partitioning columns");
            this.nodePartitioning = requireNonNull(nodePartitioning, "nodePartitioning is null");
            this.streamPartitioning = requireNonNull(streamPartitioning, "streamPartitioning is null");
            this.nullsAndAnyReplicated = nullsAndAnyReplicated;
        }

        public static Global coordinatorSingleStreamPartition()
        {
            return partitionedOn(
                    COORDINATOR_DISTRIBUTION,
                    ImmutableList.of(),
                    Optional.of(ImmutableList.of()));
        }

        public static Global singleStreamPartition()
        {
            return partitionedOn(
                    SINGLE_DISTRIBUTION,
                    ImmutableList.of(),
                    Optional.of(ImmutableList.of()));
        }

        public static Global arbitraryPartition()
        {
            return new Global(Optional.empty(), Optional.empty(), false);
        }

        public static Global partitionedOn(PartitioningHandle nodePartitioningHandle, List<Symbol> nodePartitioning, Optional<List<Symbol>> streamPartitioning)
        {
            return new Global(
                    Optional.of(Partitioning.create(nodePartitioningHandle, nodePartitioning)),
                    streamPartitioning.map(columns -> Partitioning.create(SOURCE_DISTRIBUTION, columns)),
                    false);
        }

        public static Global partitionedOn(Partitioning nodePartitioning, Optional<Partitioning> streamPartitioning)
        {
            return new Global(
                    Optional.of(nodePartitioning),
                    streamPartitioning,
                    false);
        }

        public static Global streamPartitionedOn(List<Symbol> streamPartitioning)
        {
            return new Global(
                    Optional.empty(),
                    Optional.of(Partitioning.create(SOURCE_DISTRIBUTION, streamPartitioning)),
                    false);
        }

        public Global withReplicatedNulls(boolean replicatedNulls)
        {
            return new Global(nodePartitioning, streamPartitioning, replicatedNulls);
        }

        private boolean isNullsAndAnyReplicated()
        {
            return nullsAndAnyReplicated;
        }

        /**
         * @return true if the plan will only execute on a single node
         */
        private boolean isSingleNode()
        {
            if (!nodePartitioning.isPresent()) {
                return false;
            }

            return nodePartitioning.get().getHandle().isSingleNode();
        }

        private boolean isCoordinatorOnly()
        {
            if (!nodePartitioning.isPresent()) {
                return false;
            }

            return nodePartitioning.get().getHandle().isCoordinatorOnly();
        }

        private boolean isNodePartitionedOn(Collection<Symbol> columns, Set<Symbol> constants, boolean nullsAndAnyReplicated)
        {
            return nodePartitioning.isPresent() && nodePartitioning.get().isPartitionedOn(columns, constants) && this.nullsAndAnyReplicated == nullsAndAnyReplicated;
        }

        private boolean isNodePartitionedOnExactly(Collection<Symbol> columns, Set<Symbol> constants, boolean nullsAndAnyReplicated)
        {
            return nodePartitioning.isPresent() && nodePartitioning.get().isPartitionedOnExactly(columns, constants) && this.nullsAndAnyReplicated == nullsAndAnyReplicated;
        }

        private boolean isCompatibleTablePartitioningWith(Partitioning partitioning, boolean nullsAndAnyReplicated, Metadata metadata, Session session)
        {
            return nodePartitioning.isPresent() && nodePartitioning.get().isCompatibleWith(partitioning, metadata, session) && this.nullsAndAnyReplicated == nullsAndAnyReplicated;
        }

        private boolean isCompatibleTablePartitioningWith(
                Global other,
                Function<Symbol, Set<Symbol>> symbolMappings,
                Function<Symbol, Optional<NullableValue>> leftConstantMapping,
                Function<Symbol, Optional<NullableValue>> rightConstantMapping,
                Metadata metadata,
                Session session)
        {
            return nodePartitioning.isPresent() &&
                    other.nodePartitioning.isPresent() &&
                    nodePartitioning.get().isCompatibleWith(
                            other.nodePartitioning.get(),
                            symbolMappings,
                            leftConstantMapping,
                            rightConstantMapping,
                            metadata,
                            session) &&
                    nullsAndAnyReplicated == other.nullsAndAnyReplicated;
        }

        private Optional<Partitioning> getNodePartitioning()
        {
            return nodePartitioning;
        }

        private boolean isStreamPartitionedOn(Collection<Symbol> columns, Set<Symbol> constants, boolean nullsAndAnyReplicated)
        {
            return streamPartitioning.isPresent() && streamPartitioning.get().isPartitionedOn(columns, constants) && this.nullsAndAnyReplicated == nullsAndAnyReplicated;
        }

        private boolean isStreamPartitionedOnExactly(Collection<Symbol> columns, Set<Symbol> constants, boolean nullsAndAnyReplicated)
        {
            return streamPartitioning.isPresent() && streamPartitioning.get().isPartitionedOnExactly(columns, constants) && this.nullsAndAnyReplicated == nullsAndAnyReplicated;
        }

        /**
         * @return true if all the data will effectively land in a single stream
         */
        private boolean isEffectivelySingleStream(Set<Symbol> constants)
        {
            return streamPartitioning.isPresent() && streamPartitioning.get().isEffectivelySinglePartition(constants) && !nullsAndAnyReplicated;
        }

        /**
         * @return true if repartitioning on the keys will yield some difference
         */
        private boolean isStreamRepartitionEffective(Collection<Symbol> keys, Set<Symbol> constants)
        {
            return (!streamPartitioning.isPresent() || streamPartitioning.get().isRepartitionEffective(keys, constants)) && !nullsAndAnyReplicated;
        }

        private Global translate(Partitioning.Translator translator)
        {
            return new Global(
                    nodePartitioning.flatMap(partitioning -> partitioning.translate(translator)),
                    streamPartitioning.flatMap(partitioning -> partitioning.translate(translator)),
                    nullsAndAnyReplicated);
        }

        private Global translateRowExpression(Map<Symbol, Partitioning.ArgumentBinding> inputToOutputMappings, Map<Symbol, RowExpression> assignments, TypeProvider types)
        {
            return new Global(
                    nodePartitioning.flatMap(partitioning -> partitioning.translateRowExpression(inputToOutputMappings, assignments, types)),
                    streamPartitioning.flatMap(partitioning -> partitioning.translateRowExpression(inputToOutputMappings, assignments, types)),
                    nullsAndAnyReplicated);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(nodePartitioning, streamPartitioning, nullsAndAnyReplicated);
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final Global other = (Global) obj;
            return Objects.equals(this.nodePartitioning, other.nodePartitioning) &&
                    Objects.equals(this.streamPartitioning, other.streamPartitioning) &&
                    this.nullsAndAnyReplicated == other.nullsAndAnyReplicated;
        }

        @Override
        public String toString()
        {
            return toStringHelper(this)
                    .add("nodePartitioning", nodePartitioning)
                    .add("streamPartitioning", streamPartitioning)
                    .add("nullsAndAnyReplicated", nullsAndAnyReplicated)
                    .toString();
        }
    }
}

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
package com.facebook.presto.sql.planner.iterative;

import com.facebook.presto.Session;
import com.facebook.presto.cost.PlanNodeStatsEstimate;
import com.facebook.presto.cost.StatsCalculator;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.plan.PlanNode;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.facebook.presto.cost.PlanNodeStatsEstimate.UNKNOWN_STATS;
import static com.google.common.collect.MoreCollectors.toOptional;
import static java.util.Objects.requireNonNull;

public interface Lookup
{
    /**
     * Resolves a node by materializing GroupReference nodes
     * representing symbolic references to other nodes. This method
     * is deprecated since is assumes group contains only one node.
     * <p>
     * If the node is not a GroupReference, it returns the
     * argument as is.
     */
    @Deprecated
    default PlanNode resolve(PlanNode node)
    {
        if (node instanceof GroupReference) {
            return resolveGroup(node).collect(toOptional()).get();
        }
        return node;
    }

    /**
     * Resolves nodes by materializing GroupReference nodes
     * representing symbolic references to other nodes.
     * <p>
     * @throws IllegalArgumentException if the node is not a GroupReference
     */
    Stream<PlanNode> resolveGroup(PlanNode node);

    PlanNodeStatsEstimate getStats(PlanNode node, Session session, Map<Symbol, Type> types);

    /**
     * A Lookup implementation that does not perform lookup. It satisfies contract
     * by rejecting {@link GroupReference}-s.
     */
    static Lookup noLookup()
    {
        return new Lookup() {
            @Override
            public Stream<PlanNode> resolveGroup(PlanNode node)
            {
                throw new UnsupportedOperationException();
            }

            @Override
            public PlanNodeStatsEstimate getStats(PlanNode node, Session session, Map<Symbol, Type> types)
            {
                return UNKNOWN_STATS;
            }
        };
    }

    @Deprecated
    static Lookup from(Function<GroupReference, Stream<PlanNode>> resolver)
    {
        return from(resolver,
                (planNode, lookup, session, types) -> UNKNOWN_STATS);
    }

    static Lookup from(Function<GroupReference, Stream<PlanNode>> resolver, StatsCalculator statsCalculator)
    {
        requireNonNull(resolver, "resolver is null");
        requireNonNull(statsCalculator, "statsCalculator is null");

        return new Lookup()
        {
            @Override
            public Stream<PlanNode> resolveGroup(PlanNode node)
            {
                return resolver.apply((GroupReference) node);
            }

            @Override
            public PlanNodeStatsEstimate getStats(PlanNode node, Session session, Map<Symbol, Type> types)
            {
                return statsCalculator.calculateStats(resolve(node), this, session, types);
            }
        };
    }
}

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
package com.facebook.presto.cost;

import com.facebook.presto.Session;
import com.facebook.presto.matching.Pattern;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.sql.planner.Symbol;
import com.facebook.presto.sql.planner.iterative.Lookup;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SemiJoinNode;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.NotExpression;
import com.facebook.presto.sql.tree.SymbolReference;
import com.google.common.collect.Iterables;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.facebook.presto.sql.ExpressionUtils.combineConjuncts;
import static com.facebook.presto.sql.ExpressionUtils.extractConjuncts;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public class SimpleFilterProjectSemiJoinStatsRule
        implements ComposableStatsCalculator.Rule
{
    private static final Pattern PATTERN = Pattern.matchByClass(FilterNode.class);

    private final FilterStatsCalculator filterStatsCalculator;
    private final SemiJoinStatsCalculator semiJoinStatsCalculator;

    public SimpleFilterProjectSemiJoinStatsRule(FilterStatsCalculator filterStatsCalculator, SemiJoinStatsCalculator semiJoinStatsCalculator)
    {
        this.filterStatsCalculator = requireNonNull(filterStatsCalculator, "filterStatsCalculator can not be null");
        this.semiJoinStatsCalculator = requireNonNull(semiJoinStatsCalculator, "semiJoinStatsCalculator can not be null");
    }

    @Override
    public Pattern getPattern()
    {
        return PATTERN;
    }

    @Override
    public Optional<PlanNodeStatsEstimate> calculate(PlanNode node, Lookup lookup, Session session, Map<Symbol, Type> types)
    {
        FilterNode filterNode = (FilterNode) node;
        SemiJoinNode semiJoinNode;
        if (filterNode.getSource() instanceof ProjectNode) {
            ProjectNode projectNode = (ProjectNode) filterNode.getSource();
            if (!projectNode.isIdentity()) {
                return Optional.empty();
            }
            if (!(projectNode.getSource() instanceof SemiJoinNode)) {
                return Optional.empty();
            }
            semiJoinNode = (SemiJoinNode) projectNode.getSource();
        }
        else {
            if (!(filterNode.getSource() instanceof SemiJoinNode)) {
                return Optional.empty();
            }
            semiJoinNode = (SemiJoinNode) filterNode.getSource();
        }

        return calculate(filterNode, semiJoinNode, lookup, session, types);
    }

    private Optional<PlanNodeStatsEstimate> calculate(FilterNode filterNode, SemiJoinNode semiJoinNode, Lookup lookup, Session session, Map<Symbol, Type> types)
    {
        PlanNodeStatsEstimate sourceStats = lookup.getStats(semiJoinNode.getSource(), session, types);
        PlanNodeStatsEstimate filteringSourceStats = lookup.getStats(semiJoinNode.getFilteringSource(), session, types);
        Symbol filteringSourceJoinSymbol = semiJoinNode.getFilteringSourceJoinSymbol();
        Symbol sourceJoinSymbol = semiJoinNode.getSourceJoinSymbol();

        Optional<SemiJoinOutputFilter> semiJoinOutputFilter = extractSemiJoinOutputFilter(filterNode.getPredicate(), semiJoinNode.getSemiJoinOutput());

        if (!semiJoinOutputFilter.isPresent()) {
            return Optional.empty();
        }

        PlanNodeStatsEstimate semiJoinStats;
        if (semiJoinOutputFilter.get().isNegated()) {
            semiJoinStats = semiJoinStatsCalculator.computeAntiJoin(sourceStats, filteringSourceStats, sourceJoinSymbol, filteringSourceJoinSymbol);
        }
        else {
            semiJoinStats = semiJoinStatsCalculator.computeSemiJoin(sourceStats, filteringSourceStats, sourceJoinSymbol, filteringSourceJoinSymbol);
        }

        // apply remaining predicate
        return Optional.of(filterStatsCalculator.filterStats(semiJoinStats, semiJoinOutputFilter.get().getRemainingPredicate(), session, types));
    }

    private static Optional<SemiJoinOutputFilter> extractSemiJoinOutputFilter(Expression predicate, Symbol semiJoinOutput)
    {
        List<Expression> conjuncts = extractConjuncts(predicate);
        List<Expression> semiJoinOutputReferences = conjuncts.stream()
                .filter(conjunct -> isSemiJoinOutputReference(conjunct, semiJoinOutput))
                .collect(toImmutableList());

        if (semiJoinOutputReferences.size() != 1) {
            return Optional.empty();
        }

        Expression semiJoinOutputReference = Iterables.getOnlyElement(semiJoinOutputReferences);
        Expression remainingPredicate = combineConjuncts(conjuncts.stream()
                .filter(conjunct -> conjunct != semiJoinOutputReference)
                .collect(toImmutableList()));
        boolean negated = semiJoinOutputReference instanceof NotExpression;
        return Optional.of(new SemiJoinOutputFilter(negated, remainingPredicate));
    }

    private static boolean isSemiJoinOutputReference(Expression conjunct, Symbol semiJoinOutput)
    {
        SymbolReference semiJoinOuputSymbolReference = semiJoinOutput.toSymbolReference();
        return conjunct.equals(semiJoinOuputSymbolReference) ||
                (conjunct instanceof NotExpression && ((NotExpression) conjunct).getValue().equals(semiJoinOuputSymbolReference));
    }

    private static class SemiJoinOutputFilter
    {
        private final boolean negated;
        private final Expression remainingPredicate;

        public SemiJoinOutputFilter(boolean negated, Expression remainingPredicate)
        {
            this.negated = negated;
            this.remainingPredicate = requireNonNull(remainingPredicate, "remainingPredicate can not be null");
        }

        public boolean isNegated()
        {
            return negated;
        }

        public Expression getRemainingPredicate()
        {
            return remainingPredicate;
        }
    }
}

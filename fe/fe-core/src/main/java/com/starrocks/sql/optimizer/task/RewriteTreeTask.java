// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.sql.optimizer.task;

import com.google.common.base.Preconditions;
import com.starrocks.common.profile.Timer;
import com.starrocks.common.profile.Tracers;
import com.starrocks.sql.optimizer.ExpressionContext;
import com.starrocks.sql.optimizer.OptExpression;
import com.starrocks.sql.optimizer.OptimizerTraceUtil;
import com.starrocks.sql.optimizer.operator.OperatorType;
import com.starrocks.sql.optimizer.operator.pattern.Pattern;
import com.starrocks.sql.optimizer.rule.Rule;

import java.util.List;

/*
 *
 * Rewrite whole tree by TopDown way
 * Rules will be applied to each node from the TopDown, and will repeat
 * push same task to rewrite whole tree when the task isn't only once and
 * tree was changed, until the tree is no changed.
 *
 */
public class RewriteTreeTask extends OptimizerTask {
    protected final OptExpression planTree;
    protected final boolean onlyOnce;
    protected final List<Rule> rules;
    protected long change = 0;

    public RewriteTreeTask(TaskContext context, OptExpression root, List<Rule> rules, boolean onlyOnce) {
        super(context);
        this.planTree = root;
        this.rules = rules;
        this.onlyOnce = onlyOnce;
        Preconditions.checkState(planTree.getOp().getOpType() == OperatorType.LOGICAL);
    }

    public RewriteTreeTask(TaskContext context, OptExpression root, Rule rule, boolean onlyOnce) {
        this(context, root, List.of(rule), onlyOnce);
    }

    public OptExpression getResult() {
        return planTree.getInputs().get(0);
    }

    @Override
    public void execute() {
        if (rules.stream().allMatch(rule -> context.getOptimizerContext()
                .getOptimizerOptions().isRuleDisable(rule.type()))) {
            return;
        }
        // first node must be RewriteAnchorNode
        rewrite(planTree, 0, planTree.getInputs().get(0));
        // pushdownNotNullPredicates should task-bind, reset it before another RewriteTreeTask
        // TODO: refactor TaskContext to make it local to support this requirement better?
        context.getOptimizerContext().clearNotNullPredicates();
        if (change > 0 && !onlyOnce) {
            pushTask(new RewriteTreeTask(context, planTree, rules, false));
        }
    }

    protected void rewrite(OptExpression parent, int childIndex, OptExpression root) {
        root = applyRules(parent, childIndex, root, rules);
        // prune cte column depend on prune right child first
        for (int i = root.getInputs().size() - 1; i >= 0; i--) {
            rewrite(root, i, root.getInputs().get(i));
        }
    }

    protected OptExpression applyRules(OptExpression parent, int childIndex, OptExpression root, List<Rule> rules) {
        for (Rule rule : rules) {
            if (context.getOptimizerContext().getOptimizerOptions().isRuleDisable(rule.type())) {
                continue;
            }
            if (rule.exhausted(context.getOptimizerContext())) {
                continue;
            }
            if (!match(rule.getPattern(), root) || !rule.check(root, context.getOptimizerContext())) {
                continue;
            }

            if (!rule.predecessorRules().isEmpty()) {
                root = applyRules(parent, childIndex, root, rule.predecessorRules());
            }

            OptimizerTraceUtil.logApplyRuleBefore(context.getOptimizerContext(), rule, root);
            List<OptExpression> result;
            try (Timer ignore = Tracers.watchScope(Tracers.Module.OPTIMIZER, rule.toString())) {
                result = rule.transform(root, context.getOptimizerContext());
            }
            Preconditions.checkState(result.size() <= 1, "Rewrite rule should provide at most 1 expression");

            OptimizerTraceUtil.logApplyRuleAfter(result);

            if (result.isEmpty()) {
                continue;
            }

            parent.getInputs().set(childIndex, result.get(0));
            root = result.get(0);
            change++;
            deriveLogicalProperty(root);

            if (!rule.successorRules().isEmpty()) {
                root = applyRules(parent, childIndex, root, rule.successorRules());
            }
        }
        return root;
    }

    protected boolean match(Pattern pattern, OptExpression root) {
        if (!pattern.matchWithoutChild(root)) {
            return false;
        }

        if (!pattern.children().isEmpty() && pattern.children().size() != root.getInputs().size() &&
                pattern.children().stream().noneMatch(p -> p.is(OperatorType.PATTERN_MULTI_LEAF))) {
            return false;
        }
        int patternIndex = 0;
        int childIndex = 0;

        while (patternIndex < pattern.children().size() && childIndex < root.getInputs().size()) {
            OptExpression child = root.getInputs().get(childIndex);
            Pattern childPattern = pattern.childAt(patternIndex);

            if (!match(childPattern, child)) {
                return false;
            }

            if (!(childPattern.is(OperatorType.PATTERN_MULTI_LEAF) && (root.getInputs().size() - childIndex) >
                    (pattern.children().size() - patternIndex))) {
                patternIndex++;
            }

            childIndex++;
        }
        return true;
    }

    protected void deriveLogicalProperty(OptExpression root) {
        for (OptExpression child : root.getInputs()) {
            deriveLogicalProperty(child);
        }

        if (root.getLogicalProperty() == null) {
            ExpressionContext context = new ExpressionContext(root);
            context.deriveLogicalProperty();
            root.setLogicalProperty(context.getRootProperty());
        }
    }

    public boolean hasChange() {
        return change > 0;
    }
}

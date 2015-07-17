/*
 * Copyright 2012 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.optaplanner.core.impl.score.director;

import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.ObjectUtils;
import org.optaplanner.core.api.domain.solution.Solution;
import org.optaplanner.core.api.domain.solution.cloner.SolutionCloner;
import org.optaplanner.core.api.score.Score;
import org.optaplanner.core.api.score.constraint.ConstraintMatch;
import org.optaplanner.core.api.score.constraint.ConstraintMatchTotal;
import org.optaplanner.core.impl.domain.entity.descriptor.EntityDescriptor;
import org.optaplanner.core.impl.domain.solution.descriptor.SolutionDescriptor;
import org.optaplanner.core.impl.domain.variable.descriptor.GenuineVariableDescriptor;
import org.optaplanner.core.impl.domain.variable.descriptor.VariableDescriptor;
import org.optaplanner.core.impl.domain.variable.inverserelation.SingletonInverseVariableDemand;
import org.optaplanner.core.impl.domain.variable.inverserelation.SingletonInverseVariableSupply;
import org.optaplanner.core.impl.domain.variable.listener.VariableListenerSupport;
import org.optaplanner.core.impl.domain.variable.supply.SupplyManager;
import org.optaplanner.core.impl.score.definition.ScoreDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract superclass for {@link ScoreDirector}.
 * <p>
 * Implementation note: Extending classes should follow these guidelines:
 * <ul>
 * <li>before* method: last statement should be a call to the super method</li>
 * <li>after* method: first statement should be a call to the super method</li>
 * </ul>
 * @see ScoreDirector
 */
public abstract class AbstractScoreDirector<F extends AbstractScoreDirectorFactory>
        implements InnerScoreDirector, Cloneable {

    protected final transient Logger logger = LoggerFactory.getLogger(getClass());

    protected final F scoreDirectorFactory;

    protected final boolean constraintMatchEnabledPreference;

    protected VariableListenerSupport variableListenerSupport;

    protected Solution workingSolution;
    protected long workingEntityListRevision = 0L;

    protected boolean allChangesWillBeUndoneBeforeStepEnds = false;

    protected long calculateCount = 0L;

    protected AbstractScoreDirector(F scoreDirectorFactory, boolean constraintMatchEnabledPreference) {
        this.scoreDirectorFactory = scoreDirectorFactory;
        this.constraintMatchEnabledPreference = constraintMatchEnabledPreference;
        variableListenerSupport = new VariableListenerSupport(this);
        variableListenerSupport.linkVariableListeners();
    }

    public F getScoreDirectorFactory() {
        return scoreDirectorFactory;
    }

    public SolutionDescriptor getSolutionDescriptor() {
        return scoreDirectorFactory.getSolutionDescriptor();
    }

    public ScoreDefinition getScoreDefinition() {
        return scoreDirectorFactory.getScoreDefinition();
    }

    public Solution getWorkingSolution() {
        return workingSolution;
    }

    public long getWorkingEntityListRevision() {
        return workingEntityListRevision;
    }

    public boolean isAllChangesWillBeUndoneBeforeStepEnds() {
        return allChangesWillBeUndoneBeforeStepEnds;
    }

    public void setAllChangesWillBeUndoneBeforeStepEnds(boolean allChangesWillBeUndoneBeforeStepEnds) {
        this.allChangesWillBeUndoneBeforeStepEnds = allChangesWillBeUndoneBeforeStepEnds;
    }

    public long getCalculateCount() {
        return calculateCount;
    }

    public void resetCalculateCount() {
        this.calculateCount = 0L;
    }

    public SupplyManager getSupplyManager() {
        return variableListenerSupport;
    }

    // ************************************************************************
    // Complex methods
    // ************************************************************************

    public void setWorkingSolution(Solution workingSolution) {
        this.workingSolution = workingSolution;
        variableListenerSupport.resetWorkingSolution(this);
        setWorkingEntityListDirty();
    }

    public boolean isWorkingEntityListDirty(long expectedWorkingEntityListRevision) {
        return workingEntityListRevision != expectedWorkingEntityListRevision;
    }

    protected void setWorkingEntityListDirty() {
        workingEntityListRevision++;
    }

    public Solution cloneWorkingSolution() {
        return cloneSolution(workingSolution);
    }

    public Solution cloneSolution(Solution originalSolution) {
        SolutionDescriptor solutionDescriptor = getSolutionDescriptor();
        Solution cloneSolution = solutionDescriptor.getSolutionCloner().cloneSolution(originalSolution);
        if (scoreDirectorFactory.isAssertClonedSolution()) {
            if (!ObjectUtils.equals(originalSolution.getScore(), cloneSolution.getScore())) {
                throw new IllegalStateException("Cloning corruption: "
                        + "the original's score (" + originalSolution.getScore()
                        + ") is different from the clone's score (" + cloneSolution.getScore() + ").\n"
                        + "Check the " + SolutionCloner.class.getSimpleName() + ".");
            }
            List<Object> originalEntityList = solutionDescriptor.getEntityList(originalSolution);
            Map<Object, Object> originalEntityMap = new IdentityHashMap<Object, Object>(originalEntityList.size());
            for (Object originalEntity : originalEntityList) {
                originalEntityMap.put(originalEntity, null);
            }
            for (Object cloneEntity : solutionDescriptor.getEntityList(cloneSolution)) {
                if (originalEntityMap.containsKey(cloneEntity)) {
                    throw new IllegalStateException("Cloning corruption: "
                            + "the same entity (" + cloneEntity
                            + ") is present in both the original and the clone.\n"
                            + "So when a planning variable in the original solution changes, "
                            + "the cloned solution will change too.\n"
                            + "Check the " + SolutionCloner.class.getSimpleName() + ".");
                }
            }
        }
        return cloneSolution;
    }

    public int getWorkingEntityCount() {
        return getSolutionDescriptor().getEntityCount(workingSolution);
    }

    public List<Object> getWorkingEntityList() {
        return getSolutionDescriptor().getEntityList(workingSolution);
    }

    public int getWorkingValueCount() {
        return getSolutionDescriptor().getValueCount(workingSolution);
    }

    public int countWorkingSolutionUninitializedVariables() {
        return getSolutionDescriptor().countUninitializedVariables(workingSolution);
    }

    protected void setCalculatedScore(Score score) {
        workingSolution.setScore(score);
        calculateCount++;
    }

    public AbstractScoreDirector clone() {
        // Breaks incremental score calculation.
        // Subclasses should overwrite this method to avoid breaking it if possible.
        AbstractScoreDirector clone = (AbstractScoreDirector) scoreDirectorFactory.buildScoreDirector(
                constraintMatchEnabledPreference);
        clone.setWorkingSolution(cloneWorkingSolution());
        return clone;
    }

    public void dispose() {
        variableListenerSupport.clearWorkingSolution(this);
    }

    // TODO remove this method and use the SingletonInverseVariableSupply directly
    public Object getTrailingEntity(GenuineVariableDescriptor chainedVariableDescriptor, Object planningValue) {
        SingletonInverseVariableSupply supply = variableListenerSupport.demand(new SingletonInverseVariableDemand(chainedVariableDescriptor));
        return supply.getInverseSingleton(planningValue);
    }

    // ************************************************************************
    // Entity/variable add/change/remove methods
    // ************************************************************************

    public final void beforeEntityAdded(Object entity) {
        beforeEntityAdded(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    public final void afterEntityAdded(Object entity) {
        afterEntityAdded(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    public final void beforeVariableChanged(Object entity, String variableName) {
        VariableDescriptor variableDescriptor = getSolutionDescriptor()
                .findVariableDescriptorOrFail(entity, variableName);
        beforeVariableChanged(variableDescriptor, entity);
    }

    public final void afterVariableChanged(Object entity, String variableName) {
        VariableDescriptor variableDescriptor = getSolutionDescriptor()
                .findVariableDescriptorOrFail(entity, variableName);
        afterVariableChanged(variableDescriptor, entity);
    }

    public final void beforeEntityRemoved(Object entity) {
        beforeEntityRemoved(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    public final void afterEntityRemoved(Object entity) {
        afterEntityRemoved(getSolutionDescriptor().findEntityDescriptorOrFail(entity.getClass()), entity);
    }

    public void beforeEntityAdded(EntityDescriptor entityDescriptor, Object entity) {
        variableListenerSupport.beforeEntityAdded(this, entityDescriptor, entity);
    }

    public void afterEntityAdded(EntityDescriptor entityDescriptor, Object entity) {
        variableListenerSupport.afterEntityAdded(this, entityDescriptor, entity);
        if (!allChangesWillBeUndoneBeforeStepEnds) {
            setWorkingEntityListDirty();
        }
    }

    public void beforeVariableChanged(VariableDescriptor variableDescriptor, Object entity) {
        variableListenerSupport.beforeVariableChanged(this, variableDescriptor, entity);
    }

    public void afterVariableChanged(VariableDescriptor variableDescriptor, Object entity) {
        variableListenerSupport.afterVariableChanged(this, variableDescriptor, entity);
    }

    public void changeVariableFacade(VariableDescriptor variableDescriptor, Object entity, Object newValue) {
        beforeVariableChanged(variableDescriptor, entity);
        variableDescriptor.setValue(entity, newValue);
        afterVariableChanged(variableDescriptor, entity);
    }

    public void beforeEntityRemoved(EntityDescriptor entityDescriptor, Object entity) {
        variableListenerSupport.beforeEntityRemoved(this, entityDescriptor, entity);
    }

    public void afterEntityRemoved(EntityDescriptor entityDescriptor, Object entity) {
        variableListenerSupport.afterEntityRemoved(this, entityDescriptor, entity);
        if (!allChangesWillBeUndoneBeforeStepEnds) {
            setWorkingEntityListDirty();
        }
    }

    // ************************************************************************
    // Problem fact add/change/remove methods
    // ************************************************************************

    public void beforeProblemFactAdded(Object problemFact) {
        // Do nothing
    }

    public void afterProblemFactAdded(Object problemFact) {
        variableListenerSupport.resetWorkingSolution(this); // TODO do not nuke it
    }

    public void beforeProblemFactChanged(Object problemFact) {
        // Do nothing
    }

    public void afterProblemFactChanged(Object problemFact) {
        variableListenerSupport.resetWorkingSolution(this); // TODO do not nuke it
    }

    public void beforeProblemFactRemoved(Object problemFact) {
        // Do nothing
    }

    public void afterProblemFactRemoved(Object problemFact) {
        variableListenerSupport.resetWorkingSolution(this); // TODO do not nuke it
    }

    // ************************************************************************
    // Assert methods
    // ************************************************************************

    public void assertExpectedWorkingScore(Score expectedWorkingScore, Object completedAction) {
        Score workingScore = calculateScore();
        if (!expectedWorkingScore.equals(workingScore)) {
            throw new IllegalStateException(
                    "Score corruption: the expectedWorkingScore (" + expectedWorkingScore
                    + ") is not the workingScore  (" + workingScore
                    + ") after completedAction (" + completedAction + ").");
        }
    }

    public void assertVariableListenersDoNotAffectWorkingScore(Score expectedWorkingScore) {
        variableListenerSupport.triggerAllVariableListeners(this);
        Score workingScore = calculateScore();
        if (!expectedWorkingScore.equals(workingScore)) {
            throw new IllegalStateException(
                    "VariableListener corruption: the expectedWorkingScore (" + expectedWorkingScore
                            + ") is not the workingScore  (" + workingScore
                            + ") after all VariableListeners were triggered without changes to the genuine variables.\n"
                            + "A VariableListener probably changed a shadow variable,"
                            + " despite that the genuine variable didn't change,"
                            + " which means the shadow variable's original value is probably wrong.");
        }
    }

    public void assertWorkingScoreFromScratch(Score workingScore, Object completedAction) {
        InnerScoreDirectorFactory assertionScoreDirectorFactory
                = scoreDirectorFactory.getAssertionScoreDirectorFactory();
        if (assertionScoreDirectorFactory == null) {
            assertionScoreDirectorFactory = scoreDirectorFactory;
        }
        InnerScoreDirector uncorruptedScoreDirector = assertionScoreDirectorFactory.buildScoreDirector(true);
        uncorruptedScoreDirector.setWorkingSolution(workingSolution);
        Score uncorruptedScore = uncorruptedScoreDirector.calculateScore();
        if (!workingScore.equals(uncorruptedScore)) {
            String scoreCorruptionAnalysis = buildScoreCorruptionAnalysis(uncorruptedScoreDirector);
            uncorruptedScoreDirector.dispose();
            throw new IllegalStateException(
                    "Score corruption: the workingScore (" + workingScore + ") is not the uncorruptedScore ("
                    + uncorruptedScore + ") after completedAction (" + completedAction
                    + "):\n" + scoreCorruptionAnalysis);
        } else {
            uncorruptedScoreDirector.dispose();
        }
    }

    /**
     * @param uncorruptedScoreDirector never null
     * @return never null
     */
    protected String buildScoreCorruptionAnalysis(ScoreDirector uncorruptedScoreDirector) {
        if (!isConstraintMatchEnabled() || !uncorruptedScoreDirector.isConstraintMatchEnabled()) {
            return "  Score corruption analysis could not be generated because"
                    + " either corrupted constraintMatchEnabled (" + isConstraintMatchEnabled()
                    + ") or uncorrupted constraintMatchEnabled (" + uncorruptedScoreDirector.isConstraintMatchEnabled()
                    + ") is disabled.\n"
                    + "  Check your score constraints manually.";
        }
        Collection<ConstraintMatchTotal> corruptedConstraintMatchTotals = getConstraintMatchTotals();
        Collection<ConstraintMatchTotal> uncorruptedConstraintMatchTotals
                = uncorruptedScoreDirector.getConstraintMatchTotals();

        Map<List<Object>, ConstraintMatch> corruptedMap = createConstraintMatchMap(corruptedConstraintMatchTotals);
        Map<List<Object>, ConstraintMatch> excessMap = new LinkedHashMap<List<Object>, ConstraintMatch>(
                corruptedMap);
        Map<List<Object>, ConstraintMatch> missingMap = createConstraintMatchMap(uncorruptedConstraintMatchTotals);
        excessMap.keySet().removeAll(missingMap.keySet()); // missingMap == uncorruptedMap
        missingMap.keySet().removeAll(corruptedMap.keySet());

        final int CONSTRAINT_MATCH_DISPLAY_LIMIT = 8;
        StringBuilder analysis = new StringBuilder();
        if (excessMap.isEmpty()) {
            analysis.append("  The corrupted scoreDirector has no ConstraintMatch(s) which are in excess.\n");
        } else {
            analysis.append("  The corrupted scoreDirector has ").append(excessMap.size())
                    .append(" ConstraintMatch(s) which are in excess (and should not be there):\n");
            int count = 0;
            for (ConstraintMatch constraintMatch : excessMap.values()) {
                if (count >= CONSTRAINT_MATCH_DISPLAY_LIMIT) {
                    analysis.append("    ... ").append(excessMap.size() - CONSTRAINT_MATCH_DISPLAY_LIMIT)
                            .append(" more\n");
                    break;
                }
                analysis.append("    ").append(constraintMatch).append("\n");
                count++;
            }
        }
        if (missingMap.isEmpty()) {
            analysis.append("  The corrupted scoreDirector has no ConstraintMatch(s) which are missing.\n");
        } else {
            analysis.append("  The corrupted scoreDirector has ").append(missingMap.size())
                    .append(" ConstraintMatch(s) which are missing:\n");
            int count = 0;
            for (ConstraintMatch constraintMatch : missingMap.values()) {
                if (count >= CONSTRAINT_MATCH_DISPLAY_LIMIT) {
                    analysis.append("    ... ").append(missingMap.size() - CONSTRAINT_MATCH_DISPLAY_LIMIT)
                            .append(" more\n");
                    break;
                }
                analysis.append("    ").append(constraintMatch).append("\n");
                count++;
            }
        }
        if (excessMap.isEmpty() && missingMap.isEmpty()) {
            analysis.append("  The corrupted scoreDirector has no ConstraintMatch(s) in excess or missing."
                    + " That could be a bug in this class (").append(getClass()).append(").\n");
        }
        analysis.append("  Check your score constraints.");
        return analysis.toString();
    }

    private Map<List<Object>, ConstraintMatch> createConstraintMatchMap(
            Collection<ConstraintMatchTotal> constraintMatchTotals) {
        Map<List<Object>, ConstraintMatch> constraintMatchMap
                = new LinkedHashMap<List<Object>, ConstraintMatch>(constraintMatchTotals.size() * 16);
        for (ConstraintMatchTotal constraintMatchTotal : constraintMatchTotals) {
            for (ConstraintMatch constraintMatch : constraintMatchTotal.getConstraintMatchSet()) {
                ConstraintMatch previousConstraintMatch = constraintMatchMap.put(
                        Arrays.<Object>asList(
                                constraintMatchTotal.getConstraintPackage(),
                                constraintMatchTotal.getConstraintName(),
                                constraintMatchTotal.getScoreLevel(),
                                constraintMatch.getJustificationList(),
                                constraintMatch.getWeightAsNumber()),
                        constraintMatch);
                if (previousConstraintMatch != null) {
                    throw new IllegalStateException("Score corruption because the constraintMatch (" + constraintMatch
                            + ") was added twice for constraintMatchTotal (" + constraintMatchTotal
                            + ") without removal.");
                }
            }
        }
        return constraintMatchMap;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + calculateCount + ")";
    }

}

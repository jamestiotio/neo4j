/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
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
package org.neo4j.cypher.internal.compiler.planner.logical

import org.neo4j.cypher.internal.ast.semantics.SemanticFeature
import org.neo4j.cypher.internal.compiler.ast.convert.plannerQuery.CreateIrExpressions
import org.neo4j.cypher.internal.compiler.phases.CompilationContains
import org.neo4j.cypher.internal.compiler.phases.LogicalPlanState
import org.neo4j.cypher.internal.compiler.phases.PlannerContext
import org.neo4j.cypher.internal.expressions.Ands
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.FunctionInvocation
import org.neo4j.cypher.internal.expressions.HasLabels
import org.neo4j.cypher.internal.expressions.LabelExpression
import org.neo4j.cypher.internal.expressions.LabelExpression.Leaf
import org.neo4j.cypher.internal.expressions.LabelExpression.disjoinRelTypesToLabelExpression
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.Not
import org.neo4j.cypher.internal.expressions.Ors
import org.neo4j.cypher.internal.expressions.PatternExpression
import org.neo4j.cypher.internal.expressions.RelationshipChain
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.expressions.RelationshipsPattern
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.expressions.functions.Exists
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase
import org.neo4j.cypher.internal.frontend.phases.CompilationPhaseTracer.CompilationPhase.LOGICAL_PLANNING
import org.neo4j.cypher.internal.frontend.phases.Phase
import org.neo4j.cypher.internal.frontend.phases.Transformer
import org.neo4j.cypher.internal.frontend.phases.factories.PlanPipelineTransformerFactory
import org.neo4j.cypher.internal.ir.AggregatingQueryProjection
import org.neo4j.cypher.internal.ir.DistinctQueryProjection
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.Predicate
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.QueryProjection
import org.neo4j.cypher.internal.ir.RegularSinglePlannerQuery
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.ir.UnionQuery
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.Rewritable.RewritableAny
import org.neo4j.cypher.internal.util.Rewriter
import org.neo4j.cypher.internal.util.StepSequencer
import org.neo4j.cypher.internal.util.topDown

import scala.annotation.tailrec
import scala.collection.mutable
import scala.collection.mutable.ListBuffer

case object UnnecessaryOptionalMatchesRemoved extends StepSequencer.Condition

/**
 * Remove optional match when possible.
 */
case object OptionalMatchRemover extends PlannerQueryRewriter with StepSequencer.Step
    with PlanPipelineTransformerFactory {

  override def instance(from: LogicalPlanState, context: PlannerContext): Rewriter = {
    val optionalMatchRewriter = topDown(
      rewriter = Rewriter.lift {
        case RegularSinglePlannerQuery(
            graph,
            interestingOrder,
            proj @ AggregatingQueryProjection(distinctExpressions, aggregations, _, _),
            tail,
            queryInput
          ) if validAggregations(aggregations) =>
          val projectionDeps: Iterable[LogicalVariable] =
            (distinctExpressions.values ++ aggregations.values).flatMap(_.dependencies)
          rewrite(projectionDeps, graph, interestingOrder, proj, tail, queryInput, from.anonymousVariableNameGenerator)

        case RegularSinglePlannerQuery(
            graph,
            interestingOrder,
            proj @ DistinctQueryProjection(distinctExpressions, _, _),
            tail,
            queryInput
          ) =>
          val projectionDeps: Iterable[LogicalVariable] = distinctExpressions.values.flatMap(_.dependencies)
          rewrite(projectionDeps, graph, interestingOrder, proj, tail, queryInput, from.anonymousVariableNameGenerator)

        // Remove OPTIONAL MATCH if preceding MATCH solves the exact same query graph e.g.:
        // OPTIONAL MATCH (n)
        // MATCH (n)           -> QueryGraph {Nodes: ['n'], Arguments: ['n']}
        // OPTIONAL MATCH (n)  -> QueryGraph {Nodes: ['n'], Arguments: ['n']}
        case RegularSinglePlannerQuery(qg: QueryGraph, io, h, t, qi)
          if qg.optionalMatches.exists(om => qg.connectedComponents.contains(om)) =>
          val newQg = qg.copy(optionalMatches = qg.optionalMatches.filterNot(om => qg.connectedComponents.contains(om)))
          RegularSinglePlannerQuery(queryGraph = newQg, io, h, t, qi)
      },
      cancellation = context.cancellationChecker
    )
    // The optionalMatchRewriter introduces PatternExpressions that should already have been
    // rewritten away at this point, so we have to invoke CreateIrExpressions again.
    optionalMatchRewriter andThen CreateIrExpressions(from.anonymousVariableNameGenerator)
  }

  private def rewrite(
    projectionDeps: Iterable[LogicalVariable],
    graph: QueryGraph,
    interestingOrder: InterestingOrder,
    proj: QueryProjection,
    tail: Option[SinglePlannerQuery],
    queryInput: Option[Seq[String]],
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator
  ): RegularSinglePlannerQuery = {
    val updateDeps = graph.mutatingPatterns.flatMap(_.dependencies)
    val dependencies: Set[String] = projectionDeps.map(_.name).toSet ++ updateDeps

    val optionalMatches = graph.optionalMatches.flatMapWithTail {
      (original: QueryGraph, tail: Seq[QueryGraph]) =>
        // The dependencies on an optional match are:
        val allDeps =
          // dependencies from optional matches listed later in the query
          tail.flatMap(g => g.argumentIds ++ g.selections.variableDependencies).toSet ++
            // any dependencies from the next horizon
            dependencies --
            // But we don't need to solve variables already present by the non-optional part of the QG
            graph.idsWithoutOptionalMatchesOrUpdates

        val mustInclude = allDeps -- original.argumentIds
        val mustKeep = smallestGraphIncluding(original, mustInclude)

        if (mustKeep.isEmpty)
          // We did not find anything in this OPTIONAL MATCH. Since there are no variable deps from this clause,
          // and it can't change cardinality, it's safe to ignore it
          None
        else {
          val ExtractionResult(predicatesForPatternExpression, predicatesToKeep, elementsToKeep) = {
            // We must keep all variables calculated in the previous step plus all arguments ids.
            val elementsToKeep0 = mustInclude ++ original.argumentIds

            // We must keep all variables connecting the so far elementsToKeep
            val elementsToKeep1 = smallestGraphIncluding(original, elementsToKeep0)

            // Now, if two relationships, that are currently not kept, overlap, unless the overlap is on an elementToKeep, we also need to keep the relationships.
            // Here, we keep the adjacent nodes, and in the next step we add the relationship itself
            val elementsToKeep2 =
              elementsToKeep1 ++ overlappingRels(original.patternRelationships, elementsToKeep1).flatMap(r =>
                Seq(r.left, r.right)
              )

            // We must (again) keep all variables connecting the so far elementsToKeep
            val elementsToKeep3 = smallestGraphIncluding(original, elementsToKeep2)

            extractElementsAndPatterns(original, elementsToKeep3)
          }

          val (patternsToKeep, patternsToFilter) = original.patternRelationships.partition(r => elementsToKeep(r.name))
          val patternNodes = original.patternNodes.filter(elementsToKeep.apply)

          val patternPredicates = patternsToFilter.map(toAst(
            elementsToKeep,
            predicatesForPatternExpression,
            _,
            anonymousVariableNameGenerator
          ))

          val newOptionalGraph = original.withPatternRelationships(patternsToKeep).withPatternNodes(
            patternNodes
          ).withSelections(Selections.from(predicatesToKeep) ++ patternPredicates)

          Some(newOptionalGraph)
        }
    }

    val matches = graph.withOptionalMatches(optionalMatches)
    RegularSinglePlannerQuery(matches, interestingOrder, horizon = proj, tail = tail, queryInput = queryInput)

  }

  /**
   * Given a set of relationships of the original query graph and a set of so-far elements to keep, return all relationships that must be kept.
   * These are all so-far kept relationships plus all other relationships that have an overlap, unless the overlap is on an elementToKeep.
   */
  private def overlappingRels(rels: Set[PatternRelationship], elementsToKeep: Set[String]): Set[PatternRelationship] = {
    val (keptRels, notYetKeptRels) = rels.partition(r => elementsToKeep(r.name))
    val alsoKeptRels = notYetKeptRels.filter { rel =>
      val relIds = rel.coveredIds -- elementsToKeep
      (notYetKeptRels - rel).exists { rel2 =>
        val rel2Ids = rel2.coveredIds -- elementsToKeep
        relIds.intersect(rel2Ids).nonEmpty
      }
    }
    keptRels ++ alsoKeptRels
  }

  /**
   * @param predicatesForPatternExpression predicates that can get moved into PatternExpressions.
   *                                       These are currently only `HasLabels`.
   *                                       This is a map from node variable name to the label names.
   * @param predicatesToKeep               predicate expressions that cannot be moved into patternExpressions
   * @param elementsToKeep                 node and relationship variables that cannot be moved into patternExpressions
   */
  case class ExtractionResult(
    predicatesForPatternExpression: Map[String, LabelExpression],
    predicatesToKeep: Set[Expression],
    elementsToKeep: Set[String]
  )

  @tailrec
  private def extractElementsAndPatterns(original: QueryGraph, elementsToKeepInitial: Set[String]): ExtractionResult = {
    val PartitionedPredicates(predicatesForPatterns, predicatesToKeep) =
      partitionPredicates(original.selections.predicates, elementsToKeepInitial)

    val variablesNeededForPredicates = predicatesToKeep.flatMap(expression => expression.dependencies.map(_.name))
    val elementsToKeep = smallestGraphIncluding(original, elementsToKeepInitial ++ variablesNeededForPredicates)

    if (elementsToKeep.equals(elementsToKeepInitial)) {
      ExtractionResult(predicatesForPatterns, predicatesToKeep, elementsToKeep)
    } else {
      extractElementsAndPatterns(original, elementsToKeep)
    }
  }

  /**
   * @param predicatesForPatternExpression predicates that can get moved into PatternExpressions.
   *                                       These are currently only `HasLabels`.
   *                                       This is a map from node variable name to the label names.
   * @param predicatesToKeep               predicate expressions that cannot be moved into patternExpressions
   */
  case class PartitionedPredicates(
    predicatesForPatternExpression: Map[String, LabelExpression],
    predicatesToKeep: Set[Expression]
  )

  /**
   * This is inverting what `LabelExpressionNormalizer` does.
   */
  def recreateLabelExpression(expression: Expression, variable: String): Option[LabelExpression] = expression match {
    case HasLabels(Variable(varName), labels) if variable == varName =>
      require(labels.size == 1) // We know there is only a single label here because AST rewriting
      Some(Leaf(labels.head))

    case ands @ Ands(predicates) =>
      recreateComposingLabelExpression(
        variable,
        predicates.toSeq,
        (le1, le2) => LabelExpression.Conjunctions(Seq(le1, le2))(ands.position)
      )

    case ors @ Ors(predicates) =>
      recreateComposingLabelExpression(
        variable,
        predicates.toSeq,
        (le1, le2) => LabelExpression.Disjunctions(Seq(le1, le2))(ors.position)
      )

    case not @ Not(predicate) =>
      recreateLabelExpression(predicate, variable).map(LabelExpression.Negation(_)(not.position))

    case _ => None
  }

  private def recreateComposingLabelExpression(
    variable: String,
    predicates: Seq[Expression],
    combiner: (LabelExpression, LabelExpression) => LabelExpression
  ): Option[LabelExpression] = {
    val labelExpressions = predicates.map(recreateLabelExpression(_, variable))
    if (labelExpressions.exists(_.isEmpty)) {
      None
    } else {
      labelExpressions.map(_.get).foldLeft[Option[LabelExpression]](None) {
        case (None, rhs)      => Some(rhs)
        case (Some(lhs), rhs) => Some(combiner(lhs, rhs))
      }
    }
  }

  /**
   * This method extracts predicates that need to be part of pattern expressions
   *
   * @param predicates All the original predicates of the QueryGraph
   * @param kept       Set of all variables that should not be moved to pattern expressions
   * @return Map of label predicates to move to pattern expressions,
   *         and the set of remaining predicates
   */
  private def partitionPredicates(predicates: Set[Predicate], kept: Set[String]): PartitionedPredicates = {

    val predicatesForPatternExpression = mutable.Map.empty[String, LabelExpression]
    val predicatesToKeep = mutable.Set.empty[Expression]

    def addLabel(idName: String, newLabelExpression: LabelExpression): mutable.Map[String, LabelExpression] = {
      val current = predicatesForPatternExpression.get(idName)
      val labelExpression = current match {
        case None =>
          newLabelExpression
        case Some(labelExpression: LabelExpression) =>
          LabelExpression.Conjunctions(Seq(labelExpression, newLabelExpression))(InputPosition.NONE)
      }
      predicatesForPatternExpression += idName -> labelExpression
    }

    predicates.foreach {
      case Predicate(deps, predicates) if deps.size == 1 && !kept(deps.head) =>
        val labelExpression = recreateLabelExpression(predicates, deps.head)
        if (labelExpression.isDefined) {
          addLabel(deps.head, labelExpression.get)
        } else {
          predicatesToKeep += predicates
        }
        ()

      case Predicate(_, expr) =>
        predicatesToKeep += expr
        ()
    }

    PartitionedPredicates(predicatesForPatternExpression.toMap, predicatesToKeep.toSet)
  }

  private def validAggregations(aggregations: Map[String, Expression]) =
    aggregations.isEmpty ||
      aggregations.values.forall {
        case func: FunctionInvocation => func.distinct
        case _                        => false
      }

  private def toAst(
    elementsToKeep: Set[String],
    predicates: Map[String, LabelExpression],
    pattern: PatternRelationship,
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator
  ): Expression = {
    def createVariable(name: String): Variable =
      if (!elementsToKeep(name)) {
        Variable(anonymousVariableNameGenerator.nextName)(InputPosition.NONE)
      } else {
        Variable(name)(InputPosition.NONE)
      }

    def createNode(name: String): NodePattern = {
      val labelExpression = predicates.get(name)
      NodePattern(Some(createVariable(name)), labelExpression, properties = None, predicate = None)(InputPosition.NONE)
    }

    val relName = createVariable(pattern.name)
    val leftNode = createNode(pattern.nodes._1)
    val rightNode = createNode(pattern.nodes._2)
    val relPattern = RelationshipPattern(
      Some(relName),
      disjoinRelTypesToLabelExpression(pattern.types),
      length = None,
      properties = None,
      predicate = None,
      pattern.dir
    )(InputPosition.NONE)
    val chain = RelationshipChain(leftNode, relPattern, rightNode)(InputPosition.NONE)
    val outerScope: Set[LogicalVariable] = elementsToKeep.map(createVariable)
    Exists(PatternExpression(RelationshipsPattern(chain)(InputPosition.NONE))(
      outerScope,
      anonymousVariableNameGenerator.nextName,
      anonymousVariableNameGenerator.nextName
    ))(InputPosition.NONE)
  }

  implicit class FlatMapWithTailable(in: IndexedSeq[QueryGraph]) {

    def flatMapWithTail(f: (QueryGraph, Seq[QueryGraph]) => IterableOnce[QueryGraph]): IndexedSeq[QueryGraph] = {

      @tailrec
      def recurse(
        that: QueryGraph,
        rest: Seq[QueryGraph],
        builder: mutable.Builder[QueryGraph, ListBuffer[QueryGraph]]
      ): Unit = {
        builder ++= f(that, rest)
        if (rest.nonEmpty)
          recurse(rest.head, rest.tail, builder)
      }
      if (in.isEmpty)
        IndexedSeq.empty
      else {
        val builder = ListBuffer.newBuilder[QueryGraph]
        recurse(in.head, in.tail, builder)
        builder.result().toIndexedSeq
      }
    }
  }

  /**
   * Return all variables in the smallest graph that includes all of `mustInclude`.
   */
  def smallestGraphIncluding(qg: QueryGraph, mustInclude: Set[String]): Set[String] = {
    if (mustInclude.size < 2)
      mustInclude intersect qg.allCoveredIds
    else {
      val mustIncludeRels = qg.patternRelationships.filter(r => mustInclude(r.name))
      val mustIncludeNodes =
        mustInclude.intersect(qg.patternNodes) ++ mustIncludeRels.flatMap(r => Seq(r.left, r.right))
      var accumulatedElements = mustIncludeNodes
      for {
        lhs <- mustIncludeNodes
        rhs <- mustIncludeNodes
        if lhs < rhs
      } {
        accumulatedElements ++= findPathBetween(qg, lhs, rhs)
      }
      accumulatedElements ++ mustInclude
    }
  }

  private case class PathSoFar(end: String, alreadyVisited: Set[PatternRelationship])

  private def hasExpandedInto(from: Seq[PathSoFar], into: Seq[PathSoFar]): Seq[Set[String]] =
    for {
      lhs <- from
      rhs <- into
      if rhs.alreadyVisited.exists(p => p.coveredIds.contains(lhs.end))
    } yield {
      (lhs.alreadyVisited ++ rhs.alreadyVisited).flatMap(_.coveredIds)
    }

  private def expand(queryGraph: QueryGraph, from: Seq[PathSoFar]): Seq[PathSoFar] = {
    from.flatMap {
      case PathSoFar(end, alreadyVisited) =>
        queryGraph.patternRelationships.collect {
          case pr if !alreadyVisited(pr) && pr.coveredIds(end) =>
            PathSoFar(pr.otherSide(end), alreadyVisited + pr)
        }
    }
  }

  private def findPathBetween(qg: QueryGraph, startFromL: String, startFromR: String): Set[String] = {
    var l = Seq(PathSoFar(startFromL, Set.empty))
    var r = Seq(PathSoFar(startFromR, Set.empty))
    (0 to qg.patternRelationships.size) foreach { i =>
      if (i % 2 == 0) {
        l = expand(qg, l)
        val matches = hasExpandedInto(l, r)
        if (matches.nonEmpty)
          return matches.minBy(_.size)
      } else {
        r = expand(qg, r)
        val matches = hasExpandedInto(r, l)
        if (matches.nonEmpty)
          return matches.minBy(_.size)
      }
    }

    // Did not find any path. Let's do the safe thing and return everything
    qg.patternRelationships.flatMap(_.coveredIds)
  }

  override def preConditions: Set[StepSequencer.Condition] = Set(
    // This works on the IR
    CompilationContains[UnionQuery]
  )

  override def postConditions: Set[StepSequencer.Condition] = Set(UnnecessaryOptionalMatchesRemoved)

  override def invalidatedConditions: Set[StepSequencer.Condition] = Set.empty

  override def getTransformer(
    pushdownPropertyReads: Boolean,
    semanticFeatures: Seq[SemanticFeature]
  ): Transformer[PlannerContext, LogicalPlanState, LogicalPlanState] = this
}

trait PlannerQueryRewriter extends Phase[PlannerContext, LogicalPlanState, LogicalPlanState] {
  self: Product =>

  override def phase: CompilationPhase = LOGICAL_PLANNING

  def instance(from: LogicalPlanState, context: PlannerContext): Rewriter

  override def process(from: LogicalPlanState, context: PlannerContext): LogicalPlanState = {
    val query = from.query
    val rewritten = query.endoRewrite(instance(from, context))
    from.copy(maybeQuery = Some(rewritten))
  }
}

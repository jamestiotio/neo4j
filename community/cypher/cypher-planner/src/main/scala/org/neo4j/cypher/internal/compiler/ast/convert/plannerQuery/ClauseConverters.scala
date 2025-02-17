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
package org.neo4j.cypher.internal.compiler.ast.convert.plannerQuery

import org.neo4j.cypher.internal.ast.AliasedReturnItem
import org.neo4j.cypher.internal.ast.AscSortItem
import org.neo4j.cypher.internal.ast.Clause
import org.neo4j.cypher.internal.ast.CommandClause
import org.neo4j.cypher.internal.ast.Create
import org.neo4j.cypher.internal.ast.Delete
import org.neo4j.cypher.internal.ast.DescSortItem
import org.neo4j.cypher.internal.ast.Foreach
import org.neo4j.cypher.internal.ast.InputDataStream
import org.neo4j.cypher.internal.ast.LoadCSV
import org.neo4j.cypher.internal.ast.Match
import org.neo4j.cypher.internal.ast.Merge
import org.neo4j.cypher.internal.ast.OnCreate
import org.neo4j.cypher.internal.ast.OnMatch
import org.neo4j.cypher.internal.ast.OrderBy
import org.neo4j.cypher.internal.ast.Remove
import org.neo4j.cypher.internal.ast.RemoveLabelItem
import org.neo4j.cypher.internal.ast.RemovePropertyItem
import org.neo4j.cypher.internal.ast.Return
import org.neo4j.cypher.internal.ast.ReturnItem
import org.neo4j.cypher.internal.ast.ReturnItems
import org.neo4j.cypher.internal.ast.SetClause
import org.neo4j.cypher.internal.ast.SetExactPropertiesFromMapItem
import org.neo4j.cypher.internal.ast.SetIncludingPropertiesFromMapItem
import org.neo4j.cypher.internal.ast.SetItem
import org.neo4j.cypher.internal.ast.SetLabelItem
import org.neo4j.cypher.internal.ast.SetPropertyItem
import org.neo4j.cypher.internal.ast.SetPropertyItems
import org.neo4j.cypher.internal.ast.SortItem
import org.neo4j.cypher.internal.ast.SubqueryCall
import org.neo4j.cypher.internal.ast.UnresolvedCall
import org.neo4j.cypher.internal.ast.Unwind
import org.neo4j.cypher.internal.ast.UseGraph
import org.neo4j.cypher.internal.ast.Where
import org.neo4j.cypher.internal.ast.With
import org.neo4j.cypher.internal.ast.Yield
import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.compiler.helpers.AggregationHelper
import org.neo4j.cypher.internal.compiler.planner.ProcedureCallProjection
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.HasLabels
import org.neo4j.cypher.internal.expressions.In
import org.neo4j.cypher.internal.expressions.IsAggregate
import org.neo4j.cypher.internal.expressions.LabelName
import org.neo4j.cypher.internal.expressions.ListLiteral
import org.neo4j.cypher.internal.expressions.LogicalProperty
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.MapExpression
import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.Null
import org.neo4j.cypher.internal.expressions.PathPatternPart
import org.neo4j.cypher.internal.expressions.PatternElement
import org.neo4j.cypher.internal.expressions.PatternElement.boundaryNodes
import org.neo4j.cypher.internal.expressions.PatternPart.SelectiveSelector
import org.neo4j.cypher.internal.expressions.PatternPartWithSelector
import org.neo4j.cypher.internal.expressions.Property
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.RelTypeName
import org.neo4j.cypher.internal.expressions.RelationshipChain
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.ir.AggregatingQueryProjection
import org.neo4j.cypher.internal.ir.CommandProjection
import org.neo4j.cypher.internal.ir.CreateCommand
import org.neo4j.cypher.internal.ir.CreateNode
import org.neo4j.cypher.internal.ir.CreatePattern
import org.neo4j.cypher.internal.ir.CreateRelationship
import org.neo4j.cypher.internal.ir.DeleteExpression
import org.neo4j.cypher.internal.ir.DistinctQueryProjection
import org.neo4j.cypher.internal.ir.ForeachPattern
import org.neo4j.cypher.internal.ir.HasHeaders
import org.neo4j.cypher.internal.ir.LoadCSVProjection
import org.neo4j.cypher.internal.ir.MergeNodePattern
import org.neo4j.cypher.internal.ir.MergeRelationshipPattern
import org.neo4j.cypher.internal.ir.NoHeaders
import org.neo4j.cypher.internal.ir.PassthroughAllHorizon
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.QueryGraph
import org.neo4j.cypher.internal.ir.QueryHorizon
import org.neo4j.cypher.internal.ir.QueryPagination
import org.neo4j.cypher.internal.ir.QueryProjection
import org.neo4j.cypher.internal.ir.RegularQueryProjection
import org.neo4j.cypher.internal.ir.RegularSinglePlannerQuery
import org.neo4j.cypher.internal.ir.RemoveLabelPattern
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.SetLabelPattern
import org.neo4j.cypher.internal.ir.SetMutatingPattern
import org.neo4j.cypher.internal.ir.SetNodePropertiesFromMapPattern
import org.neo4j.cypher.internal.ir.SetNodePropertiesPattern
import org.neo4j.cypher.internal.ir.SetNodePropertyPattern
import org.neo4j.cypher.internal.ir.SetPropertiesFromMapPattern
import org.neo4j.cypher.internal.ir.SetPropertiesPattern
import org.neo4j.cypher.internal.ir.SetPropertyPattern
import org.neo4j.cypher.internal.ir.SetRelationshipPropertiesFromMapPattern
import org.neo4j.cypher.internal.ir.SetRelationshipPropertiesPattern
import org.neo4j.cypher.internal.ir.SetRelationshipPropertyPattern
import org.neo4j.cypher.internal.ir.SimplePatternLength
import org.neo4j.cypher.internal.ir.SinglePlannerQuery
import org.neo4j.cypher.internal.ir.UnwindProjection
import org.neo4j.cypher.internal.ir.converters.PatternConverters
import org.neo4j.cypher.internal.ir.helpers.ExpressionConverters.PredicateConverter
import org.neo4j.cypher.internal.ir.ordering.ColumnOrder
import org.neo4j.cypher.internal.ir.ordering.ColumnOrder.Asc
import org.neo4j.cypher.internal.ir.ordering.ColumnOrder.Desc
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.ir.ordering.InterestingOrderCandidate
import org.neo4j.cypher.internal.ir.ordering.RequiredOrderCandidate
import org.neo4j.cypher.internal.label_expressions.LabelExpression
import org.neo4j.cypher.internal.label_expressions.LabelExpression.ColonConjunction
import org.neo4j.cypher.internal.label_expressions.LabelExpression.Leaf
import org.neo4j.cypher.internal.logical.plans.ResolvedCall
import org.neo4j.cypher.internal.util.AnonymousVariableNameGenerator
import org.neo4j.cypher.internal.util.CancellationChecker
import org.neo4j.cypher.internal.util.Foldable.SkipChildren
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTRelationship
import org.neo4j.exceptions.InternalException
import org.neo4j.exceptions.SyntaxException

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

object ClauseConverters {

  /**
   * Adds a clause to a PlannerQueryBuilder
   *
   * @param acc        the PlannerQueryBuilder
   * @param clause     the clause to add.
   * @param nextClause the next clause, if there is any
   * @return the updated PlannerQueryBuilder
   */
  def addToLogicalPlanInput(
    acc: PlannerQueryBuilder,
    clause: Clause,
    nextClause: Option[Clause],
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator,
    cancellationChecker: CancellationChecker,
    nonTerminating: Boolean
  ): PlannerQueryBuilder = clause match {
    case c: Return          => addReturnToLogicalPlanInput(acc, c, nonTerminating)
    case c: Match           => addMatchToLogicalPlanInput(acc, c, anonymousVariableNameGenerator)
    case c: With            => addWithToLogicalPlanInput(acc, c, nextClause)
    case c: Unwind          => addUnwindToLogicalPlanInput(acc, c)
    case c: ResolvedCall    => addCallToLogicalPlanInput(acc, c)
    case c: Create          => addCreateToLogicalPlanInput(acc, c)
    case c: SetClause       => addSetClauseToLogicalPlanInput(acc, c)
    case c: Delete          => addDeleteToLogicalPlanInput(acc, c)
    case c: Remove          => addRemoveToLogicalPlanInput(acc, c)
    case c: Merge           => addMergeToLogicalPlanInput(acc, c)
    case c: LoadCSV         => addLoadCSVToLogicalPlanInput(acc, c)
    case c: Foreach         => addForeachToLogicalPlanInput(acc, c, anonymousVariableNameGenerator, cancellationChecker)
    case c: InputDataStream => addInputDataStreamToLogicalPlanInput(acc, c)
    case c: SubqueryCall =>
      addCallSubqueryToLogicalPlanInput(acc, c, anonymousVariableNameGenerator, cancellationChecker)
    case c: CommandClause => addCommandClauseToLogicalPlanInput(acc, c)
    case c: Yield         => addYieldToLogicalPlanInput(acc, c)
    // Graph target is handled in upper layers and is a NOOP down here
    case _: UseGraph => acc

    case x: UnresolvedCall => throw new IllegalArgumentException(s"$x is not expected here")
    case x => throw new InternalException(s"Received an AST-clause that has no representation the QG: $x")
  }

  private def addLoadCSVToLogicalPlanInput(acc: PlannerQueryBuilder, clause: LoadCSV): PlannerQueryBuilder =
    acc.withHorizon(
      LoadCSVProjection(
        variable = clause.variable.name,
        url = clause.urlString,
        format = if (clause.withHeaders) HasHeaders else NoHeaders,
        clause.fieldTerminator
      )
    ).withTail(SinglePlannerQuery.empty)

  private def addInputDataStreamToLogicalPlanInput(
    acc: PlannerQueryBuilder,
    clause: InputDataStream
  ): PlannerQueryBuilder =
    acc.withQueryInput(clause.variables)

  private def asSelections(optWhere: Option[Where]) =
    Selections(optWhere.map(_.expression.asPredicates).getOrElse(Set.empty))

  private def asQueryProjection(
    distinct: Boolean,
    items: Seq[ReturnItem],
    returningQueryProjection: Boolean
  ): QueryProjection = {
    val (aggregatingItems: Seq[ReturnItem], groupingKeys: Seq[ReturnItem]) =
      items.partition(item => IsAggregate(item.expression))

    def turnIntoMap(x: Seq[ReturnItem]) = x.map(e => e.name -> e.expression).toMap

    val projectionMap = turnIntoMap(groupingKeys)
    val aggregationsMap = turnIntoMap(aggregatingItems)

    if (projectionMap.values.exists(containsAggregateOutsideOfAggregatingHorizon))
      throw new InternalException("Grouping keys contains aggregation. AST has not been rewritten?")

    if (aggregationsMap.nonEmpty)
      AggregatingQueryProjection(
        groupingExpressions = projectionMap,
        aggregationExpressions = aggregationsMap,
        isTerminating = returningQueryProjection
      )
    else if (distinct)
      DistinctQueryProjection(groupingExpressions = projectionMap, isTerminating = returningQueryProjection)
    else
      RegularQueryProjection(projections = projectionMap, isTerminating = returningQueryProjection)
  }

  private def addReturnToLogicalPlanInput(
    acc: PlannerQueryBuilder,
    clause: Return,
    nonTerminating: Boolean
  ): PlannerQueryBuilder =
    clause match {
      case Return(distinct, ReturnItems(star, items, _), optOrderBy, skip, limit, _, _) if !star =>
        val queryPagination = QueryPagination().withSkip(skip).withLimit(limit)

        val projection =
          asQueryProjection(distinct, items, returningQueryProjection = !nonTerminating).withPagination(queryPagination)
        val requiredOrder = findRequiredOrder(projection, optOrderBy)

        acc
          .withHorizon(projection)
          .withInterestingOrder(requiredOrder)
          .withPropagatedTailInterestingOrder()
      case _ =>
        throw new InternalException("AST needs to be rewritten before it can be used for planning. Got: " + clause)
    }

  def findRequiredOrder(horizon: QueryHorizon, optOrderBy: Option[OrderBy]): InterestingOrder = {

    val sortItems = if (optOrderBy.isDefined) optOrderBy.get.sortItems else Seq.empty
    val (requiredOrderCandidate, interestingOrderCandidates: Seq[InterestingOrderCandidate]) = horizon match {
      case RegularQueryProjection(projections, _, _, _) =>
        val requiredOrderCandidate = extractColumnOrderFromOrderBy(sortItems, projections)
        (requiredOrderCandidate, Seq.empty)
      case AggregatingQueryProjection(groupingExpressions, aggregationExpressions, _, _, _) =>
        val requiredOrderCandidate = extractColumnOrderFromOrderBy(sortItems, groupingExpressions)
        val interestingCandidates =
          interestingOrderCandidatesForGroupingExpressions(groupingExpressions) ++
            interestingOrderCandidateForMinOrMax(groupingExpressions, aggregationExpressions)
        (requiredOrderCandidate, interestingCandidates)
      case DistinctQueryProjection(groupingExpressions, _, _, _) =>
        val requiredOrderCandidate = extractColumnOrderFromOrderBy(sortItems, groupingExpressions)
        val interestingCandidates = interestingOrderCandidatesForGroupingExpressions(groupingExpressions)

        (requiredOrderCandidate, interestingCandidates)
      case _ => (RequiredOrderCandidate(Seq.empty), Seq.empty)
    }

    InterestingOrder(requiredOrderCandidate, interestingOrderCandidates)
  }

  private def interestingOrderCandidateForMinOrMax(
    groupingExpressions: Map[String, Expression],
    aggregationExpressions: Map[String, Expression]
  ): Option[InterestingOrderCandidate] = {
    if (groupingExpressions.isEmpty && aggregationExpressions.size == 1) {
      // just checked that there is only one key
      val value = aggregationExpressions(aggregationExpressions.keys.head)
      val columns: Seq[ColumnOrder] =
        AggregationHelper.checkMinOrMax(value, e => Seq(Asc(e)), e => Seq(Desc(e)), Seq.empty)
      Some(InterestingOrderCandidate(columns))
    } else {
      None
    }
  }

  private def interestingOrderCandidatesForGroupingExpressions(groupingExpressions: Map[String, Expression])
    : Seq[InterestingOrderCandidate] = {
    val propsAndVars = groupingExpressions.values.collect {
      case e: Property => e
      case v: Variable => v
    }.toSeq

    val orderings = Seq(Asc(_, Map.empty), Desc(_, Map.empty))
    for {
      prop <- propsAndVars
      indexOrder <- orderings
    } yield InterestingOrderCandidate(Seq(indexOrder(prop)))
  }

  private def extractColumnOrderFromOrderBy(
    sortItems: Seq[SortItem],
    projections: Map[String, Expression]
  ): RequiredOrderCandidate = {
    val columns = sortItems.map {
      // RETURN a AS b ORDER BY b.prop
      case AscSortItem(e @ Property(LogicalVariable(varName), _)) =>
        projections.get(varName) match {
          case Some(expression) => Asc(e, Map(varName -> expression))
          case None             => Asc(e)
        }
      case DescSortItem(e @ Property(LogicalVariable(varName), _)) =>
        projections.get(varName) match {
          case Some(expression) => Desc(e, Map(varName -> expression))
          case None             => Desc(e)
        }

      // RETURN n.prop as foo ORDER BY foo
      case AscSortItem(e @ LogicalVariable(name)) =>
        projections.get(name) match {
          case Some(expression) => Asc(e, Map(name -> expression))
          case None             => Asc(e)
        }
      case DescSortItem(e @ LogicalVariable(name)) =>
        projections.get(name) match {
          case Some(expression) => Desc(e, Map(name -> expression))
          case None             => Desc(e)
        }

      //  RETURN n.prop AS foo ORDER BY foo * 2
      //  RETURN n.prop ORDER BY n.prop * 2
      case AscSortItem(expression) =>
        val depNames = expression.dependencies.map(_.name)
        val orderProjections = projections.filter(p => depNames.contains(p._1))
        Asc(expression, orderProjections)
      case DescSortItem(expression) =>
        val depNames = expression.dependencies.map(_.name)
        val orderProjections = projections.filter(p => depNames.contains(p._1))
        Desc(expression, orderProjections)
    }
    RequiredOrderCandidate(columns)
  }

  private def addSetClauseToLogicalPlanInput(acc: PlannerQueryBuilder, clause: SetClause): PlannerQueryBuilder =
    clause.items.foldLeft(acc) {

      case (builder, item) =>
        builder.amendQueryGraph(_.addMutatingPatterns(toSetPattern(acc.semanticTable)(item)))
    }

  private def addCreateToLogicalPlanInput(builder: PlannerQueryBuilder, clause: Create): PlannerQueryBuilder = {
    val commands = new ArrayBuffer[CreateCommand]()

    // We need this locally to avoid creating nodes twice if they occur
    // multiple times in this clause, but haven't occured before
    val seenPatternNodes = mutable.Set[String]()
    // If the variable is both an argument and appears in a CREATE pattern it has to be a node,
    // otherwise SemanticAnalysis would already have failed.
    seenPatternNodes ++= builder.lastQGNodesAndArguments

    clause.pattern.patternParts.foreach {
      // CREATE (n :L1:L2 {prop: 42})
      case PathPatternPart(NodePattern(Some(id), labelExpression, props, None)) =>
        val labels = getLabelNameSet(labelExpression)
        commands += CreateNode(id.name, labels, props)
        seenPatternNodes += id.name
        ()

      // CREATE (n)-[r: R]->(m)
      case PathPatternPart(pattern: RelationshipChain) =>
        allCreatePatternsInOrderAndDeduped(pattern).foreach {
          case CreateNodeCommand(create, _) =>
            if (seenPatternNodes.add(create.idName)) {
              commands += create
            } else if (create.labels.nonEmpty || create.properties.nonEmpty) {
              throw new SyntaxException(
                s"Can't create node `${create.idName}` with labels or properties here. The variable is already declared in this context"
              )
            }
          case CreateRelCommand(create, _) =>
            commands += create
        }
        ()
      case _ => throw new InternalException(s"Received an AST-clause that has no representation the QG: $clause")
    }

    builder.amendQueryGraph(_.addMutatingPatterns(CreatePattern(commands.toSeq)))
  }

  private def getLabelNameSet(labelExpression: Option[LabelExpression]): Set[LabelName] =
    labelExpression.collect {
      case Leaf(labelName: LabelName, _) => Set(labelName)
      case ColonConjunction(lhs, rhs, _) => getLabelNameSet(Some(lhs)) ++ getLabelNameSet(Some(rhs))
    }.getOrElse(Set.empty)

  sealed private trait CreateEntityCommand

  private case class CreateNodeCommand(create: CreateNode, variable: LogicalVariable) extends CreateEntityCommand

  private case class CreateRelCommand(create: CreateRelationship, variable: LogicalVariable) extends CreateEntityCommand

  private def createNodeCommand(pattern: NodePattern): CreateNodeCommand = pattern match {
    case NodePattern(Some(variable), labelExpression, props, None) =>
      CreateNodeCommand(CreateNode(variable.name, getLabelNameSet(labelExpression), props), variable)
    case _ => throw new InternalException("All nodes must be named at this instance")
  }

  private def allCreatePatternsInOrderAndDeduped(chain: RelationshipChain): Seq[CreateEntityCommand] = {
    allCreatePatternsInOrderAndDeduped(chain, Vector.empty, Set.empty)._1
  }

  private def allCreatePatternsInOrderAndDeduped(
    element: PatternElement,
    acc: Vector[CreateEntityCommand],
    seenNodes: Set[String]
  ): (Vector[CreateEntityCommand], Set[String], String) = {
    def addNode(node: NodePattern): Vector[CreateEntityCommand] = {
      // avoid loops such as CREATE (a)-[:R]->(a)
      if (seenNodes.contains(node.variable.get.name)) {
        if (node.labelExpression.nonEmpty || node.properties.nonEmpty) {
          // reused patterns must be pure variable
          throw new SyntaxException(
            s"Can't create node `${node.variable.get.name}` with labels or properties here. The variable is already declared in this context"
          )
        }
        acc
      } else {
        acc :+ createNodeCommand(node)
      }

    }

    element match {
      // CREATE ()
      case np @ NodePattern(Some(node), _, _, _) =>
        (addNode(np), seenNodes + node.name, node.name)

      // CREATE ()->[:R]->()-[:R]->...->()
      case RelationshipChain(
          left,
          RelationshipPattern(Some(relVar), Some(Leaf(relType: RelTypeName, _)), _, properties, _, direction),
          rightNode @ NodePattern(Some(rightVar), _, _, _)
        ) =>
        val (addLeft, seenLeft, leftNode) = allCreatePatternsInOrderAndDeduped(left, acc, seenNodes)
        val (addRight, seenRight, _) = allCreatePatternsInOrderAndDeduped(rightNode, addLeft, seenLeft)

        val newR = CreateRelCommand(
          CreateRelationship(relVar.name, leftNode, relType, rightVar.name, direction, properties),
          relVar
        )
        (addRight :+ newR, seenRight, rightVar.name)

      case x =>
        throw new IllegalArgumentException(s"The pattern element must be a NodePattern or a RelationshipChain. Got: $x")
    }
  }

  private def addDeleteToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Delete): PlannerQueryBuilder = {
    acc.amendQueryGraph(_.addMutatingPatterns(clause.expressions.map(DeleteExpression(_, clause.forced))))
  }

  private def asReturnItems(current: QueryGraph, returnItems: ReturnItems): Seq[ReturnItem] = returnItems match {
    case ReturnItems(star, items, _) if star =>
      QueryProjection.forIds(current.allCoveredIds) ++ items
    case ReturnItems(_, items, _) =>
      items
    case _ =>
      Seq.empty
  }

  private def addMatchToLogicalPlanInput(
    acc: PlannerQueryBuilder,
    clause: Match,
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator
  ): PlannerQueryBuilder = {
    val converter = new PatternConverters(anonymousVariableNameGenerator)
    val pathPatterns = converter.convertPattern(clause.pattern)

    def qppHasDependencyToPreviousClauses: Boolean = {
      val qppDependencies = pathPatterns.allQuantifiedPathPatterns.flatMap(_.dependencies).toSet
      val availableVars = acc.currentlyAvailableVariables
      qppDependencies.intersect(availableVars).nonEmpty
    }

    def hasPatternOverlapOnInteriorVars: Boolean = {
      // MATCH SHORTEST (()--())+ ()-[r]-() (()--())+ MATCH (a)-[r]-(b)
      val previousStrictInteriorVars =
        acc.currentQueryGraph.selectivePathPatterns.flatMap(spp => spp.coveredIds -- spp.boundaryNodesSet)
      val currentPatternVars = clause.pattern.patternParts.flatMap(_.allVariables.map(_.name)).toSet
      val hasReferenceFromThisPatternToInterior = previousStrictInteriorVars.intersect(currentPatternVars).nonEmpty

      // MATCH (a)-[r]-(b) MATCH SHORTEST (()--())+ ()-[r]-() (()--())+
      val previousPatternVars = acc.currentQueryGraph.coveredIdsForPatterns
      val currentStrictInteriorVars = clause.pattern.patternParts.view.collect {
        case spp @ PatternPartWithSelector(_: SelectiveSelector, _) =>
          (spp.allVariables -- boundaryNodes(spp.element)).map(_.name)
      }.flatten.toSet
      val hasInteriorReferringToPreviouslyBoundVar = previousPatternVars.intersect(currentStrictInteriorVars).nonEmpty

      hasReferenceFromThisPatternToInterior || hasInteriorReferringToPreviouslyBoundVar
    }

    val accWithMaybeHorizon =
      if (
        // If a QPP depends on a variable from a previous clause, we need to insert a horizon.
        qppHasDependencyToPreviousClauses ||
        // also, if we have an interior variable that overlaps with another pattern node, we may not squash these.
        hasPatternOverlapOnInteriorVars
      ) {
        acc
          .withHorizon(PassthroughAllHorizon())
          .withTail(RegularSinglePlannerQuery(QueryGraph()))
      } else acc

    val selections = asSelections(clause.where)

    if (clause.optional) {
      accWithMaybeHorizon.amendQueryGraph { qg =>
        qg.withAddedOptionalMatch(
          // When adding QueryGraphs for optional matches, we always start with a new one.
          // It's either all or nothing per match clause.
          QueryGraph(
            selections = selections,
            hints = clause.hints.toSet
          ).addPathPatterns(pathPatterns)
        )
      }
    } else {
      accWithMaybeHorizon.amendQueryGraph {
        qg =>
          qg
            .addSelections(selections)
            .addHints(clause.hints)
            .addPathPatterns(pathPatterns)
      }
    }
  }

  private def addCallSubqueryToLogicalPlanInput(
    acc: PlannerQueryBuilder,
    clause: SubqueryCall,
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator,
    cancellationChecker: CancellationChecker
  ): PlannerQueryBuilder = {
    val subquery = clause.innerQuery
    val callSubquery =
      StatementConverters.toPlannerQuery(
        subquery,
        acc.semanticTable,
        anonymousVariableNameGenerator,
        cancellationChecker,
        rewrite = false,
        nonTerminating = true
      )
    acc.withCallSubquery(callSubquery, subquery.isCorrelated, subquery.isReturning, clause.inTransactionsParameters)
  }

  private def addCommandClauseToLogicalPlanInput(
    acc: PlannerQueryBuilder,
    clause: CommandClause
  ): PlannerQueryBuilder = {
    acc
      .withHorizon(CommandProjection(clause))
      .withTail(SinglePlannerQuery.empty)
  }

  private def addYieldToLogicalPlanInput(builder: PlannerQueryBuilder, `yield`: Yield): PlannerQueryBuilder = {
    val selections = asSelections(`yield`.where)
    val returnItems = asReturnItems(builder.currentQueryGraph, `yield`.returnItems)

    val queryPagination = QueryPagination().withLimit(`yield`.limit).withSkip(`yield`.skip)

    val queryProjection =
      asQueryProjection(distinct = false, returnItems, returningQueryProjection = false).withPagination(
        queryPagination
      ).withSelection(selections)

    val requiredOrder = findRequiredOrder(queryProjection, `yield`.orderBy)

    builder.withHorizon(queryProjection).withInterestingOrder(requiredOrder).withTail(RegularSinglePlannerQuery(
      QueryGraph()
    ))
  }

  private def toPropertyMap(expr: Option[Expression]): Map[PropertyKeyName, Expression] = expr match {
    case None                       => Map.empty
    case Some(MapExpression(items)) => items.toMap
    case e                          => throw new InternalException(s"Expected MapExpression, got $e")
  }

  private def toPropertySelection(identifier: LogicalVariable, map: Map[PropertyKeyName, Expression]): Seq[Expression] =
    map.map {
      case (k, e) => In(Property(identifier, k)(k.position), ListLiteral(Seq(e))(e.position))(identifier.position)
    }.toIndexedSeq

  private def toSetPattern(semanticTable: SemanticTable)(setItem: SetItem): SetMutatingPattern = setItem match {
    case SetLabelItem(id, labels) => SetLabelPattern(id.name, labels)

    case SetPropertyItem(LogicalProperty(node: Variable, propertyKey), expr)
      if semanticTable.typeFor(node).is(CTNode) =>
      SetNodePropertyPattern(node.name, propertyKey, expr)

    case SetPropertyItems(node: Variable, items) if semanticTable.typeFor(node).is(CTNode) =>
      SetNodePropertiesPattern(node.name, items)

    case SetPropertyItem(LogicalProperty(rel: Variable, propertyKey), expr)
      if semanticTable.typeFor(rel).is(CTRelationship) =>
      SetRelationshipPropertyPattern(rel.name, propertyKey, expr)

    case SetPropertyItems(rel: Variable, items) if semanticTable.typeFor(rel).is(CTRelationship) =>
      SetRelationshipPropertiesPattern(rel.name, items)

    case SetPropertyItem(LogicalProperty(entityExpr, propertyKey), expr) =>
      SetPropertyPattern(entityExpr, propertyKey, expr)

    case SetPropertyItems(entityExpr, items) =>
      SetPropertiesPattern(entityExpr, items)

    case SetExactPropertiesFromMapItem(node, expression) if semanticTable.typeFor(node).is(CTNode) =>
      SetNodePropertiesFromMapPattern(node.name, expression, removeOtherProps = true)

    case SetExactPropertiesFromMapItem(rel, expression) if semanticTable.typeFor(rel).is(CTRelationship) =>
      SetRelationshipPropertiesFromMapPattern(rel.name, expression, removeOtherProps = true)

    case SetExactPropertiesFromMapItem(vr, expression) =>
      SetPropertiesFromMapPattern(vr, expression, removeOtherProps = true)

    case SetIncludingPropertiesFromMapItem(node, expression) if semanticTable.typeFor(node).is(CTNode) =>
      SetNodePropertiesFromMapPattern(node.name, expression, removeOtherProps = false)

    case SetIncludingPropertiesFromMapItem(rel, expression) if semanticTable.typeFor(rel).is(CTRelationship) =>
      SetRelationshipPropertiesFromMapPattern(rel.name, expression, removeOtherProps = false)

    case SetIncludingPropertiesFromMapItem(vr, expression) =>
      SetPropertiesFromMapPattern(vr, expression, removeOtherProps = false)
  }

  private def addMergeToLogicalPlanInput(builder: PlannerQueryBuilder, clause: Merge): PlannerQueryBuilder = {

    val onCreate = clause.actions.collect {
      case OnCreate(setClause) => setClause.items.map(toSetPattern(builder.semanticTable))
    }.flatten
    val onMatch = clause.actions.collect {
      case OnMatch(setClause) => setClause.items.map(toSetPattern(builder.semanticTable))
    }.flatten

    clause.pattern match {
      // MERGE (n :L1:L2 {prop: 42})
      case PathPatternPart(NodePattern(Some(id), labelExpression, props, _)) =>
        val labels = getLabelNameSet(labelExpression)
        val currentlyAvailableVariables = builder.currentlyAvailableVariables
        val labelPredicates = labels.map(l => HasLabels(id, Seq(l))(id.position))
        val propertyPredicates = toPropertySelection(id, toPropertyMap(props))
        val createNodePattern = CreateNode(id.name, labels, props)

        val matchGraph = QueryGraph(
          patternNodes = Set(id.name),
          selections = Selections.from(labelPredicates ++ propertyPredicates),
          argumentIds = currentlyAvailableVariables
        )

        val queryGraph = QueryGraph.empty
          .withArgumentIds(matchGraph.argumentIds)
          .addMutatingPatterns(MergeNodePattern(createNodePattern, matchGraph, onCreate, onMatch))

        builder
          .withHorizon(PassthroughAllHorizon())
          .withTail(RegularSinglePlannerQuery(queryGraph = queryGraph))
          .withHorizon(asQueryProjection(
            distinct = false,
            QueryProjection.forIds(queryGraph.allCoveredIds),
            returningQueryProjection = false
          ))
          .withTail(RegularSinglePlannerQuery())

      // MERGE (n)-[r: R]->(m)
      case PathPatternPart(pattern: RelationshipChain) =>
        val (nodes, rels) =
          allCreatePatternsInOrderAndDeduped(pattern).foldRight((
            Seq.empty[CreateNodeCommand],
            Seq.empty[CreateRelCommand]
          )) { case (e, (ns, rs)) =>
            e match {
              case n: CreateNodeCommand => (n +: ns, rs)
              case r: CreateRelCommand  => (ns, r +: rs)
            }
          }

        // If the variable is both an argument and appears in a node pattern in a MERGE, it has to be a node,
        // otherwise SemanticAnalysis would already have failed.
        val seenPatternNodesAndArguments = builder.lastQGNodesAndArguments

        val (nodesCreatedBefore, nodesToCreate) = nodes.partition {
          case CreateNodeCommand(c, _) => seenPatternNodesAndArguments(c.idName)
        }

        nodesCreatedBefore.collectFirst {
          case CreateNodeCommand(c, _) if c.labels.nonEmpty || c.properties.nonEmpty =>
            throw new SyntaxException(
              s"Can't create node `${c.idName}` with labels or properties here. The variable is already declared in this context"
            )
        }

        val selections = asSelections(clause.where)

        val hasLabels = nodes.flatMap {
          case CreateNodeCommand(n, v) =>
            n.labels.map(l => HasLabels(v, Seq(l))(v.position))
        }

        val hasProps = nodes.flatMap {
          case CreateNodeCommand(n, v) =>
            toPropertySelection(v, toPropertyMap(n.properties))
        } ++ rels.flatMap {
          case CreateRelCommand(r, v) =>
            toPropertySelection(v, toPropertyMap(r.properties))
        }

        val matchGraph = QueryGraph(
          patternNodes = nodes.map(_.create.idName).toSet,
          patternRelationships = rels.map {
            case CreateRelCommand(r, _) =>
              PatternRelationship(r.idName, (r.leftNode, r.rightNode), r.direction, Seq(r.relType), SimplePatternLength)
          }.toSet,
          selections = selections ++ Selections.from(hasLabels ++ hasProps),
          argumentIds = builder.currentlyAvailableVariables ++ nodesCreatedBefore.map(_.create.idName)
        )

        val queryGraph = QueryGraph.empty
          .withArgumentIds(matchGraph.argumentIds)
          .addMutatingPatterns(MergeRelationshipPattern(
            nodesToCreate.map(_.create),
            rels.map(_.create),
            matchGraph,
            onCreate,
            onMatch
          ))

        builder.withHorizon(PassthroughAllHorizon()).withTail(
          RegularSinglePlannerQuery(queryGraph = queryGraph)
        ).withHorizon(asQueryProjection(
          distinct = false,
          QueryProjection.forIds(queryGraph.allCoveredIds),
          returningQueryProjection = false
        )).withTail(
          RegularSinglePlannerQuery()
        )

      case x => throw new InternalException(s"Received an AST-clause that has no representation the QG: $x")
    }
  }

  private def addWithToLogicalPlanInput(
    builder: PlannerQueryBuilder,
    clause: With,
    nextClause: Option[Clause]
  ): PlannerQueryBuilder = {

    /**
     * If we have OPTIONAL MATCHes, we can only keep building the same PlannerQuery, if the next clause is also an OPTIONAL MATCH
     * and the WITH clause has no WHERE sub-clause.
     */
    def optionalMatchesOK(where: Option[Where]): Boolean = {
      !builder.currentQueryGraph.hasOptionalPatterns || (where.isEmpty && (nextClause match {
        case Some(m: Match) if m.optional => true
        case _                            => false
      }))
    }

    /**
     * If there are updates, we need to keep the order between read and write parts correct.
     */
    def noUpdates: Boolean = !builder.currentQueryGraph.containsUpdates && builder.readOnly

    def noShortestPaths: Boolean = builder.currentQueryGraph.shortestRelationshipPatterns.isEmpty

    /**
     * If there are projections or aggregations, we have to continue in a new PlannerQuery.
     */
    def returnItemsOK(ri: ReturnItems): Boolean = {
      ri.items.forall {
        case item: AliasedReturnItem =>
          !containsAggregateOutsideOfAggregatingHorizon(item.expression) && item.expression == item.variable
        case _ => throw new InternalException("This should have been rewritten to an AliasedReturnItem.")
      }
    }

    clause match {

      /*
      When encountering a WITH that is not an event horizon
      we simply continue building on the current PlannerQuery. Our ASTRewriters rewrite queries in such a way that
      a lot of queries have these WITH clauses.

      Handles: ... WITH * [WHERE <predicate>] ...
       */
      case With(false, ri, None, None, None, where, _)
        if optionalMatchesOK(where)
          && noUpdates
          && returnItemsOK(ri)
          && noShortestPaths =>
        val selections = asSelections(where)

        builder
          .amendQueryGraph(_.addSelections(selections))

      /*
      When encountering a WITH that is an event horizon, we introduce the horizon and start a new empty QueryGraph.

      Handles all other WITH clauses
       */
      case With(distinct, projection, orderBy, skip, limit, where, _) =>
        val selections = asSelections(where)
        val returnItems = asReturnItems(builder.currentQueryGraph, projection)

        val queryPagination = QueryPagination().withLimit(limit).withSkip(skip)

        val queryProjection =
          asQueryProjection(distinct, returnItems, returningQueryProjection = false).withPagination(
            queryPagination
          ).withSelection(selections)

        val requiredOrder = findRequiredOrder(queryProjection, orderBy)

        builder
          .withHorizon(queryProjection)
          .withInterestingOrder(requiredOrder)
          .withPropagatedTailInterestingOrder()
          .withTail(RegularSinglePlannerQuery(QueryGraph()))

      case _ =>
        throw new InternalException("AST needs to be rewritten before it can be used for planning. Got: " + clause)
    }
  }

  private def addUnwindToLogicalPlanInput(builder: PlannerQueryBuilder, clause: Unwind): PlannerQueryBuilder =
    builder.withHorizon(
      UnwindProjection(
        variable = clause.variable.name,
        exp = clause.expression
      )
    ).withTail(SinglePlannerQuery.empty)

  private def addCallToLogicalPlanInput(builder: PlannerQueryBuilder, call: ResolvedCall): PlannerQueryBuilder = {
    builder
      .withHorizon(ProcedureCallProjection(call))
      .withTail(SinglePlannerQuery.empty)
  }

  private def addForeachToLogicalPlanInput(
    builder: PlannerQueryBuilder,
    clause: Foreach,
    anonymousVariableNameGenerator: AnonymousVariableNameGenerator,
    cancellationChecker: CancellationChecker
  ): PlannerQueryBuilder = {
    val currentlyAvailableVariables = builder.currentlyAvailableVariables

    val foreachVariable = clause.variable

    val innerBuilder = new PlannerQueryBuilder(SinglePlannerQuery.empty, builder.semanticTable)
      .amendQueryGraph(_.addArgumentIds(foreachVariable.name +: currentlyAvailableVariables.toIndexedSeq))
      .withHorizon(PassthroughAllHorizon())

    val innerPlannerQuery = StatementConverters.addClausesToPlannerQueryBuilder(
      clause.updates,
      innerBuilder,
      anonymousVariableNameGenerator,
      cancellationChecker,
      nonTerminating = false
    ).build()

    val foreachPattern = ForeachPattern(
      variable = clause.variable.name,
      expression = clause.expression,
      innerUpdates = innerPlannerQuery
    )

    val foreachGraph = QueryGraph(
      argumentIds = currentlyAvailableVariables,
      mutatingPatterns = IndexedSeq(foreachPattern)
    )

    // Since foreach can contain reads (via inner merge) we put it in its own separate planner query
    // to maintain the strict ordering of reads followed by writes within a single planner query
    builder
      .withHorizon(PassthroughAllHorizon())
      .withTail(RegularSinglePlannerQuery(queryGraph = foreachGraph))
      .withHorizon(PassthroughAllHorizon()) // NOTE: We do not expose anything from foreach itself
      .withTail(RegularSinglePlannerQuery())
  }

  private def addRemoveToLogicalPlanInput(acc: PlannerQueryBuilder, clause: Remove): PlannerQueryBuilder = {
    clause.items.foldLeft(acc) {
      // REMOVE n:Foo
      case (builder, RemoveLabelItem(variable, labelNames)) =>
        builder.amendQueryGraph(_.addMutatingPatterns(RemoveLabelPattern(variable.name, labelNames)))

      // REMOVE n.prop
      case (builder, RemovePropertyItem(Property(variable: Variable, propertyKey)))
        if acc.semanticTable.typeFor(variable).is(CTNode) =>
        builder.amendQueryGraph(_.addMutatingPatterns(
          SetNodePropertyPattern(variable.name, propertyKey, Null()(propertyKey.position))
        ))

      // REMOVE rel.prop
      case (builder, RemovePropertyItem(Property(variable: Variable, propertyKey)))
        if acc.semanticTable.typeFor(variable).is(CTRelationship) =>
        builder.amendQueryGraph(_.addMutatingPatterns(
          SetRelationshipPropertyPattern(variable.name, propertyKey, Null()(propertyKey.position))
        ))

      // REMOVE rel.prop when unknown whether node or rel
      case (builder, RemovePropertyItem(Property(variable, propertyKey))) =>
        builder.amendQueryGraph(_.addMutatingPatterns(
          SetPropertyPattern(variable, propertyKey, Null()(propertyKey.position))
        ))

      case (_, other) =>
        throw new InternalException(s"REMOVE $other not supported in cost planner yet")
    }
  }

  private def containsAggregateOutsideOfAggregatingHorizon(expr: Expression): Boolean = {
    expr.folder.treeFold[Boolean](false) {
      case _: AggregatingQueryProjection => _ => SkipChildren(false)
      case IsAggregate(_)                => _ => SkipChildren(true)
    }

  }
}

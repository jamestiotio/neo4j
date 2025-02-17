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
package org.neo4j.cypher.internal.ir.converters

import org.neo4j.cypher.internal.expressions.FixedQuantifier
import org.neo4j.cypher.internal.expressions.GraphPatternQuantifier
import org.neo4j.cypher.internal.expressions.IntervalQuantifier
import org.neo4j.cypher.internal.expressions.NamedPatternPart
import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.ParenthesizedPath
import org.neo4j.cypher.internal.expressions.PathConcatenation
import org.neo4j.cypher.internal.expressions.PathPatternPart
import org.neo4j.cypher.internal.expressions.PatternElement
import org.neo4j.cypher.internal.expressions.PlusQuantifier
import org.neo4j.cypher.internal.expressions.QuantifiedPath
import org.neo4j.cypher.internal.expressions.RelationshipChain
import org.neo4j.cypher.internal.expressions.ShortestPathsPatternPart
import org.neo4j.cypher.internal.expressions.StarQuantifier
import org.neo4j.cypher.internal.ir.NodeBinding
import org.neo4j.cypher.internal.ir.PatternRelationship
import org.neo4j.cypher.internal.ir.QuantifiedPathPattern
import org.neo4j.cypher.internal.ir.Selections
import org.neo4j.cypher.internal.ir.VariableGrouping
import org.neo4j.cypher.internal.util.NonEmptyList
import org.neo4j.cypher.internal.util.Repetition
import org.neo4j.cypher.internal.util.UpperBound

object QuantifiedPathPatternConverters {

  def convertQuantifiedPath(
    outerLeft: String,
    quantifiedPath: QuantifiedPath,
    outerRight: String
  ): QuantifiedPathPattern = {
    val patternPart = getPathPattern(quantifiedPath)
    val patternElement = patternPart.element
    val patternRelationships = getPatternRelationships(patternElement)
    val groupings = VariableGroupings.build(quantifiedPath.variableGroupings)
    patternRelationships
      .map(relationship => QuantifiedPathPatternBuilder.fromPatternRelationship(groupings, relationship))
      .reduceLeft(_.append(_))
      .build(
        outerLeft = outerLeft,
        outerRight = outerRight,
        selections = Selections.from(quantifiedPath.optionalWhereExpression),
        repetition = convertGraphPatternQuantifier(quantifiedPath.quantifier)
      )
  }

  def convertGraphPatternQuantifier(quantifier: GraphPatternQuantifier): Repetition =
    quantifier match {
      case PlusQuantifier()       => Repetition(min = 1, max = UpperBound.Unlimited)
      case StarQuantifier()       => Repetition(min = 0, max = UpperBound.Unlimited)
      case FixedQuantifier(value) => Repetition(min = value.value, max = UpperBound.Limited(value.value))
      case IntervalQuantifier(lower, upper) =>
        Repetition(
          min = lower.fold(0L)(_.value),
          max = upper.fold(UpperBound.unlimited)(x => UpperBound.Limited(x.value))
        )
    }

  private def getPathPattern(quantifiedPath: QuantifiedPath): PathPatternPart =
    quantifiedPath.part match {
      case part: PathPatternPart => part
      case shortest: ShortestPathsPatternPart =>
        throw new IllegalArgumentException(s"${shortest.name}() is not allowed inside of a quantified path pattern")
      case _: NamedPatternPart =>
        throw new IllegalArgumentException("sub-path assignment is not currently supported")
    }

  private def getPatternRelationships(patternElement: PatternElement): NonEmptyList[PatternRelationship] =
    patternElement match {
      case relationshipChain: RelationshipChain => SimplePatternConverters.convertRelationshipChain(relationshipChain)
      case _: NodePattern =>
        throw new IllegalArgumentException("quantified path patterns must contain at least one relationship")
      case _: PathConcatenation =>
        throw new IllegalArgumentException(
          "path concatenation is not currently supported inside of quantified path patterns"
        )
      case _: QuantifiedPath =>
        throw new IllegalArgumentException("nested quantified path patterns are not currently supported")
      case _: ParenthesizedPath =>
        throw new IllegalArgumentException(
          "parenthesised path patterns are not currently supported inside of quantified path patterns"
        )
    }

  final private case class QuantifiedPathPatternBuilder(
    leftMostNode: String,
    rightMostNode: String,
    patternRelationships: Vector[PatternRelationship],
    patternNodes: Set[String],
    nodeVariableGroupings: Set[VariableGrouping],
    relationshipVariableGroupings: Set[VariableGrouping]
  ) {

    def append(right: QuantifiedPathPatternBuilder): QuantifiedPathPatternBuilder =
      QuantifiedPathPatternBuilder(
        leftMostNode = leftMostNode,
        rightMostNode = right.rightMostNode,
        patternRelationships = patternRelationships ++ right.patternRelationships,
        patternNodes = patternNodes.union(right.patternNodes),
        nodeVariableGroupings = nodeVariableGroupings.union(right.nodeVariableGroupings),
        relationshipVariableGroupings = relationshipVariableGroupings.union(right.relationshipVariableGroupings)
      )

    def build(
      outerLeft: String,
      outerRight: String,
      selections: Selections,
      repetition: Repetition
    ): QuantifiedPathPattern =
      QuantifiedPathPattern(
        leftBinding = NodeBinding(leftMostNode, outerLeft),
        rightBinding = NodeBinding(rightMostNode, outerRight),
        patternRelationships = patternRelationships,
        patternNodes = patternNodes,
        argumentIds = Set.empty,
        selections = selections,
        repetition = repetition,
        nodeVariableGroupings = nodeVariableGroupings,
        relationshipVariableGroupings = relationshipVariableGroupings
      )
  }

  private object QuantifiedPathPatternBuilder {

    def fromPatternRelationship(
      variableGroupings: VariableGroupings,
      patternRelationship: PatternRelationship
    ): QuantifiedPathPatternBuilder = {
      val patternNodes = Set(patternRelationship.left, patternRelationship.right)
      QuantifiedPathPatternBuilder(
        leftMostNode = patternRelationship.left,
        rightMostNode = patternRelationship.right,
        patternRelationships = Vector(patternRelationship),
        patternNodes = patternNodes,
        nodeVariableGroupings = variableGroupings.forSingletonNames(patternNodes),
        relationshipVariableGroupings = variableGroupings.forSingletonName(patternRelationship.name).toSet
      )
    }
  }

  final private case class VariableGroupings(singletonNameToGroupName: Map[String, String]) extends AnyVal {

    def forSingletonNames(singletonNames: Set[String]): Set[VariableGrouping] =
      singletonNames.flatMap(forSingletonName)

    def forSingletonName(singletonName: String): Option[VariableGrouping] =
      singletonNameToGroupName
        .get(singletonName)
        .map(groupName => VariableGrouping(singletonName, groupName))
  }

  private object VariableGroupings {

    def build(groupings: Set[org.neo4j.cypher.internal.expressions.VariableGrouping]): VariableGroupings =
      VariableGroupings(
        groupings
          .view
          .map(grouping => grouping.singleton.name -> grouping.group.name)
          .toMap
      )
  }
}

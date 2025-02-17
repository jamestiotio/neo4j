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
package org.neo4j.cypher.internal.compiler.planner.logical.steps

import org.neo4j.cypher.internal.ast.semantics.SemanticTable
import org.neo4j.cypher.internal.expressions.CaseExpression
import org.neo4j.cypher.internal.expressions.DesugaredMapProjection
import org.neo4j.cypher.internal.expressions.LogicalProperty
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.expressions.MapExpression
import org.neo4j.cypher.internal.expressions.Property
import org.neo4j.cypher.internal.expressions.PropertyKeyName
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.logical.plans.AbstractLetSelectOrSemiApply
import org.neo4j.cypher.internal.logical.plans.AbstractLetSemiApply
import org.neo4j.cypher.internal.logical.plans.AbstractSelectOrSemiApply
import org.neo4j.cypher.internal.logical.plans.AbstractSemiApply
import org.neo4j.cypher.internal.logical.plans.Aggregation
import org.neo4j.cypher.internal.logical.plans.Anti
import org.neo4j.cypher.internal.logical.plans.ApplyPlan
import org.neo4j.cypher.internal.logical.plans.CacheProperties
import org.neo4j.cypher.internal.logical.plans.CanGetValue
import org.neo4j.cypher.internal.logical.plans.Eager
import org.neo4j.cypher.internal.logical.plans.ForeachApply
import org.neo4j.cypher.internal.logical.plans.IndexedProperty
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.LogicalPlans
import org.neo4j.cypher.internal.logical.plans.NodeIndexLeafPlan
import org.neo4j.cypher.internal.logical.plans.OrderedAggregation
import org.neo4j.cypher.internal.logical.plans.OrderedUnion
import org.neo4j.cypher.internal.logical.plans.ProjectingPlan
import org.neo4j.cypher.internal.logical.plans.RelationshipIndexLeafPlan
import org.neo4j.cypher.internal.logical.plans.RollUpApply
import org.neo4j.cypher.internal.logical.plans.Selection
import org.neo4j.cypher.internal.logical.plans.SetNodeProperties
import org.neo4j.cypher.internal.logical.plans.SetNodePropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetNodeProperty
import org.neo4j.cypher.internal.logical.plans.SetProperties
import org.neo4j.cypher.internal.logical.plans.SetProperty
import org.neo4j.cypher.internal.logical.plans.SetRelationshipProperties
import org.neo4j.cypher.internal.logical.plans.SetRelationshipPropertiesFromMap
import org.neo4j.cypher.internal.logical.plans.SetRelationshipProperty
import org.neo4j.cypher.internal.logical.plans.TransactionForeach
import org.neo4j.cypher.internal.logical.plans.Union
import org.neo4j.cypher.internal.planner.spi.PlanningAttributes.EffectiveCardinalities
import org.neo4j.cypher.internal.util.EffectiveCardinality
import org.neo4j.cypher.internal.util.Foldable.FoldableAny
import org.neo4j.cypher.internal.util.Foldable.SkipChildren
import org.neo4j.cypher.internal.util.Foldable.TraverseChildren
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.Rewriter
import org.neo4j.cypher.internal.util.attribution.Attributes
import org.neo4j.cypher.internal.util.attribution.Id
import org.neo4j.cypher.internal.util.bottomUp
import org.neo4j.cypher.internal.util.symbols.CTNode
import org.neo4j.cypher.internal.util.symbols.CTRelationship

import scala.collection.mutable

case object PushdownPropertyReads {

  // Negligible quantity of cardinality when considering pushdown
  private val CARDINALITY_EPSILON = EffectiveCardinality(0.0000001)

  /**
   * Rewrites the specified plan to include CacheProperties at cardinality optimums.
   *
   * Note, input position is NOT guaranteed to be accurate in cached properties.
   */
  def pushdown(
    logicalPlan: LogicalPlan,
    effectiveCardinalities: EffectiveCardinalities,
    attributes: Attributes[LogicalPlan],
    semanticTable: SemanticTable
  ): LogicalPlan = {

    def isNodeOrRel(variable: LogicalVariable): Boolean =
      semanticTable.typeFor(variable).isAnyOf(CTNode, CTRelationship)

    case class CardinalityOptimum(cardinality: EffectiveCardinality, logicalPlanId: Id, variableName: String)
    case class Acc(
      variableOptima: Map[String, CardinalityOptimum],
      propertyReadOptima: Seq[(CardinalityOptimum, PushDownProperty)],
      availableProperties: Set[PushDownProperty],
      availableWholeEntities: Set[String],
      incomingCardinality: EffectiveCardinality
    )

    def shouldCoRead(optimumProperties: List[(CardinalityOptimum, PushDownProperty)], plan: LogicalPlan): Boolean = {
      optimumProperties.size > 1 &&
      plan.rhs.isEmpty &&
      plan.lhs.nonEmpty &&
      !plan.isInstanceOf[Selection] &&
      optimumProperties.exists { case (_, p) => !p.inMapProjection } // Map projection is faster than cached properties
    }

    def findProperties(expression: Any): Seq[PushDownProperty] =
      expression.folder.treeFold(Seq.empty[PushDownProperty]) {
        case PushableProperty(p) if isNodeOrRel(p.variable) =>
          acc => TraverseChildren(acc :+ p)
      }

    def foldSingleChildPlan(acc: Acc, plan: LogicalPlan): Acc = {
      val newPropertyExpressions =
        plan.folder.treeFold(List.empty[PushDownProperty]) {
          case lp: LogicalPlan if lp.id != plan.id =>
            acc2 => SkipChildren(acc2) // do not traverse further
          case _: CaseExpression => // we don't want to pushdown properties inside case expressions since it's not sure we will ever need to read them
            acc2 => SkipChildren(acc2)
          case PushableProperty(p) if isNodeOrRel(p.variable) =>
            acc2 => TraverseChildren(p :: acc2)
          case mp: DesugaredMapProjection =>
            val mapProperties =
              findProperties(mp).map(p => PushDownProperty(p.property)(p.variable, inMapProjection = true))
            acc2 => SkipChildren(acc2 ++ mapProperties)
        }

      val newPushableProperties: Map[LogicalVariable, List[(CardinalityOptimum, PushDownProperty)]] =
        newPropertyExpressions
          .flatMap { p =>
            acc.variableOptima.get(p.variable.name) match {
              case Some(optimum: CardinalityOptimum) =>
                if (!acc.availableProperties.contains(p) && !acc.availableWholeEntities.contains(p.variable.name))
                  Some((optimum, p))
                else
                  None
              // this happens for variables introduced in expressions, we ignore those for now
              case None => None
            }
          }
          .groupBy { case (_, p) => p.variable }

      val newPropertyReadOptima =
        newPushableProperties.toSeq.flatMap {
          case (v, optimumProperties) =>
            val (optimum, _) = optimumProperties.head
            if (optimum.cardinality < acc.incomingCardinality) {
              optimumProperties
            } else if (shouldCoRead(optimumProperties, plan)) {
              val uniqueProps = optimumProperties.map(_._2).toSet
              if (uniqueProps.size > 1) {
                val beforeThisPlanOptimum = CardinalityOptimum(acc.incomingCardinality, plan.lhs.get.id, v.name)
                uniqueProps.toSeq.map(p => (beforeThisPlanOptimum, p))
              } else {
                Nil
              }
            } else {
              Nil
            }
        }

      val outgoingCardinality = effectiveCardinalities(plan.id)
      val outgoingReadOptima = acc.propertyReadOptima ++ newPropertyReadOptima

      plan match {
        case _: Anti =>
          throw new IllegalStateException(
            "This plan is introduced in physical planning, I shouldn't need to know about it."
          )

        case _: Aggregation |
          _: OrderedAggregation |
          _: Eager =>
          // Do _not_ pushdown past these plans
          val newVariables = plan.availableSymbols
          val outgoingVariableOptima =
            newVariables.map(v => (v.name, CardinalityOptimum(outgoingCardinality, plan.id, v.name))).toMap

          Acc(outgoingVariableOptima, outgoingReadOptima, Set.empty, Set.empty, outgoingCardinality)

        case p: ProjectingPlan => // except for aggregations which were already matched
          val renamings: Map[String, String] =
            p.projectExpressions.collect {
              case (key, v: Variable) if key.name != v.name => (v.name, key.name)
            }

          val renamedVariableOptima =
            acc.variableOptima.map {
              case (oldName, optimum) =>
                (renamings.getOrElse(oldName, oldName), optimum)
            }

          val renamedAvailableProperties =
            (acc.availableProperties ++ newPropertyExpressions).map(prop => {
              renamings.get(prop.variable.name) match {
                case Some(newName) => propertyWithName(newName, prop)
                case None          => prop
              }
            })

          Acc(
            renamedVariableOptima,
            outgoingReadOptima,
            renamedAvailableProperties,
            acc.availableWholeEntities,
            outgoingCardinality
          )

        case _ =>
          val newLowestCardinalities =
            acc.variableOptima.view.mapValues(optimum =>
              if (outgoingCardinality <= (optimum.cardinality + CARDINALITY_EPSILON)) {
                CardinalityOptimum(outgoingCardinality, plan.id, optimum.variableName)
              } else {
                optimum
              }
            )

          val currentVariables = plan.availableSymbols
          val newVariables = currentVariables.map(_.name) -- acc.variableOptima.keySet
          val newVariableCardinalities = newVariables.map(v => (v, CardinalityOptimum(outgoingCardinality, plan.id, v)))
          val outgoingVariableOptima = newLowestCardinalities ++ newVariableCardinalities

          val propertiesFromPlan: Seq[PushDownProperty] =
            plan match {
              case indexPlan: NodeIndexLeafPlan =>
                indexPlan.properties
                  .filter(_.getValueFromIndex == CanGetValue)
                  // NOTE: as we pushdown before inserting cached properties
                  //       the getValue behaviour will still be CanGetValue
                  //       instead of GetValue
                  .map(asProperty(indexPlan.idName.name))
              case indexPlan: RelationshipIndexLeafPlan =>
                indexPlan.properties
                  .filter(_.getValueFromIndex == CanGetValue)
                  // NOTE: as we pushdown before inserting cached properties
                  //       the getValue behaviour will still be CanGetValue
                  //       instead of GetValue
                  .map(asProperty(indexPlan.idName.name))

              case SetProperty(_, variable: LogicalVariable, propertyKey, _) =>
                Seq(PushableProperty(variable, propertyKey))

              case SetProperties(_, variable: LogicalVariable, items) =>
                items.map { case (p, _) => PushableProperty(variable, p) }

              case SetNodeProperty(_, idName, propertyKey, _) =>
                Seq(PushableProperty(idName, propertyKey))

              case SetNodeProperties(_, idName, items) =>
                items.map { case (p, _) => PushableProperty(idName, p) }

              case SetRelationshipProperty(_, idName, propertyKey, _) =>
                Seq(PushableProperty(idName, propertyKey))

              case SetRelationshipProperties(_, idName, items) =>
                items.map { case (p, _) => PushableProperty(idName, p) }

              case SetNodePropertiesFromMap(_, idName, map: MapExpression, false) =>
                propertiesFromMap(idName.name, map)

              case SetRelationshipPropertiesFromMap(_, idName, map: MapExpression, false) =>
                propertiesFromMap(idName.name, map)

              case _ => Seq.empty
            }

          val maybeEntityFromPlan =
            plan match {
              case SetNodePropertiesFromMap(_, idName, _, true)                                      => Some(idName)
              case SetNodePropertiesFromMap(_, idName, expr, _) if !expr.isInstanceOf[MapExpression] => Some(idName)
              case SetRelationshipPropertiesFromMap(_, idName, _, true)                              => Some(idName)
              case SetRelationshipPropertiesFromMap(_, idName, expr, _) if !expr.isInstanceOf[MapExpression] =>
                Some(idName)
              case _ => None
            }

          val outgoingAvailableProperties = acc.availableProperties ++ newPropertyExpressions ++ propertiesFromPlan

          Acc(
            outgoingVariableOptima.toMap,
            outgoingReadOptima,
            outgoingAvailableProperties,
            acc.availableWholeEntities ++ maybeEntityFromPlan.map(_.name),
            outgoingCardinality
          )
      }
    }

    def foldTwoChildPlan(lhsAcc: Acc, rhsAcc: Acc, plan: LogicalPlan): Acc = {
      plan match {

        // Do _not_ pushdown from on top of these plans to the LHS or the RHS
        case _: Union |
          _: OrderedUnion |
          // TransactionForeach will clear caches, so it's useless to push down reads
          _: TransactionForeach =>
          val newVariables = plan.availableSymbols
          val outgoingCardinality = effectiveCardinalities(plan.id)
          val outgoingVariableOptima =
            newVariables.map(v => (v.name, CardinalityOptimum(outgoingCardinality, plan.id, v.name))).toMap
          Acc(
            // Keep only optima of variables introduced in these plans
            outgoingVariableOptima,
            // Keep any pushdowns identified in either side
            lhsAcc.propertyReadOptima ++ rhsAcc.propertyReadOptima,
            // Keep no available properties/entities, which allows pushing down on top of these plans
            Set.empty,
            Set.empty,
            outgoingCardinality
          )

        // Do not pushdown from on top of these plans to the RHS, but allow pushing down to the LHS
        case _: AbstractSemiApply
          | _: AbstractLetSemiApply
          | _: AbstractSelectOrSemiApply
          | _: AbstractLetSelectOrSemiApply
          | _: ForeachApply
          | _: RollUpApply =>
          val mergedAcc = Acc(
            // Keep only optima for variables from the LHS
            lhsAcc.variableOptima,
            // Keep any pushdowns identified in either side
            lhsAcc.propertyReadOptima ++ rhsAcc.propertyReadOptima,
            // Keep only the available properties/entities from the LHS
            lhsAcc.availableProperties,
            lhsAcc.availableWholeEntities,
            effectiveCardinalities(plan.id)
          )
          foldSingleChildPlan(mergedAcc, plan)

        case _: ApplyPlan =>
          foldSingleChildPlan(rhsAcc, plan)

        case _ =>
          val mergedVariableOptima =
            lhsAcc.variableOptima ++ rhsAcc.variableOptima.map {
              case (v, rhsOptimum) =>
                lhsAcc.variableOptima.get(v) match {
                  case Some(lhsOptimum) =>
                    (v, Seq(lhsOptimum, rhsOptimum).minBy(_.cardinality))
                  case None =>
                    (v, rhsOptimum)
                }
            }

          Acc(
            mergedVariableOptima,
            lhsAcc.propertyReadOptima ++ rhsAcc.propertyReadOptima,
            lhsAcc.availableProperties ++ rhsAcc.availableProperties,
            lhsAcc.availableWholeEntities ++ rhsAcc.availableWholeEntities,
            effectiveCardinalities(plan.id)
          )
      }
    }

    def mapArguments(argumentAcc: Acc, plan: LogicalPlan): Acc = {
      plan match {
        case _: TransactionForeach =>
          Acc(
            // Keep no optima of variables so that no property read can get pushed down from RHS to LHS
            Map.empty,
            // Keep any pushdowns identified so far
            argumentAcc.propertyReadOptima,
            // Keep no available properties/entities, which allows pushing down within the RHS
            Set.empty,
            Set.empty,
            argumentAcc.incomingCardinality
          )
        case _ => argumentAcc
      }
    }

    val Acc(_, propertyReadOptima, _, _, _) =
      LogicalPlans.foldPlan(Acc(Map.empty, Seq.empty, Set.empty, Set.empty, EffectiveCardinality(1)))(
        logicalPlan,
        foldSingleChildPlan,
        foldTwoChildPlan,
        mapArguments
      )

    val propertyMap = new mutable.HashMap[Id, Set[PushDownProperty]].withDefaultValue(Set.empty)
    propertyReadOptima foreach {
      case (CardinalityOptimum(_, id, variableNameAtOptimum), property) =>
        propertyMap(id) += propertyWithName(variableNameAtOptimum, property)
    }

    val propertyReadInsertRewriter = bottomUp(Rewriter.lift {
      case lp: LogicalPlan if propertyMap.contains(lp.id) =>
        val copiedProperties = propertyMap(lp.id).map {
          p =>
            p.property.copy()(p.property.position): LogicalProperty
        }
        CacheProperties(lp, copiedProperties)(attributes.copy(lp.id))
    })

    propertyReadInsertRewriter(logicalPlan).asInstanceOf[LogicalPlan]
  }

  private def asProperty(idName: String)(indexedProperty: IndexedProperty): PushDownProperty = {
    PushableProperty(idName, PropertyKeyName(indexedProperty.propertyKeyToken.name)(InputPosition.NONE))
  }

  private def propertyWithName(idName: String, p: PushDownProperty): PushDownProperty = {
    if (p.variable.name == idName) {
      p
    } else {
      val variable = Variable(idName)(InputPosition.NONE)
      PushDownProperty(Property(variable, p.property.propertyKey)(InputPosition.NONE))(variable)
    }
  }

  private def propertiesFromMap(idName: String, map: MapExpression): Seq[PushDownProperty] = {
    map.items.map {
      case (prop, _) => PushableProperty(idName, prop)
    }
  }
}

case class PushDownProperty(property: Property)(val variable: LogicalVariable, val inMapProjection: Boolean = false)

object PushableProperty {

  def apply(variable: LogicalVariable, propertyKey: PropertyKeyName): PushDownProperty =
    PushDownProperty(Property(variable, propertyKey)(InputPosition.NONE))(variable)

  def apply(variable: String, propertyKey: PropertyKeyName): PushDownProperty =
    apply(Variable(variable)(InputPosition.NONE), propertyKey)

  def unapply(property: Property): Option[PushDownProperty] = property match {
    case Property(v: LogicalVariable, _) => Some(PushDownProperty(property)(v))
    case _                               => None
  }
}

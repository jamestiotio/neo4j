/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
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
package org.neo4j.cypher.internal.rewriting.rewriters

import org.neo4j.cypher.internal.ast.CallClause
import org.neo4j.cypher.internal.ast.Clause
import org.neo4j.cypher.internal.ast.Create
import org.neo4j.cypher.internal.ast.Limit
import org.neo4j.cypher.internal.ast.Match
import org.neo4j.cypher.internal.ast.Merge
import org.neo4j.cypher.internal.ast.PeriodicCommitHint
import org.neo4j.cypher.internal.ast.Return
import org.neo4j.cypher.internal.ast.SetClause
import org.neo4j.cypher.internal.ast.Unwind
import org.neo4j.cypher.internal.ast.With
import org.neo4j.cypher.internal.expressions.ContainerIndex
import org.neo4j.cypher.internal.expressions.DoubleLiteral
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.IntegerLiteral
import org.neo4j.cypher.internal.expressions.ListLiteral
import org.neo4j.cypher.internal.expressions.Literal
import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.Parameter
import org.neo4j.cypher.internal.expressions.RelationshipPattern
import org.neo4j.cypher.internal.expressions.StringLiteral
import org.neo4j.cypher.internal.util.ASTNode
import org.neo4j.cypher.internal.util.IdentityMap
import org.neo4j.cypher.internal.util.Rewriter
import org.neo4j.cypher.internal.util.bottomUp
import org.neo4j.cypher.internal.util.symbols.CTAny
import org.neo4j.cypher.internal.util.symbols.CTFloat
import org.neo4j.cypher.internal.util.symbols.CTInteger
import org.neo4j.cypher.internal.util.symbols.CTList
import org.neo4j.cypher.internal.util.symbols.CTString

object literalReplacement {

  case class LiteralReplacement(parameter: Parameter, value: AnyRef)
  type LiteralReplacements = IdentityMap[Expression, LiteralReplacement]

  case class ExtractParameterRewriter(replaceableLiterals: LiteralReplacements) extends Rewriter {
    def apply(that: AnyRef): AnyRef = rewriter.apply(that)

    private val rewriter: Rewriter = bottomUp(Rewriter.lift {
      case l: Expression if replaceableLiterals.contains(l) => replaceableLiterals(l).parameter
    })
  }

  private val literalMatcher: PartialFunction[Any, LiteralReplacements => (LiteralReplacements, Option[LiteralReplacements => LiteralReplacements])] = {
    case _: Match |
         _: Create |
         _: Merge |
         _: SetClause |
         _: Return |
         _: With |
         _: Unwind |
         _: CallClause =>
      acc => (acc, Some(identity))
    case _: Clause |
         _: PeriodicCommitHint |
         _: Limit =>
      acc => (acc, None)
    case n: NodePattern =>
      acc => (n.properties.treeFold(acc)(literalMatcher), None)
    case r: RelationshipPattern =>
      acc => (r.properties.treeFold(acc)(literalMatcher), None)
    case ContainerIndex(_, _: StringLiteral) =>
      acc => (acc, None)
    case l: StringLiteral =>
      acc =>
        if (acc.contains(l)) (acc, None) else {
          val parameter = Parameter(s"  AUTOSTRING${acc.size}", CTString)(l.position)
          (acc + (l -> LiteralReplacement(parameter, l.value)), None)
        }
    case l: IntegerLiteral =>
      acc =>
        if (acc.contains(l)) (acc, None) else {
          val parameter = Parameter(s"  AUTOINT${acc.size}", CTInteger)(l.position)
          (acc + (l -> LiteralReplacement(parameter, l.value)), None)
        }
    case l: DoubleLiteral =>
      acc =>
        if (acc.contains(l)) (acc, None) else {
          val parameter = Parameter(s"  AUTODOUBLE${acc.size}", CTFloat)(l.position)
          (acc + (l -> LiteralReplacement(parameter, l.value)), None)
        }
    case l: ListLiteral if l.expressions.forall(_.isInstanceOf[Literal]) =>
      acc =>
        if (acc.contains(l)) (acc, None) else {
          val parameter = Parameter(s"  AUTOLIST${acc.size}", CTList(CTAny))(l.position)
          val values: Seq[AnyRef] = l.expressions.map(_.asInstanceOf[Literal].value).toIndexedSeq
          (acc + (l -> LiteralReplacement(parameter, values)), None)
        }
  }

  private def doIt(term: ASTNode) = {
    val replaceableLiterals = term.treeFold(IdentityMap.empty: LiteralReplacements)(literalMatcher)

    val extractedParams: Map[String, AnyRef] = replaceableLiterals.map {
      case (_, LiteralReplacement(parameter, value)) => (parameter.name, value)
    }

    (ExtractParameterRewriter(replaceableLiterals), extractedParams)
  }

  def apply(term: ASTNode, paramExtraction: LiteralExtraction): (Rewriter, Map[String, Any]) = paramExtraction match {
    case Never =>
      Rewriter.noop -> Map.empty
    case Forced =>
      doIt(term)
    case IfNoParameter =>
      val containsParameter: Boolean = term.treeExists {
        case _: Parameter => true
      }

      if (containsParameter) Rewriter.noop -> Map.empty
      else doIt(term)
  }
}

sealed trait LiteralExtraction
case object Forced extends LiteralExtraction
case object IfNoParameter extends LiteralExtraction
case object Never extends LiteralExtraction

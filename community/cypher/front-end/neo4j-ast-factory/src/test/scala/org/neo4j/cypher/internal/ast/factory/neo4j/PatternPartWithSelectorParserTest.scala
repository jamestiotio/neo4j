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
package org.neo4j.cypher.internal.ast.factory.neo4j

import org.neo4j.cypher.internal.ast
import org.neo4j.cypher.internal.ast.AstConstructionTestSupport
import org.neo4j.cypher.internal.ast.Clause
import org.neo4j.cypher.internal.ast.CountExpression
import org.neo4j.cypher.internal.ast.ExistsExpression
import org.neo4j.cypher.internal.ast.Match
import org.neo4j.cypher.internal.ast.SubqueryCall
import org.neo4j.cypher.internal.cst.factory.neo4j.AntlrRule
import org.neo4j.cypher.internal.cst.factory.neo4j.Cst
import org.neo4j.cypher.internal.expressions.MatchMode
import org.neo4j.cypher.internal.expressions.NamedPatternPart
import org.neo4j.cypher.internal.expressions.PathPatternPart
import org.neo4j.cypher.internal.expressions.Pattern
import org.neo4j.cypher.internal.expressions.PatternPart
import org.neo4j.cypher.internal.expressions.PatternPartWithSelector
import org.neo4j.cypher.internal.expressions.PlusQuantifier
import org.neo4j.cypher.internal.expressions.QuantifiedPath
import org.neo4j.cypher.internal.util.InputPosition
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class PatternPartWithSelectorParserTest extends CypherFunSuite
    with ParserSyntaxTreeBase[Cst.Clause, ast.Clause]
    with AstConstructionTestSupport {

  implicit val javaccRule: JavaccRule[Clause] = JavaccRule.Clause
  implicit val antlrRule: AntlrRule[Cst.Clause] = AntlrRule.Clause

  private val selectors = Map(
    "ALL" -> allPathsSelector(),
    "ALL PATH" -> allPathsSelector(),
    "ALL PATHS" -> allPathsSelector(),
    "ANY" -> anyPathSelector("1"),
    "ANY PATH" -> anyPathSelector("1"),
    "ANY PATHS" -> anyPathSelector("1"),
    "ANY 2" -> anyPathSelector("2"),
    "ANY 2 PATH" -> anyPathSelector("2"),
    "ANY 2 PATHS" -> anyPathSelector("2"),
    "ANY SHORTEST" -> anyShortestPathSelector("1"),
    "ANY SHORTEST PATH" -> anyShortestPathSelector("1"),
    "ANY SHORTEST PATHS" -> anyShortestPathSelector("1"),
    "SHORTEST 2" -> anyShortestPathSelector("2"),
    "SHORTEST 2 PATH" -> anyShortestPathSelector("2"),
    "SHORTEST 2 PATHS" -> anyShortestPathSelector("2"),
    "ALL SHORTEST" -> allShortestPathsSelector(),
    "ALL SHORTEST PATH" -> allShortestPathsSelector(),
    "ALL SHORTEST PATHS" -> allShortestPathsSelector(),
    "SHORTEST 2 GROUP" -> shortestGroups("2"),
    "SHORTEST 2 GROUPS" -> shortestGroups("2"),
    "SHORTEST PATH GROUP" -> shortestGroups("1"),
    "SHORTEST PATHS GROUP" -> shortestGroups("1"),
    "SHORTEST PATH GROUPS" -> shortestGroups("1"),
    "SHORTEST PATHS GROUPS" -> shortestGroups("1"),
    "SHORTEST 2 PATH GROUP" -> shortestGroups("2"),
    "SHORTEST 2 PATH GROUPS" -> shortestGroups("2"),
    "SHORTEST 2 PATHS GROUP" -> shortestGroups("2"),
    "SHORTEST 2 PATHS GROUPS" -> shortestGroups("2")
  )

  test("MATCH $selector (a)-[r]->(b)") {
    selectors.foreach { case selector -> astNode =>
      withClue(s"selector = $selector") {
        parsing(s"MATCH $selector (a)-[r]->(b)") shouldGive {
          Match(
            optional = false,
            matchMode = MatchMode.default(pos),
            Pattern.ForMatch(Seq(
              PatternPartWithSelector(
                astNode,
                PatternPart(relationshipChain(nodePat(Some("a")), relPat(Some("r")), nodePat(Some("b"))))
              )
            ))(pos),
            Seq(),
            None
          )(pos)
        }
      }
    }
  }

  test("MATCH path = $selector ((a)-[r]->(b) WHERE a.prop = b.prop)") {
    selectors.foreach { case selector -> astNode =>
      withClue(s"selector = $selector") {
        parsing(s"MATCH path = $selector ((a)-[r]->(b) WHERE a.prop = b.prop)") shouldGive {
          Match(
            optional = false,
            matchMode = MatchMode.default(pos),
            Pattern.ForMatch(Seq(PatternPartWithSelector(
              selector = astNode,
              part = NamedPatternPart(
                varFor("path"),
                PathPatternPart(
                  parenthesizedPath(
                    relationshipChain(
                      nodePat(Some("a")),
                      relPat(Some("r")),
                      nodePat(Some("b"))
                    ),
                    Some(equals(prop("a", "prop"), prop("b", "prop")))
                  )
                )
              )(pos)
            )))(pos),
            List(),
            None
          )(pos)
        }
      }
    }
  }

  test("MATCH $selector ((a)-[r]->(b))+") {
    selectors.foreach { case selector -> astNode =>
      withClue(s"selector = $selector") {
        parsing(s"MATCH $selector ((a)-[r]->(b))+") shouldGive {
          Match(
            optional = false,
            matchMode = MatchMode.default(pos),
            Pattern.ForMatch(Seq(
              PatternPartWithSelector(
                selector = astNode,
                part = PathPatternPart(QuantifiedPath(
                  PathPatternPart(
                    relationshipChain(nodePat(Some("a")), relPat(Some("r")), nodePat(Some("b")))
                  ),
                  PlusQuantifier()(pos),
                  None
                )(pos))
              )
            ))(pos),
            Seq(),
            None
          )(pos)
        }
      }
    }
  }

  test("OPTIONAL MATCH $selector (a)-[r]->(b)") {
    selectors.foreach { case selector -> astNode =>
      withClue(s"selector = $selector") {
        parsing(s"OPTIONAL MATCH $selector (a)-[r]->(b)") shouldGive {
          Match(
            optional = true,
            matchMode = MatchMode.default(pos),
            Pattern.ForMatch(Seq(
              PatternPartWithSelector(
                selector = astNode,
                part = PathPatternPart(
                  relationshipChain(nodePat(Some("a")), relPat(Some("r")), nodePat(Some("b")))
                )
              )
            ))(pos),
            Seq(),
            None
          )(pos)
        }
      }
    }
  }

  test("RETURN COUNT { MATCH $selector (a)-[r]->(b) }") {
    selectors.foreach { case selector -> astNode =>
      withClue(s"selector = $selector") {
        parsing(s"RETURN COUNT { MATCH $selector (a)-[r]->(b) }") shouldGive {
          return_(
            returnItem(
              CountExpression(
                singleQuery(
                  Match(
                    optional = false,
                    matchMode = MatchMode.default(pos),
                    Pattern.ForMatch(Seq(
                      PatternPartWithSelector(
                        selector = astNode,
                        part = PathPatternPart(relationshipChain(
                          nodePat(Some("a")),
                          relPat(Some("r")),
                          nodePat(Some("b"))
                        ))
                      )
                    ))(pos),
                    Seq(),
                    None
                  )(pos)
                )
              )(pos, None, None),
              s"COUNT { MATCH $selector (a)-[r]->(b) }"
            )
          )
        }
      }
    }
  }

  test("RETURN EXISTS { MATCH $selector (a)-[r]->(b) }") {
    selectors.foreach { case selector -> astNode =>
      withClue(s"selector = $selector") {
        parsing(s"RETURN EXISTS { MATCH $selector (a)-[r]->(b) }") shouldGive {
          return_(
            returnItem(
              ExistsExpression(
                singleQuery(
                  Match(
                    optional = false,
                    matchMode = MatchMode.default(pos),
                    Pattern.ForMatch(Seq(
                      PatternPartWithSelector(
                        selector = astNode,
                        part = PathPatternPart(relationshipChain(
                          nodePat(Some("a")),
                          relPat(Some("r")),
                          nodePat(Some("b"))
                        ))
                      )
                    ))(pos),
                    Seq(),
                    None
                  )(pos)
                )
              )(pos, None, None),
              s"EXISTS { MATCH $selector (a)-[r]->(b) }"
            )
          )
        }
      }
    }
  }

  test("CALL { MATCH $selector (a)-[r]->(b) }") {
    selectors.foreach { case selector -> astNode =>
      withClue(s"selector = $selector") {
        parsing(s"CALL { MATCH $selector (a)-[r]->(b) }") shouldGive {
          SubqueryCall(
            singleQuery(
              Match(
                optional = false,
                matchMode = MatchMode.default(pos),
                Pattern.ForMatch(Seq(
                  PatternPartWithSelector(
                    selector = astNode,
                    part =
                      PathPatternPart(relationshipChain(nodePat(Some("a")), relPat(Some("r")), nodePat(Some("b"))))
                  )
                ))(pos),
                Seq(),
                None
              )(pos)
            ),
            None
          )(pos)
        }
      }
    }
  }

  // failing queries
  selectors.foreach { selector =>
    withClue(s"selector = ${selector._1}") {
      test(s"FOREACH (x in [ ${selector._1} (a)-->(b) | a ] | SET x.prop = 12 )") {
        failsToParse
      }
    }
  }

  selectors.foreach { selector =>
    withClue(s"selector = ${selector._1}") {
      test(s"RETURN reduce(sum=0, x IN [${selector._1} (a)-[:r]->(b) | b.prop] | sum + x)") {
        failsToParse
      }
    }
  }

  selectors.foreach { selector =>
    withClue(s"selector = ${selector._1}") {
      test(s"MATCH shortestPath(${selector._1} (a)-[r]->(b))") {
        failsToParse
      }
    }
  }

  selectors.foreach { selector =>
    withClue(s"selector = ${selector._1}") {
      test(s"MATCH allShortestPaths(${selector._1} (a)-[r]->(b))") {
        failsToParse
      }
    }
  }

  selectors.foreach { selector =>
    withClue(s"selector = ${selector._1}") {
      test(s"RETURN [ ${selector._1} (a)-->(b) | a ]") {
        failsToParse
      }
    }
  }

  // The tests below use `failsToParseOnlyJavaCC` because selectors in QPPs, PPPs, and update clauses are allowed by the grammar.
  // They are rejected by the AST factory later, but ANTLR doesn't go that far yet.

  test("MATCH $selector (() ($selector (a)-[r]->(b))* ()-->())") {
    selectors.foreach { case selector -> _ =>
      withClue(s"selector = $selector") {
        failsToParseOnlyJavaCC(s"MATCH $selector (() ($selector (a)-[r]->(b))* ()-->())")
      }
    }
  }

  test("CREATE $selector (a)-[r]->(b)") {
    selectors.foreach { case selector -> _ =>
      withClue(s"selector = $selector") {
        failsToParseOnlyJavaCC(s"CREATE $selector (a)-[r]->(b)")
      }
    }
  }

  test("MERGE $selector (a)-[r]->(b)") {
    selectors.foreach { case selector -> _ =>
      withClue(s"selector = $selector") {
        failsToParseOnlyJavaCC(s"MERGE $selector (a)-[r]->(b)")
      }
    }
  }

  test("ANY (a)-[:Rel]->(b)") {
    val clausesToTest = Seq(("CREATE", InputPosition(7, 1, 8)), ("MERGE", InputPosition(6, 1, 7)))
    for ((clause, pos) <- clausesToTest) withClue(clause) {
      val q = s"$clause $testName"
      assertFailsWithMessage(
        q,
        s"Path selectors such as `ANY 1 PATHS` cannot be used in a $clause clause, but only in a MATCH clause. ($pos)",
        failsOnlyJavaCC = true
      )
    }
  }

  // Selectors may not be placed inside QPPs and PPPs
  selectors.foreach { case (selector -> astSelector) =>
    Seq("+", "").foreach { quantifier =>
      test(s"MATCH ($selector (a)-[r]->(b))$quantifier") {
        val pathPatternKind = if (quantifier == "") "parenthesized" else "quantified"
        assertFailsWithMessageStart(
          testName,
          s"Path selectors such as `${astSelector.prettified}` are not supported within $pathPatternKind path patterns.",
          failsOnlyJavaCC = true
        )
      }

      if (quantifier == "+") {
        test(s"MATCH (() ($selector (a)-[r]->(b))$quantifier ()--())") {
          assertFailsWithMessageStart(
            testName,
            s"Path selectors such as `${astSelector.prettified}` are not supported within quantified path patterns.",
            failsOnlyJavaCC = true
          )
        }
      }
    }
  }

  Seq(
    "ANY ALL",
    "ANY -1",
    "ALL 2",
    "ALL PATHS 2",
    "ALL GROUPS",
    "ANY GROUP",
    "ALL SHORTEST GROUPS",
    "ANY SHORTEST GROUP",
    "SHORTEST -2 PATHS",
    "SHORTEST -0 PATHS",
    "SHORTEST 2 GROUP PATHS",
    "SHORTEST -1 GROUP"
  ).foreach { selector =>
    test(s"MATCH $selector (a)-[r]->(b)") {
      failsToParse
    }
  }
}

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

import org.antlr.v4.runtime.ParserRuleContext
import org.neo4j.cypher.internal.cst.factory.neo4j.AntlrRule
import org.neo4j.cypher.internal.cst.factory.neo4j.Cst
import org.neo4j.cypher.internal.expressions.NodePattern
import org.neo4j.cypher.internal.expressions.Variable
import org.neo4j.cypher.internal.util.ASTNode

class EscapedSymbolicNameParserTest extends ParserSyntaxTreeBase[ParserRuleContext, ASTNode] {

  test("escaped variable name") {
    implicit val javaccRule: JavaccRule[Variable] = JavaccRule.Variable
    implicit val antlrRule: AntlrRule[Cst.Variable] = AntlrRule.Variable

    parsing("`This isn\\'t a common variable`") shouldGive varFor("This isn\\'t a common variable")
    parsing("`a``b`") shouldGive varFor("a`b")
  }

  test("escaped label name") {
    implicit val javaccRule: JavaccRule[NodePattern] = JavaccRule.NodePattern
    implicit val antlrRule: AntlrRule[Cst.NodePattern] = AntlrRule.NodePattern

    parsing("(n:`Label`)") shouldGive nodePat(Some("n"), Some(labelLeaf("Label")))
    parsing("(n:`Label``123`)") shouldGive nodePat(Some("n"), Some(labelLeaf("Label`123")))
    parsing("(n:`````Label```)") shouldGive nodePat(Some("n"), Some(labelLeaf("``Label`")))

    assertFails("(n:`L`abel`)")
  }
}

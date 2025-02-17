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
package org.neo4j.cypher.internal.runtime.ast

import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.exceptions.InternalException

object ExpressionVariable {

  def cast(variable: LogicalVariable): ExpressionVariable =
    variable match {
      case ev: ExpressionVariable => ev
      case v =>
        throw new InternalException(
          s"Error during interpreted physical planning: expression variable '$v' has not been allocated"
        )
    }
}

abstract class ExpressionVariable(name: String) extends RuntimeVariable(name) {
  def offset: Int
  override def asCanonicalStringVal: String = name
}

/**
 * Variable which only lives for the duration of an expression evaluation.
 *
 * @param offset offset of the variable at runtime in the expression variable space
 * @param name name of the variable (used for debuging only at this point)
 */
case class TemporaryExpressionVariable(offset: Int, override val name: String) extends ExpressionVariable(name)

/**
 * Variable which lives for the duration of the query.
 *
 * @param offset offset of the variable at runtime in the expression variable space
 * @param name name of the variable (used for debuging only at this point)
 */
case class ConstantExpressionVariable(offset: Int, override val name: String) extends ExpressionVariable(name)

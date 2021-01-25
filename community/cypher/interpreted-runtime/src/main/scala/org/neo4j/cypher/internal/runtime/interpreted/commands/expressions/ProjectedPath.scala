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
package org.neo4j.cypher.internal.runtime.interpreted.commands.expressions

import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.interpreted.commands.AstNode
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.values.AnyValue

object ProjectedPath {

  type Projector = (ReadableRow, PathValueBuilder) => PathValueBuilder

  object nilProjector extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) = builder
  }

  case class singleNodeProjector(node: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) = {

      tailProjector(ctx, builder.addNode(ctx.getByName(node)))
    }
  }

  case class singleIncomingRelationshipProjector(rel: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) =
      tailProjector(ctx, builder.addIncomingRelationship(ctx.getByName(rel)))
  }

  case class singleRelationshipWithKnownTargetProjector(rel: String, target: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) =
      tailProjector(ctx, builder.addRelationship(ctx.getByName(rel)).addNode(ctx.getByName(target)))
  }

  case class singleOutgoingRelationshipProjector(rel: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) =
      tailProjector(ctx, builder.addOutgoingRelationship(ctx.getByName(rel)))
  }

  case class singleUndirectedRelationshipProjector(rel: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) =
      tailProjector(ctx, builder.addUndirectedRelationship(ctx.getByName(rel)))
  }

  case class multiIncomingRelationshipProjector(rels: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) =
      tailProjector(ctx, builder.addIncomingRelationships(ctx.getByName(rels)))
  }

  case class multiOutgoingRelationshipProjector(rels: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) =
      tailProjector(ctx, builder.addOutgoingRelationships(ctx.getByName(rels)))
  }

  case class multiUndirectedRelationshipProjector(rels: String, tailProjector: Projector) extends Projector {
    def apply(ctx: ReadableRow, builder: PathValueBuilder) =
      tailProjector(ctx, builder.addUndirectedRelationships(ctx.getByName(rels)))
  }
}

/*
 Expressions for materializing new paths (used by ronja)

 These expressions cannot be generated by the user directly
 */
case class ProjectedPath(symbolTableDependencies: Set[String], projector: ProjectedPath.Projector) extends Expression {
  override def apply(row: ReadableRow, state: QueryState): AnyValue = projector(row, state.clearPathValueBuilder).result()

  override def arguments: Seq[Expression] = Seq.empty

  override def children: Seq[AstNode[_]] = Seq.empty

  override def rewrite(f: Expression => Expression): Expression = f(this)
}



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
package org.neo4j.cypher.internal.runtime.spec.tests

import org.neo4j.cypher.internal.CypherRuntime
import org.neo4j.cypher.internal.RuntimeContext
import org.neo4j.cypher.internal.logical.plans.IndexOrderNone
import org.neo4j.cypher.internal.runtime.spec.Edition
import org.neo4j.cypher.internal.runtime.spec.LogicalQueryBuilder
import org.neo4j.cypher.internal.runtime.spec.RuntimeTestSuite

abstract class LetAntiSemiApplyTestBase[CONTEXT <: RuntimeContext](
  edition: Edition[CONTEXT],
  runtime: CypherRuntime[CONTEXT],
  sizeHint: Int
) extends RuntimeTestSuite[CONTEXT](edition, runtime) {

  test("should only write let = true when RHS is empty") {
    val inputRows = for {
      i <- 0 until sizeHint
    } yield Array[Any](i)

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "let")
      .letAntiSemiApply("let")
      .|.filter("x < 3")
      .|.argument("x")
      .input(variables = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, inputValues(inputRows: _*))

    // then
    runtimeResult should beColumns("x", "let").withRows((0 until sizeHint).map(i =>
      if (i >= 3) Array[AnyVal](i, true)
      else Array[AnyVal](i, false)
    ))
  }

  test("should write let = false for everything if rhs always is non empty") {
    // given
    val inputRows = for {
      i <- 0 until sizeHint
    } yield Array[Any](i)

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "let")
      .letAntiSemiApply("let")
      .|.filter("true")
      .|.argument("x")
      .input(variables = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, inputValues(inputRows: _*))

    // then
    runtimeResult should beColumns("x", "let").withRows((0 until sizeHint).map(i => Array[AnyVal](i, false)))
  }

  test("should write let = true for everything if rhs always is empty") {
    // given
    val inputRows = for {
      i <- 0 until sizeHint
    } yield Array[Any](i)

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "let")
      .letAntiSemiApply("let")
      .|.filter("false")
      .|.argument("x")
      .input(variables = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, inputValues(inputRows: _*))

    // then
    runtimeResult should beColumns("x", "let").withRows((0 until sizeHint).map(i => Array[AnyVal](i, true)))
  }

  test("should not run rhs if lhs is empty") {
    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .letAntiSemiApply("let")
      .|.filter("1/0 > 0")
      .|.argument("x")
      .input(variables = Seq("x"))
      .build()

    val runtimeResult = execute(logicalQuery, runtime, inputValues())

    // then
    // should not throw 1/0 exception
    runtimeResult should beColumns("x").withNoRows()
  }

  test("should support limit on top of let anti semi apply") {
    // given
    val nodesPerLabel = 50
    val (nodes, _) = given {
      bipartiteGraph(nodesPerLabel, "A", "B", "R")
    }
    val input = inputColumns(100000, 3, i => nodes(i % nodes.size)).stream()

    val limit = nodesPerLabel * nodesPerLabel - 1

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x", "let")
      .limit(limit)
      .letAntiSemiApply("let")
      .|.expandAll("(x)-->(y)")
      .|.argument()
      .input(nodes = Seq("x"))
      .build()

    // then
    val runtimeResult = execute(logicalQuery, runtime, input)

    runtimeResult should beColumns("x", "let").withRows(rowCount(limit))
    input.hasMore shouldBe true
  }

  test(
    "should handle aggregation on top of let anti semi apply with expand and limit and aggregation on rhs of apply"
  ) {
    // given
    val nodesPerLabel = 10
    given {
      bipartiteGraph(nodesPerLabel, "A", "B", "R")
    }

    val limit = nodesPerLabel / 2

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("counts")
      .aggregation(Seq.empty, Seq("count(x) AS counts"))
      .letAntiSemiApply("let")
      .|.aggregation(Seq.empty, Seq("count(*) AS c"))
      .|.limit(limit)
      .|.expand("(x)-[:R]->(y)")
      .|.argument("x")
      .nodeByLabelScan("x", "A", IndexOrderNone)
      .build()

    val runtimeResult = execute(logicalQuery, runtime)
    runtimeResult should beColumns("counts").withSingleRow(nodesPerLabel)
  }

  test("should handle aggregation with no grouping on top of let semi apply with expand on RHS") {
    // given
    val nodesPerLabel = 10
    val (aNodes, _) = given {
      bipartiteGraph(nodesPerLabel, "A", "B", "R")
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("xs")
      .aggregation(Seq.empty, Seq("count(x) AS xs"))
      .letAntiSemiApply("let")
      .|.expandAll("(x)-->(y)")
      .|.argument()
      .nodeByLabelScan("x", "A", IndexOrderNone)
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    runtimeResult should beColumns("xs").withSingleRow(aNodes.size)
  }

  test("should aggregate with grouping on top of let anti semi apply") {
    // given
    given {
      nodePropertyGraph(
        sizeHint,
        {
          case i if i % 4 == 0 => Map("prop" -> i)
        },
        "A"
      )
    }

    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("let", "xs")
      .aggregation(Seq("let AS let"), Seq("count(x) AS xs"))
      .letAntiSemiApply("let")
      .|.filter("x.prop IS NOT NULL")
      .|.argument("x")
      .nodeByLabelScan("x", "A", IndexOrderNone)
      .build()

    val runtimeResult = execute(logicalQuery, runtime)

    // then
    val expected = Seq(Array[Any](false, sizeHint * 1 / 4), Array[Any](true, sizeHint * 3 / 4))
    runtimeResult should beColumns("let", "xs").withRows(expected)
  }

  test("should handle let anti semi apply on top of distinct") {
    // when
    val logicalQuery = new LogicalQueryBuilder(this)
      .produceResults("x")
      .letAntiSemiApply("let")
      .|.argument()
      .distinct("x AS x")
      .input(variables = Seq("x"))
      .build()

    val result = execute(logicalQuery, runtime, inputValues(Array(1), Array(2), Array(1), Array(4)))

    // then
    result should beColumns("x").withRows(Seq(Array(1), Array(2), Array(4)))
  }
}

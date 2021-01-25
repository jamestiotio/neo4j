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
package org.neo4j.cypher.internal.runtime.interpreted.pipes.aggregation

import org.neo4j.cypher.internal.runtime.ReadableRow
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.NumericHelper
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.exceptions.InvalidArgumentException
import org.neo4j.memory.MemoryTracker
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.Values

abstract class PercentileFunction(val value: Expression, val percentile: Expression, memoryTracker: MemoryTracker) extends AggregationFunction
                                                                                     with NumericExpressionOnly {

  protected var temp: Vector[AnyValue] = Vector[AnyValue]() // TODO: Use heap tracking collection
  protected var count: Int = 0
  protected var perc: Double = 0

  override def apply(data: ReadableRow, state: QueryState) {
    actOnNumber(value(data, state), number => {
      if (count < 1) {
        perc = NumericHelper.asDouble(percentile(data, state)).doubleValue()
        if (perc < 0 || perc > 1.0)
          throw new InvalidArgumentException(
            s"Invalid input '$perc' is not a valid argument, must be a number in the range 0.0 to 1.0")
      }
      count += 1
      temp = temp :+ number
      memoryTracker.allocateHeap(number.estimatedHeapUsage())
    })
  }
}

class PercentileContFunction(value: Expression, percentile: Expression, memoryTracker: MemoryTracker)
  extends PercentileFunction(value, percentile, memoryTracker) {

  def name = "PERCENTILE_CONT"

  override def result(state: QueryState): AnyValue = {
    temp = temp.sortBy((num: AnyValue) => NumericHelper.asDouble(num).doubleValue())

    if (perc == 1.0 || count == 1) {
      temp.last
    } else if (count > 1) {
      val floatIdx = perc * (count - 1)
      val floor = floatIdx.toInt
      val ceil = math.ceil(floatIdx).toInt
      if (ceil == floor || floor == count - 1) temp(floor)
      else Values.doubleValue(NumericHelper.asDouble(temp(floor)).doubleValue() * (ceil - floatIdx) +
        NumericHelper.asDouble(temp(ceil)).doubleValue() * (floatIdx - floor))
    } else {
      Values.NO_VALUE
    }
  }
}

class PercentileDiscFunction(value: Expression, percentile: Expression, memoryTracker: MemoryTracker)
  extends PercentileFunction(value, percentile, memoryTracker) {

  def name = "PERCENTILE_DISC"

  override def result(state: QueryState): AnyValue = {
    temp = temp.sortBy((num: AnyValue) => NumericHelper.asDouble(num).doubleValue())

    if (perc == 1.0 || count == 1) {
      temp.last
    } else if (count > 1) {
      val floatIdx = perc * count
      var idx = floatIdx.toInt
      idx = if (floatIdx != idx || idx == 0) idx
      else idx - 1
      temp(idx)
    } else {
      Values.NO_VALUE
    }
  }
}

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
package org.neo4j.cypher.internal.runtime.interpreted.commands.showcommands

import org.neo4j.configuration.GraphDatabaseSettings
import org.neo4j.cypher.internal.ast.CommandResultItem
import org.neo4j.cypher.internal.ast.ShowColumn
import org.neo4j.cypher.internal.runtime.ClosingIterator
import org.neo4j.cypher.internal.runtime.CypherRow
import org.neo4j.cypher.internal.runtime.QueryContext
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.Expression
import org.neo4j.cypher.internal.runtime.interpreted.pipes.QueryState
import org.neo4j.exceptions.ParameterWrongTypeException
import org.neo4j.values.AnyValue
import org.neo4j.values.storable.StringValue
import org.neo4j.values.virtual.ListValue

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

import scala.jdk.CollectionConverters.IteratorHasAsScala

abstract class Command(columns: List[ShowColumn]) {

  def originalNameRows(state: QueryState, baseRow: CypherRow): ClosingIterator[Map[String, AnyValue]]

  final def rows(state: QueryState, baseRow: CypherRow): ClosingIterator[Map[String, AnyValue]] = {
    originalNameRows(state, baseRow).map { map =>
      columns.map {
        case ShowColumn(lv, _, originalName) => lv.name -> map(originalName)
      }.toMap
    }
  }

  protected def getConfiguredTimeZone(ctx: QueryContext): ZoneId =
    ctx.getConfig.get(GraphDatabaseSettings.db_timezone).getZoneId

  protected def formatTime(startTime: Long, zoneId: ZoneId): OffsetDateTime =
    OffsetDateTime.ofInstant(Instant.ofEpochMilli(startTime), zoneId)
}

object Command {

  def extractNames(names: Either[List[String], Expression], state: QueryState, baseRow: CypherRow): List[String] = {
    // Get the string values and make sure we don't have duplicates
    names match {
      case Left(ls) => ls.toSet.toList
      case Right(e) =>
        e(baseRow, state) match {
          case s: StringValue => List(s.stringValue())
          case l: ListValue =>
            val list = l.iterator().asScala
            list.map {
              case s: StringValue => s.stringValue()
              case x              => throw new ParameterWrongTypeException(s"Expected a string, but got: ${x.toString}")
            }.toSet.toList
          case x =>
            throw new ParameterWrongTypeException(s"Expected a string or a list of strings, but got: ${x.toString}")
        }
    }
  }
}

abstract class TransactionCommand(defaultColumns: List[ShowColumn], yieldColumns: List[CommandResultItem])
    extends Command(TransactionCommand.getColumns(defaultColumns, yieldColumns)) {

  // Update to rename columns which have been renamed in YIELD
  def updateRowsWithPotentiallyRenamedColumns(rows: List[Map[String, AnyValue]]): List[Map[String, AnyValue]] =
    rows.map(row =>
      row.map { case (key, value) =>
        val newKey =
          yieldColumns.find(c => c.originalName.equals(key)).map(_.aliasedVariable.name).getOrElse(key)
        (newKey, value)
      }
    )
}

object TransactionCommand {

  // Make sure to get the yielded columns (and their potential renames) if YIELD was specified
  // otherwise get the default columns
  private def getColumns(defaultColumns: List[ShowColumn], yieldColumns: List[CommandResultItem]): List[ShowColumn] = {
    if (yieldColumns.nonEmpty) yieldColumns.map(c => {
      val column = defaultColumns.find(s => s.variable.name.equals(c.originalName)).get
      ShowColumn(c.aliasedVariable, column.cypherType, c.aliasedVariable.name)
    })
    else defaultColumns
  }
}

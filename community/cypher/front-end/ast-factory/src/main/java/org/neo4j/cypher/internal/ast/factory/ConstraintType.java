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
package org.neo4j.cypher.internal.ast.factory;

public enum ConstraintType {
    NODE_UNIQUE("IS NODE UNIQUE"),
    REL_UNIQUE("IS RELATIONSHIP UNIQUE"),
    NODE_KEY("IS NODE KEY"),
    REL_KEY("IS RELATIONSHIP KEY"),
    NODE_EXISTS("EXISTS"),
    NODE_IS_NOT_NULL("IS NOT NULL"),
    REL_EXISTS("EXISTS"),
    REL_IS_NOT_NULL("IS NOT NULL"),
    NODE_IS_TYPED("IS TYPED"),
    REL_IS_TYPED("IS TYPED");

    private final String description;

    ConstraintType(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}

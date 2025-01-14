/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.router.query;

import java.util.Optional;
import java.util.function.Supplier;
import org.neo4j.cypher.internal.ast.CatalogName;

/**
 * Parse a query into a target database override
 *
 * A query can override the target database if it:
 * - contains a USE-clause, or
 * - contains a system admin command
 */
public interface QueryTargetParser {

    Optional<CatalogName> parseQueryTarget(Query query);

    interface Cache {
        Optional<CatalogName> computeIfAbsent(String query, Supplier<Optional<CatalogName>> supplier);
    }

    Cache NO_CACHE = new Cache() {
        @Override
        public Optional<CatalogName> computeIfAbsent(String query, Supplier<Optional<CatalogName>> supplier) {
            return supplier.get();
        }
    };
}

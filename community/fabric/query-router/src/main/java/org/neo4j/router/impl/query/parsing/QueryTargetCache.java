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
package org.neo4j.router.impl.query.parsing;

import java.util.Optional;
import java.util.function.Supplier;
import org.neo4j.cypher.internal.ast.CatalogName;
import org.neo4j.cypher.internal.cache.CacheSize;
import org.neo4j.cypher.internal.cache.CacheTracer;
import org.neo4j.cypher.internal.cache.CaffeineCacheFactory;
import org.neo4j.cypher.internal.cache.LFUCache;
import org.neo4j.function.Observable;
import org.neo4j.router.query.QueryTargetParser;

public class QueryTargetCache implements QueryTargetParser.Cache {

    private final LFUCache<String, Optional<CatalogName>> cache;

    public QueryTargetCache(
            CaffeineCacheFactory cacheFactory, Observable<Integer> cacheSize, CacheTracer<String> tracer) {
        this.cache = new LFUCache<>(cacheFactory, new CacheSize.Dynamic(cacheSize), tracer);
    }

    public QueryTargetCache(CaffeineCacheFactory cacheFactory, int cacheSize, CacheTracer<String> tracer) {
        this.cache = new LFUCache<>(cacheFactory, new CacheSize.Static(cacheSize), tracer);
    }

    @Override
    public Optional<CatalogName> computeIfAbsent(String query, Supplier<Optional<CatalogName>> supplier) {
        return cache.computeIfAbsent(query, supplier::get);
    }
}

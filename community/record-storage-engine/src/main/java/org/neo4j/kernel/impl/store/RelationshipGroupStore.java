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
package org.neo4j.kernel.impl.store;

import org.eclipse.collections.api.set.ImmutableSet;

import java.io.File;
import java.nio.file.OpenOption;

import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.internal.id.IdGeneratorFactory;
import org.neo4j.internal.id.IdType;
import org.neo4j.io.pagecache.PageCache;
import org.neo4j.io.pagecache.tracing.cursor.PageCursorTracer;
import org.neo4j.kernel.impl.store.format.RecordFormats;
import org.neo4j.kernel.impl.store.record.RelationshipGroupRecord;
import org.neo4j.logging.LogProvider;

public class RelationshipGroupStore extends CommonAbstractStore<RelationshipGroupRecord,IntStoreHeader>
{
    public static final String TYPE_DESCRIPTOR = "RelationshipGroupStore";

    public RelationshipGroupStore(
            File file,
            File idFile,
            Config config,
            IdGeneratorFactory idGeneratorFactory,
            PageCache pageCache,
            LogProvider logProvider,
            RecordFormats recordFormats,
            ImmutableSet<OpenOption> openOptions )
    {
        super( file, idFile, config, IdType.RELATIONSHIP_GROUP, idGeneratorFactory, pageCache, logProvider, TYPE_DESCRIPTOR,
                recordFormats.relationshipGroup(), new IntStoreHeaderFormat( config.get( GraphDatabaseSettings.dense_node_threshold ) ),
                recordFormats.storeVersion(), openOptions );
    }

    @Override
    public <FAILURE extends Exception> void accept( Processor<FAILURE> processor, RelationshipGroupRecord record, PageCursorTracer cursorTracer )
            throws FAILURE
    {
        processor.processRelationshipGroup( this, record, cursorTracer );
    }
}

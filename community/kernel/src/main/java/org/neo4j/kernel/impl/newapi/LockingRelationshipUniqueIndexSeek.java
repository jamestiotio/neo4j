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
package org.neo4j.kernel.impl.newapi;

import static org.neo4j.kernel.impl.locking.ResourceIds.indexEntryResourceId;
import static org.neo4j.lock.ResourceType.INDEX_ENTRY;

import org.neo4j.internal.kernel.api.PropertyIndexQuery;
import org.neo4j.internal.kernel.api.RelationshipValueIndexCursor;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotApplicableKernelException;
import org.neo4j.internal.kernel.api.exceptions.schema.IndexNotFoundKernelException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.kernel.api.StatementConstants;
import org.neo4j.kernel.api.index.ValueIndexReader;
import org.neo4j.kernel.impl.locking.LockManager;
import org.neo4j.lock.LockTracer;

class LockingRelationshipUniqueIndexSeek {
    static <CURSOR extends RelationshipValueIndexCursor> long apply(
            LockManager.Client locks,
            LockTracer lockTracer,
            CURSOR cursor,
            UniqueRelationshipIndexSeeker<CURSOR> relationshipIndexSeeker,
            Read read,
            IndexDescriptor index,
            PropertyIndexQuery.ExactPredicate[] predicates)
            throws IndexNotApplicableKernelException, IndexNotFoundKernelException {
        int[] entityTokenIds = index.schema().getEntityTokenIds();
        if (entityTokenIds.length != 1) {
            throw new IndexNotApplicableKernelException("Multi-token index " + index + " does not support uniqueness.");
        }
        long indexEntryId = indexEntryResourceId(entityTokenIds[0], predicates);

        // First try to find relationship under a shared lock
        // if not found upgrade to exclusive and try again
        locks.acquireShared(lockTracer, INDEX_ENTRY, indexEntryId);
        try (IndexReaders readers = new IndexReaders(index, read)) {
            relationshipIndexSeeker.relationshipIndexSeekWithFreshIndexReader(
                    cursor, readers.createReader(), predicates);
            if (!cursor.next()) {
                locks.releaseShared(INDEX_ENTRY, indexEntryId);
                locks.acquireExclusive(lockTracer, INDEX_ENTRY, indexEntryId);
                relationshipIndexSeeker.relationshipIndexSeekWithFreshIndexReader(
                        cursor, readers.createReader(), predicates);
                if (cursor.next()) // we found it under the exclusive lock
                {
                    // downgrade to a shared lock
                    locks.acquireShared(lockTracer, INDEX_ENTRY, indexEntryId);
                    locks.releaseExclusive(INDEX_ENTRY, indexEntryId);
                    return cursor.relationshipReference();
                } else {
                    return StatementConstants.NO_SUCH_RELATIONSHIP;
                }
            }

            return cursor.relationshipReference();
        }
    }

    @FunctionalInterface
    interface UniqueRelationshipIndexSeeker<CURSOR extends RelationshipValueIndexCursor> {
        void relationshipIndexSeekWithFreshIndexReader(
                CURSOR cursor, ValueIndexReader indexReader, PropertyIndexQuery.ExactPredicate... predicates)
                throws IndexNotApplicableKernelException;
    }
}

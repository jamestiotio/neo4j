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
package org.neo4j.storageengine.api.txstate.validation;

import static org.neo4j.storageengine.api.txstate.validation.TransactionValidationResource.EMPTY_VALIDATION_RESOURCE;

import java.util.Collection;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.kernel.impl.api.LeaseClient;
import org.neo4j.lock.LockTracer;
import org.neo4j.storageengine.api.StorageCommand;

/**
 * Transaction validator that is invoked as part of transaction commit preparation for write transactions
 * Its invoked for when we already have a set of commands ready but commit process for the chunk was not yet started.
 */
@FunctionalInterface
public interface TransactionValidator {
    TransactionValidator EMPTY_VALIDATOR =
            (commands, transactionSequenceNumber, cursorContext, leaseClient, lockTracer) -> EMPTY_VALIDATION_RESOURCE;

    TransactionValidationResource validate(
            Collection<StorageCommand> commands,
            long transactionSequenceNumber,
            CursorContext cursorContext,
            LeaseClient leaseClient,
            LockTracer lockTracer);
}

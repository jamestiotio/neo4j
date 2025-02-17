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
package org.neo4j.fabric.transaction.parent;

import java.util.function.Supplier;
import org.neo4j.fabric.executor.FabricException;
import org.neo4j.fabric.executor.Location;
import org.neo4j.fabric.transaction.TransactionMode;
import org.neo4j.kernel.api.exceptions.Status;

/**
 * Represents a transaction which is made up of multiple child transactions
 */
public interface CompoundTransaction<Child extends ChildTransaction> {

    void commit();

    void rollback();

    /**
     * Returns {@code true} if the transaction has been marked for termination by the caller,
     * {@code false} otherwise (For instance, if the transaction has been already terminated by someone else).
     */
    boolean markForTermination(Status reason);

    /**
     * Registers a child transaction created by the supplier.
     * The mode determines how the child transaction is treated.
     * <ul>
     *   <li>{@link TransactionMode#DEFINITELY_WRITE}: Maximum 1 child transactions can exist with this mode. Upgrading to write is a no-op.</li>
     *   <li>{@link TransactionMode#MAYBE_WRITE}: Multiple child transactions can exist with this mode. Upgrading to write is possible.</li>
     *   <li>{@link TransactionMode#DEFINITELY_READ}: Multiple child transactions can exist with this mode. Upgrading to write is not possible.</li>
     * </ul>
     */
    <Tx extends Child> Tx registerNewChildTransaction(
            Location location, TransactionMode mode, Supplier<Tx> transactionSupplier) throws FabricException;

    /**
     * Upgrades the childTransaction to become the single DEFINITELY_WRITE transaction
     * of this parent transaction, if possible.
     */
    <Tx extends Child> void upgradeToWritingTransaction(Tx childTransaction) throws FabricException;
}

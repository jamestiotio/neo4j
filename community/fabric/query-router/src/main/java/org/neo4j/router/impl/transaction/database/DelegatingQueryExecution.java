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
package org.neo4j.router.impl.transaction.database;

import org.neo4j.graphdb.ExecutionPlanDescription;
import org.neo4j.graphdb.Notification;
import org.neo4j.graphdb.QueryExecutionType;
import org.neo4j.kernel.impl.query.QueryExecution;

public class DelegatingQueryExecution implements QueryExecution {
    private final QueryExecution queryExecution;

    protected DelegatingQueryExecution(QueryExecution queryExecution) {
        this.queryExecution = queryExecution;
    }

    @Override
    public boolean executionMetadataAvailable() {
        return queryExecution.executionMetadataAvailable();
    }

    @Override
    public QueryExecutionType executionType() {
        return queryExecution.executionType();
    }

    @Override
    public ExecutionPlanDescription executionPlanDescription() {
        return queryExecution.executionPlanDescription();
    }

    @Override
    public Iterable<Notification> getNotifications() {
        return queryExecution.getNotifications();
    }

    @Override
    public String[] fieldNames() {
        return queryExecution.fieldNames();
    }

    @Override
    public void awaitCleanup() {
        queryExecution.awaitCleanup();
    }

    @Override
    public void request(long numberOfRecords) throws Exception {
        queryExecution.request(numberOfRecords);
    }

    @Override
    public void cancel() {
        queryExecution.cancel();
    }

    @Override
    public boolean await() throws Exception {
        return queryExecution.await();
    }

    @Override
    public void consumeAll() throws Exception {
        queryExecution.consumeAll();
    }
}

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
package org.neo4j.kernel.api.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.neo4j.graphdb.Label.label;
import static org.neo4j.internal.kernel.api.IndexQueryConstraints.constrained;
import static org.neo4j.internal.kernel.api.IndexQueryConstraints.unconstrained;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.allEntries;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.exact;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.exists;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.range;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.stringContains;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.stringPrefix;
import static org.neo4j.internal.kernel.api.PropertyIndexQuery.stringSuffix;
import static org.neo4j.io.pagecache.context.CursorContext.NULL_CONTEXT;
import static org.neo4j.values.storable.Values.stringValue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.exceptions.KernelException;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Label;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.Transaction;
import org.neo4j.internal.kernel.api.Cursor;
import org.neo4j.internal.kernel.api.IndexMonitor;
import org.neo4j.internal.kernel.api.IndexQueryConstraints;
import org.neo4j.internal.kernel.api.IndexReadSession;
import org.neo4j.internal.kernel.api.NodeValueIndexCursor;
import org.neo4j.internal.kernel.api.PropertyIndexQuery;
import org.neo4j.internal.kernel.api.RelationshipValueIndexCursor;
import org.neo4j.internal.kernel.api.SchemaWrite;
import org.neo4j.internal.kernel.api.TokenRead;
import org.neo4j.internal.kernel.api.TokenWrite;
import org.neo4j.internal.kernel.api.exceptions.InvalidTransactionTypeKernelException;
import org.neo4j.internal.schema.IndexDescriptor;
import org.neo4j.internal.schema.IndexOrder;
import org.neo4j.internal.schema.IndexPrototype;
import org.neo4j.internal.schema.IndexProviderDescriptor;
import org.neo4j.internal.schema.IndexType;
import org.neo4j.internal.schema.SchemaDescriptor;
import org.neo4j.internal.schema.SchemaDescriptors;
import org.neo4j.kernel.api.impl.schema.TextIndexProvider;
import org.neo4j.kernel.impl.coreapi.TransactionImpl;
import org.neo4j.kernel.impl.newapi.KernelAPIReadTestBase;
import org.neo4j.kernel.impl.newapi.ReadTestSupport;
import org.neo4j.memory.EmptyMemoryTracker;
import org.neo4j.monitoring.Monitors;

public class TextIndexQueryTest extends KernelAPIReadTestBase<ReadTestSupport> {
    private static final Label PERSON = label("PERSON");
    private static final RelationshipType FRIEND = RelationshipType.withName("FRIEND");
    private static final IndexAccessMonitor MONITOR = new IndexAccessMonitor();
    static final String NODE_INDEX_NAME = "some_node_text_index";
    private static final String REL_INDEX_NAME = "some_rel_text_index";
    static final String NAME = "name";
    private static final String ADDRESS = "address";
    private static final String SINCE = "since";

    long mikeNodeId;
    long noahNodeId;

    @BeforeEach
    void setup() {
        MONITOR.reset();
    }

    @Override
    public void createTestGraph(GraphDatabaseService db) {
        try (var tx = db.beginTx()) {
            TokenWrite tokenWrite = getTokenWrite(tx);
            tokenWrite.labelGetOrCreateForName(PERSON.name());
            tokenWrite.relationshipTypeGetOrCreateForName(FRIEND.name());
            tokenWrite.propertyKeyGetOrCreateForName(NAME);
            tokenWrite.propertyKeyGetOrCreateForName(SINCE);
            tx.commit();
        } catch (KernelException e) {
            throw new RuntimeException(e);
        }

        try (var tx = db.beginTx()) {
            TokenRead tokenRead = getTokenRead(tx);
            SchemaWrite schemaWrite = getSchemaWrite(tx);
            schemaWrite.indexCreate(IndexPrototype.forSchema(asSchemaDescriptor(tokenRead, PERSON, NAME))
                    .withName(NODE_INDEX_NAME)
                    .withIndexType(IndexType.TEXT)
                    .withIndexProvider(getIndexProviderDescriptor()));
            schemaWrite.indexCreate(IndexPrototype.forSchema(asSchemaDescriptor(tokenRead, FRIEND, SINCE))
                    .withName(REL_INDEX_NAME)
                    .withIndexType(IndexType.TEXT)
                    .withIndexProvider(getIndexProviderDescriptor()));
            tx.commit();
        } catch (KernelException e) {
            throw new RuntimeException(e);
        }

        try (var tx = db.beginTx()) {
            var mike = tx.createNode(PERSON);
            mike.setProperty(NAME, "Mike Smith");
            mike.setProperty(ADDRESS, "United Kingdom");
            mikeNodeId = mike.getId();

            var james = tx.createNode(PERSON);
            james.setProperty(NAME, "James Smith");
            james.setProperty(ADDRESS, "Heathrow, United Kingdom");
            james.createRelationshipTo(mike, FRIEND).setProperty(SINCE, "3 years");

            var smith = tx.createNode(PERSON);
            smith.setProperty(NAME, "Smith James Luke");
            smith.setProperty(ADDRESS, "United Emirates");
            smith.createRelationshipTo(mike, FRIEND).setProperty(SINCE, "2 years, 2 months");
            smith.createRelationshipTo(james, FRIEND).setProperty(SINCE, "2 years");

            var o = tx.createNode(PERSON);
            o.setProperty(NAME, "o");

            var bo = tx.createNode(PERSON);
            bo.setProperty(NAME, "Bo");

            var bob = tx.createNode(PERSON);
            bob.setProperty(NAME, "Bob");

            var noah = tx.createNode(PERSON);
            noah.setProperty(NAME, "Noah");
            noah.createRelationshipTo(mike, FRIEND).setProperty(SINCE, "4 years");
            noahNodeId = noah.getId();

            var alex = tx.createNode(PERSON);
            alex.setProperty(NAME, "Alex");

            var matt = tx.createNode(PERSON);
            matt.setProperty(NAME, 42);
            matt.createRelationshipTo(mike, FRIEND).setProperty(SINCE, 694_717_800);

            var jack = tx.createNode(PERSON);
            jack.setProperty(NAME, "77");
            jack.createRelationshipTo(matt, FRIEND).setProperty(SINCE, "1 year");

            var anonymous = tx.createNode(PERSON);
            anonymous.setProperty(NAME, "");
            anonymous.createRelationshipTo(jack, FRIEND).setProperty(SINCE, "");

            var x = tx.createNode(PERSON);
            x.setProperty(NAME, "");

            tx.commit();
        }
    }

    @Test
    void shouldRejectInvalidConstraints() {
        var query = exact(token.propertyKey(NAME), "Mike Smith");
        var needsValue = constrained(IndexOrder.NONE, true);

        assertThatThrownBy(() -> indexedNodes(needsValue, query))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("%s index has no value capability", IndexType.TEXT);

        assertThatThrownBy(() -> indexedRelations(needsValue, query))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("%s index has no value capability", IndexType.TEXT);
    }

    @Test
    void shouldFindNodes() throws Exception {
        assertThat(indexedNodes(allEntries())).isEqualTo(11);
        assertThat(indexedNodes(exact(token.propertyKey(NAME), "Mike Smith"))).isEqualTo(1);
        assertThat(indexedNodes(exact(token.propertyKey(NAME), "Unknown"))).isEqualTo(0);
        assertThat(indexedNodes(exact(token.propertyKey(NAME), "42"))).isEqualTo(0);
        assertThat(indexedNodes(exact(token.propertyKey(NAME), "77"))).isEqualTo(1);
        assertThat(indexedNodes(exact(token.propertyKey(NAME), "o"))).isEqualTo(1);
        assertThat(indexedNodes(exact(token.propertyKey(NAME), "Bo"))).isEqualTo(1);
        assertThat(indexedNodes(exact(token.propertyKey(NAME), "Bob"))).isEqualTo(1);
        assertThat(indexedNodes(stringPrefix(token.propertyKey(NAME), stringValue("Smith"))))
                .isEqualTo(1);
        assertThat(indexedNodes(stringPrefix(token.propertyKey(NAME), stringValue("o"))))
                .isEqualTo(1);
        assertThat(indexedNodes(stringPrefix(token.propertyKey(NAME), stringValue("Bo"))))
                .isEqualTo(2);
        assertThat(indexedNodes(stringPrefix(token.propertyKey(NAME), stringValue(""))))
                .isEqualTo(11);
        assertThat(indexedNodes(stringContains(token.propertyKey(NAME), stringValue("Smith"))))
                .isEqualTo(3);
        assertThat(indexedNodes(stringContains(token.propertyKey(NAME), stringValue("ob"))))
                .isEqualTo(1);
        assertThat(indexedNodes(stringContains(token.propertyKey(NAME), stringValue("Bo"))))
                .isEqualTo(2);
        assertThat(indexedNodes(stringContains(token.propertyKey(NAME), stringValue(""))))
                .isEqualTo(11);
        assertThat(indexedNodes(stringSuffix(token.propertyKey(NAME), stringValue("Smith"))))
                .isEqualTo(2);
        assertThat(indexedNodes(stringSuffix(token.propertyKey(NAME), stringValue("b"))))
                .isEqualTo(1);
        assertThat(indexedNodes(stringSuffix(token.propertyKey(NAME), stringValue("o"))))
                .isEqualTo(2);
        assertThat(indexedNodes(stringSuffix(token.propertyKey(NAME), stringValue(""))))
                .isEqualTo(11);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "Mike Smith", true, "Noah", true)))
                .isEqualTo(2);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "", true, "James", true)))
                .isEqualTo(6);
    }

    @Test
    void shouldFindForRangesWithEmptyString() throws Exception {
        assertThat(indexedNodes(range(token.propertyKey(NAME), "", true, "Noah", true)))
                .isEqualTo(9);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "", false, "Noah", false)))
                .isEqualTo(6);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "Noah", true, "", true)))
                .isEqualTo(0);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "Noah", false, "", false)))
                .isEqualTo(0);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "", true, "", true)))
                .isEqualTo(2);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "", false, "", false)))
                .isEqualTo(0);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "", false, "", true)))
                .isEqualTo(0);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "", true, "", false)))
                .isEqualTo(0);
    }

    @Test
    void shouldFindForRangesWithNull() throws Exception {
        // null means open range and doesn't care about inclusion
        assertThat(indexedNodes(range(token.propertyKey(NAME), (String) null, true, null, true)))
                .isEqualTo(11);
        assertThat(indexedNodes(range(token.propertyKey(NAME), (String) null, true, null, false)))
                .isEqualTo(11);
        assertThat(indexedNodes(range(token.propertyKey(NAME), (String) null, false, null, true)))
                .isEqualTo(11);
        assertThat(indexedNodes(range(token.propertyKey(NAME), (String) null, false, null, false)))
                .isEqualTo(11);
        assertThat(indexedNodes(range(token.propertyKey(NAME), null, true, "Noah", true)))
                .isEqualTo(9);
        assertThat(indexedNodes(range(token.propertyKey(NAME), null, true, "Noah", false)))
                .isEqualTo(8);
        assertThat(indexedNodes(range(token.propertyKey(NAME), null, false, "Noah", true)))
                .isEqualTo(9);
        assertThat(indexedNodes(range(token.propertyKey(NAME), null, false, "Noah", false)))
                .isEqualTo(8);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "Noah", true, null, true)))
                .isEqualTo(3);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "Noah", true, null, false)))
                .isEqualTo(3);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "Noah", false, null, true)))
                .isEqualTo(2);
        assertThat(indexedNodes(range(token.propertyKey(NAME), "Noah", false, null, false)))
                .isEqualTo(2);
    }

    @Test
    void shouldFindRelations() throws Exception {
        assertThat(indexedRelations(allEntries())).isEqualTo(6);
        assertThat(indexedRelations(exact(token.propertyKey(SINCE), "3 years"))).isEqualTo(1);
        assertThat(indexedRelations(exact(token.propertyKey(SINCE), "694717800")))
                .isEqualTo(0);
        assertThat(indexedRelations(exact(token.propertyKey(SINCE), "Unknown"))).isEqualTo(0);
        assertThat(indexedRelations(stringContains(token.propertyKey(SINCE), stringValue("years"))))
                .isEqualTo(4);
        assertThat(indexedRelations(stringContains(token.propertyKey(SINCE), stringValue(""))))
                .isEqualTo(6);
        assertThat(indexedRelations(stringSuffix(token.propertyKey(SINCE), stringValue("years"))))
                .isEqualTo(3);
        assertThat(indexedRelations(stringSuffix(token.propertyKey(SINCE), stringValue(""))))
                .isEqualTo(6);
        assertThat(indexedRelations(stringPrefix(token.propertyKey(SINCE), stringValue("2 years"))))
                .isEqualTo(2);
        assertThat(indexedRelations(stringPrefix(token.propertyKey(SINCE), stringValue(""))))
                .isEqualTo(6);
        assertThat(indexedRelations(range(token.propertyKey(SINCE), "2 years", true, "3 years", true)))
                .isEqualTo(3);
        assertThat(indexedRelations(range(token.propertyKey(SINCE), "", true, "3", true)))
                .isEqualTo(4);
    }

    @Test
    void shouldThrowOnExistsQuery() {
        PropertyIndexQuery query = exists(token.propertyKey(SINCE));

        assertThatThrownBy(() -> indexedNodes(query))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Index query not supported for %s index. Query: %s",
                        org.neo4j.graphdb.schema.IndexType.TEXT, query);

        assertThatThrownBy(() -> indexedRelations(exists(token.propertyKey(SINCE))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Index query not supported for %s index. Query: %s",
                        org.neo4j.graphdb.schema.IndexType.TEXT, query);
    }

    @Test
    void shouldThrowOnNonTextValues() {
        PropertyIndexQuery name = exact(token.propertyKey(SINCE), 77);
        assertThatThrownBy(() -> indexedNodes(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Index query not supported for %s index. Query: %s",
                        org.neo4j.graphdb.schema.IndexType.TEXT, name);

        PropertyIndexQuery since = exact(token.propertyKey(SINCE), 694_717_800);
        assertThatThrownBy(() -> indexedRelations(since))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Index query not supported for %s index. Query: %s",
                        org.neo4j.graphdb.schema.IndexType.TEXT, since);
    }

    protected IndexProviderDescriptor getIndexProviderDescriptor() {
        return TextIndexProvider.DESCRIPTOR;
    }

    private long indexedNodes(PropertyIndexQuery... query) throws Exception {
        return indexedNodes(unconstrained(), query);
    }

    private long indexedNodes(IndexQueryConstraints constraints, PropertyIndexQuery... query) throws Exception {
        MONITOR.reset();
        IndexReadSession index = read.indexReadSession(getIndex(NODE_INDEX_NAME));
        try (NodeValueIndexCursor cursor =
                cursors.allocateNodeValueIndexCursor(NULL_CONTEXT, EmptyMemoryTracker.INSTANCE)) {
            read.nodeIndexSeek(tx.queryContext(), index, cursor, constraints, query);
            assertThat(MONITOR.accessed(IndexType.TEXT)).isEqualTo(1);
            return count(cursor);
        }
    }

    private long indexedRelations(PropertyIndexQuery... query) throws Exception {
        return indexedRelations(unconstrained(), query);
    }

    private long indexedRelations(IndexQueryConstraints constraints, PropertyIndexQuery... query) throws Exception {
        MONITOR.reset();
        IndexReadSession index = read.indexReadSession(getIndex(REL_INDEX_NAME));
        try (RelationshipValueIndexCursor cursor =
                cursors.allocateRelationshipValueIndexCursor(NULL_CONTEXT, EmptyMemoryTracker.INSTANCE)) {
            read.relationshipIndexSeek(tx.queryContext(), index, cursor, constraints, query);
            assertThat(MONITOR.accessed(IndexType.TEXT)).isEqualTo(1);
            return count(cursor);
        }
    }

    IndexDescriptor getIndex(String indexName) {
        return schemaRead.indexGetForName(indexName);
    }

    protected SchemaDescriptor asSchemaDescriptor(TokenRead tokenRead, Label label, String prop) {
        var labelId = tokenRead.nodeLabel(label.name());
        var propId = tokenRead.propertyKey(prop);
        return SchemaDescriptors.forLabel(labelId, propId);
    }

    private SchemaDescriptor asSchemaDescriptor(TokenRead tokenRead, RelationshipType relType, String prop) {
        var labelId = tokenRead.relationshipType(relType.name());
        var propId = tokenRead.propertyKey(prop);
        return SchemaDescriptors.forRelType(labelId, propId);
    }

    private TokenRead getTokenRead(Transaction tx) {
        var ktx = ((TransactionImpl) tx).kernelTransaction();
        return ktx.tokenRead();
    }

    private TokenWrite getTokenWrite(Transaction tx) {
        var ktx = ((TransactionImpl) tx).kernelTransaction();
        return ktx.tokenWrite();
    }

    private SchemaWrite getSchemaWrite(Transaction tx) {
        var ktx = ((TransactionImpl) tx).kernelTransaction();
        SchemaWrite schemaWrite;
        try {
            schemaWrite = ktx.schemaWrite();
        } catch (InvalidTransactionTypeKernelException e) {
            throw new RuntimeException(e);
        }
        return schemaWrite;
    }

    private static class IndexAccessMonitor extends IndexMonitor.MonitorAdapter {
        private final Map<IndexType, Integer> counts = new HashMap<>();

        @Override
        public void queried(IndexDescriptor descriptor) {
            counts.putIfAbsent(descriptor.getIndexType(), 0);
            counts.computeIfPresent(descriptor.getIndexType(), (type, value) -> value + 1);
        }

        public int accessed(IndexType type) {
            return counts.getOrDefault(type, 0);
        }

        void reset() {
            counts.clear();
        }
    }

    @Override
    public ReadTestSupport newTestSupport() {
        Monitors monitors = new Monitors();
        monitors.addMonitorListener(MONITOR);
        ReadTestSupport support = new ReadTestSupport();
        support.setMonitors(monitors);
        return support;
    }

    private long count(Cursor cursor) {
        int result = 0;
        while (cursor.next()) {
            result++;
        }
        return result;
    }
}

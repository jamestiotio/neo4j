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
package org.neo4j.values.virtual;

import static org.neo4j.memory.HeapEstimator.shallowSizeOfInstance;

import java.util.Arrays;
import org.neo4j.values.AnyValueWriter;

public abstract class PathReference extends VirtualPathValue {

    public static PathReference path(long[] nodes, long[] relationships) {
        return new PathReferencePrimitive(nodes, relationships);
    }

    public static PathReference path(VirtualNodeValue[] nodes, VirtualRelationshipValue[] relationships) {
        return new PathReferenceReferences(nodes, relationships);
    }

    @Override
    public abstract ListValue relationshipsAsList();

    private static class PathReferencePrimitive extends PathReference {
        private static final long SHALLOW_SIZE = shallowSizeOfInstance(PathReferencePrimitive.class);

        private final long[] nodes;
        private final long[] relationships;

        PathReferencePrimitive(long[] nodes, long[] relationships) {
            this.nodes = nodes;
            this.relationships = relationships;
        }

        @Override
        public long estimatedHeapUsage() {
            return SHALLOW_SIZE;
        }

        @Override
        public long startNodeId() {
            return nodes[0];
        }

        @Override
        public long endNodeId() {
            return nodes[nodes.length - 1];
        }

        @Override
        public long[] nodeIds() {
            return nodes;
        }

        @Override
        public long[] relationshipIds() {
            return relationships;
        }

        @Override
        public ListValue relationshipsAsList() {

            int size = relationships.length;
            ListValueBuilder builder = ListValueBuilder.newListBuilder(size);
            for (long relationship : relationships) {
                builder.add(VirtualValues.relationship(relationship));
            }
            return builder.build();
        }

        @Override
        public int size() {
            return relationships.length;
        }

        @Override
        public <E extends Exception> void writeTo(AnyValueWriter<E> writer) throws E {
            writer.writePathReference(nodes, relationships);
        }

        @Override
        public ListValue asList() {

            int size = nodes.length + relationships.length;
            ListValueBuilder builder = ListValueBuilder.newListBuilder(size);
            for (int i = 0; i < size; i++) {
                if (i % 2 == 0) {
                    builder.add(VirtualValues.node(nodes[i / 2]));
                } else {
                    builder.add(VirtualValues.relationship(relationships[i / 2]));
                }
            }
            return builder.build();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(getTypeName() + "{");
            int i = 0;
            sb.append("(").append(nodes[0]).append(")");
            for (; i < relationships.length; i++) {
                sb.append("-");
                sb.append("[").append(relationships[i]).append("]");
                sb.append("-");
                sb.append("(").append(nodes[i + 1]).append(")");
            }
            sb.append('}');
            return sb.toString();
        }
    }

    private static class PathReferenceReferences extends PathReference {
        private static final long SHALLOW_SIZE = shallowSizeOfInstance(PathReferencePrimitive.class);

        private final VirtualNodeValue[] nodes;
        private final VirtualRelationshipValue[] relationships;

        PathReferenceReferences(VirtualNodeValue[] nodes, VirtualRelationshipValue[] relationships) {
            this.nodes = nodes;
            this.relationships = relationships;
        }

        @Override
        public long estimatedHeapUsage() {
            return SHALLOW_SIZE;
        }

        @Override
        public long startNodeId() {
            return nodes[0].id();
        }

        @Override
        public long endNodeId() {
            return nodes[nodes.length - 1].id();
        }

        @Override
        public long[] nodeIds() {
            long[] res = new long[nodes.length];
            for (int i = 0; i < nodes.length; i++) {
                res[i] = nodes[i].id();
            }
            return res;
        }

        @Override
        public long[] relationshipIds() {
            long[] res = new long[relationships.length];
            for (int i = 0; i < relationships.length; i++) {
                res[i] = relationships[i].id();
            }
            return res;
        }

        @Override
        public ListValue relationshipsAsList() {
            return VirtualValues.fromList(Arrays.asList(relationships));
        }

        @Override
        public int size() {
            return relationships.length;
        }

        @Override
        public <E extends Exception> void writeTo(AnyValueWriter<E> writer) throws E {
            writer.writePathReference(nodes, relationships);
        }

        @Override
        public ListValue asList() {

            int size = nodes.length + relationships.length;
            ListValueBuilder builder = ListValueBuilder.newListBuilder(size);
            for (int i = 0; i < size; i++) {
                if (i % 2 == 0) {
                    builder.add(nodes[i / 2]);
                } else {
                    builder.add(relationships[i / 2]);
                }
            }
            return builder.build();
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(getTypeName() + "{");
            int i = 0;
            sb.append("(").append(nodes[0].id()).append(")");
            for (; i < relationships.length; i++) {
                sb.append("-");
                sb.append("[").append(relationships[i].id()).append("]");
                sb.append("-");
                sb.append("(").append(nodes[i + 1].id()).append(")");
            }
            sb.append('}');
            return sb.toString();
        }
    }
}

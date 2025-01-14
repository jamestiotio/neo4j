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
package org.neo4j.kernel.api.database.enrichment;

import java.util.List;
import org.eclipse.collections.api.map.primitive.ImmutableByteObjectMap;
import org.eclipse.collections.impl.factory.primitive.ByteObjectMaps;

/**
 * The type of entity change in the enrichment data
 */
public enum DeltaType {
    ADDED((byte) 0),
    MODIFIED((byte) 1),
    DELETED((byte) 2),
    STATE((byte) 3);

    public static final ImmutableByteObjectMap<DeltaType> BY_ID =
            ByteObjectMaps.immutable.from(List.of(DeltaType.values()), DeltaType::id, v -> v);

    private final byte id;

    DeltaType(byte id) {
        this.id = id;
    }

    public byte id() {
        return id;
    }
}

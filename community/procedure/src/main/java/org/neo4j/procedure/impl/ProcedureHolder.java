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
package org.neo4j.procedure.impl;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;
import org.neo4j.internal.kernel.api.procs.QualifiedName;

/**
 * Simple in memory store for procedures.
 *
 * The implementation preserves ids for QualifiedName's in order
 * to allow for entries to be overwritten.
 *
 * Should only be accessed from a single thread
 * @param <T> the type to be stored
 */
class ProcedureHolder<T> {
    private final Map<QualifiedName, Integer> nameToId = new HashMap<>();
    private final Map<QualifiedName, Integer> caseInsensitiveName2Id = new HashMap<>();
    private final List<Object> store = new ArrayList<>();

    private static final Object TOMBSTONE = new Object();

    T get(QualifiedName name) {
        Integer id = name2Id(name);
        if (id == null) {
            return null;
        }
        Object value = store.get(id);
        if (value == TOMBSTONE) {
            return null;
        }

        return (T) value;
    }

    T get(int id) {
        Object element = store.get(id);
        if (element == TOMBSTONE) {
            return null;
        }

        return (T) element;
    }

    int put(QualifiedName name, T item, boolean caseInsensitive) {
        Integer id = name2Id(name);

        // Existing entry -> preserve ids
        if (id != null) {
            store.set(id, item);
        } else {
            id = store.size();
            nameToId.put(name, id);
            store.add(item);
        }

        // Update case sensitivity
        var lowercaseName = toLowerCaseName(name);
        if (caseInsensitive) {
            caseInsensitiveName2Id.put(lowercaseName, id);
        } else {
            caseInsensitiveName2Id.remove(lowercaseName);
        }

        return id;
    }

    /**
     * Create a tombstoned copy of the ProcedureHolder.
     *
     * @param src The source ProcedureHolder from which the copy is made.
     * @param preserve The ids that should be preserved, if any.
     *
     * @return A new ProcedureHolder
     */
    public static <T> ProcedureHolder<T> tombstone(ProcedureHolder<T> src, Set<Integer> preserve) {
        requireNonNull(preserve);

        var ret = new ProcedureHolder<T>();

        for (int i = 0; i < src.store.size(); i++) {
            if (preserve.contains(i)) {
                ret.store.add(src.store.get(i));
            } else {
                ret.store.add(TOMBSTONE);
            }
        }

        src.caseInsensitiveName2Id.forEach((k, v) -> ret.caseInsensitiveName2Id.put(k, v));
        src.nameToId.forEach((k, v) -> ret.nameToId.put(k, v));

        return ret;
    }

    int idOf(QualifiedName name) {
        Integer id = name2Id(name);

        if (id == null || store.get(id) == TOMBSTONE) {
            throw new NoSuchElementException();
        }

        return id;
    }

    List<T> all() {
        return (List<T>) store.stream().filter(e -> e != TOMBSTONE).collect(Collectors.toList());
    }

    boolean contains(QualifiedName name) {
        return get(name) != null;
    }

    private Integer name2Id(QualifiedName name) {
        Integer id = nameToId.get(name);
        if (id == null) { // Did not find it in the case sensitive lookup - let's check for case insensitive objects
            QualifiedName lowerCaseName = toLowerCaseName(name);
            id = caseInsensitiveName2Id.get(lowerCaseName);
        }

        return id;
    }

    private QualifiedName toLowerCaseName(QualifiedName name) {
        String[] oldNs = name.namespace();
        String[] lowerCaseNamespace = new String[oldNs.length];
        for (int i = 0; i < oldNs.length; i++) {
            lowerCaseNamespace[i] = oldNs[i].toLowerCase(Locale.ROOT);
        }
        String lowercaseName = name.name().toLowerCase(Locale.ROOT);
        return new QualifiedName(lowerCaseNamespace, lowercaseName);
    }

    public void unregister(QualifiedName name) {
        Integer id = name2Id(name);
        if (id != null) {
            store.set(id, TOMBSTONE);
        }
    }
}

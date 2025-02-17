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
package org.neo4j.util.concurrent;

import static java.lang.Math.max;

import java.util.Arrays;

public class SequenceArray {
    private static final long UNSET = -1L;
    // This is the backing store, treated as a ring, courtesy of cursor
    private long[] array;
    private int cursor;
    private int itemsAhead;
    private final int longsPerItem;
    private int capacity;

    SequenceArray(int longsPerItem, int initialCapacity) {
        this.longsPerItem = longsPerItem;
        this.capacity = initialCapacity;
        this.array = new long[capacity * longsPerItem];
    }

    public void clear() {
        cursor = 0;
        itemsAhead = 0;
    }

    void offer(long baseNumber, long number, long[] meta) {
        int diff = (int) (number - baseNumber);
        ensureArrayCapacity(diff);
        int index = cursor + diff - 1;

        // If we offer a value a bit ahead of the last offered value then clear the values in between
        for (int i = cursor + itemsAhead; i < index; i++) {
            array[index(i)] = UNSET;
        }

        int absIndex = index(index);
        array[absIndex] = number;
        System.arraycopy(meta, 0, array, absIndex + 1, longsPerItem - 1);
        itemsAhead = max(itemsAhead, diff);
    }

    private int index(int logicalIndex) {
        return (logicalIndex % capacity) * longsPerItem;
    }

    long pollHighestGapFree(long given, long[] meta) {
        // assume that "given" would be placed at cursor
        long number = given;
        int length = itemsAhead - 1;
        int absIndex = 0;
        for (int i = 0; i < length; i++) {
            // Advance the cursor first because this method is only assumed to be called when offering the number
            // immediately after
            // the current highest gap-free number
            advanceCursor();
            int tentativeAbsIndex = index(cursor);
            if (array[tentativeAbsIndex] == UNSET) { // we found a gap, return the number before the gap
                break;
            }

            absIndex = tentativeAbsIndex;
            number++;
            assert array[absIndex] == number
                    : "Expected index " + cursor + " to be " + number + ", but was " + array[absIndex]
                            + ". This is for i=" + i;
        }

        // copy the meta values into the supplied meta
        System.arraycopy(array, absIndex + 1, meta, 0, longsPerItem - 1);
        return number;
    }

    private void advanceCursor() {
        assert itemsAhead > 0;
        itemsAhead--;
        cursor = advanceCursor(cursor);
    }

    private int advanceCursor(int cursor) {
        return (cursor + 1) % capacity;
    }

    private void ensureArrayCapacity(int capacity) {
        while (capacity > this.capacity) {
            int newCapacity = this.capacity * 2;
            long[] newArray = new long[newCapacity * longsPerItem];
            // Copy contents to new array, newArray starting at 0
            for (int i = 0; i < itemsAhead; i++) {
                System.arraycopy(array, index(cursor + i), newArray, index(i), longsPerItem);
            }
            this.array = newArray;
            this.capacity = newCapacity;
            this.cursor = 0;
        }
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < itemsAhead; i++) {
            long value = array[index(cursor + i)];
            if (value != UNSET) {
                builder.append(builder.length() > 0 ? "," : "").append(value);
            }
        }
        return builder.toString();
    }

    long[] missingItems(long gapFree) {
        long[] missingItems = new long[8];

        int remaining = itemsAhead - 1;
        int queueCursor = cursor + 1;
        long expected = gapFree + 1;
        int resultCursor = 0;
        while (remaining-- > 0) {
            int absIndex = index(queueCursor);
            long number = array[absIndex];
            if (number != UNSET) {
                if (number != expected) {
                    for (long missing = expected; missing < number; missing++) {
                        if (missingItems.length == resultCursor) {
                            missingItems = Arrays.copyOf(missingItems, missingItems.length << 1);
                        }
                        missingItems[resultCursor++] = missing;
                    }
                }
                expected = number + 1;
            }
            queueCursor = advanceCursor(queueCursor);
        }

        if (resultCursor < missingItems.length) {
            missingItems = Arrays.copyOf(missingItems, resultCursor);
        }
        return missingItems;
    }

    long[][] snapshot() {
        long[][] temp = new long[itemsAhead][]; // worst-case size
        int remaining = itemsAhead - 1;
        int queueCursor = cursor + 1;
        int resultCursor = 0;
        while (remaining-- > 0) {
            int absIndex = index(queueCursor);
            long number = array[absIndex];
            if (number != UNSET) {
                temp[resultCursor++] = Arrays.copyOfRange(array, absIndex, absIndex + longsPerItem);
            }
            queueCursor = advanceCursor(queueCursor);
        }
        if (resultCursor < temp.length) {
            // shrink to actual size
            temp = Arrays.copyOf(temp, resultCursor);
        }
        return temp;
    }
}

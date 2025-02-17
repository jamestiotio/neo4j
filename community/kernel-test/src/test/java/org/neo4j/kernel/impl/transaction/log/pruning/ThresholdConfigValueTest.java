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
package org.neo4j.kernel.impl.transaction.log.pruning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.configuration.SettingValueParsers.FALSE;
import static org.neo4j.configuration.SettingValueParsers.TRUE;
import static org.neo4j.kernel.impl.transaction.log.pruning.ThresholdConfigParser.parse;

import org.junit.jupiter.api.Test;
import org.neo4j.io.ByteUnit;

class ThresholdConfigValueTest {

    @Test
    void daysPeriodWithSizeRestriction() {
        var thresholdConfigValue = parse("17 days 128M");
        assertEquals("days", thresholdConfigValue.type());
        assertEquals(17, thresholdConfigValue.value());

        assertTrue(thresholdConfigValue.hasAdditionalRestriction());
        assertEquals(ByteUnit.mebiBytes(128), thresholdConfigValue.additionalRestriction());
    }

    @Test
    void hoursPeriodWithSizeRestriction() {
        var thresholdConfigValue = parse("42 hours 256k");
        assertEquals("hours", thresholdConfigValue.type());
        assertEquals(42, thresholdConfigValue.value());

        assertTrue(thresholdConfigValue.hasAdditionalRestriction());
        assertEquals(ByteUnit.kibiBytes(256), thresholdConfigValue.additionalRestriction());
    }

    @Test
    void shouldParseCorrectly() {
        ThresholdConfigParser.ThresholdConfigValue value = parse("25 files");
        assertEquals("files", value.type());
        assertEquals(25, value.value());

        value = parse("4g size");
        assertEquals("size", value.type());
        assertEquals(1L << 32, value.value());
    }

    @Test
    void shouldThrowExceptionOnUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> parse("more than one spaces is invalid"));
    }

    @Test
    void shouldReturnNoPruningForTrue() {
        ThresholdConfigParser.ThresholdConfigValue value = parse(TRUE);
        assertSame(ThresholdConfigParser.ThresholdConfigValue.NO_PRUNING, value);
    }

    @Test
    void shouldReturnKeepOneEntryForFalse() {
        ThresholdConfigParser.ThresholdConfigValue value = parse(FALSE);
        assertEquals("entries", value.type());
        assertEquals(1, value.value());
    }
}

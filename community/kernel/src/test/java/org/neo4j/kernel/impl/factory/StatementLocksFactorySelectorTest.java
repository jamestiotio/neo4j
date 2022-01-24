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
package org.neo4j.kernel.impl.factory;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import org.neo4j.configuration.Config;
import org.neo4j.kernel.impl.locking.Locks;
import org.neo4j.kernel.impl.locking.Locks.Client;
import org.neo4j.kernel.impl.locking.SimpleStatementLocks;
import org.neo4j.kernel.impl.locking.SimpleStatementLocksFactory;
import org.neo4j.kernel.impl.locking.StatementLocks;
import org.neo4j.kernel.impl.locking.StatementLocksFactory;
import org.neo4j.logging.internal.LogService;
import org.neo4j.logging.internal.NullLogService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StatementLocksFactorySelectorTest
{
    @Test
    void loadSimpleStatementLocksFactoryWhenNoServices()
    {
        Locks locks = mock( Locks.class );
        Locks.Client locksClient = mock( Client.class );
        when( locks.newClient() ).thenReturn( locksClient );

        StatementLocksFactorySelector loader = newLoader( locks );

        StatementLocksFactory factory = loader.select();
        StatementLocks statementLocks = factory.newInstance();

        assertThat( factory ).isInstanceOf( SimpleStatementLocksFactory.class );
        assertThat( statementLocks ).isInstanceOf( SimpleStatementLocks.class );

        assertSame( locksClient, statementLocks.optimistic() );
        assertSame( locksClient, statementLocks.pessimistic() );
    }

    @Test
    void loadSingleAvailableFactory()
    {
        Locks locks = mock( Locks.class );
        StatementLocksFactory factory = mock( StatementLocksFactory.class );

        StatementLocksFactorySelector loader = newLoader( locks, factory );

        StatementLocksFactory loadedFactory = loader.select();

        assertSame( factory, loadedFactory );
        verify( factory ).initialize( same( locks ), any( Config.class ) );
    }

    @Test
    void throwWhenMultipleFactoriesLoaded()
    {
        TestStatementLocksFactorySelector loader = newLoader( mock( Locks.class ),
                mock( StatementLocksFactory.class ),
                mock( StatementLocksFactory.class ),
                mock( StatementLocksFactory.class ) );

        try
        {
            loader.select();
        }
        catch ( Exception e )
        {
            assertThat( e ).isInstanceOf( IllegalStateException.class );
        }
    }

    private static TestStatementLocksFactorySelector newLoader( Locks locks, StatementLocksFactory... factories )
    {
        return new TestStatementLocksFactorySelector( locks, Config.defaults(), NullLogService.getInstance(), factories );
    }

    private static class TestStatementLocksFactorySelector extends StatementLocksFactorySelector
    {
        private final List<StatementLocksFactory> factories;

        TestStatementLocksFactorySelector( Locks locks, Config config, LogService logService,
                StatementLocksFactory... factories )
        {
            super( locks, config, logService );
            this.factories = Arrays.asList( factories );
        }

        @Override
        List<StatementLocksFactory> serviceLoadFactories()
        {
            return factories;
        }
    }
}
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
package org.neo4j.server.exception;

import org.neo4j.server.ServerStartupException;
import org.neo4j.storageengine.migration.UpgradeNotAllowedException;

import static java.lang.String.format;
import static org.apache.commons.lang3.exception.ExceptionUtils.getRootCause;

/**
 * Helps translate known common user errors to avoid long java stack traces and other bulk logging that obscures
 * what went wrong.
 */
public class ServerStartupErrors
{
    private ServerStartupErrors()
    {
    }

    public static ServerStartupException translateToServerStartupError( Throwable cause )
    {
        Throwable rootCause = getRootCause( cause );
        if ( rootCause instanceof UpgradeNotAllowedException )
        {
            return new UpgradeDisallowedStartupException( (UpgradeNotAllowedException) rootCause );
        }
        return new ServerStartupException( format( "Starting Neo4j failed: %s", cause.getMessage() ), cause );
    }
}
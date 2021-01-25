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
package org.neo4j.logging.internal;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import org.neo4j.io.fs.FileSystemAbstraction;
import org.neo4j.kernel.lifecycle.Lifecycle;
import org.neo4j.logging.FormattedLogFormat;
import org.neo4j.logging.FormattedLogProvider;
import org.neo4j.logging.Level;
import org.neo4j.logging.LogProvider;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.logging.RotatingFileOutputStreamSupplier;
import org.neo4j.scheduler.Group;
import org.neo4j.scheduler.JobScheduler;

import static org.neo4j.io.fs.FileSystemUtils.createOrOpenAsOutputStream;

public final class StoreLogService extends AbstractLogService implements Lifecycle
{
    public static final class Builder
    {
        private LogProvider userLogProvider = NullLogProvider.getInstance();
        private Executor rotationExecutor;
        private long internalLogRotationThreshold;
        private long internalLogRotationDelay;
        private int maxInternalLogArchives;
        private Consumer<LogProvider> rotationListener = logProvider ->
        {
        };
        private Map<String, Level> logLevels = new HashMap<>();
        private Level defaultLevel = Level.INFO;
        private ZoneId timeZoneId = ZoneOffset.UTC;
        private File debugLog;
        private FormattedLogFormat format = FormattedLogFormat.STANDARD_FORMAT;

        private Builder()
        {
        }

        public Builder withUserLogProvider( LogProvider userLogProvider )
        {
            this.userLogProvider = userLogProvider;
            return this;
        }

        public Builder withRotation( long internalLogRotationThreshold, long internalLogRotationDelay,
                int maxInternalLogArchives, JobScheduler jobScheduler )
        {
            return withRotation( internalLogRotationThreshold, internalLogRotationDelay, maxInternalLogArchives,
                    jobScheduler.executor( Group.LOG_ROTATION ) );
        }

        public Builder withRotation( long internalLogRotationThreshold, long internalLogRotationDelay,
                int maxInternalLogArchives, Executor rotationExecutor )
        {
            this.internalLogRotationThreshold = internalLogRotationThreshold;
            this.internalLogRotationDelay = internalLogRotationDelay;
            this.maxInternalLogArchives = maxInternalLogArchives;
            this.rotationExecutor = rotationExecutor;
            return this;
        }

        public Builder withRotationListener( Consumer<LogProvider> rotationListener )
        {
            this.rotationListener = rotationListener;
            return this;
        }

        public Builder withLevel( String context, Level level )
        {
            this.logLevels.put( context, level );
            return this;
        }

        public Builder withLevels( Map<String,Level> levels )
        {
            this.logLevels.putAll( levels );
            return this;
        }

        public Builder withTimeZone( ZoneId timeZoneId )
        {
            this.timeZoneId = timeZoneId;
            return this;
        }

        public Builder withDefaultLevel( Level defaultLevel )
        {
            this.defaultLevel = defaultLevel;
            return this;
        }

        public Builder withInternalLog( File logFile )
        {
            this.debugLog = logFile;
            return this;
        }

        public Builder withFormat( FormattedLogFormat format )
        {
            this.format = format;
            return this;
        }

        public StoreLogService build( FileSystemAbstraction fileSystem ) throws IOException
        {
            if ( debugLog == null )
            {
                throw new IllegalArgumentException( "Debug log can't be null; set its value using `withInternalLog`" );
            }
            return new StoreLogService( userLogProvider, fileSystem, debugLog, logLevels, defaultLevel, timeZoneId,
                    internalLogRotationThreshold, internalLogRotationDelay, maxInternalLogArchives, rotationExecutor,
                    rotationListener, format );
        }
    }

    public static Builder withUserLogProvider( LogProvider userLogProvider )
    {
        return new Builder().withUserLogProvider( userLogProvider );
    }

    public static Builder withRotation( long internalLogRotationThreshold, long internalLogRotationDelay,
            int maxInternalLogArchives, JobScheduler jobScheduler )
    {
        return new Builder().withRotation( internalLogRotationThreshold, internalLogRotationDelay, maxInternalLogArchives,
                        jobScheduler );
    }

    public static Builder withInternalLog( File logFile )
    {
        return new Builder().withInternalLog( logFile );
    }

    private final Closeable closeable;
    private final SimpleLogService logService;
    // keep a (somewhat redundant) reference to the internal log provider, although more strongly typed
    private final FormattedLogProvider internalLogProvider;
    // keep a reference to the sent in user log provider for log level change to be possible
    private final LogProvider originalUserLogProvider;

    private StoreLogService( LogProvider userLogProvider,
            FileSystemAbstraction fileSystem,
            File internalLog,
            Map<String, Level> logLevels,
            Level defaultLevel,
            ZoneId logTimeZone,
            long internalLogRotationThreshold,
            long internalLogRotationDelay,
            int maxInternalLogArchives,
            Executor rotationExecutor,
            final Consumer<LogProvider> rotationListener,
            FormattedLogFormat format ) throws IOException
    {
        if ( !internalLog.getParentFile().exists() )
        {
            fileSystem.mkdirs( internalLog.getParentFile() );
        }

        final FormattedLogProvider.Builder internalLogBuilder = FormattedLogProvider.withZoneId( logTimeZone )
                .withDefaultLogLevel( defaultLevel ).withLogLevels( logLevels ).withFormat( format );

        FormattedLogProvider internalLogProvider;
        if ( internalLogRotationThreshold == 0 )
        {
            OutputStream outputStream = createOrOpenAsOutputStream( fileSystem, internalLog, true );
            internalLogProvider = internalLogBuilder.toOutputStream( outputStream );
            rotationListener.accept( internalLogProvider );
            this.closeable = outputStream;
        }
        else
        {
            RotatingFileOutputStreamSupplier rotatingSupplier = new RotatingFileOutputStreamSupplier( fileSystem, internalLog,
                    internalLogRotationThreshold, internalLogRotationDelay, maxInternalLogArchives,
                    rotationExecutor, new RotatingFileOutputStreamSupplier.RotationListener()
            {
                @Override
                public void outputFileCreated( OutputStream newStream )
                {
                    FormattedLogProvider logProvider = internalLogBuilder.toOutputStream( newStream );
                    logProvider.getLog( StoreLogService.class ).info( "Opened new internal log file" );
                    rotationListener.accept( logProvider );
                }

                @Override
                public void rotationCompleted( OutputStream newStream )
                {
                    FormattedLogProvider logProvider = internalLogBuilder.toOutputStream( newStream );
                    logProvider.getLog( StoreLogService.class ).info( "Rotated internal log file" );
                }

                @Override
                public void rotationError( Exception e, OutputStream outStream )
                {
                    FormattedLogProvider logProvider = internalLogBuilder.toOutputStream( outStream );
                    logProvider.getLog( StoreLogService.class ).info( "Rotation of internal log file failed:", e );
                }
            } );
            internalLogProvider = internalLogBuilder.toOutputStream( rotatingSupplier );
            this.closeable = rotatingSupplier;
        }
        this.originalUserLogProvider = userLogProvider;
        // If the user log provider comes from us we make sure that it starts with the default log level.
        setUserLogLevel( defaultLevel );

        this.logService = new SimpleLogService( originalUserLogProvider, internalLogProvider );
        this.internalLogProvider = internalLogProvider;
    }

    @Override
    public void init()
    {
    }

    @Override
    public void start()
    {
    }

    @Override
    public void stop()
    {
    }

    @Override
    public void shutdown() throws Exception
    {
        closeable.close();
    }

    @Override
    public LogProvider getUserLogProvider()
    {
        return logService.getUserLogProvider();
    }

    @Override
    public LogProvider getInternalLogProvider()
    {
        return internalLogProvider;
    }

    public void setDefaultLogLevel( Level newLevel )
    {
        internalLogProvider.setDefaultLevel( newLevel );
        setUserLogLevel( newLevel );
    }

    public void setContextLogLevels( Map<String,Level> newLevels )
    {
        internalLogProvider.setContextLogLevels( newLevels );
    }

    private void setUserLogLevel( Level level )
    {
        if ( originalUserLogProvider instanceof FormattedLogProvider )
        {
            ((FormattedLogProvider) originalUserLogProvider).setDefaultLevel( level );
        }
    }
}

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
package org.neo4j.kernel.impl.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_MOCKS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.neo4j.configuration.GraphDatabaseSettings.DEFAULT_DATABASE_NAME;
import static org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo.EMBEDDED_CONNECTION;
import static org.neo4j.internal.kernel.api.security.LoginContext.AUTH_DISABLED;
import static org.neo4j.io.pagecache.PageCacheOpenOptions.MULTI_VERSIONED;
import static org.neo4j.kernel.api.exceptions.Status.Transaction.TransactionTimedOut;
import static org.neo4j.kernel.api.exceptions.Status.Transaction.TransactionTimedOutClientConfiguration;
import static org.neo4j.kernel.database.DatabaseIdFactory.from;
import static org.neo4j.storageengine.api.txstate.validation.TransactionValidatorFactory.EMPTY_VALIDATOR_FACTORY;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.collections.api.map.primitive.MutableLongObjectMap;
import org.eclipse.collections.api.set.primitive.MutableLongSet;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.neo4j.collection.Dependencies;
import org.neo4j.collection.diffset.MutableLongDiffSets;
import org.neo4j.collection.factory.CollectionsFactory;
import org.neo4j.collection.factory.OnHeapCollectionsFactory;
import org.neo4j.collection.pool.Pool;
import org.neo4j.configuration.Config;
import org.neo4j.configuration.GraphDatabaseSettings;
import org.neo4j.configuration.database.readonly.ConfigBasedLookupFactory;
import org.neo4j.dbms.database.DbmsRuntimeRepository;
import org.neo4j.dbms.database.readonly.DefaultReadOnlyDatabases;
import org.neo4j.dbms.identity.ServerIdentity;
import org.neo4j.internal.kernel.api.security.CommunitySecurityLog;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.internal.kernel.api.security.SecurityContext;
import org.neo4j.internal.schema.SchemaState;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.pagecache.context.CursorContext;
import org.neo4j.io.pagecache.context.CursorContextFactory;
import org.neo4j.io.pagecache.context.EmptyVersionContextSupplier;
import org.neo4j.io.pagecache.tracing.DefaultPageCacheTracer;
import org.neo4j.io.pagecache.tracing.version.DefaultVersionStorageTracer;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.api.TransactionTimeout;
import org.neo4j.kernel.api.procedure.ProcedureView;
import org.neo4j.kernel.availability.AvailabilityGuard;
import org.neo4j.kernel.database.DatabaseIdRepository;
import org.neo4j.kernel.database.DatabaseTracers;
import org.neo4j.kernel.database.NamedDatabaseId;
import org.neo4j.kernel.impl.api.index.IndexingService;
import org.neo4j.kernel.impl.api.index.stats.IndexStatisticsStore;
import org.neo4j.kernel.impl.api.tracer.DefaultTracer;
import org.neo4j.kernel.impl.api.txid.TransactionIdGenerator;
import org.neo4j.kernel.impl.constraints.StandardConstraintSemantics;
import org.neo4j.kernel.impl.factory.CanWrite;
import org.neo4j.kernel.impl.factory.GraphDatabaseFacade;
import org.neo4j.kernel.impl.locking.LockManager;
import org.neo4j.kernel.impl.query.TransactionExecutionMonitor;
import org.neo4j.kernel.impl.transaction.TransactionMonitor;
import org.neo4j.kernel.impl.transaction.log.LogicalTransactionStore;
import org.neo4j.kernel.impl.transaction.log.TransactionCommitmentFactory;
import org.neo4j.kernel.impl.transaction.tracing.TransactionWriteEvent;
import org.neo4j.kernel.internal.event.DatabaseTransactionEventListeners;
import org.neo4j.lock.LockTracer;
import org.neo4j.logging.NullLogProvider;
import org.neo4j.memory.MemoryGroup;
import org.neo4j.memory.MemoryPools;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.monitoring.DatabaseHealth;
import org.neo4j.resources.CpuClock;
import org.neo4j.storageengine.api.CommandBatch;
import org.neo4j.storageengine.api.CommandBatchToApply;
import org.neo4j.storageengine.api.CommandCreationContext;
import org.neo4j.storageengine.api.MetadataProvider;
import org.neo4j.storageengine.api.StorageEngine;
import org.neo4j.storageengine.api.StorageReader;
import org.neo4j.storageengine.api.TransactionApplicationMode;
import org.neo4j.storageengine.api.TransactionIdStore;
import org.neo4j.storageengine.api.cursor.StoreCursors;
import org.neo4j.storageengine.api.enrichment.ApplyEnrichmentStrategy;
import org.neo4j.storageengine.api.enrichment.EnrichmentMode;
import org.neo4j.storageengine.api.txstate.ReadableTransactionState;
import org.neo4j.storageengine.api.txstate.TxStateVisitor;
import org.neo4j.test.LatestVersions;
import org.neo4j.time.Clocks;
import org.neo4j.time.FakeClock;
import org.neo4j.token.TokenHolders;
import org.neo4j.token.api.TokenHolder;
import org.neo4j.values.ElementIdMapper;
import org.neo4j.values.storable.Value;

class KernelTransactionTestBase {
    protected final ServerIdentity serverIdentity = mock(ServerIdentity.class);
    protected final ApplyEnrichmentStrategy enrichmentStrategy = mock(ApplyEnrichmentStrategy.class);
    protected final StorageEngine storageEngine = mock(StorageEngine.class, RETURNS_MOCKS);
    protected final StorageReader storageReader = mock(StorageReader.class);
    protected final MetadataProvider metadataProvider = mock(MetadataProvider.class);
    protected final CommandCreationContext commandCreationContext = mock(CommandCreationContext.class);
    protected final TransactionMonitor transactionMonitor = mock(TransactionMonitor.class);
    protected final CapturingCommitProcess commitProcess = new CapturingCommitProcess();
    protected final AvailabilityGuard availabilityGuard = mock(AvailabilityGuard.class);
    protected final FakeClock clock = Clocks.fakeClock();
    protected final Pool<KernelTransactionImplementation> txPool = mock(Pool.class);
    protected final LockManager.Client locksClient = mock(LockManager.Client.class);
    protected CollectionsFactory collectionsFactory;

    private final ProcedureView procedureView = mock(ProcedureView.class);

    protected final Config config = Config.defaults();
    private final TransactionTimeout defaultTransactionTimeoutMillis =
            new TransactionTimeout(config.get(GraphDatabaseSettings.transaction_timeout), TransactionTimedOut);

    @BeforeEach
    public void before() throws Exception {
        collectionsFactory = Mockito.spy(new TestCollectionsFactory());
        when(storageEngine.newReader()).thenReturn(storageReader);
        when(storageEngine.newCommandCreationContext()).thenReturn(commandCreationContext);
        when(storageEngine.metadataProvider()).thenReturn(metadataProvider);
        when(storageEngine.createStorageCursors(any())).thenReturn(StoreCursors.NULL);
        when(storageEngine.createCommands(
                        any(ReadableTransactionState.class),
                        any(StorageReader.class),
                        any(CommandCreationContext.class),
                        any(LockTracer.class),
                        any(TxStateVisitor.Decorator.class),
                        any(CursorContext.class),
                        any(StoreCursors.class),
                        any(MemoryTracker.class)))
                .thenReturn(List.of(new TestCommand()));

        when(enrichmentStrategy.check()).thenReturn(EnrichmentMode.OFF);
    }

    public KernelTransactionImplementation newTransaction(long transactionTimeoutMillis) {
        return newTransaction(
                0,
                AUTH_DISABLED,
                new TransactionTimeout(
                        Duration.ofMillis(transactionTimeoutMillis), TransactionTimedOutClientConfiguration),
                1L);
    }

    public KernelTransactionImplementation newTransaction(LoginContext loginContext) {
        return newTransaction(0, loginContext, defaultTransactionTimeoutMillis, 1L);
    }

    public KernelTransactionImplementation newTransaction(
            long lastTransactionIdWhenStarted,
            LoginContext loginContext,
            TransactionTimeout transactionTimeout,
            long userTransactionId) {
        KernelTransactionImplementation tx = newNotInitializedTransaction();
        initialize(lastTransactionIdWhenStarted, loginContext, transactionTimeout, userTransactionId, tx);
        return tx;
    }

    void initialize(
            long lastTransactionIdWhenStarted,
            LoginContext loginContext,
            TransactionTimeout transactionTimeout,
            long userTransactionId,
            KernelTransactionImplementation tx) {
        SecurityContext securityContext = loginContext.authorize(
                LoginContext.IdLookup.EMPTY, DEFAULT_DATABASE_NAME, CommunitySecurityLog.NULL_LOG);
        tx.initialize(
                lastTransactionIdWhenStarted,
                KernelTransaction.Type.EXPLICIT,
                securityContext,
                transactionTimeout,
                userTransactionId,
                EMBEDDED_CONNECTION,
                procedureView);
    }

    KernelTransactionImplementation newNotInitializedTransaction() {
        return newNotInitializedTransaction(
                LeaseService.NO_LEASES, config, from(DEFAULT_DATABASE_NAME, UUID.randomUUID()));
    }

    KernelTransactionImplementation newNotInitializedTransaction(Config config) {
        return newNotInitializedTransaction(
                LeaseService.NO_LEASES, config, from(DEFAULT_DATABASE_NAME, UUID.randomUUID()));
    }

    KernelTransactionImplementation newNotInitializedTransaction(Config config, NamedDatabaseId databaseId) {
        return newNotInitializedTransaction(LeaseService.NO_LEASES, config, databaseId);
    }

    KernelTransactionImplementation newNotInitializedTransaction(
            LeaseService leaseService, Config config, NamedDatabaseId databaseId) {
        Dependencies dependencies = new Dependencies();
        var locks = mock(LockManager.class);
        when(locks.newClient()).thenReturn(locksClient);
        dependencies.satisfyDependency(mock(GraphDatabaseFacade.class));
        var memoryPool = new MemoryPools().pool(MemoryGroup.TRANSACTION, ByteUnit.mebiBytes(4), null);

        DatabaseIdRepository databaseIdRepository = mock(DatabaseIdRepository.class);
        Mockito.when(databaseIdRepository.getByName(databaseId.name())).thenReturn(Optional.of(databaseId));
        var readOnlyLookup = new ConfigBasedLookupFactory(config, databaseIdRepository);
        var readOnlyChecker = new DefaultReadOnlyDatabases(readOnlyLookup);
        DefaultPageCacheTracer pageCacheTracer = new DefaultPageCacheTracer();
        DefaultVersionStorageTracer versionStorageTracer = new DefaultVersionStorageTracer(pageCacheTracer);
        return new KernelTransactionImplementation(
                config,
                mock(DatabaseTransactionEventListeners.class),
                null,
                commitProcess,
                transactionMonitor,
                txPool,
                clock,
                new AtomicReference<>(CpuClock.NOT_AVAILABLE),
                new DatabaseTracers(
                        new DefaultTracer(pageCacheTracer), LockTracer.NONE, pageCacheTracer, versionStorageTracer),
                storageEngine,
                any -> CanWrite.INSTANCE,
                new CursorContextFactory(pageCacheTracer, EmptyVersionContextSupplier.EMPTY),
                () -> collectionsFactory,
                new StandardConstraintSemantics(),
                mock(SchemaState.class),
                mockedTokenHolders(),
                mock(ElementIdMapper.class),
                mock(IndexingService.class),
                mock(IndexStatisticsStore.class),
                dependencies,
                databaseId,
                leaseService,
                memoryPool,
                readOnlyChecker.forDatabase(databaseId),
                TransactionExecutionMonitor.NO_OP,
                CommunitySecurityLog.NULL_LOG,
                locks,
                mock(TransactionCommitmentFactory.class),
                mock(KernelTransactions.class),
                TransactionIdGenerator.EMPTY,
                mock(DbmsRuntimeRepository.class),
                LatestVersions.LATEST_KERNEL_VERSION_PROVIDER,
                mock(LogicalTransactionStore.class),
                serverIdentity,
                enrichmentStrategy,
                mock(DatabaseHealth.class),
                NullLogProvider.getInstance(),
                EMPTY_VALIDATOR_FACTORY,
                storageEngine.getOpenOptions().contains(MULTI_VERSIONED));
    }

    KernelTransactionImplementation newNotInitializedTransaction(LeaseService leaseService) {
        return newNotInitializedTransaction(leaseService, config, from(DEFAULT_DATABASE_NAME, UUID.randomUUID()));
    }

    public static class CapturingCommitProcess implements TransactionCommitProcess {
        private long txId = TransactionIdStore.BASE_TX_ID;
        public List<CommandBatch> transactions = new ArrayList<>();

        @Override
        public long commit(
                CommandBatchToApply batch,
                TransactionWriteEvent transactionWriteEvent,
                TransactionApplicationMode mode) {
            transactions.add(batch.commandBatch());
            return ++txId;
        }
    }

    private static TokenHolders mockedTokenHolders() {
        return new TokenHolders(mock(TokenHolder.class), mock(TokenHolder.class), mock(TokenHolder.class));
    }

    private static class TestCollectionsFactory implements CollectionsFactory {

        @Override
        public MutableLongSet newLongSet(MemoryTracker memoryTracker) {
            return OnHeapCollectionsFactory.INSTANCE.newLongSet(memoryTracker);
        }

        @Override
        public MutableLongDiffSets newLongDiffSets(MemoryTracker memoryTracker) {
            return OnHeapCollectionsFactory.INSTANCE.newLongDiffSets(memoryTracker);
        }

        @Override
        public MutableLongObjectMap<Value> newObjectMap(MemoryTracker memoryTracker) {
            return new LongObjectHashMap<>();
        }

        @Override
        public void release() {
            // nop
        }
    }
}

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
package org.neo4j.io.fs;

import static java.lang.Math.min;
import static java.lang.Math.toIntExact;
import static org.neo4j.io.memory.HeapScopedBuffer.EMPTY_BUFFER;

import java.io.Flushable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import java.util.zip.Checksum;
import org.neo4j.io.ByteUnit;
import org.neo4j.io.memory.HeapScopedBuffer;
import org.neo4j.io.memory.ScopedBuffer;
import org.neo4j.memory.MemoryTracker;
import org.neo4j.util.FeatureToggles;
import org.neo4j.util.VisibleForTesting;

/**
 * The main implementation of {@link FlushableChannel}. This class provides buffering over a simple {@link StoreChannel}
 * and, as a side effect, allows control of the flushing of that buffer to disk.
 */
public class PhysicalFlushableChannel implements FlushableChannel {
    static final boolean DISABLE_WAL_CHECKSUM =
            FeatureToggles.flag(PhysicalFlushableChannel.class, "disableChecksum", false);

    public static final int DEFAULT_BUFFER_SIZE = toIntExact(ByteUnit.kibiBytes(4));

    protected StoreChannel channel;

    private final ByteBuffer checksumView;
    private final Checksum checksum;

    private ScopedBuffer scopedBuffer;
    private ByteBuffer buffer;
    private volatile boolean closed;

    @VisibleForTesting
    public PhysicalFlushableChannel(StoreChannel channel, MemoryTracker memoryTracker) {
        this(channel, new HeapScopedBuffer(DEFAULT_BUFFER_SIZE, ByteOrder.LITTLE_ENDIAN, memoryTracker));
    }

    public PhysicalFlushableChannel(StoreChannel channel, ScopedBuffer scopedBuffer) {
        this.channel = channel;
        this.scopedBuffer = scopedBuffer;
        this.buffer = scopedBuffer.getBuffer();
        this.checksumView = this.buffer.duplicate();
        checksum = CHECKSUM_FACTORY.get();
    }

    /**
     * External synchronization between this method and close is required so that they aren't called concurrently.
     * Currently that's done by acquiring the PhysicalLogFile monitor.
     */
    @Override
    public Flushable prepareForFlush() throws IOException {
        // Update checksum with what we got
        if (!DISABLE_WAL_CHECKSUM) {
            checksumView.limit(buffer.position());
            checksum.update(checksumView);
            checksumView.clear();
        }

        buffer.flip();
        Flushable flushable = flushToChannel(channel, buffer);
        buffer.clear();
        return flushable;
    }

    @Override
    public FlushableChannel put(byte value) throws IOException {
        bufferWithGuaranteedSpace(Byte.BYTES).put(value);
        return this;
    }

    @Override
    public FlushableChannel putShort(short value) throws IOException {
        bufferWithGuaranteedSpace(Short.BYTES).putShort(value);
        return this;
    }

    @Override
    public FlushableChannel putInt(int value) throws IOException {
        bufferWithGuaranteedSpace(Integer.BYTES).putInt(value);
        return this;
    }

    @Override
    public FlushableChannel putLong(long value) throws IOException {
        bufferWithGuaranteedSpace(Long.BYTES).putLong(value);
        return this;
    }

    @Override
    public FlushableChannel putFloat(float value) throws IOException {
        bufferWithGuaranteedSpace(Float.BYTES).putFloat(value);
        return this;
    }

    @Override
    public FlushableChannel putDouble(double value) throws IOException {
        bufferWithGuaranteedSpace(Double.BYTES).putDouble(value);
        return this;
    }

    @Override
    public FlushableChannel put(byte[] value, int offset, int length) throws IOException {
        assert length >= 0;
        int localOffset = 0;
        int capacity = buffer.capacity();
        while (localOffset < length) {
            int remaining = buffer.remaining();
            int bufferCapacity = remaining > 0 ? remaining : capacity;
            int chunkSize = min(length - localOffset, bufferCapacity);
            bufferWithGuaranteedSpace(chunkSize).put(value, offset + localOffset, chunkSize);
            localOffset += chunkSize;
        }
        return this;
    }

    @Override
    public FlushableChannel putAll(ByteBuffer src) throws IOException {
        if (src.remaining() <= buffer.remaining()) {
            buffer.put(src);
            return this;
        }

        prepareForFlush(); // Flush internal buffer

        src.mark();
        flushToChannel(channel, src); // Write directly to channel
        src.reset();
        checksum.update(src);

        return this;
    }

    @Override
    public boolean isOpen() {
        return !closed;
    }

    /**
     * External synchronization between this method and emptyBufferIntoChannelAndClearIt is required so that they
     * aren't called concurrently. Currently that's done by acquiring the PhysicalLogFile monitor.
     */
    @Override
    public void close() throws IOException {
        prepareForFlush().flush();
        this.closed = true;
        this.channel.close();
        this.scopedBuffer.close();
        this.scopedBuffer = EMPTY_BUFFER;
        this.buffer = EMPTY_BUFFER.getBuffer();
    }

    /**
     * @return the position of the channel, also taking into account buffer position.
     * @throws IOException if underlying channel throws {@link IOException}.
     */
    public long position() throws IOException {
        return channel.position() + buffer.position();
    }

    /**
     * Sets position of this channel to the new {@code position}. This works only if the underlying channel
     * supports positioning.
     *
     * @param position new position (byte offset) to set as new current position.
     * @throws IOException if underlying channel throws {@link IOException}.
     */
    public void position(long position) throws IOException {
        // Currently we take the pessimistic approach of flushing (doesn't imply forcing) buffer to
        // channel before moving to a new position. This works in all cases, but there could be
        // made an optimization where we could see that we're moving within the current buffer range
        // and if so skip flushing and simply move the cursor in the buffer.
        prepareForFlush();
        channel.position(position);
    }

    private ByteBuffer bufferWithGuaranteedSpace(int spaceInBytes) throws IOException {
        assert spaceInBytes <= buffer.capacity();
        if (buffer.remaining() < spaceInBytes) {
            prepareForFlush();
        }
        return buffer;
    }

    private Flushable flushToChannel(StoreChannel destination, ByteBuffer src) throws IOException {
        try {
            destination.writeAll(src);
        } catch (ClosedChannelException e) {
            handleClosedChannelException(e);
        }
        return destination;
    }

    private void handleClosedChannelException(ClosedChannelException e) throws ClosedChannelException {
        // We don't want to check the closed flag every time we empty, instead we can avoid unnecessary the
        // volatile read and catch ClosedChannelException where we see if the channel being closed was
        // deliberate or not. If it was deliberately closed then throw IllegalStateException instead so
        // that callers won't treat this as a kernel panic.
        if (closed) {
            throw new IllegalStateException("This log channel has been closed", e);
        }

        // OK, this channel was closed without us really knowing about it, throw exception as is.
        throw e;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        int remaining = src.remaining();
        putAll(src);
        return remaining;
    }

    @Override
    public void beginChecksumForWriting() {
        if (DISABLE_WAL_CHECKSUM) {
            return;
        }

        checksum.reset();
        checksumView.limit(checksumView.capacity());
        checksumView.position(buffer.position());
    }

    @Override
    public int putChecksum() throws IOException {
        if (DISABLE_WAL_CHECKSUM) {
            buffer.putInt(0xDEAD5EED);
            return 0xDEAD5EED;
        }

        // Make sure we can append checksum
        bufferWithGuaranteedSpace(Integer.BYTES);

        // Consume remaining bytes
        checksumView.limit(buffer.position());
        checksum.update(checksumView);
        int calculatedChecksum = (int) this.checksum.getValue();

        // Append
        buffer.putInt(calculatedChecksum);

        return calculatedChecksum;
    }
}

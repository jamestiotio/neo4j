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
package org.neo4j.storageengine.api.enrichment;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.neo4j.io.fs.ReadableChannel;
import org.neo4j.io.fs.WritableChannel;

class ChannelBuffer implements WritableChannel, ReadableChannel {

    private final ByteBuffer buffer;
    private boolean isClosed;

    ChannelBuffer(int capacity) {
        this.buffer = ByteBuffer.allocate(capacity).order(ByteOrder.LITTLE_ENDIAN);
    }

    @Override
    public byte get() {
        return buffer.get();
    }

    @Override
    public short getShort() {
        return buffer.getShort();
    }

    @Override
    public int getInt() {
        return buffer.getInt();
    }

    @Override
    public long getLong() {
        return buffer.getLong();
    }

    @Override
    public float getFloat() {
        return buffer.getFloat();
    }

    @Override
    public double getDouble() {
        return buffer.getDouble();
    }

    @Override
    public void get(byte[] bytes, int length) {
        buffer.get(bytes, 0, length);
    }

    @Override
    public int read(ByteBuffer dst) {
        final var remaining = buffer.remaining();
        if (remaining >= dst.remaining()) {
            final var subBuffer = buffer.slice().limit(dst.remaining());
            final var subRemaining = subBuffer.remaining();
            dst.put(subBuffer);
            buffer.position(buffer.position() + subRemaining);
            return subRemaining;
        }

        dst.put(buffer);
        return remaining;
    }

    @Override
    public ChannelBuffer put(byte value) {
        buffer.put(value);
        return this;
    }

    @Override
    public ChannelBuffer putShort(short value) {
        buffer.putShort(value);
        return this;
    }

    @Override
    public ChannelBuffer putInt(int value) {
        buffer.putInt(value);
        return this;
    }

    @Override
    public ChannelBuffer putLong(long value) {
        buffer.putLong(value);
        return this;
    }

    @Override
    public ChannelBuffer putFloat(float value) {
        buffer.putFloat(value);
        return this;
    }

    @Override
    public ChannelBuffer putDouble(double value) {
        buffer.putDouble(value);
        return this;
    }

    @Override
    public ChannelBuffer put(byte[] value, int offset, int length) {
        buffer.put(value, offset, length);
        return this;
    }

    @Override
    public ChannelBuffer putAll(ByteBuffer src) throws IOException {
        buffer.put(src);
        return this;
    }

    @Override
    public int write(ByteBuffer src) throws IOException {
        final var remaining = src.remaining();
        buffer.put(src);
        return remaining;
    }

    @Override
    public boolean isOpen() {
        return !isClosed;
    }

    @Override
    public void beginChecksumForWriting() {
        // no-op
    }

    @Override
    public int putChecksum() throws IOException {
        // no-op
        return 0;
    }

    @Override
    public void beginChecksum() {
        // no-op
    }

    @Override
    public int getChecksum() {
        // no-op
        return 0;
    }

    @Override
    public int endChecksumAndValidate() {
        // no-op
        return 0;
    }

    @Override
    public long position() {
        return buffer.position();
    }

    @Override
    public void position(long byteOffset) throws IOException {
        buffer.position(Math.toIntExact(byteOffset));
    }

    @Override
    public void close() {
        isClosed = true;
    }

    char getChar() {
        return buffer.getChar();
    }

    ChannelBuffer flip() {
        buffer.flip();
        return this;
    }
}

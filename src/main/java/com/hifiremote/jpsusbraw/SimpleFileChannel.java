/* JpsUsbRaw - a userspace settings driver for UEI's USB universal remotes
 * Written in 2016 by Sam Hanes <sam@maltera.com>
 *
 * To the extent possible under law, the author(s) have dedicated all copyright
 * and related and neighboring rights to this software to the public domain
 * worldwide. This software is distributed without any warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software. If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package com.hifiremote.jpsusbraw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;

/** Partial implementation of {@code FileChannel} with fewer features.
 *
 * This class Partially implements {@link FileChannel} for implementations
 * exposing a fixed-size file-like data store which does not support write caching,
 * in-kernel data copy, in-kernel scatter/gather, memory mapping, or locking. It
 * also implements position tracking in memory and position bounds checking.
 */
abstract class SimpleFileChannel
extends FileChannel {
    private long position = 0;

    @Override
    public long position()
    throws IOException {
        if (!this.isOpen())
            throw new ClosedChannelException();

        return position;
    }

    @Override
    public FileChannel position (long newPosition)
    throws IOException {
        if (newPosition < 0)
            throw new IllegalArgumentException("position may not be negative");

        if (newPosition > this.size())
            throw new IOException("can't seek past end of file");

        if (!this.isOpen())
            throw new ClosedChannelException();

        this.position = newPosition;
        return this;
    }

    @Override
    public FileChannel truncate (long size)
    throws IOException {
        throw new IOException("truncation is not supported");
    }


    protected abstract int implRead (ByteBuffer dst, long position)
    throws IOException;

    @Override
    public int read (ByteBuffer dst)
    throws IOException {
        int length = this.read( dst, this.position );

        if (length > 0)
            this.position += length;

        return length;
    }

    @Override
    public int read (ByteBuffer dst, long position)
    throws IOException {
        if (position < 0) {
            throw new IllegalArgumentException( "position may not be negative" );
        } else if (position >= size()) {
            return -1;
        }

        if (dst == null) {
            throw new IllegalArgumentException( "dst may not be null" );
        } else if (dst.remaining() == 0) {
            return 0;
        }

        return implRead( dst, position );
    }

    @Override
    public long read (ByteBuffer[] dsts, int offset, int length)
    throws IOException {
        if (offset < 0 || offset > dsts.length)
            throw new IndexOutOfBoundsException();

        if (length < 0 || length > dsts.length - offset)
            throw new IndexOutOfBoundsException();

        long total = 0;
        for (int idx = offset; idx < offset + length; idx++) {
            ByteBuffer buffer = dsts[ idx ];
            long count = this.read( buffer );

            if (count < 0)
                return count;

            total += count;
        }

        return total;
    }



    protected abstract int implWrite (ByteBuffer src, long position)
    throws IOException;

    @Override
    public int write (ByteBuffer src)
    throws IOException {
        int length = this.write( src, this.position );
        this.position += length;
        return length;
    }

    @Override
    public int write (ByteBuffer src, long position)
    throws IOException {
        if (position < 0)
            throw new IllegalArgumentException("position may not be negative");

        if (position >= this.size())
            throw new IOException("can't write past end of file");

        return this.implWrite( src, position );
    }

    @Override
    public long write (ByteBuffer[] srcs, int offset, int length)
    throws IOException {
        if (offset < 0 || offset > srcs.length)
            throw new IndexOutOfBoundsException();

        if (length < 0 || length > srcs.length - offset)
            throw new IndexOutOfBoundsException();

        long total = 0;
        for (int idx = offset; idx < offset + length; idx++) {
            ByteBuffer buffer = srcs[ idx ];
            total += this.write( buffer );
        }

        return total;
    }



    @Override
    public long transferTo (long position,
            long count, WritableByteChannel target)
    throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate( (int) count );
        this.read( buffer, position );

        buffer.flip();
        return target.write( buffer );
    }

    @Override
    public long transferFrom (
            ReadableByteChannel src, long position, long count)
    throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate( (int) count );
        src.read( buffer );

        buffer.flip();
        return this.write( buffer, position );
    }



    @Override
    public void force (boolean metaData) {
        // no-op; we don't perform write caching
    }

    @Override
    public MappedByteBuffer map (
            FileChannel.MapMode mode,
            long position,
            long size )
    throws IOException {
        throw new IOException("memory mapping is not supported for this channel");
    }

    @Override
    public FileLock lock (long position, long size, boolean shared)
    throws IOException {
        throw new IOException("locking is not supported for this channel");
    }

    @Override
    public FileLock tryLock (long position, long size, boolean shared)
    throws IOException {
        throw new IOException("locking is not supported for this channel");
    }
}

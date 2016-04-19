package com.hifiremote.jpsusbraw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.usb.UsbDevice;
import javax.usb.UsbException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/** NIO FileChannel implementation for USB Mass Storage devices.
 */
class UsbMassStorageChannel
extends SimpleFileChannel {
    private static final Logger log = LogManager.getLogger();

    private static final int BUFFER_BLOCKS = 16;
    private static final int MAX_READ_BLOCKS = 128;
    private static final int MAX_WRITE_BLOCKS = 128;

    private final UsbMassStorageDriver driver;
    private final int blockCount, blockSize;
    private final ByteBuffer buffer;

    UsbMassStorageChannel (final UsbDevice device, final boolean force)
    throws IOException {
        driver = new UsbMassStorageDriver( device, force );

        log.trace( "reading device capacity" );
        ByteBuffer data = ByteBuffer.allocate( 8 );
        ByteBuffer cbd = ByteBuffer.allocate( 10 );
        cbd.order( ByteOrder.BIG_ENDIAN );
        cbd.put( 0, (byte) 0x25 ); // READ CAPACITY (10)
        cbd.put( 4, (byte) data.remaining() ); // ALLOCATION LENGTH

        sendCommand( cbd, data, true );
        blockCount = data.getInt( 0 );
        blockSize  = data.getInt( 4 );

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "received capacity blocks=%d size=%d",
                    blockCount, blockSize
                ));
        }

        buffer = ByteBuffer.allocate( BUFFER_BLOCKS * blockSize );

        log.trace( "successfully initialized USB Mass Storage channel" );
    }

    @Override
    public long size() {
        return blockCount * blockSize;
    }


    public void sendCommand (ByteBuffer command)
    throws IOException {
        sendCommand( command, null, 0, false );
    }

    public void sendCommand (ByteBuffer command, ByteBuffer data, boolean in)
    throws IOException {
        sendCommand( command, data, data.remaining(), in );
    }

    private void sendCommand (ByteBuffer command,
            ByteBuffer data, int dataLength, boolean in)
    throws IOException {
        boolean ok = driver.sendCommand( command, data, dataLength, in );
        if (ok) return;

        log.trace( "command failed, sending REQUEST SENSE" );
        ByteBuffer sense = ByteBuffer.allocate( 252 );
        ByteBuffer cbd = ByteBuffer.allocate( 6 );
        cbd.put( 0, (byte) 0x03 ); // REQUEST SENSE
        cbd.put( 4, (byte) sense.capacity() ); // ALLOCATION LENGTH
        if (!driver.sendCommand( cbd, sense, true )) {
            log.error( "command failed and REQUEST SENSE also failed" );
            throw new IOException(
                    "command failed and retrieving error code also failed" );
        }

        throw new ScsiException( sense.array() );
    }

    public synchronized void rawRead (ByteBuffer dst, long offset, int count)
    throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException( "offset may not be negative" );
        } else if (offset >= blockCount) {
            throw new IllegalArgumentException(
                    "offset is beyond end of volume" );
        } else if (offset > 65535) {
            throw new UnsupportedOperationException(
                    "only 16-bit LBAs are supported" );
        }

        if (count < 0) {
            throw new IllegalArgumentException( "count may not be negative" );
        } else if (count == 0) {
            return;
        } else if (count > blockCount - offset) {
            throw new IllegalArgumentException(
                    "cannot read past end of volume" );
        } else if (count > 256) {
            throw new UnsupportedOperationException(
                    "cannot read more than 256 blocks" );
        }

        if (dst == null) {
            throw new IllegalArgumentException( "dst may not be null" );
        } else if (dst.remaining() < count * blockSize) {
            throw new IllegalArgumentException( "dst buffer is not large enough" );
        }

        ByteBuffer cbd = ByteBuffer.allocate( 6 );
        cbd.order( ByteOrder.BIG_ENDIAN );
        cbd.put( 0, (byte) 0x08 ); // READ (6)
        cbd.putShort( 2, (short)offset ); // LOGICAL BLOCK ADDRESS
        cbd.put( 4, (byte)count );       // TRANSFER LENGTH

        sendCommand( cbd, dst, count * blockSize, true );
    }


    @Override
    protected synchronized int implRead (ByteBuffer dst, long position)
    throws IOException {
        long offset = (long) Math.floor( position / blockSize );
        int skip = (int)( position % blockSize );

        int count = (int) Math.ceil( (dst.remaining() + skip) / blockSize );
        int drop = (int)( (dst.remaining() + skip) % blockSize );

        // if we're at the beginning or end of the target buffer
        // and that boundary is not block-aligned, read a few
        // blocks into an intermediate buffer so we can skip
        // the unwanted partial block
        if (skip != 0 || (drop != 0 && count < BUFFER_BLOCKS)) {
            buffer.clear();

            // don't read more than will fit in the read buffer
            count = Math.min( count, BUFFER_BLOCKS );

            rawRead( buffer, offset, count );

            buffer.flip();
            buffer.position( skip );
            buffer.limit( buffer.limit() - drop );

            dst.put( buffer );
            return buffer.position() - skip;
        }

        // otherwise, if the last block is partial but we're not close
        // enough to the end of the target buffer to reach it in a
        // single buffered read, avoid reading the last block
        else if (drop != 0) count--;

        // don't read too much at once
        count = Math.min( count, MAX_READ_BLOCKS );

        rawRead( dst, offset, count );
        return count * blockSize;
    }

    @Override
    protected int implWrite (ByteBuffer src, long position)
    throws IOException {
        // TODO: implement
        return 0;
    }

    @Override
    protected void implCloseChannel()
    throws IOException {
        driver.close();
    }
}

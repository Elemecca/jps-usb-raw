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
import java.nio.ByteOrder;

import javax.usb.UsbDevice;
import javax.usb.UsbException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

class ScsiDriver {
    private static final Logger log = LogManager.getLogger();

    private static final int BUFFER_BLOCKS = 16;
    private static final int MAX_READ_BLOCKS = 128;
    private static final int MAX_WRITE_BLOCKS = 128;

    private final UsbMassStorageDriver driver;
    private final int blockCount, blockSize;
    private final long size;
    private final ByteBuffer buffer;

    ScsiDriver (final UsbMassStorageDriver driver)
    throws IOException {
        this.driver = driver;

        log.trace( "reading device capacity" );
        ByteBuffer data = ByteBuffer.allocate( 8 );
        ByteBuffer cbd = ByteBuffer.allocate( 10 );
        cbd.order( ByteOrder.BIG_ENDIAN );
        cbd.put( 0, (byte) 0x25 ); // READ CAPACITY (10)
        cbd.put( 4, (byte) data.remaining() ); // ALLOCATION LENGTH

        sendCommand( cbd, data, true );
        blockCount = data.getInt( 0 );
        blockSize  = data.getInt( 4 );
        size = blockCount * blockSize;

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "received capacity blocks=%d size=%d",
                    blockCount, blockSize
                ));
        }

        buffer = ByteBuffer.allocate( BUFFER_BLOCKS * blockSize );

        log.trace( "successfully initialized USB Mass Storage channel" );
    }

    public long size() {
        return size;
    }

    public int blockCount() {
        return blockCount;
    }

    public int blockSize() {
        return blockSize;
    }

    private void sendCommand (ByteBuffer command)
    throws IOException {
        sendCommand( command, null, 0, false );
    }

    private void sendCommand (ByteBuffer command, ByteBuffer data, boolean in)
    throws IOException {
        sendCommand( command, data, data.remaining(), in );
    }

    private void sendCommand (ByteBuffer command,
            ByteBuffer data, int dataLength, boolean in)
    throws IOException {
        command.mark();
        data.mark();

        for (int retry = 2; retry >= 0; retry--) try {
            command.reset();
            data.reset();

            boolean ok = driver.sendCommand( command, data, dataLength, in );
            if (ok) return;

            break;
        } catch (UsbMassStorageDriver.RecoverableException caught) {
            log.warn( "caught recoverable USBMS error", caught );

            if (retry <= 0) throw caught;
            else continue;
        }

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
        } else if (offset > Math.pow(2, 32) - 1) {
            throw new UnsupportedOperationException(
                    "only 32-bit LBAs are supported" );
        }

        if (count < 0) {
            throw new IllegalArgumentException( "count may not be negative" );
        } else if (count == 0) {
            return;
        } else if (count > blockCount - offset) {
            throw new IllegalArgumentException(
                    "cannot read past end of volume" );
        } else if (count > Math.pow(2, 16) - 1) {
            throw new UnsupportedOperationException(
                    "only 16-bit read lengths are supported" );
        }

        if (dst == null) {
            throw new IllegalArgumentException( "dst may not be null" );
        } else if (dst.remaining() < count * blockSize) {
            throw new IllegalArgumentException( "dst buffer is not large enough" );
        }

        ByteBuffer cbd = ByteBuffer.allocate( 10 );
        cbd.order( ByteOrder.BIG_ENDIAN );
        cbd.put( 0, (byte) 0x28 ); // READ (10)
        cbd.putInt( 2, (int) offset ); // LOGICAL BLOCK ADDRESS
        cbd.putShort( 7, (short) count );  // TRANSFER LENGTH

        sendCommand( cbd, dst, count * blockSize, true );
    }


    public synchronized int read (ByteBuffer dst, long offset, long count)
    throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException( "offset may not be negative" );
        } else if (offset >= size) {
            return -1;
        }

        if (count < 0) {
            throw new IllegalArgumentException( "count may not be negative" );
        } else if (count == 0) {
            return 0;
        } else if (count > size - offset) {
            throw new IllegalArgumentException( "cannot read past end of volume" );
        }

        if (dst == null) {
            throw new IllegalArgumentException( "dst may not be null" );
        } else if (dst.remaining() < count) {
            throw new IllegalArgumentException( "dst buffer is not large enough" );
        }


        long blockOffset = (long) Math.floor( offset / blockSize );
        int skip = (int)( offset % blockSize );

        long countAdj = Math.min( count, this.size() - offset );
        int blockCount = (int) Math.ceil(
                (double)(countAdj + skip) / (double)blockSize );
        int drop = (int)( (countAdj + skip) % blockSize );
        if (drop != 0) drop = blockSize - drop;

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "read requested offset=%d count=%d count_adj=%d"
                        + " blockOffset=%d blockCount=%d skip=%d drop=%d",
                    offset, count, countAdj, blockOffset, blockCount, skip, drop
                ));
        }


        // if the target buffer has an array (which UsbMassStorageDriver
        // requires), and we don't need to buffer the read to compensate
        // for an unaligned boundary, then read directly into the target
        if (dst.hasArray() && skip == 0
                && (drop == 0 || blockCount > BUFFER_BLOCKS) ) {

            // if the last block is partial but we're not close
            // enough to the end of the target buffer to reach it in a
            // single buffered read, avoid reading the last block
            if (drop != 0) blockCount--;

            // don't read too much at once
            blockCount = Math.min( blockCount, MAX_READ_BLOCKS );

            if (log.isTraceEnabled()) {
                log.trace( String.format(
                        "reading directly blockOffset=0x%08x blockCount=0x%04x",
                        blockOffset, blockCount
                    ));
            }

            rawRead( dst, blockOffset, blockCount );
            return blockCount * blockSize;
        }

        buffer.clear();

        // don't read more than will fit in the read buffer
        if (blockCount > BUFFER_BLOCKS) {
            blockCount = BUFFER_BLOCKS;
            drop = 0;
        }

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "reading indirectly blockOffset=0x%08x blockCount=0x%04x",
                    blockOffset, blockCount
                ));
        }

        rawRead( buffer, blockOffset, blockCount );

        buffer.flip();
        buffer.position( skip );
        buffer.limit( buffer.limit() - drop );

        dst.put( buffer );
        return buffer.position() - skip;
    }




    public synchronized void rawWrite (ByteBuffer src, long offset, int count)
    throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException( "offset may not be negative" );
        } else if (offset >= blockCount) {
            throw new IllegalArgumentException(
                    "offset is beyond end of volume" );
        } else if (offset > Math.pow(2, 32) - 1) {
            throw new UnsupportedOperationException(
                    "only 32-bit LBAs are supported" );
        }

        if (count < 0) {
            throw new IllegalArgumentException( "count may not be negative" );
        } else if (count == 0) {
            return;
        } else if (count > blockCount - offset) {
            throw new IllegalArgumentException(
                    "cannot read past end of volume" );
        } else if (count > Math.pow(2, 16) - 1) {
            throw new UnsupportedOperationException(
                    "only 16-bit read lengths are supported" );
        }

        if (src == null) {
            throw new IllegalArgumentException( "src may not be null" );
        } else if (src.remaining() < count * blockSize) {
            throw new IllegalArgumentException( "src buffer is not large enough" );
        }

        ByteBuffer cbd = ByteBuffer.allocate( 10 );
        cbd.order( ByteOrder.BIG_ENDIAN );
        cbd.put( 0, (byte) 0x2A ); // WRITE (10)
        cbd.putInt( 2, (int) offset ); // LOGICAL BLOCK ADDRESS
        cbd.putShort( 7, (short) count );  // TRANSFER LENGTH

        sendCommand( cbd, src, count * blockSize, false );
    }


    public synchronized int write (ByteBuffer src, long offset, long count)
    throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException( "offset may not be negative" );
        } else if (offset >= size) {
            return -1;
        }

        if (count < 0) {
            throw new IllegalArgumentException( "count may not be negative" );
        } else if (count == 0) {
            return 0;
        } else if (count > size - offset) {
            throw new IllegalArgumentException( "cannot write past end of volume" );
        }

        if (src == null) {
            throw new IllegalArgumentException( "src may not be null" );
        } else if (src.remaining() < count) {
            throw new IllegalArgumentException( "src buffer is not large enough" );
        }


        long blockOffset = (long) Math.floor( offset / blockSize );
        int skip = (int)( offset % blockSize );

        int blockCount = (int) Math.ceil(
                (double)(count + skip) / (double)blockSize );
        int drop = (int)( (count + skip) % blockSize );
        if (drop != 0) drop = blockSize - drop;

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "write requested offset=%d count=%d"
                        + " blockOffset=%d blockCount=%d skip=%d drop=%d",
                    offset, count, blockOffset, blockCount, skip, drop
                ));
        }


        // if the source buffer has an array (which UsbMassStorageDriver
        // requires), and we don't need to buffer the write to compensate
        // for an unaligned boundary, then write directly from the source
        if (src.hasArray() && skip == 0
                && (drop == 0 || blockCount > BUFFER_BLOCKS) ) {

            // if the last block is partial but we're not close
            // enough to the end of the source buffer to reach it in a
            // single buffered write, avoid writing the last block
            if (drop != 0) blockCount--;

            // don't write too much at once
            blockCount = Math.min( blockCount, MAX_WRITE_BLOCKS );

            if (log.isTraceEnabled()) {
                log.trace( String.format(
                        "writing directly blockOffset=0x%08x blockCount=0x%04x",
                        blockOffset, blockCount
                    ));
            }

            rawWrite( src, blockOffset, blockCount );
            return blockCount * blockSize;
        }

        buffer.clear();

        // don't write more than will fit in the buffer
        if (blockCount > BUFFER_BLOCKS) {
            blockCount = BUFFER_BLOCKS;
            drop = 0;
        }

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "writing indirectly blockOffset=0x%08x blockCount=0x%04x",
                    blockOffset, blockCount
                ));
        }

        if (skip != 0 || drop != 0) {
            log.trace( "reading to get unaligned block contents" );
            rawRead( buffer, blockOffset, blockCount );
            buffer.rewind();
        }

        src.get( buffer.array(), skip, (int) count );
        buffer.limit( blockCount * blockSize );

        rawWrite( buffer, blockOffset, blockCount );
        return buffer.position() - skip;
    }



    public void close()
    throws IOException {
        driver.close();
    }
}

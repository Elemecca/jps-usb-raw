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

    private final UsbMassStorageDriver driver;
    private final int blockCount, blockSize;

    UsbMassStorageChannel (final UsbDevice device, final boolean force)
    throws IOException {
        driver = new UsbMassStorageDriver( device, force );

        log.trace( "reading device capacity" );
        ByteBuffer data = ByteBuffer.allocate( 8 );
        ByteBuffer cbd = ByteBuffer.allocate( 10 );
        cbd.put( 0, (byte) 0x25 ); // READ CAPACITY (10)
        cbd.put( 4, (byte) data.remaining() ); // ALLOCATION LENGTH

        driver.sendCommand( cbd, data, true );
        blockCount = data.getInt( 0 );
        blockSize  = data.getInt( 4 );

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "received capacity blocks=%d size=%d",
                    blockCount, blockSize
                ));
        }

        log.trace( "successfully initialized USB Mass Storage channel" );
    }

    @Override
    public long size() {
        return blockCount * blockSize;
    }

    @Override
    protected int implRead (ByteBuffer dst, long position)
    throws IOException {
        // TODO: implement
        return 0;
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

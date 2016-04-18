package com.hifiremote.jpsusbraw;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.usb.UsbDevice;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/** NIO FileChannel implementation for USB Mass Storage devices.
 */
class UsbMassStorageChannel
extends SimpleFileChannel {
    private static final Logger log = LogManager.getLogger();

    private final UsbMassStorageDriver driver;

    UsbMassStorageChannel (final UsbDevice device, final boolean force)
    throws IOException {
        driver = new UsbMassStorageDriver( device, force );
    }

    @Override
    public long size()
    throws IOException {
        // TODO: implement
        return 0;
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

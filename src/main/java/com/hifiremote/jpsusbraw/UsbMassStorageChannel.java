package com.hifiremote.jpsusbraw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

import javax.usb.UsbConfiguration;
import javax.usb.UsbConst;
import javax.usb.UsbDevice;
import javax.usb.UsbEndpoint;
import javax.usb.UsbEndpointDescriptor;
import javax.usb.UsbException;
import javax.usb.UsbInterface;
import javax.usb.UsbInterfaceDescriptor;
import javax.usb.UsbInterfacePolicy;
import javax.usb.UsbIrp;
import javax.usb.UsbPipe;
import javax.usb.UsbStallException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

/** NIO FileChannel implementation for USB Mass Storage devices.
 *
 * We assume that the device has only one configuration, that Bulk-Only
 * Mass Storage is the default setting for its interface, and that the
 * device either does not support multiple LUNs or that we want LUN 0.
 * These assumption are consistent with observed UEI devices. However,
 * the implementation will fail cleanly if they are violated.
 */
class UsbMassStorageChannel
extends SimpleFileChannel {
    private static final Logger log = LogManager.getLogger();

    private final UsbDevice device;
    private final UsbInterface iface;
    private final UsbPipe pipeIn;
    private final UsbPipe pipeOut;
    private final Random rand = new Random();

    UsbMassStorageChannel (final UsbDevice device, final boolean force)
    throws IOException {
        this.device = device;

        // locate the Mass Storage Bulk-Only interface
        UsbInterface foundIface = null;
        UsbConfiguration config = device.getActiveUsbConfiguration();
        for (UsbInterface cand : (List<UsbInterface>) config.getUsbInterfaces()) {
            UsbInterfaceDescriptor desc = cand.getUsbInterfaceDescriptor();

            if (log.isTraceEnabled()) {
                log.trace( String.format(
                        "saw interface num=%02x alt=%02x"
                            + " class=%02x subclass=%02x proto=%02x",
                        desc.bInterfaceNumber(),
                        desc.bAlternateSetting(),
                        desc.bInterfaceClass(),
                        desc.bInterfaceSubClass(),
                        desc.bInterfaceProtocol()
                    ));
            }

            if (desc.bInterfaceClass() == (byte)0x08 // Mass Storage
                    && desc.bInterfaceSubClass() == (byte)0x06 // SCSI
                    && desc.bInterfaceProtocol() == (byte)0x50 // Bulk-Only
                    ) {
                if (log.isDebugEnabled()) {
                    log.debug( String.format(
                            "matched interface num=%02x alt=%02x",
                            desc.bInterfaceNumber(),
                            desc.bAlternateSetting()
                        ));
                }

                foundIface = cand;
                break;
            }
        }

        if (foundIface == null)
            throw new IllegalArgumentException("device has no suitable interface");

        iface = foundIface;

        // locate the in and out bulk endpoints
        UsbEndpoint endIn = null, endOut = null;
        for (UsbEndpoint cand : (List<UsbEndpoint>) iface.getUsbEndpoints()) {
            if (log.isTraceEnabled()) {
                UsbEndpointDescriptor desc = cand.getUsbEndpointDescriptor();
                log.trace( String.format(
                        "saw endpoint addr=%02x attr=%02x",
                        desc.bEndpointAddress(),
                        desc.bmAttributes()
                    ));
            }

            if (cand.getType() == UsbConst.ENDPOINT_TYPE_BULK) {
                if (cand.getDirection() == UsbConst.ENDPOINT_DIRECTION_IN
                        && endIn == null) {
                    log.debug( String.format(
                            "matched IN endpoint %02x",
                            cand.getUsbEndpointDescriptor().bEndpointAddress()
                        ));

                    endIn = cand;
                    if (endOut != null) break;
                }

                if (cand.getDirection() == UsbConst.ENDPOINT_DIRECTION_OUT
                        && endOut == null) {
                    log.debug( String.format(
                            "matched OUT endpoint %02x",
                            cand.getUsbEndpointDescriptor().bEndpointAddress()
                        ));

                    endOut = cand;
                    if (endIn != null) break;
                }
            }
        }

        if (endIn == null)
            throw new IllegalArgumentException("device has no bulk in endpoint");

        if (endOut == null)
            throw new IllegalArgumentException("device has no bulk out endpoint");

        pipeIn  = endIn.getUsbPipe();
        pipeOut = endOut.getUsbPipe();

        // open the device
        try {
            iface.claim( new UsbInterfacePolicy() {
                public boolean forceClaim (UsbInterface target) {
                    return force;
                }
            } );
        } catch (UsbException caught) {
            throw new IOException(
                    "error claiming interface: " + caught.getMessage(),
                    caught
                );
        }

        try {
            pipeIn.open();
        } catch (UsbException caught) {
            throw new IOException(
                    "error opening IN pipe: " + caught.getMessage(),
                    caught
                );
        }

        try {
            pipeOut.open();
        } catch (UsbException caught) {
            throw new IOException(
                    "error opening OUT pipe: " + caught.getMessage(),
                    caught
                );
        }

        log.debug( "successfully initialized USB Mass Storage device" );
    }

    private void clearPipe (UsbPipe pipe)
    throws UsbException {
        device.syncSubmit( device.createUsbControlIrp(
                (byte)( UsbConst.REQUESTTYPE_TYPE_STANDARD
                    | UsbConst.REQUESTTYPE_DIRECTION_OUT
                    | UsbConst.REQUESTTYPE_RECIPIENT_ENDPOINT ),
                UsbConst.REQUEST_CLEAR_FEATURE,
                UsbConst.FEATURE_SELECTOR_ENDPOINT_HALT,
                pipe.getUsbEndpoint()
                    .getUsbEndpointDescriptor()
                    .bEndpointAddress()
            ));
    }

    private void resetBulkOnly()
    throws UsbException {
        device.syncSubmit( device.createUsbControlIrp(
                (byte)( UsbConst.REQUESTTYPE_TYPE_CLASS
                    | UsbConst.REQUESTTYPE_DIRECTION_OUT
                    | UsbConst.REQUESTTYPE_RECIPIENT_INTERFACE),
                (byte) 0xFF, // Bulk-Only Mass Storage Reset
                (byte) 0x00, // no parameter for this request
                iface.getUsbInterfaceDescriptor()
                    .bInterfaceNumber()
            ));
    }

    private void resetRecovery()
    throws UsbException {
        // BBB 5.3.4
        resetBulkOnly();
        clearPipe( pipeIn );
        clearPipe( pipeOut );
    }

    private void sendCommand (ByteBuffer command, ByteBuffer data, boolean in)
    throws UsbException {
        if (command == null)
            throw new IllegalArgumentException("command may not be null");

        if (command.remaining() > 16 || command.remaining() < 1)
            throw new IllegalArgumentException("invalid command length");

        ByteBuffer cbw = ByteBuffer.allocate( 31 );
        cbw.order( ByteOrder.LITTLE_ENDIAN );

        // write the fixed signature to dCBWSignature
        cbw.putInt( 0, 0x43425355 );

        // write a random tag to dCBWTag
        final int tag = rand.nextInt();
        cbw.putInt( 4, tag );

        if (data != null) {
            // store length in dCBWDataTransferLength
            cbw.putInt( 8, data.remaining() );

            // store direction in bmCBWFlags
            cbw.put( 12, (byte)(in ? 0x80 : 0x00) );
        }

        // store command size in bCBWCBLength
        cbw.put( 14, (byte) command.remaining() );

        // store command in CBWCB
        cbw.position( 15 );
        cbw.put( command );


        UsbIrp cbwIrp = pipeOut.createUsbIrp();
        cbwIrp.setData( cbw.array() );
        cbwIrp.setAcceptShortPacket( true );


        UsbIrp dataIrp = null;
        if (data != null) {
            dataIrp = (in ? pipeIn : pipeOut).createUsbIrp();
            dataIrp.setData( data.array(), data.position(), data.remaining() );
            dataIrp.setAcceptShortPacket( false );
        }


        ByteBuffer csw = ByteBuffer.allocate( 13 );
        csw.order( ByteOrder.LITTLE_ENDIAN );

        UsbIrp cswIrp = pipeIn.createUsbIrp();
        cswIrp.setData( csw.array() );
        cswIrp.setAcceptShortPacket( true );


        try {
            pipeOut.syncSubmit( cbwIrp );
        } catch (UsbStallException caught) {
            // BBB 6.6.1 - the CBW is not valid
            // BBB 5.3.1 - host must perform Reset Recovery
            // TODO: implement async reset recovery
        }

        if (dataIrp != null) try {
            (in ? pipeIn : pipeOut).syncSubmit( dataIrp );
        } catch (UsbStallException caught) {
            // BBB 6.7.2 host 3 - clear the Bulk-In pipe and read CSW
            // BBB 6.7.3 host 3 - clear the Buld-Out pipe and read CSW
            clearPipe( in ? pipeIn : pipeOut );
        }

        try {
            pipeIn.syncSubmit( cswIrp );
        } catch (UsbStallException caught) {
            clearPipe( pipeIn );

            cswIrp = pipeIn.createUsbIrp();
            cswIrp.setData( csw.array() );
            cswIrp.setAcceptShortPacket( true );

            try {
                pipeIn.syncSubmit( cswIrp );
            } catch (UsbStallException caughtAgain) {
                // BBB fig 2 - host must perform Reset Recovery
                // TODO: implement async reset recovery
            }
        }

        // check static signature in dCSWSignature
        if (csw.getInt( 0 ) != 0x53425355) {
            // BBB fig 2 - host must perform Reset Recovery
            // TODO: implement async reset recovery
        }

        // check dCSWTag matches value from CDW
        if (csw.getInt( 4 ) != tag) {
            // BBB fig 2 - host must perform Reset Recovery
            // TODO: implement async reset recovery
        }

        // advance the data buffer position by the number of
        // valid bytes transferred from dCSWDataResidue
        if (data != null) {
            data.position( data.position()
                    + data.remaining() // the amount we expected to move
                    - csw.getInt( 8 ) // the part of that which wasn't moved
                );
        }

        // check the status in bCSWStatus
        final byte status = csw.get( 12 );
        switch (status) {
        case 0x00: // Command Passed
            break;

        case 0x01: // Command Failed
            throw new RuntimeException( "command failed" );

        case 0x02: // Phase Error
            // BBB 5.3.3.1 - host must perform Reset Recovery
            // TODO: implement async reset recovery
            break;

        default:
            throw new RuntimeException( "unknown CSW status " + status );
        }
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
        try {
            pipeOut.abortAllSubmissions();
            pipeOut.close();
        } catch (UsbException caught) {
            throw new IOException( "error closing OUT pipe", caught );
        }

        try {
            pipeIn.abortAllSubmissions();
            pipeIn.close();
        } catch (UsbException caught) {
            throw new IOException( "error closing IN pipe", caught );
        }

        try {
            iface.release();
        } catch (UsbException caught) {
            throw new IOException( "error releasing interface", caught );
        }
    }
}

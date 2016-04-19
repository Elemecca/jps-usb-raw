package com.hifiremote.jpsusbraw;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;

import javax.usb.UsbConfiguration;
import javax.usb.UsbDevice;
import javax.usb.UsbDeviceDescriptor;
import javax.usb.UsbHostManager;
import javax.usb.UsbHub;
import javax.usb.UsbInterface;
import javax.usb.UsbInterfaceDescriptor;
import javax.usb.UsbPort;
import javax.usb.UsbException;

import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

public class JpsUsbRaw {
    private static final Logger log = LogManager.getLogger();
    private static List<DevicePath> devices = null;

    private static boolean checkDeviceSupported (UsbDeviceDescriptor desc) {
        return desc.idVendor() == (short)0x06e7
            && desc.idProduct() == (short)0x8020;
    }

    private static void walkDevices (
            List<DevicePath> results, UsbHub hub, DevicePathBuilder path)
    throws UsbException {
        for (UsbDevice device : (List<UsbDevice>) hub.getAttachedUsbDevices()) {
            UsbDeviceDescriptor desc = device.getUsbDeviceDescriptor();
            path.set( device.getParentUsbPort().getPortNumber() );

            if (log.isTraceEnabled()) {
                log.trace( String.format(
                        "saw USB device vnd=%04x dev=%04x path=%s",
                        desc.idVendor(), desc.idProduct(),
                        path.toString()
                    ));
            }

            if (checkDeviceSupported( desc )) {
                if (log.isDebugEnabled()) {
                    log.debug( String.format(
                            "matched USB device vnd=%04x dev=%04x path=%s",
                            desc.idVendor(), desc.idProduct(),
                            path.toString()
                        ));
                }

                results.add( path.toDevicePath() );
            }

            if (device.isUsbHub()) {
                path.push();
                walkDevices( results, (UsbHub) device, path );
                path.pop();
            }
        }
    }

    public static List<DevicePath> findDevices()
    throws UsbException {
        log.debug("searching for supported USB devices...");
        UsbHub rootHub = UsbHostManager.getUsbServices().getRootUsbHub();
        List<DevicePath> results = new ArrayList<DevicePath>();
        walkDevices( results, rootHub, new DevicePathBuilder() );

        log.debug("found " + results.size() + " devices");
        devices = results;
        return results;
    }

    public static List<DevicePath> getDevices()
    throws UsbException {
        if (null == devices) return findDevices();
        else return devices;
    }

    /**
     * @throws IllegalStateException if the path given no longer refers
     *                               to a valid and supported device
     */
    public static JpsUsbRaw open (DevicePath path)
    throws UsbException, IOException {
        // walk the USB hub tree to find the device matchng the given path
        byte[] pathArray = path.toArray();
        UsbDevice device = UsbHostManager.getUsbServices().getRootUsbHub();
        for (int idx = 0; idx < pathArray.length; idx++) {
            if (!device.isUsbHub()) {
                throw new IllegalStateException(
                        "USB device at depth " + idx
                        + " is not a hub, but has children"
                    );
            }

            UsbPort port = ((UsbHub)device).getUsbPort( pathArray[ idx ] );
            if (null == port) {
                throw new IllegalStateException(
                        "USB hub at depth " + idx
                        + " does not have a port " + pathArray[ idx ]
                    );
            }

            device = port.getUsbDevice();
            if (null == port) {
                throw new IllegalStateException(
                        "no device is attached to port "
                        + port.getPortNumber()
                        + " of USB hub at depth " + idx
                    );
            }
        }

        // check that the found device is still a supported device
        UsbDeviceDescriptor desc = device.getUsbDeviceDescriptor();
        if (!checkDeviceSupported( desc )) {
            throw new IllegalStateException(
                    "provided device is not supported"
                );
        }

        if (log.isDebugEnabled()) {
            log.debug( String.format(
                    "opening USB device vnd=%04x dev=%04x path=%s",
                    desc.idVendor(), desc.idProduct(),
                    path.toString()
                ));
        }

        // actually open the thing
        return new JpsUsbRaw( device );
    }

    private final UsbMassStorageChannel storage;
    private final int partOffset, partLength;

    private JpsUsbRaw (UsbDevice device)
    throws IOException {
        storage = new UsbMassStorageChannel( device, true );

        log.debug( "reading partition table" );

        ByteBuffer mbr = ByteBuffer.allocate( 512 );
        mbr.order( ByteOrder.LITTLE_ENDIAN );
        while (mbr.remaining() > 0
                && storage.read( mbr, mbr.position() ) > 0);

        short signature = mbr.getShort( 0x1FE );
        if (signature != (short) 0xAA55) {
            throw new IOException( String.format(
                        "invalid MBR signature 0x%04X", signature ));
        }

        byte partType = mbr.get( 0x1BE + 0x4 );
        if (partType != (byte) 0x01) {
            throw new IOException( String.format(
                        "unrecognized partition type 0x%02X", partType ));
        }

        partOffset = mbr.getInt( 0x1BE + 0x8 );
        int length = mbr.getInt( 0x1BE + 0xC );

        if (log.isDebugEnabled()) {
            log.debug( String.format(
                    "using partition 1: offset=%d length=%d",
                    partOffset, length
                ));
        }

        // sometimes the partition length is set incorrectly
        if (partOffset + length > storage.blockCount()) {
            log.warn( String.format(
                    "partition length %d puts end %d after"
                        + " volume end %d, correcting to %d",
                    length, partOffset + length, storage.blockCount(),
                    storage.blockCount() - partOffset
                ));

            length = (int)( storage.blockCount() - partOffset );
        }
        partLength = length;
    }
}

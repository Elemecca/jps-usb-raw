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
import java.io.UnsupportedEncodingException;
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

public class JpsUsbRaw
extends SimpleFileChannel {
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

    static UsbDevice getDevice (DevicePath path)
    throws UsbException {
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

        return device;
    }


    /**
     * @throws IllegalStateException if the path given no longer refers
     *                               to a valid and supported device
     */
    public static JpsUsbRaw open (DevicePath path)
    throws IOException {
        UsbDevice device;

        try {
            device = getDevice( path );

            if (log.isDebugEnabled()) {
                UsbDeviceDescriptor desc = device.getUsbDeviceDescriptor();
                log.debug( String.format(
                        "opening USB device vnd=%04x dev=%04x path=%s",
                        desc.idVendor(), desc.idProduct(),
                        path.toString()
                    ));
            }
        } catch (UsbException caught) {
            throw new IOException(
                    "error locating device: " + caught.getMessage(),
                    caught
                );
        }

        // actually open the thing
        return new JpsUsbRaw( device );
    }

    private final ScsiDriver storage;
    private final int partOffset, partLength;
    private final int fileOffset, fileLength;
    private final long fileOffsetAbs;

    private JpsUsbRaw (UsbDevice device)
    throws IOException {
        storage = new ScsiDriver( new UsbMassStorageDriver( device, true ) );

        log.debug( "reading partition table" );

        ByteBuffer mbr = ByteBuffer.allocate( 512 );
        mbr.order( ByteOrder.LITTLE_ENDIAN );
        storage.rawRead( mbr, 0, 1 );

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



        log.debug( "reading FAT system area" );

        ByteBuffer sys = ByteBuffer.allocate( storage.blockSize() * 16 );
        sys.order( ByteOrder.LITTLE_ENDIAN );
        storage.rawRead( sys, partOffset, 16 );

        // layout from 107-9.2
        final short sectorSize  = sys.getShort( 11 );
        final byte  clusterSize = sys.get( 13 );
        final short reserved    = sys.getShort( 14 );
        final byte  fatCount    = sys.get( 16 );
        final short dirEntries  = sys.getShort( 17 );
        final short sectorCount = sys.getShort( 19 );
        final short fatSize     = sys.getShort( 22 );

        // defined in 107-6.3.4
        final short systemSize = (short)(
                reserved + fatCount * fatSize
                + Math.ceil( 32 * dirEntries / sectorSize )
            );;

        // defined in 107-10.2.4
        final short clusterCount = (short) Math.floor(
                (sectorCount - systemSize) / clusterSize );

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "FAT parameters: sectorSize=%d sectorCount=%d"
                        + " clusterSize=%d clusterCount=%d"
                        + " fatSize=%d fatCount=%d"
                        + " reserved=%d system=%d dirEntries=%d",
                    sectorSize, sectorCount, clusterSize, clusterCount,
                    fatSize, fatCount, reserved, systemSize, dirEntries
                ));
        }

        if (sectorSize != storage.blockSize()) {
            throw new IOException( String.format(
                    "FAT sector size %d is not equal to device block size %d",
                    sectorSize, storage.blockSize()
                ));
        }

        // extract the first FAT from the system area
        // position defined in 107-6.3.2
        sys.position( reserved * sectorSize );
        sys.limit( sys.position() + fatSize * sectorSize );
        ByteBuffer fat = sys.slice();
        fat.order( ByteOrder.LITTLE_ENDIAN );

        // extract the root directory
        // position defined in 107-6.3.3
        sys.position( (reserved + fatCount * fatSize) * sectorSize );
        sys.limit( sys.position() + dirEntries * 32 );
        ByteBuffer dir = sys.slice();
        dir.order( ByteOrder.LITTLE_ENDIAN );

        // find the SETTINGS.BIN file in the root directory
        // directory format defined in 107-11
        short fileCluster = 0;
        int foundLength = 0;
        for (int entry = 0; entry < dirEntries; entry++) {
            byte first = dir.get( entry * 32 +  0 );
            byte flags = dir.get( entry * 32 + 11 );

            // skip unused and non-file entries
            if (first == 0x00 || first == 0xE5 || (flags & 0x18) != 0)
                continue;

            String name = readString( dir, entry * 32 + 0, 8 );
            String ext  = readString( dir, entry * 32 + 8, 3 );

            if (log.isTraceEnabled()) {
                log.trace( String.format(
                        "considering file %d: '%s.%s'",
                        entry, name, ext
                    ));
            }

            if ("SETTINGS".equalsIgnoreCase( name )
                    && "BIN".equalsIgnoreCase( ext )) {
                fileCluster = dir.getShort( entry * 32 + 26 );
                foundLength  = dir.getInt(   entry * 32 + 28 );

                if (log.isTraceEnabled()) {
                    log.trace( String.format(
                            "matched file %d: cluster=0x%03x length=0x%08x",
                            entry, fileCluster, foundLength
                        ));
                }

                break;
            }
        }

        if (fileCluster == 0) {
            throw new IOException( "file SETTINGS.BIN not found" );
        }

        fileOffset = (fileCluster - 2) * clusterSize + systemSize;
        fileOffsetAbs = (partOffset + fileOffset) * sectorSize;
        fileLength = foundLength;

        if (log.isDebugEnabled()) {
            log.debug( String.format(
                    "file offset(sectors)=%d abs_offset(bytes)=%d length(bytes)=%d",
                    fileOffset, fileOffsetAbs, fileLength
                ));
        }

        // read the FAT to ensure the file is contiguous
        // FAT layout defined in 107-10
        // 12-bit int packing method defined in 107-8.4
        final int fileSizeClusters = (int) Math.ceil(
                fileLength / (clusterSize * sectorSize) );
        boolean lastCluster = false;
        short cluster = fileCluster;
        for (int idx = 0; idx <= fileSizeClusters; idx++) {
            short offset = (short)( Math.floor( (cluster - 2) / 2 ) * 3 + 3 );
            short value;

            if (cluster % 2 == 0) {
                value = (short)( fat.getShort( offset ) & 0x0FFF );
            } else {
                value = (short)( (fat.getShort( offset + 1 ) & 0xFFF0) >>> 4 );
            }

            if (log.isTraceEnabled()) {
                log.trace( String.format(
                        "cluster %03d num=0x%03x offs=0x%03x value=0x%03x",
                        idx, cluster, offset, value
                    ));
            }

            if (value >= 0xFF8 && value <= 0xFFF) {
                log.trace( "last cluster" );
                lastCluster = true;
                break;
            } else if (value >= 2 && value < clusterCount + 2) {
                if (value != cluster + 1) {
                    throw new IOException( String.format(
                            "discontiguity at cluster 0x%03x (to 0x%03x);"
                                + " discontiguous files are not supported",
                            cluster, value
                        ));
                }

                cluster = value;
            } else {
                throw new IOException( String.format(
                        "invalid value for cluster %03x: %03x",
                        cluster, value
                    ));
            }
        }

        if (!lastCluster) throw new IOException(
                "did not reach last cluster of file" );
    }

    private static String readString (ByteBuffer buffer, int offset, int length) {
        byte[] array = new byte[ length ];
        buffer.position( offset );
        buffer.get( array );

        try {
            return new String( array, 0, length,  "US-ASCII" );
        } catch (UnsupportedEncodingException caught) {
            // US-ASCII is a required encoding
            throw new RuntimeException( caught );
        }
    }

    @Override
    public long size() {
        return fileLength;
    }

    public int blockSize() {
        return storage.blockSize();
    }

    @Override
    protected synchronized int implRead (ByteBuffer dst, long position)
    throws IOException {
        long offset = position + fileOffsetAbs;
        long count = Math.min( dst.remaining(), fileLength - position );

        if (log.isTraceEnabled()) {
            log.trace( String.format(
                    "read requested offset=%d count=%d volOffset=%d volCount=%d",
                    position, dst.remaining(), offset, count
                ));
        }

        return storage.read( dst, offset, count );
    }

    @Override
    protected int implWrite (ByteBuffer src, long position)
    throws IOException {
        // TODO: implement
        throw new UnsupportedOperationException();
    }

    @Override
    protected void implCloseChannel()
    throws IOException {
        storage.close();
    }
}

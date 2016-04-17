package com.hifiremote.jpsusbraw;

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
    private static Logger log = LogManager.getLogger();

    private static void searchDevices (
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

            if (desc.idVendor() == (short)0x06e7 && desc.idProduct() == (short)0x8020) {
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
                searchDevices( results, (UsbHub) device, path );
                path.pop();
            }
        }
    }

    public static List<DevicePath> getDevices()
    throws UsbException {
        log.debug("searching for supported USB devices...");
        UsbHub rootHub = UsbHostManager.getUsbServices().getRootUsbHub();
        List<DevicePath> devices = new ArrayList<DevicePath>();
        searchDevices( devices, rootHub, new DevicePathBuilder() );

        log.debug("found " + devices.size() + " devices");
        return devices;
    }
}

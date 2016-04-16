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

class JpsUsbRaw {
    private static Logger log = LogManager.getLogger();

    private static String makeDevicePath (UsbDevice device)
    throws UsbException {
        UsbPort port = device.getParentUsbPort();
        String portNumber = String.valueOf( port.getPortNumber() );

        UsbHub hub = port.getUsbHub();
        if (!hub.isRootUsbHub()) {
            return makeDevicePath( hub ) + "." + portNumber;
        } else {
            return portNumber;
        }
    }

    private static void searchDevices (List<UsbDevice> results, UsbHub hub)
    throws UsbException {
        for (UsbDevice device : (List<UsbDevice>) hub.getAttachedUsbDevices()) {
            UsbDeviceDescriptor desc = device.getUsbDeviceDescriptor();

            if (log.isTraceEnabled()) {
                log.trace( String.format(
                        "saw USB device vnd=%04x dev=%04x path=%s",
                        desc.idVendor(), desc.idProduct(),
                        makeDevicePath( device )
                    ));
            }

            if (desc.idVendor() == (short)0x06e7 && desc.idProduct() == (short)0x8020) {
                if (log.isDebugEnabled()) {
                    log.debug( String.format(
                            "matched USB device vnd=%04x dev=%04x path=%s",
                            desc.idVendor(), desc.idProduct(),
                            makeDevicePath( device )
                        ));
                }

                results.add( device );
            }

            if (device.isUsbHub()) {
                searchDevices( results, (UsbHub) device );
            }
        }
    }

    public static List<UsbDevice> getDevices()
    throws UsbException {
        log.debug("searching for supported USB devices...");
        UsbHub rootHub = UsbHostManager.getUsbServices().getRootUsbHub();
        List<UsbDevice> devices = new ArrayList<UsbDevice>();
        searchDevices( devices, rootHub );

        log.debug("found " + devices.size() + " devices");
        return devices;
    }
}

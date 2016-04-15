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

class JpsUsbRaw {

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

            System.out.println( String.format(
                    "dev %s vnd=%04x:%04x dev=%04x:%04x class=%02x",
                    makeDevicePath( device ),
                    desc.idVendor(), 0x06e7,
                    desc.idProduct(), 0x8020,
                    desc.bDeviceClass()
                ));


            if (desc.idVendor() == (short)0x06e7 && desc.idProduct() == (short)0x8020) {
                System.out.println( "  => MATCH" );
                results.add( device );
            }

            if (device.isUsbHub()) {
                searchDevices( results, (UsbHub) device );
            }
        }
    }

    public static List<UsbDevice> getDevices()
    throws UsbException {
        UsbHub rootHub = UsbHostManager.getUsbServices().getRootUsbHub();
        List<UsbDevice> devices = new ArrayList<UsbDevice>();
        searchDevices( devices, rootHub );

        System.out.println( "count: " + devices.size() );
        return devices;
    }
}

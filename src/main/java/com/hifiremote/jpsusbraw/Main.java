package com.hifiremote.jpsusbraw;

import java.util.List;

class Main {
    public static void main (String[] args)
    throws Exception {
        List<DevicePath> devices = JpsUsbRaw.getDevices();
        if (devices.size() < 1) {
            System.out.println( "no devices found" );
            return;
        }

        JpsUsbRaw settings = JpsUsbRaw.open( devices.get( 0 ) );
    }
}

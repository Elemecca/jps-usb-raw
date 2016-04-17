package com.hifiremote.jpsusbraw;

import java.util.Arrays;

import com.hifiremote.jpsusbraw.DevicePath;

class DevicePathBuilder {
    private byte[] path = new byte[7]; // the USB tree is limited depth
    private int level = 0;

    public void push() {
        if (level >= 7)
            throw new IllegalStateException("the USB supports only seven levels");

        level++;
    }

    public void pop() {
        if (level <= 0)
            throw new IllegalStateException("level may not be negative");

        path[ level ] = 0;
        level--;
    }

    public void set (byte value) {
        path[ level ] = value;
    }

    public DevicePath toDevicePath() {
        int last = level;

        if (path[ last ] == 0)
            last--;

        return new DevicePath( Arrays.copyOf( path, last + 1 ) );
    }

    public String toString() {
        return Arrays.toString( path );
    }
}

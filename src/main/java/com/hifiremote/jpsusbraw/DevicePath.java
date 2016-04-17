package com.hifiremote.jpsusbraw;

import java.util.Arrays;

public class DevicePath {
    private final byte[] path;

    DevicePath (byte[] path) {
        this.path = path;
    }

    public String toString() {
        return Arrays.toString( path );
    }
}

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

import java.util.Arrays;

public class DevicePath {
    private final byte[] path;

    DevicePath (byte[] path) {
        this.path = path;
    }

    public String toString() {
        return Arrays.toString( path );
    }

    public byte[] toArray() {
        return Arrays.copyOf( path, path.length );
    }
}

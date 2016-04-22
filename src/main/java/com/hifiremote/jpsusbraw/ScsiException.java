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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

class ScsiException
extends IOException {
    private static final String[] SENSE = {
            "No Sense",
            "Recovered Error",
            "Not Ready",
            "Medium Error",
            "Hardware Error",
            "Illegal Request",
            "Unit Attention",
            "Data Protect",
            "Blank Check",
            "Vendor Specific",
            "Copy Aborted",
            "Aborted Command",
            "[obsolete]",
            "Volume Overflow",
            "Miscompare",
            "[reserved]"
        };

    private final ByteBuffer raw;
    private final byte key, addl, qual;
    private String message = null;

    ScsiException (byte[] sense) {
        raw = ByteBuffer.wrap( Arrays.copyOf( sense, 252 ) );
        raw.order( ByteOrder.BIG_ENDIAN );

        key  = (byte)( raw.get( 2 ) & 0x0F );
        addl = raw.get( 12 );
        qual = raw.get( 13 );
    }

    @Override
    public String getMessage() {
        if (message != null)
            return message;

        StringBuilder str = new StringBuilder();
        str.append( String.format( "SCSI error sense: %01xh ", key ));
        str.append( SENSE[ key ] );
        str.append( String.format( ", add'l: %02xh", addl ));
        str.append( String.format( ", qual: %02xh", qual ));

        message = str.toString();
        return message;
    }
}

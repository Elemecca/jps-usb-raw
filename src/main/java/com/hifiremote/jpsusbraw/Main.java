package com.hifiremote.jpsusbraw;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.util.List;

import javax.usb.UsbException;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.ParameterException;

class Main {
    public static void main (String[] args) {
        (new Main()).run( args );
    }

    @Parameter( names="--help", help=true )
    private boolean help;

    @Parameter( names={ "-d", "--debug" },
            description="print extra debugging information" )
    private boolean debug;

    public void run (String[] args) {
        final JCommander cmd = new JCommander( this );

        cmd.addCommand( new CommandRead() );

        try {
            cmd.parse( args );
        } catch (ParameterException caught) {
            StringBuilder msg = new StringBuilder();
            msg.append( caught.getMessage() );
            msg.append( "\n\n" );
            cmd.usage( msg );

            System.err.println( msg );
            System.exit( 1 );
        }

        String cmdName = cmd.getParsedCommand();

        if (help) {
            if (cmdName != null) {
                cmd.usage( cmd.getParsedCommand() );
            } else {
                cmd.usage();
            }
            System.exit( 1 );
        }

        if (null == cmdName) {
            StringBuilder msg = new StringBuilder();
            msg.append( "no command specified\n\n" );
            cmd.usage( msg );

            System.err.println( msg );
            System.exit( 1 );
        }

        JCommander cmdArgs = (JCommander) cmd.getCommands().get( cmdName );
        Command    cmdImpl = (Command) cmdArgs.getObjects().get( 0 );

        if (cmdImpl.help) {
            cmd.usage( cmdName );
            System.exit( 1 );
        }

        try {
            cmdImpl.cmd = cmdArgs;
            cmdImpl.run();
        } catch (Exception caught) {
            String message = caught.getMessage();
            if (message != null) {
                System.err.println( "error: " + message );
            } else {
                System.err.println( "an unknown error occurred: " + caught );
            }

            if (debug) caught.printStackTrace( System.err );

            System.exit( 2 );
        }
    }

    private abstract class Command {
        public JCommander cmd;

        @Parameter( names="--help", help=true )
        private boolean help;

        public abstract void run()
        throws Exception;
    }

    @Parameters( commandNames="read",
        commandDescription="read the settings from the device to a file" )
    private class CommandRead
    extends Command {
        @Parameter( description="file", arity=1, required=true )
        private List<String> files;

        @Parameter( names={ "-o", "--overwrite" },
                description="write the output file even if it exists" )
        private boolean overwrite;

        @Parameter( names={ "-c", "--check", "--verify" },
                description="after writing, read back and verify the contents" )
        private boolean verify;

        public void run()
        throws Exception {
            if (files.size() != 1) {
                StringBuilder builder = new StringBuilder();
                builder.append( "exactly one file is required\n" );
                cmd.usage( builder );
                System.err.println( builder );
                System.exit( 1 );
            }

            File file = new File( files.get( 0 ) );
            if (file.isFile()) {
                if (!overwrite) {
                    System.err.println( "file '" + file
                            + "' exists, refusing to overwrite" );
                    System.exit( 3 );
                }
            } else if (file.exists()) {
                System.err.println( "path '" + file
                        + "' exists but is not a file, aborting" );
                System.exit( 3 );
            }

            JpsUsbRaw settings = openDefaultDevice();
            if (settings == null) {
                System.err.println( "no supported device found" );
                System.exit( 3 );
            }

            RandomAccessFile stream = new RandomAccessFile( file, "rw" );
            FileChannel channel = stream.getChannel();
            channel.truncate( 0 );


            long offset = 0;
            long length = settings.size();
            while (length > 0) {
                long count = channel.transferFrom( settings, offset, length );
                offset += count;
                length -= count;
            }

            if (verify) {
                long completed = 0;

                if (channel.size() != settings.size()) {
                    System.err.println( "verification failed:"
                            + " file is " + channel.size()
                            + " bytes, but settings are "
                            + settings.size() + " bytes"
                        );
                    System.exit( 5 );
                }

                channel.position( 0 );
                settings.position( 0 );

                ByteBuffer expected = ByteBuffer.allocate( settings.blockSize() * 64 );
                ByteBuffer actual   = ByteBuffer.allocate( expected.capacity() );

                while (completed < settings.size()) {
                    expected.clear();
                    actual.clear();

                    while (expected.remaining() > 0
                            && -1 != settings.read( expected ));
                    expected.flip();

                    actual.limit( expected.limit() );
                    while (actual.remaining() > 0
                            && -1 != channel.read( actual ));
                    actual.flip();

                    if (0 != expected.compareTo( actual )) {
                        System.err.println( "verification failed:"
                                + " contents differ at byte "
                                + (completed + expected.position())
                            );
                        System.exit( 5 );
                    }

                    completed += expected.position();
                }
            }

            channel.close();
            settings.close();
        }
    }

    private JpsUsbRaw openDefaultDevice()
    throws IOException, UsbException {
        List<DevicePath> devices = JpsUsbRaw.getDevices();
        if (devices.size() < 1) {
            return null;
        }

        return JpsUsbRaw.open( devices.get( 0 ) );
    }
}

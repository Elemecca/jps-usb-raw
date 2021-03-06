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

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;

class Main {
    public static void main (String[] args) {
        (new Main()).run( args );
    }

    @Parameter( names="--help", help=true )
    private boolean help;

    @Parameter( names={ "-v", "--verbose" },
            description="numeric level of log output" )
    private int verbose = 0;

    public void run (String[] args) {
        final JCommander cmd = new JCommander( this );

        cmd.addCommand( new CommandRead() );
        cmd.addCommand( new CommandWrite() );

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

        setupLogging();

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

            if (verbose > 0)
                caught.printStackTrace( System.err );

            System.exit( 2 );
        }
    }

    private void setupLogging() {
        Level level;
        if (verbose >= 3) {
            level = Level.TRACE;
        } else if (verbose >= 2) {
            level = Level.DEBUG;
        } else if (verbose >= 1) {
            level = Level.INFO;
        } else {
            level = Level.ERROR;
        }

        ConfigurationBuilder<?> builder =
            ConfigurationBuilderFactory.newConfigurationBuilder();

        builder.setStatusLevel( Level.ERROR );
        builder.setConfigurationName( "JpsUsbRaw" );

        builder.add( builder.newAppender( "STDOUT", "Console" )
                .add( builder.newLayout( "PatternLayout" )
                        .addAttribute( "pattern",
                                "%d{HH:mm:ss.SSSS} [%t] %-5level %c{1.}  %msg%n"
                            )
                    )
            );

        builder.add( builder.newRootLogger( Level.ERROR )
                .add( builder.newAppenderRef( "STDOUT" ) )
            );

        builder.add( builder.newLogger( "com.hifiremote.jpsusbraw", level ));

        Configurator.initialize( builder.build() );
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
                System.out.println( "read completed, beginning verification..." );
                verify( settings, channel, settings.blockSize() );
                System.out.println( "verification completed successfully" );
            }

            channel.close();
            settings.close();
        }
    }




    @Parameters( commandNames="write",
        commandDescription="write the settings from a file to the device" )
    private class CommandWrite
    extends Command {
        @Parameter( description="file", arity=1, required=true )
        private List<String> files;

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
            if (!file.isFile()) {
                if (file.exists()) {
                    System.err.println( "path '" + file
                            + "' exists but is not a file, aborting" );
                } else {
                    System.err.println( "file '" + file + "' does not exist" );
                }
                System.exit( 3 );
            }

            JpsUsbRaw settings = openDefaultDevice();
            if (settings == null) {
                System.err.println( "no supported device found" );
                System.exit( 3 );
            }

            RandomAccessFile stream = new RandomAccessFile( file, "r" );
            FileChannel channel = stream.getChannel();


            if (channel.size() != settings.size()) {
                System.err.println( "input file is "
                        + channel.size() + " bytes, but settings should be "
                        + settings.size() + " bytes"
                    );
                System.exit( 3 );
            }

            long offset = 0;
            long length = channel.size();
            while (length > 0) {
                long count = channel.transferTo( offset, length, settings );
                offset += count;
                length -= count;
            }


            if (verify) {
                System.out.println( "write completed, beginning verification..." );
                verify( channel, settings, settings.blockSize() );
                System.out.println( "verification completed successfully" );
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

    private void verify (FileChannel chanExpected,
            FileChannel chanActual, int blockSize)
    throws IOException {
        long completed = 0;

        if (chanActual.size() != chanExpected.size()) {
            System.err.println( "verification failed:"
                    + " file is " + chanActual.size()
                    + " bytes, but chanExpected are "
                    + chanExpected.size() + " bytes"
                );
            System.exit( 5 );
        }

        chanActual.position( 0 );
        chanExpected.position( 0 );

        ByteBuffer expected = ByteBuffer.allocate( blockSize * 32 );
        ByteBuffer actual   = ByteBuffer.allocate( expected.capacity() );

        while (completed < chanExpected.size()) {
            expected.clear();
            actual.clear();

            while (expected.remaining() > 0
                    && -1 != chanExpected.read( expected ));
            expected.flip();

            actual.limit( expected.limit() );
            while (actual.remaining() > 0
                    && -1 != chanActual.read( actual ));
            actual.flip();

            if (0 != expected.compareTo( actual )) {
                System.err.println( "verification failed:"
                        + " contents differ at byte "
                        + (completed + expected.position())
                    );
                System.exit( 5 );
            }

            completed += expected.limit();
        }
    }
}

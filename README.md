JpsUsbRaw is a tool for manipulating the settings file on UEI universal
remotes that have a USB interface without using the operating system's
implementations of USB Mass Storage or the FAT file system.

Some universal remote controls manufactured by [UEI] have a USB port for
configuration, notably the OARUSB04G in the US and the URC-6440 in the
EU. These remotes expose a FAT-formatted drive to the computer via USB
Mass Storage which contains a file called `settings.bin`. The settings
file is intended to be uploaded to [SimpleSet], a web application for
editing the remote's settings. It can also be manipulated by [RMIR], a
much more powerful editing application created by the community at the
[JP1 Remotes Forum][jp1].

Unfortunately these devices don't implement the USB Mass Storage and FAT
standards entirely correctly. While they work just fine on Windows,
certain write operations fail under Linux and OSX. This project
interacts with the remote directly using [`libusb`][libusb] and provides
implementations of USB Mass Storage and the FAT file system tailored to
the behavior of UEI devices. It can therefore be used to read or write
the `settings.bin` file even on platforms whose native implementations
aren't compatible.

[UEI]: http://uei.com/north-america/branded-retail
[SimpleSet]: http://simpleset.com/
[RMIR]: https://sourceforge.net/projects/controlremote/
[jp1]: http://www.hifi-remote.com/forums/viewforum.php?f=9
[libusb]: http://libusb.info/



## Usage

Unfortunately the project isn't quite done enough to be usable yet.

### Linux

Fortunately `libusb` already works just fine on Linux, and we can take
control of USB devices away from the platform drivers just by asking.
If you want to be able to write to your remote without being root
(*strongly* recommended), you need to make sure that your user has
write access to the device. First make sure your user is a member of
the `dialout` group, then install the following snippet as
`/etc/udev/rules.d/80-jps-usb-raw.rules`. That file provides rules
for the device manager that ask it to make UEI remote devices available
to the `dialout` group. If your remote is alreay plugged in, you'll
need to unplug it and plug it back in for the new rules to take effect.

```udev
# /etc/udev/rules.d/80-jps-usb-raw.rules
# Allow 'dialout' group access to UEI universal remotes with USB interfaces.
SUBSYSTEM=="usb", ATTR{idVendor}=="06e7", ATTR{idProduct}=="8020", MODE="0660", GROUP="dialout"
```

### Mac OS X

The documentation for `libusb` says it works on OS X, but that the
device must not be claimed by a platform driver, and since the UEI
remotes are Mass Storage devices they will be. There's supposedly a
workaround similar to the one for Windows involving registering a driver
for the device which does nothing. Unfortunately I don't have a Mac to
test with, so OS X support will depend on someone else to come up with
instructions or patches. If you're interested see Contributing, below.

### Windows

Running `libusb`-based applications (including this one) on Windows
requires that you first [install a device driver][libusb-win]. You
shouldn't need to use JpsUsbRaw on Windows, though, as the UEI remotes
work just fine with Windows' native USB Mass Storage and FAT
implementations. Just copy your `settings.bin` file to the drive that
shows up when you plug in your remote with the Windows file explorer.

[libusb-win]: https://github.com/libusb/libusb/wiki/Windows#How_to_use_libusb_on_Windows



## Contributing

This is a [Maven] project, so you can compile everything and build the
JARs with `mvn package`. Two JARs are produced. One is the main artifact
and contains only the files from this project. The other has the suffix
`-shaded` and contains the application and all its dependencies.

If you wish to contribute, please submit a pull request or open an issue
on [GitHub]. If you can't do either of those, you can send reports and
patches by email to sam@maltera.com. All code contributions must agree
to the CC0 public domain declaration and license in `COPYING.txt`.

[Maven]: https://maven.apache.org/
[GitHub]: https://github.com/Elemecca/jps-usb-raw

### Reference Documentation

- [USB 2.0 Specification](http://www.usb.org/developers/docs/usb20_docs/usb_20_040816.zip),
  specifically `usb_20.pdf`
  - Sections 5.2 - 5.4 and 5.8 for an overview of the USB model
  - Section 8.4.5 for an explanation of STALL packets
  - Chapter 9 for details on device states and configuration
- [USB Mass Storage Overview](http://www.usb.org/developers/docs/devclass_docs/Mass_Storage_Specification_Overview_v1.4_2-19-2010.pdf)
- [USB Mass Storage Bulk Only](http://www.usb.org/developers/docs/devclass_docs/usbmassbulk_10.pdf)
- [Seagate SCSI Command Reference](http://www.seagate.com/staticfiles/support/disc/manuals/Interface%20manuals/100293068c.pdf)
- [ECMA-107 (FAT File System)](http://www.ecma-international.org/publications/standards/Ecma-107.htm)



## Copying

Written in 2016 by Sam Hanes <sam@maltera.com>

To the extent possible under law, the author(s) have dedicated all copyright
and related and neighboring rights to this software to the public domain
worldwide. This software is distributed without any warranty.

You should have received a copy of the CC0 Public Domain Dedication
along with this software. If not, see
<http://creativecommons.org/publicdomain/zero/1.0/>.


#### Linux

Fortunately libusb already works just fine on Linux, and we can take
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

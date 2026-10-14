# USB Bluetooth dongle firmware

`UsbBtFirmwareLoader` can upload vendor firmware to Broadcom/Realtek USB
Bluetooth dongles that ship with empty patchram (they ignore HCI_Reset
until the blob is uploaded).

## Firmware files are NOT bundled

This repository ships **no firmware binaries**. To enable the upload path,
place the file for your dongle in the app's assets before building:

```
shared/src/main/assets/usbbt_fw/<vid>_<pid>.hcd   # Broadcom (e.g. 0a5c_21e8.hcd)
shared/src/main/assets/usbbt_fw/<vid>_<pid>.bin   # Realtek  (e.g. 0bda_8771.bin)
```

`<vid>`/`<pid>` are the lowercase 4-digit hex USB vendor/product IDs.

## Where to get the firmware

Obtain the file yourself from a public source, for example:

- Broadcom: extract the `.hcd` from the vendor's Windows driver package
  (the `.hex` inside can be converted with the public `hex2hcd` tool), or
  from the `broadcom-bt-firmware` community collection.
- Realtek: the `rtl_bt/*_fw` files from the Linux firmware tree
  (e.g. `rtl8761a_fw`) — rename to `<vid>_<pid>.bin`.

Only use firmware for hardware you own, from a source you trust. When no
file is present the loader reports "no firmware" and the app keeps the
previous graceful-degradation behaviour (diagnostic hint only).

## USB ports and hubs

The dongle needs a free USB port. On head units whose ports are already
taken (e.g. T3 with a dashcam + iPhone), use a USB hub for the dongle.
Hub + dongle + iPhone coexistence on the head unit's USB host controller
has not been verified on all units — if the dongle is not detected,
try it without the hub first to rule out a hub problem.

# Third-Party Notices

## libopus

- **Project:** Opus Audio Codec
- **Upstream:** https://github.com/xiph/opus
- **Version:** libopus 1.3.1
- **Pinned commit:** `e85ed7726db5d677c9c0677298ea0cb9c65bdd23`
- **License:** BSD-style, 3-clause

libopus is used to provide software Opus microphone encoding on legacy Android devices that do not have a compatible system MediaCodec Opus encoder.

The build retrieves the pinned upstream source using `scripts/prepare-libopus.py` and compiles it into `libopus_microphone.so` through Android NDK.

The original copyright notices, license terms, and disclaimers are provided in the upstream libopus source distribution:

https://github.com/xiph/opus/blob/e85ed7726db5d677c9c0677298ea0cb9c65bdd23/COPYING

Copyright and license notices of the upstream project remain applicable to the bundled native library.

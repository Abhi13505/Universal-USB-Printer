# Universal USB Printer

Zero-driver-setup Android application (Kotlin + Jetpack Compose + Material 3) for printing PDFs and photos directly to standard office and home USB printers over a USB OTG cable.

## Key Capabilities

- **Universal USB OTG Discovery & Probing**: Automatically matches USB Printer Class (`7/1/1`, `7/1/2`, `7/1/4` IPP-over-USB) plus Samsung SCX-3200 fallback (`0x04E8:0x3441`). Probes IPP-over-USB (`Get-Printer-Attributes`) and IEEE 1284 (`GET_DEVICE_ID` / `GET_PORT_STATUS` / `SOFT_RESET`).
- **Streaming 128-Line Wire Encoders**:
  - **IPP-over-USB (`7/1/4`)**: HTTP/1.1 `Print-Job` with `image/pwg-raster` (`"RaS2"`, PackBits `sRGB`/`sGray`/`Black_1`) and `application/pdf`.
  - **PCL 5c / PCL 5e / PCL 6 (PCL-XL)**: PJL + TIFF PackBits (`ESC * b 2 M`) and PCL-XL strips.
  - **PDF-Direct & PostScript Level 2/3**: `@PJL ENTER LANGUAGE = PDF` and PostScript `colorimage`/`image`.
  - **Samsung / Xerox QPDL (`0x11` Algo0x11, `0x13` JBIG1, `0x15` Color)**: 32px-aligned 128-line bands, column-major transposed bitwise-inverted LZS, ITU-T T.82 QM-arithmetic JBIG1, and BE checksums.
- **Photo Size & Layout Studio**: Shared millimeter-accurate `PhotoLayoutGeometry` driving both live blueprint/paper previews and USB print bands (ID/Passport/Wallet/4×6"/5×7"/6×8"/Full presets, gaps, margins, 3×3 anchor pad, cut marks, repeat-single-photo, and auto-rotate).
- **Resilient 3-Tier Photo Decoder & Pre-Print Tuning**: Supports JPEG, PNG, WebP, HEIC, AVIF via Android Photo Picker / SAF, interactive Crop Area, 90° rotate, brightness/contrast, and Floyd-Steinberg / Crisp Text / 4×4 Bayer dithering.
- **Android System `PrintService` & Persistence**: Native `BIND_PRINT_SERVICE` integration, DataStore per-`VID:PID` overrides, and Room Print History.

## Build & Test

```bash
gradle assembleDebug
gradle :app:testDebugUnitTest
```

# NCD List OCR

Android app for scanning printed name-and-age lists and preparing editable records.

## Features

- Capture a photo with the camera or select one from the gallery. EXIF orientation is corrected automatically.
- Rotate, manually crop, or auto-crop the photo, then run on-device Latin-script OCR.
- Review OCR results in a spreadsheet-style grid. Tap cells to select them; long-press a cell in the photo preview to select its column.
- Pair selected name and age columns, or automatically parse name/age values such as `Vinay Makwana 32`.
- Review and edit the resulting Name/Age records.
- Open a full-screen table preview and tap an individual Name or Age cell to copy it. Copy the full table or export an `.xlsx` workbook.
- Import Excel (`.xlsx`) and CSV files.

## Build

The GitHub Actions workflow builds a debug APK on pushes to `main` and on manual dispatch. The APK is uploaded as a workflow artifact and attached to the configured GitHub release.

## Technology

Kotlin, Jetpack Compose, Material 3, on-device ML Kit text recognition, ExifInterface, and a lightweight Kotlin XLSX reader/writer.

Developed by Vin∆y Makwana.

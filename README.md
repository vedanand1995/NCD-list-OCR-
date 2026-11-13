# NCD List OCR

Android app (Kotlin + Jetpack Compose) jema:

- **Gallery thi photo select** karo (photo picker)
- **ML Kit on-device OCR** photo parthi text vanchhe
- Text mathi **Name, Surname, Phone, Age, Gender** parse kari ne list aape
- **Export CSV** button thi list share kari shakay

## Build

1. Android Studio ma open karo (AGP 8.5+).
2. Run - app phone par install thase.

## Tech

- Kotlin, Jetpack Compose, Material 3
- com.google.mlkit:text-recognition:16.0.1 (fully on-device, internet ni jarur nathi)
- ActivityResultContracts.PickVisualMedia for gallery

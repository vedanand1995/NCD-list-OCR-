# NCD List OCR v2.1 — Terminal Edition

- Black background + teal-blue terminal UI
- Camera / Gallery photo input, scanning effect
- **Column select (Google Lens style):**
  1. "1:NAME COL" mode ma NAME column kek ek cell tap karo → aakhi column select (teal)
  2. "2:AGE COL" mode ma AGE column kek ek cell tap karo → aakhi column select (blue)
  3. **PAIR & ADD** → name ane age row-wise pair thai ne list ma aave
- AUTO PARSE (full photo), editable list (tap to edit/delete), COPY button
- CSV import (Excel ma thi "Save as CSV")
- Credit: **developed by Vin∆y Makwana**

## APK

GitHub Actions push par automatic debug APK banave:
- Actions tab → Build APK → artifact "NCD-List-OCR-debug-apk"
- Releases page par "v2.1" tag sathe APK

Tech: Kotlin, Jetpack Compose, Material 3, ML Kit text-recognition (on-device).

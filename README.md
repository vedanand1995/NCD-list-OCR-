# NCD List OCR v2.3 — Terminal Edition

- Black background + teal-blue terminal UI
- Camera / Gallery input, **EXIF auto-rotate fix** (photo ultu nathi thayu)
- **⟳ ROTATE** button (90°), **✂ CROP** (drag rectangle), **▣ AUTOCROP** (text area auto-crop)
- **Excel-style selection:** tap cell = select, long-press = aakhi column, GRID viewer ma pan select
- **▦ GRID viewer** — OCR cells ne Excel jeva rows/columns ma dekhay
- PAIR & ADD — name column + age column row-wise pair, "name age" list
- AUTO PARSE, editable list (tap = edit/delete), COPY button, CSV import
- Credit: **developed by Vin∆y Makwana**

## APK

GitHub Actions push par automatic debug APK banave:
- Actions tab → Build APK → artifact "NCD-List-OCR-debug-apk"
- Releases page par APK pan malse

Tech: Kotlin, Jetpack Compose, Material 3, ML Kit text-recognition (on-device), ExifInterface.

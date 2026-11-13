# NCD List OCR v2.0 — Terminal Edition

Android app (Kotlin + Jetpack Compose) jema:

- **Black background + teal-blue terminal UI**
- **Camera ane Gallery** — photo click karo ke select karo
- **Scanning effect** — OCR chalu thay tyare teal scan line
- **Google Lens jevu cell selection** — photo par OCR na cell (line) tap karo, select thay, "ADD SELECTED" thi list ma aave
- **AUTO PARSE** — badha cells mathi automatic name + age list
- **Editable list** — koi pan row par tap karo → name/age edit, delete
- **COPY button** — "name age" format ma clipboard ma copy (Excel/Sheets ma paste karo)
- **CSV import** — CSV/text file mathi name+age list import
- Credit: **developed by Vin∆y Makwana**

## APK

GitHub Actions push par automatic **debug APK** banave:
- Actions tab → Build APK → artifact "NCD-List-OCR-debug-apk"
- Releases page par "v2.0" tag sathe APK attach thase

## Tech

- Kotlin, Jetpack Compose, Material 3
- ML Kit text-recognition 16.0.1 (on-device)
- Camera: ActivityResultContracts.TakePicture + FileProvider
- File import: ActivityResultContracts.OpenDocument

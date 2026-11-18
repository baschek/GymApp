# Navigationsaufzeichnung nach der Testversion entfernen

Die Aufzeichnung lebt pro Prozess. Beim Start der App ist der Schalter aus. Beim Trainingsende, beim manuellen Stopp und nach drei Stunden endet die Aufnahme. Die Uhr beendet beim Senden auch die laufende Aufnahme. Das Handy kann eine laufende Aufnahme teilen. Beide Geräte behalten die lokalen Originale bis zur Entfernung.

## Dateien und Aufrufe löschen

Alle Pfade in dieser Liste beginnen in `android/`, außer der ausdrücklich genannten `.gitignore`.

| Datei | Änderung in der Folgeversion |
| --- | --- |
| `core/src/main/kotlin/de/baschek/gymapp/core/recorder/{Recording,RecorderIds}.kt` | Ereignisformat, Frist, Screenshotabstand und feste Kennungen löschen. |
| `core/src/test/kotlin/de/baschek/gymapp/core/recorder/RecordingTest.kt` | Formattests löschen. |
| `navrec/src/de/baschek/gymapp/recorder/shared/{DeviceRecorder,RecorderOverlay}.kt` | Gemeinsamen Dateischreiber, ZIP-Helfer, Aufnahmepunkt und PixelCopy-Helfer löschen. |
| `app/build.gradle.kts`, `watch/build.gradle.kts` | Jeweils das zusätzliche Kotlin-Quellverzeichnis `../navrec/src` entfernen. |
| `app/src/main/kotlin/de/baschek/gymapp/recorder/{NavRecorder,RecorderSettings,WatchRecordingListener}.kt` | Freigabe, Einstellungen, Empfang und Entpacken löschen. |
| `app/src/test/kotlin/de/baschek/gymapp/recorder/{DeviceRecorderTest,RecorderExportTest}.kt` | Integrationstests löschen. |
| `app/src/main/kotlin/de/baschek/gymapp/AppContainer.kt` | `NavRecorder.install(app)` und den Collector für `NavRecorder.workoutChanged` entfernen. |
| `app/src/main/kotlin/de/baschek/gymapp/telemetry/Telemetry.kt` | In `track` die Zeile `NavRecorder.log(screen, element)` entfernen. |
| `app/src/main/kotlin/de/baschek/gymapp/ui/AppRoot.kt` | Recorder-State, Routen-`LaunchedEffect` und `RecorderOverlay` entfernen. |
| `app/src/main/kotlin/de/baschek/gymapp/ui/more/SettingsScreen.kt` | `item { …RecorderSettings() }` entfernen. Darin liegen „Navigation aufzeichnen (Test, nur diese Version)“ und „Aufzeichnung teilen“. |
| `app/src/main/AndroidManifest.xml` | Nur den Service `.recorder.WatchRecordingListener` samt `DATA_CHANGED`-Filter für `/gymapp/navrec/v1/` löschen. |
| `app/src/main/res/xml/file_paths.xml` | Nur `<files-path name="navrec" path="navrec/shared/" />` löschen. Andere Funktionen verwenden denselben FileProvider. |
| `watch/src/main/kotlin/de/baschek/gymapp/watch/recorder/NavRecorder.kt` | Sendefunktion und Asset-Pfad `/gymapp/navrec/v1/<watch-Aufnahme>` löschen. |
| `watch/src/main/kotlin/de/baschek/gymapp/watch/WatchApplication.kt` | `NavRecorder.install(this)` und `NavRecorder.workoutChanged(…)` im Repository-Collector entfernen. |
| `watch/src/main/kotlin/de/baschek/gymapp/watch/MainActivity.kt` | `RecorderOverlay` und dessen umgebende Box bei `WatchRoot` entfernen. |
| `watch/src/main/kotlin/de/baschek/gymapp/watch/ui/{WatchRoot,WatchUi}.kt` | Recorder-Aufrufe entfernen: in `WatchRoot` `NavRecorder.action(...)` im Dispatcher und `NavRecorder.tap(recorderElement(element))` im Beobachter; in `WatchUi` `recording`, den Block `NavRecorder.auto(...)` in `go(...)`, `visibleId` mit `LaunchedEffect(visibleId, recording)` und die zugehörigen Imports. Die bisherige Interaktionstelemetrie wird weiterhin gebraucht. |
| `watch/src/main/kotlin/de/baschek/gymapp/watch/ui/WatchNav.kt` | Am Ende `recorderScreen`, `RECORDER_ELEMENTS` und `recorderElement` löschen. |
| `watch/src/main/kotlin/de/baschek/gymapp/watch/ui/OtherScreens.kt` | `item { RecorderSettings() }` in `SettingsScreen` und die Funktion `RecorderSettings` (Schalter und „Aufzeichnung ans Handy senden“) löschen. |
| `.gitignore` im Repository-Stamm | Eintrag `gymapp-navrec-*` entfernen. |
| `docs/navrec-removal.md` | Dieses Dokument mit der Funktion entfernen. |

Die zugehörigen Imports entfernen. Es gibt keine gespeicherte Schalterpräferenz und keine Room-Migration.

## Aufräumen beim ersten Start der Folgeversion

Im Versionswechsel beider Applications (`GymApplication.onCreate` und `WatchApplication.onCreate`) einmal auf einem IO-Thread ausführen:

```kotlin
java.io.File(filesDir, "navrec").deleteRecursively()
```

Das entfernt JSONL, WebP, empfangene Aufnahmen, unvollständige Empfangsordner und lokale Freigabe-ZIPs. Bereits an eine andere App geteilte Kopien verwaltet die empfangende App. Der Handy-Listener löscht zugestellte DataItems nach erfolgreicher Ablage. Beim Entfernen auch eventuell noch ausstehende eigene DataItems der Uhr unter `/gymapp/navrec/v1/` per `DataClient.deleteDataItems(prefixUri, DataClient.FILTER_PREFIX)` bereinigen.

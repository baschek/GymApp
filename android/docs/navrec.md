# Navigationsaufzeichnung: Wartung und Aufbewahrung

Die Navigationsaufzeichnung läuft dauerhaft im Hintergrund auf Handy und Uhr, ohne Schalter. Sie startet nach `install` beim App-Start (`AppContainer` und `WatchApplication`) und endet nie. Es werden nur feste Kennungen (`core/recorder/RecorderIds`) und stark maskierte Bilder gespeichert, nie Werte oder Texte. Die Maskierung (`redact` in `RecorderOverlay`) darf nicht gelockert werden.

## Sitzungen

Jede Sitzung ist ein Ordner `files/navrec/<phone|watch>-<uuid>/` mit `events.jsonl` und `screen-<ms>.webp`. Der Ordner entsteht erst beim ersten Schreiben, leere Starts hinterlassen nichts. Eine neue Sitzung beginnt (Rollover, mit dem zuletzt gesehenen Bildschirm als erstem Ereignis):

- bei jedem Wechsel des aktiven Trainings (`workoutChanged`), sofern die Sitzung nicht leer ist,
- nach `Recording.LIMIT_MS` (3 h),
- auf der Uhr beim Senden (`closeForTransfer`): erst Rollover, dann werden die geschlossenen, noch nicht gesendeten Sitzungen übertragen (Marker `.sent`). Die laufende Sitzung wird nicht angehalten.

## Grenzen (`RecorderConfig`)

| | Handy | Uhr |
| --- | --- | --- |
| Mindestabstand der Bilder | 2 s | 5 s |
| Bilder pro Sitzung | 300 | 60 |
| Sitzungen | 20 | 20 |
| Alter | 14 Tage | 14 Tage |
| Gesamtgröße | 100 MB | 20 MB |

Die Uhr macht Bilder nur bei eingeschaltetem Bildschirm und nie im Ambient-Modus (`shotsAllowed`); Ereignisse werden immer geschrieben. Ist das Bildbudget einer Sitzung aufgebraucht, laufen nur noch die Ereignisse weiter.

## Aufbewahrung

`Retention.expired` (rein, getestet) wählt aus: erst alles über dem Alterslimit, dann die ältesten, bis Anzahl und Größe passen. Die laufende Sitzung wird nie gelöscht. Der Lauf passiert auf dem Schreib-Thread beim Start und bei jedem Rollover, nach dem Empfang einer Uhr-Aufnahme auf dem Handy und fängt jeden Fehler ab. Zusätzlich entfernt er alte Teil-ZIPs, Empfangs-Zwischenordner und Freigabe-ZIPs (älter als ein Tag).

## Teilen und Löschen

- Handy, Einstellungen: „Aufzeichnung teilen“ packt Handy-Sitzungen und empfangene Uhr-Sitzungen in ein ZIP (FileProvider-Pfad `navrec/shared/`); die laufende Aufnahme geht weiter. „Aufzeichnungen löschen“ (mit Rückfrage) löscht alles außer der laufenden Sitzung.
- Uhr, Einstellungen: „Aufzeichnung ans Handy senden“ (`/gymapp/navrec/v1/<watch-Sitzung>`, ein Asset je Sitzung). `WatchRecordingListener` auf dem Handy legt jede Sitzung atomar ab und quittiert das DataItem.

## Neue Kennungen

Neue Bildschirm-, Element- oder Ursachen-Namen kommen nur über `RecorderIds` hinein; Unbekanntes wird zu `unknown`, `control`, `action` oder `auto:other`. Auf der Uhr bildet `recorderElement` (`WatchNav.kt`) Beschriftungen auf diese Kennungen ab. Wird ein Bedienelement entfernt, auch seine Kennung dort entfernen.

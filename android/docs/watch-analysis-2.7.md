# GymApp Watch: Analyse des Testfeedbacks gegen `android-v2.7.0`

Stand: 17.11.2026. Grundlage ist der Code im Tag `android-v2.7.0` (Commit `1cbd3d3`). Getestet wurde auf der Uhr mit 2.6.0, deshalb ist bei jedem Punkt vermerkt, ob 2.7 ihn schon behebt. Es wurde nichts geändert, committet oder gepusht.
Pfadkürzel: `watch/…` = `android/watch/src/main/kotlin/de/baschek/gymapp/watch/…`, `backend/…` = `android/wear-backend/src/main/kotlin/de/baschek/gymapp/wear/backend/…`, `core/…` = `android/core/src/main/kotlin/de/baschek/gymapp/core/…`, `app/…` = `android/app/src/main/kotlin/de/baschek/gymapp/…`.

---

## 1. Kurzfazit

- Daten und Synchronisierung sind solide: Ereignis-IDs, Undo, Offline-Betrieb und Konflikte sind sauber gebaut. Die meisten Probleme liegen in der **UI-Schicht der Uhr** (`WatchUi.kt`, `WatchRoot.kt`), nicht im Backend.
- **Der wichtigste Bug, Bildschirme springen (Punkt 3):** Ein `LaunchedEffect` setzt während einer Pause *jede Sekunde* den Bildschirm zurück auf „Pause“. Wer in der Pause zu den Übungen oder zur Steuerung wechselt, wird spätestens nach einer Sekunde zurückgeholt. Externe Zeitgeber (Handy, Einstellzeit) lösen dasselbe aus.
- **Nächste Übung (Punkt 1):** Die Uhr zeigt auf „Übung fertig“ als Ziel Übung 3 an, der Knopf führt aber zu Übung 5. Beschriftung und Aktion nutzen zwei verschiedene Regeln. Das Handy springt ebenfalls zur ersten offenen Übung (3).
- **Puls (Punkt 4):** Die Pulsaufzeichnung startet nie automatisch. Sie liegt drei Ebenen tief unter Steuerung → Gesundheit → „Puls aufzeichnen“. Ohne diesen Schritt zeigt die Uhr „– –“ und rät irreführend, die Uhr enger zu tragen.
- **Progression (Punkt 7):** Die Uhr fragt nie nach der Anstrengung („1–2 übrig“) und zeigt keinen Gewichtsvorschlag. Die Kernregel braucht zwei passende Sätze in einem Training. Ein einzelner starker Satz mit 180 kg bewirkt deshalb nichts, und das nächste Training beginnt wieder mit 170 kg. Ein konkreter Regelvorschlag mit den betroffenen Golden-Fällen steht bei Punkt 7.
- Notizen fehlen auf der Uhr ganz (Repository-Funktion vorhanden, keine UI). „Aktion nicht möglich“ erscheint bei jeder Aktion, die keine Änderung bewirkt, etwa „Jetzt“ auf die aktuelle Übung, Verschieben am Rand oder zweimal „Weiter“.
- **Design:** 2.7 hat den Pausenbildschirm so stark entrümpelt, dass der nächste Satz nicht mehr sichtbar ist (steht nur noch in der Bedienungshilfe-Beschreibung). Die Wear-Material-3-Bausteine (`ScreenScaffold`, `TimeText`, `EdgeButton`, `TransformingLazyColumn`, Seitenindikator) werden nicht genutzt, obwohl die Bibliothek 1.7.1 eingebunden ist.

---

## 2. Feedbackpunkte 1–11

### Punkt 1: Reihenfolge nach einem Sprung (2 → 4 → erwartet 5, angeboten 3)

- **Status in 2.7: Fehler noch vorhanden** (Logik zwischen 2.6 und 2.7 unverändert).
- **Ursache:**
  - `backend/workout/WatchWorkoutRepository.kt:247`: `logSet` behält den Fokus auf der gerade beendeten Übung 4.
  - Nach der Pause erzwingt `watch/ui/WatchUi.kt:178` den Bildschirm `EXERCISE_DONE`.
  - Die **Beschriftung** des Knopfs kommt aus `watch/ui/WatchRoot.kt:118`: die *erste* Übung mit offenem Satz in Listenreihenfolge, also **Übung 3** (oder 2, falls dort noch Sätze offen sind).
  - Die **Aktion** `WatchAction.NextExercise` → `WatchWorkoutRepository.nextExercise()` (`:262-266`) sucht dagegen *zyklisch ab der aktuellen Übung* und landet bei **Übung 5**. Die Uhr bietet also „3“ an und springt zu „5“.
  - Zum Vergleich das Handy: `app/workout/WorkoutActions.kt:214-215` setzt nach dem letzten Satz einer angesprungenen Übung den Fokus auf `null`. `core/training/SessionCorrections.kt:30-33` (`upNextExerciseIndex`) fällt dann auf `currentExerciseIndex` zurück, die erste offene Übung. **Das Handy geht also ebenfalls zu Übung 3.** Die erwartete 5 setzt keines der beiden Geräte um.
- **Vorschlag:** Eine gemeinsame reine Funktion in `core/training`, zum Beispiel `nextAfter(exercises, finishedId)`: erst die offenen Übungen *nach* der zuletzt bearbeiteten, dann zyklisch die übersprungenen davor (5, 6, …, dann 3). Handy (`WorkoutActions.logSet`, `RestScreen`, Benachrichtigung) und Uhr (Beschriftung und Aktion) nutzen genau diese Funktion. Dazu Unit-Tests im Kern.
- **Aufwand:** S–M (Kern und beide Apps, Tests). **Typ:** Bug (Uhr inkonsistent) und Funktion (neue Regel auf beiden Geräten).

### Punkt 2: Zielbereich der Wiederholungen fehlt (korrigiert: Wdh.-Ziel, z. B. 8–12)

- **Status in 2.7: Fehler noch vorhanden.**
- **Ursache:**
  - Der Satzbildschirm (`watch/ui/WatchUi.kt:263-271`) zeigt Puls, Übungsname, „Satz n von m“ und Gewicht × Wdh., aber **kein Ziel**.
  - Das Ziel wird zwar gebaut (`watch/ui/WatchRoot.kt:128`, `"Ziel min–max"`), aber nur im Wert-Editor angezeigt (`WatchUi.kt:639`, nur bei `Editor.AMOUNT`).
  - Außerdem ist der Text fest auf „Ziel a–b“ gebaut: Bei Zeit- oder Meterzielen, oder wenn min und max gleich sind, kommt unpassender Text heraus.
  - Das Handy zeigt „Wiederholungen · Ziel 8–12“ in der Satzerfassung (`RepsCard`) und nutzt dafür `core/training/SetListing.kt:82` (`describeTarget`).
- **Vorschlag:** Auf dem Satzbildschirm unter dem Wert eine kleine Zeile „Ziel 8–12“ mit `describeTarget` aus dem Kern (deckt auch Sekunden und Meter ab). Optional als Bereich auf dem Wert, Muted-Farbe.
- **Aufwand:** S. **Typ:** Funktion/Design.
- **Hinweis:** „1–2 übrig“ auf dem Handy ist die **Anstrengung** (Wiederholungen in Reserve), nicht der Zielbereich. Diese Eingabe fehlt auf der Uhr ebenfalls, siehe Punkt 7.

### Punkt 3: Bildschirme springen ohne Eingabe nach links/rechts

- **Status in 2.7: Fehler noch vorhanden**, mit neuen Auslösern. Das Seitenwischen wurde von 6 auf 3 Seiten umgebaut und es gibt jetzt Wischen-zum-Schließen.
- **Ursachen, nach Wahrscheinlichkeit:**
  1. **Erzwungene Bildschirmwechsel jede Sekunde.**
     - `watch/ui/WatchUi.kt:172-181`: `LaunchedEffect(state.sessionActive, state.restRemainingSeconds, state.setupRemainingSeconds, …)`.
     - `restRemainingSeconds` und `setupRemainingSeconds` ändern sich jede Sekunde. Der Effekt läuft also sekündlich neu und setzt `screen = REST` bzw. `screen = SETUP`.
     - Folge: Wer in der Pause mit Zurück oder Wischen zum Training, zu den Übungen oder zur Steuerung geht, wird innerhalb einer Sekunde zurück auf „Pause“ geworfen. Nach Ablauf zählt die Pause ins Minus und bleibt in der Phase `REST`, das Zurückziehen geht also weiter, bis jemand „Weiter“ drückt.
     - Genauso hält der Einstell-Zeitgeber („Gerät einstellen“, `WatchRoot.kt:85-91`) den Bildschirm `SETUP` fest, auch nach 0 s.
  2. **Fernereignisse vom Handy.**
     - Jedes vom Handy übernommene Ereignis ersetzt Zeitgeber und Fokus der Uhr (`WatchWorkoutRepository.receive`, `:355-383`).
     - Satz am Handy erfasst → Pause startet → die Uhr springt auf „Pause“. „Weiter“ am Handy → die Uhr springt auf den Satz bzw. „Übung fertig“.
     - Der Fokus vom Handy (`app/workout/WorkoutActions.publishFocus`, `:119`) ändert die gezeigte Übung.
     - Anpassungen des Assistenten auf dem Handy (alle ca. 45 s Sync) ändern `current.weight`. Dadurch setzt `remember(...)` in `WatchUi.kt:161-163` die gerade bearbeiteten Entwurfswerte zurück.
  3. **Rand-Wischen.**
     - `WatchUi.kt:247-250`: `BasicSwipeToDismissBox` mit `edgeSwipeToDismiss` auf dem Seitenwischer.
     - Ein Wischen von links am Rand führt `back()` aus (`:190-191`): auf Seite 0 oder 2 ein animierter Sprung zur Mitte, auf Seite 1 `screen = HOME`.
     - Auf HOME holt der Effekt aus Ursache 1 beim nächsten Zeitwechsel zurück auf das Training.
     - Google empfiehlt dafür eine reservierte Randzone von ca. 20 %. Die eigene Implementierung hat keine abgestimmte Gesteneingrenzung (`gestureInclusion`).
  4. **Touch-Lünette.**
     - Auf Seite 1 öffnet jedes Drehereignis sofort den Gewichts- bzw. Wert-Editor (`WatchUi.kt:259-262`, Fokus wird in `:171` immer wieder angefordert).
     - Auf der Galaxy Watch erzeugt ein Wischen nahe am Rand leicht Lünetten-Ereignisse. Das wirkt wie ein „Sprung“ in eine andere Ansicht.
  5. **Zustand ohne Speicherung.**
     - `screen` und `PagerState` liegen in `remember` (`WatchUi.kt:142`, `:167`), nicht in `rememberSaveable`.
     - Wird die Activity neu erzeugt, etwa durch das Antippen der Trainings-Benachrichtigung (`HeartRecordingService.kt:37`, ohne `FLAG_ACTIVITY_SINGLE_TOP`) oder durch Prozess-Neustart, springt die Uhr auf Seite 1 bzw. zum Start.
  6. **„Rückgängig“ wechselt mit dem Status.** In der Pause wechselt die obere Zeile zwischen „Rückgängig“ und „Pause“, je nachdem, ob `undo` gesetzt ist. Ein Fernereignis löscht `undo` (`receive` … `undo = null`), das Layout springt.
- **Vorschlag:**
  - Automatische Bildschirmwechsel nur bei *Phasenwechseln* auslösen: Schlüssel `timer.phase`/`timer.startedAt`/`exerciseFinished` statt sekündlicher Werte. Nur wechseln, wenn der Nutzer nicht gerade in einer Unteransicht ist.
  - Fernereignisse höchstens als Hinweis zeigen („Handy: Pause gestartet“), nicht als Navigation.
  - Lünette auf Seite 1 nur nach Antippen eines Werts aktivieren oder mit Schwelle und Haptik.
  - `rememberSaveable` für den Navigationszustand.
  - Zum Wear-Navigationsbaustein wechseln: `SwipeDismissableNavHost` und `HorizontalPagerScaffold`.
- **Aufwand:** M. **Typ:** Bug.

### Punkt 4: Puls trotz Erlaubnis nicht gemessen oder angezeigt

- **Status in 2.7: Fehler noch vorhanden.**
- **Pfad:**
  - UI `WatchAction.StartRecording` (nur in `watch/ui/WatchUi.kt:386`, Bildschirm „Gesundheit“) → `WatchRoot.kt:67`
  - → `watch/runtime/AndroidWatchRuntime.kt:32-44` (Rechteprüfung, sonst Anfrage und erneuter Versuch nach Zustimmung)
  - → `HeartRecordingService` (Vordergrunddienst Typ `health`, `HeartRecordingService.kt:33-50`)
  - → `backend/recording/WearHeartRecorder.start` (`:181-204`, Health Services `ExerciseClient`, `STRENGTH_TRAINING` mit `HEART_RATE_BPM`)
  - → `RuntimeMonitor.state.bpm` → `WatchRoot.kt:110/178` (nur wenn `heartSessionId == sessionId`) → `PulseHeader` (`WatchUi.kt:265`).
- **Wahrscheinliche Ursachen:**
  1. **Die Aufzeichnung startet nie automatisch, sehr wahrscheinlich die Hauptursache.**
     - Weder Trainingsstart noch Fortsetzen ruft `startRecording` auf.
     - Der einzige Einstieg ist Steuerung → Gesundheit → „Puls aufzeichnen“, bzw. Einstellungen → Gesundheit.
     - Die Erlaubnis kann trotzdem erteilt sein: Auf Wear OS 6 fordert das System `READ_HEART_RATE` bei der Installation teils selbst an, und bestehende `BODY_SENSORS`-Rechte werden übernommen.
     - Folge: Erlaubnis da, aber keine Messung.
  2. **Irreführende Anzeige.** Mit Erlaubnis und ohne Aufzeichnung zeigt „Puls“ den Text „Kein Messwert – Trag die Uhr enger“ (`WatchUi.kt:316`). Kein Hinweis, dass gar nicht aufgezeichnet wird.
  3. **Hängende alte Aufzeichnung.**
     - `WearHeartRecorder.start` bricht ab, wenn noch eine Aufzeichnung gespeichert ist (`:184`, „Export and clear the previous recording first“).
     - `archiveTerminal` (`HeartRecordingService.kt:102-123`) räumt nur Aufzeichnungen im Endzustand auf.
     - Bleibt eine alte Aufzeichnung in `STARTING`/`ACTIVE`/`STOPPING` hängen (etwa nach Prozessende, ohne erneutes `reconnect` durch einen Start-Intent), scheitert jeder neue Start dauerhaft mit „Pulsaufzeichnung nicht verfügbar“.
     - Die Fehlermeldung des Recorders (`error`-Flow) wird nirgends angezeigt.
  4. **Fähigkeiten- und Besitzprüfung.** `:185-191` wirft, wenn `STRENGTH_TRAINING` mit Puls nicht gemeldet wird, oder wenn ein anderes Training läuft, etwa Samsung Health, das „Krafttraining“ automatisch erkennt. Ergebnis ist nur der allgemeine Text „Pulsaufzeichnung nicht verfügbar“.
  5. **Bibliotheksversion.**
     - `wear-backend` nutzt `androidx.health:health-services-client:1.0.0` mit `targetSdk 36`.
     - Manifest und Laufzeitprüfung sind korrekt nach Googles Migrationsanleitung: `BODY_SENSORS maxSdkVersion=35` plus `health.READ_HEART_RATE`, Laufzeit über `SDK_INT >= 36`.
     - Ob der Client 1.0.0 auf Wear OS 6 mit den neuen Rechten korrekt zusammenspielt, ist **auf echter Hardware zu prüfen**. Ein Logeintrag der Ausnahme würde das sofort zeigen.
  6. Vordergrunddienst-Typ `health`: Die Voraussetzung (eine Gesundheitsberechtigung, auf API 36 `READ_HEART_RATE`) ist bei erteilter Erlaubnis erfüllt. Ein `SecurityException` würde „Pulserlaubnis fehlt“ anzeigen.
- **Vorschlag:**
  - Beim Trainingsstart auf der Uhr die Aufzeichnung automatisch anbieten bzw. starten. Ein bewusster Nutzerakt bleibt erhalten: einmalige Zustimmung „Puls bei jedem Training aufzeichnen“.
  - Den Zustand „nicht aktiv“ klar anzeigen: „Puls aus · Starten“.
  - Den echten Fehlergrund im Gesundheitsbildschirm zeigen.
  - Beim Start eine verwaiste nicht-terminale Aufzeichnung erst per `reconnect` abgleichen und danach aufräumen.
  - `health-services-client` auf die aktuelle Version prüfen.
- **Aufwand:** M. **Typ:** Bug und Funktion.

### Punkt 5: Notizen lassen sich auf der Uhr nicht anlegen

- **Status in 2.7: fehlt weiterhin.**
- **Handy:**
  - Notiz pro Übung im laufenden Training über die Leiste „Notiz“ (`app/ui/workout/TrainingScreen.kt:260`, `NoteSheet` `:276-279`, Freitext).
  - Zusätzlich eine „Schmerz“-Notiz beim Überspringen mit Grund Schmerz.
  - Gespeichert in `SessionExercise.notes` (`core/training/SessionEdit.kt:164` `setExerciseNote`).
  - Notizfelder gibt es auch an Studio, Plan, Übung, Maschinenprofil und Tagesworkout; Satz- oder Sitzungsnotizen hat das Handy im Training nicht.
- **Uhr:**
  - `WatchWorkoutRepository.setNote` existiert (`:257`) und ist samt Synchronisierung fertig.
  - Es fehlen aber `WatchAction.SetNote` und jede Eingabe-UI.
  - `TRAIN_NOTE` (`WatchUi.kt:308-312`) zeigt nur Einstellung und vorhandene Notiz (Sitzungsnotiz, sonst Bibliotheksnotiz) zum Lesen.
- **Vorschlag:**
  - „Notiz“ → Systemeingabe über `RemoteInput`/`RemoteInputIntentHelper` (androidx.wear:wear-input). Das öffnet Diktat oder Tastatur des Systems; Compose-Textfelder sind auf Wear unzuverlässig.
  - Dazu Schnellbausteine („Sitz +1“, „Schmerz“, „Gerät besetzt“, „Griff gewechselt“) als Liste, anhängen statt überschreiben.
- **Aufwand:** S–M. **Typ:** Funktion.

### Punkt 6: Rotes „Aktion nicht möglich“ unten

- **Status in 2.7: noch vorhanden.**
- **Ursache:**
  - `watch/ui/WatchRoot.kt:84`: `if (!ok) error = "Aktion nicht möglich"`. Angezeigt als rote Leiste in `WatchUi.kt:414`. Sie bleibt stehen, bis die nächste Aktion ausgelöst wird; ein Timeout gibt es nicht.
  - `ok == false` kommt von `WatchWorkoutRepository.change()` (`:211-216`), wenn die Aktion **nichts ändert** oder das Training nicht aktiv ist.
- **Konkrete Auslöser beim Übungswechsel:**
  - „Jetzt“ auf die schon aktuelle Übung (`setFocus` `:258-261`: gleicher Fokus → keine Änderung → false).
  - „Jetzt“ auf eine Übung ohne offene Sätze: Der Fokus bleibt, also false. Abgeschlossene Übungen blenden „Jetzt“ zwar aus, aber ein gleichzeitiges Fernereignis kann sie inzwischen abgeschlossen haben.
  - „Nach oben“ auf der ersten bzw. „Nach unten“ auf der letzten Übung (`moveExercise`).
  - Nächste Übung (`nextExercise`), wenn keine andere Übung offen ist.
  - „Weiter“ oder „+15 s“, wenn die Pause gerade vom Handy oder durch einen doppelten Tipp beendet wurde (`skipRest`/`changeRest` `:289-299`).
  - Satz erfassen oder überspringen, wenn das Handy diesen Satz gerade selbst erfasst hat (Satz nicht mehr `PENDING`).
  - Rückgängig, nachdem ein Fernereignis `undo` gelöscht hat.
- **Vorschlag:**
  - Leerlauf-Aktionen still ignorieren (Ergebnis `Unchanged` statt `false`).
  - Echte Ablehnungen mit Grund melden („Pause schon beendet“, „Übung ist schon aktuell“).
  - Meldung nach ca. 3 s ausblenden.
  - „Jetzt“ für die aktuelle Übung ausblenden, „Nach oben“/„Nach unten“ am Rand deaktivieren.
- **Aufwand:** S. **Typ:** Bug/Design.

### Punkt 7: Steigerungsvorschläge (korrigiert: Progression soll früher reagieren)

- **Status in 2.7: unverändert.**

**Ist-Zustand im Kern (`core/progression/Progression.kt`):**

| Regel | Fundstelle | Verhalten | Warum 170 kg bleibt |
|---|---|---|---|
| Dauerhaftes Arbeitsgewicht | `setMeetsTarget` `:210-220`, `qualifyingWeights` `:222`, `provenWorkingWeight` `:225-237` | Ein Satz zählt als Nachweis, wenn Wdh. ≥ `target.min` und Anstrengung (Untergrenze der Reserve) ≥ `rirMin`. Für ein **neues** Gewicht braucht es **≥ 2** solche Sätze in **einem** Training; es gilt das **zweithöchste** nachgewiesene Gewicht. Wird bei `finishSession`/`recalculateWorkingWeights` (`Recalculate.kt:84ff`) gespeichert. | Ein Satz mit 180 × 10 allein zählt nicht, das nächste Training startet wieder bei 170 (`suggestStartWeight` nimmt `machine_profile.working_weight_kg`). |
| Vorschlag im Training | `suggestNextWeight` `:74-98`, Text in `core/training/WeightHint.kt:57-90` | Schätzt das Einwiederholungsmaximum aus Gewicht × (Wdh. + Reserve) und rechnet auf `target.max + Mittel(rir)` zurück, also auf das *obere* Zielende (12 + 1,5). | 170 × 12 bei „1–2 übrig“: 1RM ≈ 243,7 → 243,7 / 1,45 ≈ 168 → **170 halten**. Erst bei „3–4 übrig“ (≈ 175,9) oder mehr Wiederholungen wird eine höhere Stufe vorgeschlagen. Er wird also nicht nach oben gedrückt. |
| Vorbelegung des nächsten Satzes | `core/progression/GymWeights.kt:195-208` (`prefillSet`) | Gewicht = vom Assistenten geplant > zuletzt erfasst > Startvorschlag. Ein Vorschlag fließt **nicht** automatisch ein; auf dem Handy muss man „x kg übernehmen“ antippen (`WeightHintBlock`). | |

**Uhr:**
- Weder `weightHint` noch eine Übernahme-Schaltfläche.
- Keine Eingabe der Anstrengung. `WatchAction.Log` sendet immer `current.effort` = `prefill.effort` (`WatchRoot.kt:130`, `GymWeights.kt:201-206`), und das ist die Voreinstellung aus `rirMin` (bei Ziel 1–2: „one_two“).
- Folge 1: Auf der Uhr erfasste Sätze erfüllen die Reserve-Bedingung *immer*. Die Anstrengung ist also nicht gemessen, sondern angenommen.
- Folge 2: Der Vorschlag im Training würde auf der Uhr nie eine Steigerung empfehlen, weil die Reserve konstant ist.

**Vorschlag einer Regeländerung** (nur zusammen mit den Golden-Fällen, siehe AGENTS.md; laut AGENTS.md gilt dieselbe Regel auch auf dem Sync-Server, dort also mitziehen):

- **A. „Ein starker Satz reicht“ für das dauerhafte Arbeitsgewicht.**
  - In `provenWorkingWeight` gilt ein Gewicht W über dem aktuellen Arbeitsgewicht auch mit **einem** Satz als nachgewiesen, wenn Wdh. ≥ `target.min + ceil((target.max − target.min) / 2)` (bei 8–12 also **≥ 10**) und Anstrengung ≥ `rirMin`.
  - Bei mehreren Kandidaten gilt das höchste so nachgewiesene Gewicht; die Zwei-Satz-Regel bleibt daneben bestehen.
  - Beispiel: 180 × 10 bei „1–2 übrig“ → ab dem nächsten Training ist 180 der Start.
  - Optional nur, wenn schon ein Arbeitsgewicht existiert; ohne Arbeitsgewicht bleibt die vorsichtige Regel „zweithöchstes“.
- **B. Früher nach oben drücken im Training (doppelte Progression).**
  - In `suggestNextWeight` für `load_reps`/`added_weight_reps`: Wenn Wdh. ≥ `target.max` und Anstrengung ≥ `rirMin`, dann mindestens die **nächste verfügbare Stufe** über dem letzten Gewicht, auch wenn die 1RM-Rechnung auf dasselbe Gewicht rundet.
  - Beispiel: 170 × 12 bei „1–2 übrig“ → Vorschlag 180 (bzw. die nächste Stufe).
- **C. Voraussetzung auf der Uhr:**
  - Ohne echte Anstrengungseingabe würde Regel A auf der Uhr *jeden* Satz mit ≥ 10 Wdh. als Nachweis werten.
  - Darum auf der Uhr eine schnelle Auswahl nach dem Satz einbauen (vier Stufen, vorausgewählt, per Lünette): „Am Limit · 1–2 · 3–4 · 5+“.
  - Danach Hinweis und Übernahme wie auf dem Handy, etwa „▲ 180 kg?“ auf dem Pausenbildschirm, ein Tipp übernimmt.
  - Optional bis dahin Uhr-Sätze ohne bestätigte Anstrengung nicht als Nachweis werten (Feld `effort` leer lassen statt Voreinstellung).

**Golden-Fälle, die sich ändern** (`android/core/src/test/resources/progression_golden.json`):

| Fall | Heute | Mit A | Maßnahme |
|---|---|---|---|
| `proven: one proof is not enough` (25 × 8, „one_two“, aktuell 20) | null | null (8 < 10) | bleibt, Begründung im Namen schärfen |
| `proven: skipped set is no proof` (25 × 10 „two_three“ + übersprungener Satz) | null | **25** | Erwartung ändern **oder** Satz auf 9 Wdh. setzen, damit der Fall weiter „übersprungen zählt nicht“ prüft |
| `proven: failed set is excluded` (25 × 10 „two_three“ + fehlgeschlagener Satz) | null | **25** | wie oben |
| `proven: no current weight takes second highest` (20/25/30 × 10, kein Arbeitsgewicht) | 25 | 30 (wenn A auch ohne Arbeitsgewicht gilt) | Variante „nur mit Arbeitsgewicht“ wählen → bleibt 25 |
| `proven: highest load corroborated by two sets` | 25 | 25 | unverändert |
| `evidence: one of two` / `evidence: proven will be saved` | Text | Text muss den Ein-Satz-Weg nennen (deutscher Text `progressionEvidenceDe`, `WeightHint.kt:97-114`) | Erwartungstext anpassen |
| `suggest: heavier trial uses available weight` | prüfen | mit B möglicherweise eine Stufe höher | neu rechnen |
| `recalculate: …`, `per gym: …`, `reassign: …`, `manual baseline: …` | prüfen | Sitzungen mit einem einzelnen starken Satz können jetzt ein Arbeitsgewicht setzen | alle neu durchrechnen; neue Fälle „one strong set proves load (≥ mid-range reps)“, „strong set below mid-range is no proof“, „top of range with reserve suggests next level (B)“ ergänzen |

- **Aufwand:** M (Kern + Golden + Server-Abgleich + Uhr-Anstrengungseingabe). **Typ:** Funktion (Regeländerung), auf der Uhr auch Bug (die Anstrengung wird nur angenommen).

### Punkt 8: Geräteeinstellungen („Fußplatte“ 3 → 4,5 bei Seitheben) auf der Uhr

- **Status: unterstützt und sichtbar**, mit Einschränkungen.
- **Weg:**
  - Server → Handy-Sync → `GymRepository.observeWatchCatalogChanges()` reagiert auf Änderungen an `MACHINE_PROFILE` (`app/data/GymRepository.kt:304-312`).
  - `WatchCatalogPublisher` veröffentlicht den Katalog neu (`app/wear/WatchCatalogPublisher.kt:27`).
  - Die Uhr übernimmt ihn mit `replaceCatalog`.
  - `WatchRoot.kt:114/129` sucht das Profil über `gymId` + `exerciseId` und baut `"Name Wert · …"` aus `setup_parameters`.
- **Angezeigt:**
  - (a) Info → Notiz → Karte „Einstellung“ (`WatchUi.kt:309`).
  - (b) Bildschirm „Gerät einstellen“ mit 45-s-Zeitgeber, aber **nur** nach Jetzt, nächster Übung, Übung hinzufügen oder Trainingsstart (`WatchRoot.kt:85-91`).
- **Nicht angezeigt:**
  - Wenn die nächste Übung *implizit* kommt, also über die Fokusregel nach dem letzten Satz.
  - Auf dem Pausenbildschirm vor einer neuen Übung (das Handy zeigt dort `SetupLine`).
- Das Profil muss zum Studio der laufenden Sitzung gehören.
- **Vorschlag:** Die Einstellung auf dem Pausenbildschirm zeigen, sobald als Nächstes eine neue Übung kommt (eine Zeile „Fußplatte 4,5“). Siehe auch Punkt 3: Der Zeitgeber für „Gerät einstellen“ hält den Bildschirm fest.
- **Aufwand:** S. **Typ:** Design.

### Punkt 9: Pausenbildschirm „voll gestopft“: Bestand in 2.7

- **Status: in 2.7 umgebaut.** Getestet wurde 2.6, das zeigte zusätzlich „Als Nächstes: 170 kg × 12“ und eine 42-sp-Uhr.
- **Inhalt in 2.7** (`WatchUi.kt:346-366`):

| Element | Position (Anteil der Höhe) | Inhalt |
|---|---|---|
| Fortschrittsring | ganzer Rand, 6 dp | Restzeit / Gesamtzeit |
| Oben | 6 % | „Rückgängig“-Pille (88×48 dp), sobald Undo vorhanden ist, also nach jedem Satz fast immer |
| oder | 14 % | Status „Pause“ / „Los“ / „Übung fertig“ (nur ohne Undo) |
| Mitte | 34 % | Countdown 32 sp, antippen → Pausen-Editor (±15-s-Schritte per Lünette oder −/+) |
| Unten | 61,5 % | „+15 s“ und „Weiter“ (je 60×48 dp) |
| Überlagert | oben / unten | „Testprofil“, rote Fehlerzeile |
| Immer-an-Anzeige | | Übungsname/„Pause“, Restzeit (aktualisiert nur jede Minute, wirkt also eingefroren), Gewicht × Wdh. |

- **Fehlt jetzt:**
  - Nächster Satz bzw. nächste Übung: steht nur in der Bedienungshilfe-Beschreibung (`:358`), nicht sichtbar.
  - Satz n von m, Gewicht, Ziel, Geräteeinstellung, Puls.
  - Die Steigerungsempfehlung (Punkt 7).
  - Zugang zur Übungsliste: Wegen Punkt 3 kommt man in der Pause praktisch nicht hin.
- **Vorschlag:**
  - Ein Bildschirm, drei Zonen: oben Status und Rest, Mitte nächster Satz (Übung, „Satz 3/4“, „170 kg × 10–12“), unten eine Hauptaktion „Weiter“ als `EdgeButton`.
  - „+15 s“ und „Rückgängig“ auf einer zweiten, vertikal erreichbaren Seite oder als kleine Symbole.
  - Details (Einstellung, Vorschlag) als Unteransicht per Tipp auf die mittlere Zone.
- **Aufwand:** M. **Typ:** Design.

### Punkt 10: Übersicht wirkt unübersichtlich; lieber mehr Klicks und Ordnung

- **Status:** In 2.7 teilweise geändert. Start ist eine Liste; im Training gibt es Info (links), Satz (Mitte) und Steuerung (rechts).
- **Befund:**
  - Die „Steuerung“-Seite (`WatchUi.kt:279-293`) mischt 13 Einträge ohne Gruppierung. Darunter sind satzbezogene Aktionen (Aufwärmsatz, Satz hinzufügen/überspringen), trainingsbezogene (Übung hinzufügen, beenden), Systemeinträge (Gesundheit, Verbindung, Einstellungen, auf Handy öffnen) und eine nicht antippbare Infokarte („Pause ändern – während der Pause verfügbar“).
  - „Zurück“-Pillen am Ende jeder Liste sind auf Wear doppelt, weil Wischen-zum-Schließen bereits zurückführt.
  - Keine Uhrzeit (`TimeText`), kein Seitenindikator (nur Text „‹ Info  Steuerung ›“ auf Seite 1).
- **Die gelobten Gesten in 2.7:**
  - „Nach links wischen → alle Übungen“ war in **2.6** direkt so (Seite 0 = Übungsliste). In **2.7** liegt links eine Menüseite „Info“; die Übungen brauchen einen weiteren Tipp.
  - „Von oben nach unten wischen → Einstellungen“ ist **nicht die App**, sondern das System-Schnellmenü von Wear OS/One UI. Die App hat keinen eigenen Wischweg von oben. Bitte im Design-Chat klären, was gemeint war.
- **Vorschlag:**
  - Wischen links → direkt die Übungsliste (wie 2.6).
  - Wischen rechts → Steuerung in **Gruppen** mit Kopfzeilen: „Dieser Satz“, „Training“, „Uhr & Handy“.
  - Systemeinträge in Einstellungen bündeln.
  - Ein Hauptelement pro Bildschirm, Details eine Ebene tiefer. Keine Funktion entfernen.
- **Aufwand:** M. **Typ:** Design.

### Punkt 11: Was gut funktioniert (positiv)

- Robuste lokale Datenhaltung und Undo, inklusive Rücknahme des Trainingsendes (`finishUndo`).
- Ereignis-Synchronisierung mit Bestätigung erst nach dauerhafter Übernahme, Wiederholung und Konfliktauflösung.
- Progression und Gewichte pro Studio kommen aus demselben Kern wie auf dem Handy.
- Lünetten-Bedienung für Gewicht, Wert und Pause mit Maschinenstufen und Zusatzgewichten.
- Gemessene Zeitsätze, Aufwärm- und Zusatzsätze, Feier bei Gewichtssteigerung, Zusammenfassung.
- Immer-an-Darstellung, Laufende Aktivität (`OngoingActivity`), Komplikation.
- 48-dp-Touchziele sind überall eingehalten. Der eigene Updater prüft SHA-256 und die Signatur.
- Die Grundlage ist also stimmig; die Arbeit liegt vor allem in Navigationslogik und Bildschirmaufteilung.

---

## 3. Samsung- und Wear-OS-Designprinzipien und Abgleich

### Quellenlage

- Samsungs eigene Watch-Designrichtlinien (One UI Watch für Tizen) sind als „Distribution of Tizen-based watch apps has been discontinued“ markiert. Für Wear-OS-basierte Galaxy Watches verweist Samsung praktisch auf Googles Wear-OS-Richtlinien. Die Tizen-Seiten sind nur noch als Referenz für runde Layouts brauchbar.
- `developer.samsung.com` war in dieser Sitzung nicht erreichbar. Zitiert wird deshalb nur, was die Suche ausgab.

**Quellen:**
- Google, UX-Prinzipien für Wearables: <https://developer.android.com/design/ui/wear/guides/get-started/design-for-wearables/principles>
- Google, Design-Qualitätsstufen (Material 3 Expressive, Randabstände in Prozent, Randknöpfe): <https://developer.android.com/design/ui/wear/guides/foundations/quality-tiers>
- Google, verschiedene Bildschirmgrößen (`ScreenScaffold`, `TransformingLazyColumn`, Grenzwert 225 dp, scrollende Layouts als Standard): <https://developer.android.com/training/wearables/compose/screen-size>
- Google, Bildschirmformen (kleinster runder Emulator 192 dp, runde Displays haben ca. 22 % weniger Fläche): <https://developer.android.com/training/wearables/design/screen-shapes>
- Google, Navigation (Wischen zum Schließen, vertikale Achse, Randzone): <https://developer.android.com/design/ui/wear/guides/behaviors-and-patterns/navigation> und <https://developer.android.com/training/wearables/design/navigation>
- Google, Listen und Lünette: <https://developer.android.com/training/wearables/components/lists>, <https://developer.android.com/training/wearables/compose/rotary-input>, Barrierefreiheit (Lünette) <https://developer.android.com/training/wearables/accessibility>
- Google, Immer-an-Anzeige: <https://developer.android.com/training/wearables/always-on>
- Google, Health-Services-Berechtigungen und Wear-OS-6-Migration: <https://developer.android.com/health-and-fitness/health-services/permissions>, <https://developer.android.com/training/wearables/versions/6/changes>
- Google, Texteingabe auf Wear: <https://developer.android.com/training/wearables/user-input/wear-ime>; Praxisbeispiel `RemoteInput` (Compose): <https://proandroiddev.com/typing-on-a-tiny-screen-e7601e846118>
- Wear Compose `HorizontalPager` (Gesten-Randzone für Wischen-zum-Schließen): <https://composables.com/docs/androidx.wear.compose/compose-foundation/composable-functions/HorizontalPager>
- Samsung (veraltet, Tizen): Kreis-UX <https://developer.samsung.com/one-ui-watch-tizen/circular-ux.html>, Layout <https://developer.samsung.com/one-ui-watch-tizen/visual/layout.html>
- Zusätzlich SAP Fiori für Wear OS (Dritte, „nur eine relevante Aktion, unter 5 s erledigt“): <https://www.sap.com/design-system/fiori-design-android/sap-fiori-for-wear-os/foundation/wear-os-design-principles>

### Prinzipien, die konkret für die GymApp gelten

1. **Kritische Aufgaben, in Sekunden erledigt.** „Focus on one or two tasks“ und „complete tasks … within seconds“: Satz bestätigen und Pause überbrücken sind die Kernaufgaben, alles andere ist Nebensache.
2. **Eine Hauptaktion pro Bildschirm.** Material 3 Expressive nutzt den randanliegenden `EdgeButton` als typische Hauptaktion unten. Weitere Aktionen gehören in eine scrollende Liste.
3. **Randabstände in Prozent.** Keine festen dp-Werte. `ScreenScaffold` liefert prozentuale Innenabstände. Auf kleinste runde Bildschirme (192 dp) testen, bei ≥ 225 dp mehr zeigen, nie weniger.
4. **Scrollende Layouts als Standard.** `TransformingLazyColumn` mit `ListHeader`; Ausnahme ist der eigentliche Trainingsbildschirm, der nicht scrollen muss.
5. **Navigation.** Zurück per Wischen von links; tiefe Hierarchien flach halten. Horizontale Seiten sind in M3 mit `HorizontalPagerScaffold` und Seitenindikator erlaubt; die ältere Richtlinie riet ganz davon ab. Für Wischen-zum-Schließen eine Randzone (ca. 20 %) reservieren.
6. **Lünette/Drehkrone.** Scrollt Listen. Für Werte nur, wenn der Fokus klar sichtbar ist. Immer eine Touch-Alternative anbieten.
7. **Typografie und Lesbarkeit.** Kurze Texte ohne Abschneiden; runde Displays brauchen größere Ränder. Die Uhrzeit (`TimeText`) gehört oben auf jeden Bildschirm.
8. **Immer-an-Anzeige.** Mindestens 85 % schwarz. Sich schnell ändernde Werte (Sekunden-Countdown, Puls) durch Platzhalter ersetzen, weil sie nur etwa einmal pro Minute aktualisiert werden. Keine eigene Pixelverschiebung (das macht das System). Uhrzeit zeigen. Elemente an derselben Stelle wie im aktiven Modus lassen.
9. **Kontextbezug.** Zeigen, was jetzt gebraucht wird; in der Pause also „was kommt als Nächstes“.

### Abgleich je Bildschirm

| Bildschirm | Folgt | Verstößt |
|---|---|---|
| **Start** (Liste, `WatchUi.kt:213-220`) | Scrollende Liste, große Touchziele, Hauptaktion „Start/Fortsetzen“ hervorgehoben | Keine Uhrzeit (`TimeText`); feste Innenabstände 25/24 dp statt Prozent; eigene Liste ohne `TransformingLazyColumn`/Scrollindikator; Verbindungszeile als Knopf ist Nebeninformation auf Hauptebene |
| **Training/Satz** (Seite 1, `:259-298`) | Wenige Elemente, eine Hauptaktion (✓), Werte per Lünette | ✓ ist kein `EdgeButton`; die Lünette öffnet ungefragt den Editor (Punkt 3); Ziel fehlt (Punkt 2); keine Uhrzeit; Seitenhinweis als Text statt Seitenindikator; eigener Wischen-zum-Schließen-Rand kollidiert mit dem Seitenwischen |
| **Info/Steuerung** (Seiten 0/2) | Scrollend | Steuerung mit 13 ungruppierten Einträgen; nicht antippbare Infokarte zwischen Knöpfen; „Zurück“-Pillen redundant; Systemeinträge mitten in den Trainingsaktionen |
| **Pause** (`:346-366`) | Eine klare Hauptaktion „Weiter“; Elemente innerhalb der runden Sicherheitszone; Ring als einfache Fortschrittsanzeige | Kontext fehlt: Der nächste Satz ist unsichtbar (verstößt gegen „Kontextbezug“); Rückgängig verdrängt den Status; Layout springt je nach Undo; erzwungenes Zurückholen (Punkt 3); Immer-an-Anzeige zeigt einen eingefrorenen Sekunden-Countdown statt Platzhalter bzw. Minutenangabe |
| **Übungsliste** (`TRAIN_EXERCISES` + Menü `exerciseMenu`) | Scrollend, aktuelle Übung hervorgehoben | Status als Textpräfix („Offen  Beinpresse“) statt Symbol bzw. zweiter Zeile; Unterseite ohne Kopfzeile; „Jetzt“ auch für die aktuelle Übung → Fehlermeldung (Punkt 6); erreichbar erst über Info → Übungen (2.6 war direkter) |
| **Einstellungen** (`:401-411`) | Scrollend | Ausgewählt/nicht ausgewählt als zwei Knöpfe statt eines Schalters (`SwitchButton`); Infokarten und Knöpfe gemischt; Update-Status als langer Text |
| **Immer-an-Anzeige** (`:205-211`) | Schwarz, Umrissschrift | Eigene Pixelverschiebung (`ambientOffsetX/Y`, `WatchRoot.kt:180`) entgegen der Richtlinie; keine Uhrzeit; eingefrorener Sekunden-Countdown |

---

## 4. Konzept: Klick- und Navigationsrekorder (B), temporär für eine Version

### Bestand

- **Handy:**
  - `app/telemetry/Telemetry.kt`: `Telemetry.track(screen, element)` zählt nur **Tageszähler** pro Bildschirm × Element in Room und lädt sie gesammelt beim Sync hoch.
  - Keine Reihenfolge, keine Zeitstempel.
  - Wird laut AGENTS.md bei *jeder* Interaktion aufgerufen (Helfer `tracked`, `app/ui/Track.kt`).
- **Uhr:**
  - `AndroidWatchRuntime.trackInteraction` (`watch/runtime/AndroidWatchRuntime.kt:51-62`) zählt in SharedPreferences `watch-telemetry` pro Tag:Bildschirm:Element.
  - Bildschirm ist immer `"watch"`, Element `"tap"` (alle Pillen über `LocalInteraction`) bzw. der Klassenname der Aktion.
  - Nicht hochgeladen, keine Abfolge, keine Bildschirmwechsel.

### Ziel

Für **ein** Training die echte Abfolge auf Uhr und Handy aufzeichnen: Bildschirm, Element, Zeitstempel, optional ein Screenshot pro Bildschirmwechsel. Das soll mit **einer** Version kommen und in der Folgeversion **komplett entfernt** werden.

### Entwurf (einfach, hinter einem Schalter, leicht zu entfernen)

1. **Je App ein Paket `recorder/`** mit genau einer Klasse `NavRecorder`:
   - `fun log(screen: String, element: String)`, `fun screen(name: String)`, `fun start()`, `fun stop()`, `fun export()`.
   - Keine Room-Änderung, kein Protokoll- oder Sync-Bezug. Dateien in `filesDir/navrec/`.
2. **Einhängepunkte (wenige Zeilen, beim Entfernen einfach löschen):**
   - Handy: in `Telemetry.track` ein `NavRecorder.log(screen, element)` (eine Zeile, deckt alle vorhandenen Aufrufe ab). Dazu ein Beobachter auf die Navigation (Routenwechsel in `AppRoot`) für `NavRecorder.screen(route)`.
   - Uhr:
     - In `WatchRoot` beim `dispatch` (Aktionstyp).
     - In `LocalInteraction` (Tipp). Dafür `Pill`/`RowPill` den Beschriftungstext an den Callback geben, eine Signaturänderung in `WatchUi.kt`.
     - Ein `LaunchedEffect(screen, pager.currentPage, editor, exerciseMenu)` für Bildschirmwechsel. Der `Screen`-Enum ist privat, deshalb ein Callback-Parameter `onScreen`.
     - **Wichtig für Punkt 3:** Zusätzlich *automatische* Wechsel mit Ursache protokollieren (`auto:rest_tick`, `auto:remote_event`, `auto:setup`). Damit lässt sich das Springen direkt belegen.
3. **Format:**
   - JSON Lines, eine Datei pro Training bzw. Aufnahme.
   - Felder pro Zeile: `t` (Unix-ms), `mono` (ms seit Start, monotone Uhr), `dev` (`watch`/`phone`), `type` (`tap`/`screen`/`auto`/`action`), `screen`, `element`, optional `shot` (Dateiname).
   - Keine Gewichte, keine Notiztexte. Beschriftungen können Übungsnamen enthalten. Das ist akzeptabel, weil die Datei lokal bleibt.
4. **Screenshots ohne Root:**
   - Im eigenen Fenster mit `PixelCopy.request(window, bitmap, …)` (ab API 26, Handy und Uhr) oder in Compose mit `rememberGraphicsLayer()` + `drawWithContent { record… }` + `toImageBitmap()`.
   - Erfasst nur die eigene App, also keine Systemdialoge oder Benachrichtigungen. Das reicht für die Navigationsanalyse.
   - Nur bei Bildschirmwechsel, höchstens 1 pro 2 s, auf 50 % verkleinert als WebP (Uhr ca. 10–20 KB).
   - `MediaProjection` ist unnötig, weil es jedes Mal eine Systemfreigabe braucht.
   - Alternativ ohne Code: `adb exec-out screencap -p` per WLAN-ADB. Für ein echtes Training zu umständlich.
5. **Schalter (opt-in):**
   - Handy: „Navigation aufzeichnen (Test, nur diese Version)“ in Einstellungen.
   - Uhr: derselbe Schalter in Einstellungen.
   - Standard aus. Schaltet sich bei Trainingsende oder nach 3 h selbst ab; ein sichtbarer Punkt zeigt, dass aufgezeichnet wird.
6. **Export:**
   - Uhr: „Aufzeichnung ans Handy senden“ über die vorhandene Data-Layer-Übertragung (`withTransferAsset`/`ChannelClient`, neuer Pfad `/gymapp/navrec/v1`). Das Handy legt die Datei neben die eigene.
   - Handy: „Aufzeichnung teilen“ packt beide Dateien und Screenshots in ein ZIP und gibt es über die Android-Teilen-Funktion (`FileProvider`) bzw. „Speichern unter“ (SAF) weiter.
   - **Nicht** über den Sync-Server und nicht automatisch.
7. **Öffentliches Repo:**
   - Im Code keine Hostnamen, keine Daten.
   - `.gitignore`-Muster `gymapp-navrec-*` (wie bei den Backups), damit Exporte nie versehentlich eingecheckt werden.
   - Tests nur mit synthetischen Einträgen.
8. **Entfernen in der Folgeversion:** Pakete `recorder/` löschen, den Einhängeaufruf in `Telemetry.track`, die Hooks in `WatchRoot`/`WatchUi`, die zwei Schalter und den Data-Layer-Pfad entfernen. Keine Datenmigration nötig, weil nur Dateien. Beim ersten Start der Folgeversion `filesDir/navrec` löschen (eine Zeile).

### Aufwand

| Teil | Aufwand |
|---|---|
| Handy | S–M (ca. 0,5–1 Tag) |
| Uhr | M (ca. 1 Tag, wegen Bildschirmwechsel und automatischer Wechsel) |
| Screenshots auf beiden Geräten | S–M (ca. 0,5 Tag) |
| Übertragung Uhr → Handy und ZIP-Export | S–M (ca. 0,5 Tag) |
| **Gesamt** | **ca. 2,5–3 Tage** |

---

## 5. Priorisierte To-do-Liste

**Bugs zuerst:**

1. **Automatische Navigation entschärfen** (Punkt 3): Effekt nur bei Phasenwechseln, nicht sekündlich; Fernereignisse ohne Navigation; Lünette auf Seite 1 nicht sofort in den Editor; `rememberSaveable`. *M*
2. **Nächste Übung einheitlich** (Punkt 1): Beschriftung und Aktion aus derselben Kernfunktion. Danach die neue Reihenfolgeregel (5 statt 3) auf Handy und Uhr, wenn im Design-Chat bestätigt. *S–M*
3. **Puls** (Punkt 4): Aufzeichnung beim Start anbieten oder automatisch starten; „nicht aktiv“ statt „Trag die Uhr enger“; echten Fehlergrund zeigen; verwaiste Aufzeichnung abgleichen; Health-Services-Version prüfen; auf der Galaxy Watch testen. *M*
4. **„Aktion nicht möglich“** (Punkt 6): Leerlauf-Aktionen still ignorieren, konkrete Gründe nennen, Meldung nach 3 s ausblenden. *S*
5. **Anstrengungseingabe auf der Uhr** (Punkt 7 C): Sonst sind Uhr-Sätze für die Progression geraten. *S–M*

**Funktionen:**

6. Zielbereich auf dem Satzbildschirm (Punkt 2), mit `describeTarget`. *S*
7. Progressionsregel A/B mit Golden-Fällen und Server-Abgleich (Punkt 7); auf der Uhr den Hinweis „▲ 180 kg?“ mit Übernahme. *M*
8. Notizen auf der Uhr: `RemoteInput` plus Schnellbausteine (Punkt 5). *S–M*
9. Navigationsrekorder für eine Version (Abschnitt 4). *M (~3 Tage)*

**Design:**

10. Pausenbildschirm neu: nächster Satz sichtbar, eine Hauptaktion, Rest auf zweiter Ebene (Punkt 9). *M*
11. Steuerung gruppieren, Übungsliste wieder direkt per Wischen links (Punkt 10). *M*
12. Auf die Wear-M3-Bausteine umstellen: `AppScaffold`/`ScreenScaffold`, `TimeText`, `EdgeButton`, `TransformingLazyColumn`, `HorizontalPagerScaffold` mit Seitenindikator, `SwipeDismissableNavHost`. Immer-an-Anzeige nach Richtlinie (keine eigene Verschiebung, Platzhalter für Sekunden und Puls). *L*
13. Geräteeinstellung auf dem Pausenbildschirm vor einer neuen Übung (Punkt 8). *S*

---

## 6. Offene Fragen für den Design-Chat

1. **Reihenfolge nach einem Sprung:** Nach dem Abschluss der angesprungenen Übung 4:
   - (a) weiter mit 5, die übersprungene 3 kommt am Ende;
   - (b) zurück zur ersten offenen (3, heutiges Handy-Verhalten);
   - (c) nachfragen: „Weiter mit 5 · Zurück zu 3“ als zwei Knöpfe.

   Soll das Handy dieselbe Regel bekommen?
2. **Pausenbildschirm, was ist Pflicht?** Auswahl:
   - Countdown, Übungsname, „Satz 3/4“, Gewicht × Wdh., Ziel 8–12, Steigerungsvorschlag, Geräteeinstellung, Puls, „+15 s“, „Rückgängig“, „Noch ein Satz“, Restdauer des Trainings.

   Welche **drei** stehen auf der ersten Ebene, was kommt per Tipp oder Wischen nach unten?
3. **Pausen-Hauptaktion:**
   - (a) „Weiter“ als Randknopf unten;
   - (b) Countdown antippen = weiter;
   - (c) automatisch weiter bei 0 mit Vibration.
4. **„+15 s“ und „Rückgängig“ in der Pause:**
   - (a) kleine Symbole links und rechts vom Countdown;
   - (b) eigene Seite bei Wischen nach oben;
   - (c) nur über die Lünette (+15 s) bzw. die Steuerung.
5. **Wischlogik im Training:**
   - Links: (a) direkt die Übungsliste wie in 2.6, oder (b) das Menü „Info“ wie in 2.7?
   - Rechts: Steuerung gruppiert, oder aufgeteilt in „Satz“ und „Training“?
   - Soll Wischen von links am Rand zurück zum Start führen, oder im Training ganz gesperrt sein?
6. **Was meintest du mit „von oben wischen → Einstellungen“?** Das ist das System-Schnellmenü der Uhr. Brauchst du ein eigenes App-Menü von oben, oder passt das so?
7. **Zielbereich anzeigen:**
   - (a) Zeile „Ziel 8–12“ unter dem Wert;
   - (b) Wert farbig, wenn er im Ziel liegt;
   - (c) nur im Editor wie heute.
8. **Anstrengung auf der Uhr erfassen:**
   - (a) nach jedem Satz ein Schnellbildschirm mit vier Stufen und Vorauswahl (ein Tipp oder Lünette + ✓);
   - (b) nur, wenn vom Ziel abgewichen wird (lange auf ✓ drücken);
   - (c) gar nicht, dann zählen Uhr-Sätze nicht für die Progression.
9. **Progression:**
   - Gilt die Ein-Satz-Regel ab der Hälfte des Zielbereichs (≥ 10 bei 8–12), oder ab einer festen Zahl (≥ 9)?
   - Auch ohne bisheriges Arbeitsgewicht?
   - Soll der Vorschlag im Training (Regel B) bei Wdh. ≥ max sofort die nächste Stufe zeigen?
   - Soll ein Vorschlag automatisch vorbelegt werden, oder weiterhin erst nach „übernehmen“?
10. **Notizen auf der Uhr:**
    - (a) Diktat/Tastatur über das System;
    - (b) nur Schnellbausteine (welche?);
    - (c) beides.

    Notiz pro Übung wie auf dem Handy, oder auch pro Satz?
11. **Puls:**
    - (a) bei jedem Uhr-Training automatisch aufzeichnen nach einmaliger Zustimmung;
    - (b) beim Start fragen;
    - (c) wie heute manuell.

    Wo soll der Puls im Training stehen: oben klein, oder nur auf einer Unterseite?
12. **Fernereignisse vom Handy** (Satz oder Pause am Handy erfasst): Soll die Uhr
    - (a) sofort umschalten;
    - (b) nur einen Hinweis zeigen und erst beim nächsten Antippen wechseln;
    - (c) nur umschalten, wenn sie gerade den Satzbildschirm zeigt?
13. **Rekorder:**
    - Screenshots ja oder nein (Speicher, Übungsnamen sichtbar)?
    - Nur Uhr, oder Uhr und Handy?
    - Export als ZIP über die Teilen-Funktion des Handys: in Ordnung?
14. **Startbildschirm:** Was gehört auf die erste Ebene: Start/Fortsetzen, Tagesworkout, Verbindung, Fortschritt, Einstellungen? Soll „Verbindung“ nur bei Problemen erscheinen?

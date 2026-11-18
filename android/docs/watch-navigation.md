# Navigation auf der Uhr

Umsetzung von `watch-design-spec.md`, Abschnitte A bis N. Reine Regeln stehen in `watch/ui/WatchNav.kt` (Tests `WatchNavRulesTest`), die Bildschirme in `TrainingScreens.kt`, `OtherScreens.kt` und `WatchComponents.kt`, der Zusammenbau in `WatchUi.kt`.

## Aufbau

- Wear-M3-Bausteine: `AppScaffold` mit `TimeText` (Uhrzeit oben auf jedem Bildschirm, im Training mit Pulsstatus, im Testprofil mit dauerhaftem „Testprofil“), `ScreenScaffold`, `TransformingLazyColumn` mit `ListHeader`, `HorizontalPagerScaffold` mit Seitenindikator. Runde Knöpfe (Haken, „Weiter“, Rückgängig) statt der breiten Randfläche, Innenabstände in Prozent der Bildschirmgröße, Touch-Ziele mindestens 48 dp. Nur dunkel, reines Schwarz, Akzent wie am Handy.
- Der Navigationszustand (Stapel der Ansichten und Seite) ist ein String in `rememberSaveable` (`NavState.encode`/`decode`). Activity-Neustart, Benachrichtigungstipp und Prozessende springen nicht zum Start; die Benachrichtigung öffnet die Aktivität mit `SINGLE_TOP`. Ein offener Wert-Entwurf (`Draft`) und die Auswahlen der Unteransichten (`Selection`) sind ebenfalls speicherbar.

## Training: drei Seiten

| Seite | Inhalt |
| --- | --- |
| Links | „Übungen“ (Statussymbol, Name, erledigt/geplant; Tippen öffnet Jetzt, Überspringen/Wieder aufnehmen, Nach oben, Nach unten), „Dieser Satz“ (Aufwärmsatz, Notiz, Satz überspringen, Satz hinzufügen, bisherige Sätze), „Training“ (Übung hinzufügen, Rückgängig) |
| Mitte | Satz, danach je nach Stand Anstrengung, Pause, Übung fertig oder Gerät einstellen |
| Rechts | Training beenden (fragt nach), Auf Handy öffnen, Gesundheit, Verbindung, Einstellungen |

- Standardseite beim Start und nach jedem echten Ereignis (Satz erfasst, Pause gestartet oder beendet, Übung gewechselt, Training gestartet oder beendet, auch vom Handy): Mitte, alle Unteransichten geschlossen. Der Schlüssel dafür ist `WatchPresentation.navigationKey`; ein Sekundentakt wechselt nie den Bildschirm. Die Uhr geht nie von selbst zum nächsten Satz oder zur nächsten Übung.
- Wischen vom linken Rand und die Zurück-Taste: erst die Unteransicht schließen, sonst eine Seite zurück (rechts, Mitte, links). Auf der linken Seite passiert nichts. Aus dem Training geht es nur über die Home-Taste (die Ongoing Activity läuft weiter) oder „Training beenden“. Auf dem Startbildschirm beendet derselbe Wisch die App. Dafür ist `windowSwipeToDismiss` im Theme aus; `BasicSwipeToDismissBox` in `WatchUi.kt` ersetzt die Systemgeste.
- Kein App-Menü von oben, keine „Zurück“-Knöpfe am Listenende.
- Die Lünette scrollt Listen. Auf der Satz-Seite ändert sie nichts; erst der Wert-Editor (Wert antippen) nimmt sie an und ändert in echten Maschinenstufen (Wiederholungen ±1, Sekunden ±5, Meter ±10). Daneben gibt es − und + Knöpfe.

## Satz, Anstrengung, Pause

- Satz: Name, „Satz n von m“, Gewicht × Wiederholungen (Wert antippen öffnet den Editor mit Zusatzgewichts-Chips und „Gesamt x kg“), „Ziel …“, runder Haken. Optionaler Dauer-Ring (eine Runde = 60 Minuten, Einstellung, Voreinstellung aus).
- Anstrengung: Nach dem Haken vier Stufen für 5 Sekunden (`EFFORT_PICK_TIMEOUT_MILLIS`), das Ziel ist vorgewählt. Tippen oder Lünette wählen und werden als `effortSource = "user"` gespeichert; läuft die Zeit ab, gilt die Vorauswahl als `"auto"` (`resolveEffort`).
- Pause: Countdown mit Ring, „Als Nächstes“ mit Vorschau (bei neuer Übung mit Geräteeinstellung; ein Tipp auf den Vorschlag „170 → 180 kg“ übernimmt das Gewicht), runder „Weiter“-Knopf, 6,5 Sekunden lang ein runder Rückgängig-Knopf links davon (der Platz ist immer reserviert, „Weiter“ springt nie). Wischen nach oben öffnet die zweite Ebene (+15 s, −15 s, Rückgängig, Noch ein Satz), nach unten schließt sie. Nach Ende: Vibration und Ton durch den Alarm, „Los“, Zähler „+0:07“, kein automatisches Weitergehen.
- Übung fertig: „Nächste Übung: <Name>“ (erste offene Übung, Regel `nextAfter` im Kern), „Noch ein Satz“ oder bei keiner offenen Übung „Training beenden“. Gerät einstellen: 45-Sekunden-Ring, schließt auch bei 0 nicht von selbst.
- Aktionen, die nichts ändern würden, sind deaktiviert oder werden still ignoriert; echte Ablehnungen zeigen ihren Grund 3 Sekunden (`REJECTION_VISIBLE_MILLIS`).

## Start und Ende

Start: Verbindungszeile, Plan mit Studio und Modus, „Start“ (läuft ein Training: „Fortsetzen“), darunter Tagesworkout, andere Pläne, Spontan, Fortschritt & Verlauf, Einstellungen. Ablauf: Studio (letztes vorgewählt) → Modus (mit Dauer) → Packliste mit festem „Los“. Nach dem letzten Satz folgen die Szenen „Neues Gewicht“ (Tippen geht weiter) und die Zusammenfassung.

## Immer-an-Anzeige

Fast schwarz, Umrisse in Grau an der Stelle der aktiven Ansicht, keine eigene Pixelverschiebung. Sekunden und Puls werden durch Minutenwerte („ca. 2 min“) und „–“ ersetzt.

## Haken für Telemetrie und Navigations-Recorder

Alle Bildschirmwechsel und Taps laufen durch `WatchNavObserver` (`WatchNav.kt`), den `WatchUi` aufruft (`go(...)` für Bildschirme mit Ursache `user:tap`, `user:back`, `user:swipe`, `auto:event`, `auto:start`, `auto:phase`; `tap(...)` für jedes Element). `WatchRoot.kt` erzeugt den einzigen Beobachter und leitet Taps an `runtime.trackInteraction` weiter. Ein späterer Recorder ergänzt dort `onScreen`/`onTap`.

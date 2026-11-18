# GymApp Wear OS: Design-Spec (Version nach 2.7.0)

Ziel: kleine runde Galaxy Watch 8 (kleinster runder Emulator ca. 192 dp, auf 225 dp und mehr nichts weglassen, nur mehr zeigen). Handy-App ist die Referenz. Nur Design und Bedienung. Bugfixes (springende Ansichten, falsche nächste Übung, "Aktion nicht möglich") sind hier als Regeln mit aufgenommen.

## A. Entschiedene Grundsätze
1. Lieber mehr Klicks und Ordnung als wenige Klicks und Chaos. Ein Hauptelement pro Bildschirm, Details eine Ebene tiefer, keine Funktion entfällt.
2. Kernaufgaben im Training: Satz bestätigen, Pause überbrücken, wissen, was als Nächstes kommt.
3. Nur dunkel, reines Schwarz, Papier-Akzent (wählbar wie am Handy). Status immer mit Text oder Symbol.
4. Uhr und Handy spiegeln sich gegenseitig, auch die Bildschirmwechsel, innerhalb von etwa einer Sekunde. Gewechselt wird nur bei echten Ereignissen (Satz erfasst, Pause gestartet, Übung gewechselt), nie durch einen Sekundentakt. Ein offener Wert-Entwurf geht nicht verloren.
5. Die Uhr geht nie von selbst zum nächsten Satz oder zur nächsten Übung. Zur nächsten Übung nur auf Befehl.
6. Nach einem Sprung (Übung 2 → 4 fertig) geht es zur ersten offenen Übung (hier 3), danach 5, 6. Handy und Uhr nutzen dieselbe Kernfunktion (zum Beispiel `nextAfter` in `core/training`), Beschriftung und Aktion aus derselben Regel.
7. Keine festen dp-Randabstände: prozentuale Innenabstände (`ScreenScaffold`). Touch-Ziele mindestens 48 dp. Die Uhrzeit (`TimeText`) steht auf jedem Bildschirm oben.
8. Wear-M3-Bausteine nutzen: `AppScaffold`/`ScreenScaffold`, `TimeText`, `TransformingLazyColumn` mit `ListHeader`, `HorizontalPagerScaffold` mit Seitenindikator, `SwipeDismissableNavHost`. "Weiter" und der Haken sind runde Knöpfe (nicht die breite Randfläche).

## B. Navigation (Training)
- Drei Seiten, Satz in der Mitte: LINKS (Übungen), MITTE (Satz), RECHTS (Training). Seitenindikator. Standardseite beim Start und nach jedem Ereignis: MITTE.
- Linke Seite, von oben: Kopf "Übungen" (Liste aller Übungen), dann "Dieser Satz" (Aufwärmsatz, Notiz, Satz überspringen, Satz hinzufügen), dann "Training" (Übung hinzufügen).
- Rechte Seite, von oben: Training beenden (zurückhaltend, ohne Warnfarbe, fragt nach), Auf Handy öffnen, Gesundheit, Verbindung, Einstellungen.
- Wischen von links am Rand (reservierte Zone etwa 20 % der Breite): Unteransicht schließen, sonst eine Seite zurück (rechts → Mitte → links). Auf der linken Seite passiert nichts. Raus aus dem Training nur über die Home-Taste (das Training läuft weiter, Ongoing Activity) oder "Training beenden".
- Zurück-Taste: wie Randwisch, auf der linken Seite bleibt es im Training.
- Pause: Wischen nach oben öffnet die zweite Ebene (+15 s, −15 s, Rückgängig, Noch ein Satz), Wischen nach unten schließt sie.
- Kein App-Menü von oben. Das Schnellmenü der Uhr bleibt das System.
- Keine "Zurück"-Pillen am Ende von Listen (die Geste führt zurück).
- Navigationszustand in `rememberSaveable` (Seite, Unteransicht), damit Activity-Neustart, Benachrichtigungstipp oder Prozessende nicht zum Start springen lassen. Benachrichtigung mit `SINGLE_TOP`.
- Lünette scrollt Listen. Auf der Satz-Seite öffnet sie den Editor nicht ungefragt: erst Wert antippen.

## C. Startbildschirm
Elemente (Priorität):
1. Verbindungszeile (immer sichtbar, in Worten: "Handy verbunden", "Nur Uhr", "Synchronisiere …", "Konflikt", "Sync-Fehler", mit "n offen").
2. Plan (Name, Studio, Modus mit geschätzter Dauer).
3. Hauptaktion "Start" (läuft das Training schon, am Handy oder an der Uhr: "Fortsetzen" mit Übung, Satz, Wert).
4. Darunter scrollend: Tagesworkout (nur wenn heute angeboten, mit "Workout laden"), andere Pläne, Spontan, Einstellungen.

Ablauf nach Start: Studio (Pflicht, Voreinstellung letztes Studio) → Modus (Normal/Schnell/Ultra mit Dauer) → Packliste (Häkchen, "Los" fest unten) → Satz.

```
   ╭─── 17:42 ───╮
   │ Handy verb. │
   │  B – Beine  │
   │ AI Lahnstein│
   │ [ Start ▶ ] │
   │ Tagesworkout│
   │ Andere Pläne│
   ╰─────────────╯
```

## D. Satzbildschirm (Mitte)
Priorität:
1. Übungsname (zweizeilig erlaubt).
2. "Satz n von m".
3. Gewicht × Wdh. (Zeitübung: Sekunden, Körpergewicht: nur Wdh., unterstützt: "Hilfe").
4. "Ziel 8–12" als kleine graue Zeile (Zeit: "Ziel 60–90 s").
5. Puls klein oben ("♥ 124", ohne Erlaubnis "Puls aus" mit Startknopf).
6. Hauptaktion: runder Haken unten (≥ 48 dp).

Zweite Ebene: Wert antippen öffnet den Editor (Nachbarwerte klein, aktueller Wert groß, Lünette ändert in echten Maschinenstufen, Zusatzgewichte (bis 2) als Auswahl-Chips, "Gesamt x kg"; daneben − und + Knöpfe als Touch-Alternative). Wiederholungen ±1, Sekunden ±5.

Rand: optional Dauer-Ring (eine Runde = 60 Minuten), unter Einstellungen ein- und ausschaltbar, Voreinstellung aus.

Geräteeinstellung: vor einer neuen Übung auf dem Pausenbildschirm und im Bildschirm "Gerät einstellen".

```
   ╭─── 17:42 ───╮
   │   ♥ 124     │
   │ Beinpresse  │
   │ Satz 2 von 4│
   │ 170 kg × 12 │
   │  Ziel 8–12  │
   │     (✓)     │
   ╰─────────────╯
```

## E. Anstrengung (nach dem Haken)
Nach jedem Haken erscheinen 5 Sekunden lang vier Stufen: Am Limit, 1–2 übrig, 3–4 übrig, 5+ locker. Vorausgewählt: das Ziel (zum Beispiel 1–2). Ein Tipp oder die Lünette wählt, sonst gilt nach 5 s die Vorauswahl, und die Pause startet. Die Uhr speichert nie eine nur angenommene Stufe als gemessen, sondern markiert sie als "automatisch" (Vorauswahl), damit die Progression es unterscheiden kann.

```
   ╭─ Wie anstrengend? ─╮
   │  Am Limit          │
   │ ● 1–2 übrig        │
   │  3–4 übrig         │
   │  5+ locker         │
   ╰─ 5 s, dann Vorauswahl ╯
```

## F. Pause
Priorität:
1. Countdown groß (Ring am Rand läuft ab).
2. "Pause" und "Als Nächstes" klein und grau.
3. Nächster Satz: "Satz 3 von 4", "170 kg × 10–12" (neue Übung: Name plus Geräteeinstellung, zum Beispiel "Fußplatte 4,5").
4. Hauptaktion: runder "Weiter"-Knopf unten.

Zusätzlich: nach einem Satz erscheint 6,5 Sekunden lang ein runder Rückgängig-Knopf links neben "Weiter" (nie oben, verschiebt nichts). Antippen des Countdowns öffnet den Editor für die Pause (±15 s per Lünette).

Zweite Ebene (Wisch nach oben): +15 s, −15 s, Rückgängig (für den letzten Satz, dauerhaft), Noch ein Satz.

Vorschlag für das Gewicht (siehe K): die Zeile "Als Nächstes" zeigt "170 → 180 kg ▲", ein Tipp übernimmt 180.

Ende der Pause: Vibration und Ton, Countdown zählt als "+0:07" weiter, Beschriftung "Los". Kein automatisches Weitergehen.

Letzter Satz einer Übung: Überschrift "Übung fertig", "Danach: nächste Übung auf Befehl". Weiter führt zu "Übung fertig".

```
   ╭─── 17:42 ───╮
   │   pause     │
   │    1:12     │
   │ als nächstes│
   │ Satz 3 von 4│
   │ 170 kg×10–12│
   │  ↶   (▶)    │
   ╰─────────────╯
```

## G. Übung fertig und Gerät einstellen
Übung fertig: Name, "n Sätze gespeichert", Hauptaktion "Nächste Übung: <Name>" (erste offene Übung, siehe A6), darunter "Noch ein Satz" (höchstens 20 Arbeitssätze). Gibt es keine offene mehr: "Training beenden" und Hinweis "n übersprungen". Das Training endet von selbst, nachdem der letzte offene Satz geloggt wurde (wie am Handy).

Gerät einstellen: Name, Einstellung groß ("Fußplatte 4,5"), 45-s-Ring, "Fertig". Nur bei Übungen mit Einstellung. Der Zeitgeber hält den Bildschirm nie fest, er schließt auch bei 0 nicht ungefragt.

## H. Linke Seite (Übungen und Satz-Aktionen)
Übungsliste: Zeile = Statussymbol (läuft, erledigt, übersprungen, offen), Name, "erledigt/geplant", die aktuelle in Akzent. Tippen öffnet: Jetzt (vorziehen), Überspringen oder Wieder aufnehmen, Nach oben, Nach unten. "Jetzt" für die aktuelle Übung und erledigte Übungen sowie Verschieben am Rand sind ausgeblendet oder deaktiviert.

Dieser Satz: Aufwärmsatz (zählt nicht als Arbeitssatz), Notiz, Satz überspringen, Satz hinzufügen. Training: Übung hinzufügen.

```
   ╭─── 17:42 ───╮
   │ ÜBUNGEN     │
   │ ● Beinpresse│
   │ ○ Beinbeuger│
   │ DIESER SATZ │
   │ Aufwärmsatz │
   │ Notiz       │
   ╰─────────────╯
```

## I. Notiz
Pro Übung wie am Handy. Oben Schnellbausteine zum Anhängen (Vorschlag: "Sitz +1", "Sitz −1", "Schmerz", "Gerät besetzt", "Griff gewechselt"), darunter "Sprechen oder tippen" über die Systemeingabe (`RemoteInput`). Bausteine werden angehängt, nichts überschrieben.

## J. Rechte Seite und Einstellungen
- Training beenden (Bestätigung, offene Sätze werden übersprungen).
- Auf Handy öffnen (Handy zeigt den Stand).
- Gesundheit (Aufzeichnung läuft, Puls: gemessen / Erlaubnis fehlt / kein Messwert, Export aktuell / ausstehend, mit echtem Fehlergrund).
- Verbindung (Zustand in Worten, Konflikt lösen mit "Neueste" oder "Meine", Erneut).
- Einstellungen: "Dauer am Rand" (an/aus), "Puls bei jedem Training aufzeichnen" (an, nach einmaliger Zustimmung), "Navigation aufzeichnen (Test, nur diese Version)" (Schalter, standardmäßig aus).

Puls: automatisch bei jedem Training nach einmaliger Zustimmung. Läuft er nicht, steht "Puls aus" mit Startknopf. Eine verwaiste alte Aufzeichnung wird beim Start abgeglichen und aufgeräumt.

## K. Progression (Handy und Uhr gleich, Kern in `core/progression`)
- Nächster Satz im Training: Wdh. ≥ Obergrenze und Reserve ≥ Ziel-RIR: nächste verfügbare Stufe vorschlagen (170 → 180). Wdh. in der Spanne: halten. Zu wenig Wdh. oder Reserve: halten, bei klarem Misserfolg eine Stufe tiefer. Nur ein Vorschlag, der Besitzer übernimmt mit einem Tipp.
- Nächstes Training: Ein Gewicht über dem Arbeitsgewicht gilt schon mit einem Satz als nachgewiesen, wenn Wdh. ≥ Untergrenze + 1 (bei 8–12: 9) und Reserve ≥ Ziel-RIR (echt angegeben, nicht nur vorausgewählt). Absicherungen: nicht der erste Satz nach einer Pause von über zwei Wochen, und ein späterer Satz mit demselben Gewicht darf den Nachweis nicht widerlegen. Fehlt die Reserve, gilt die alte Zwei-Sätze-Regel. Beides nur zusammen mit den Golden-Fällen (`progression_golden.json`) ändern.
- Sprunggröße: immer die nächste verfügbare Stufe. Ist sie sehr groß, zuerst ein Zusatzgewicht (Mikro-Steigerung). Nach Misserfolg: halten, nach zwei Trainings in Folge eine Stufe tiefer. Zeitübungen wie Wiederholungen. Unterstützte Übungen: weniger Hilfe ist Fortschritt (invertiert). Pro Hand: Hantelstufen.
- Die Quellen zur Progression sind Praxisregeln, keine belegten Grenzwerte.

## L. Fehlermeldungen und Zustände
- "Aktion nicht möglich" entfällt: Leerlauf-Aktionen (zum Beispiel "Jetzt" auf die aktuelle Übung, zweimal "Weiter") werden still ignoriert. Echte Ablehnungen nennen den Grund ("Pause schon beendet") und verschwinden nach 3 Sekunden.
- Testprofil: dauerhafter schmaler Streifen "Testprofil", Daten werden nicht synchronisiert und nicht exportiert.
- Offline, Konflikt, Sync-Fehler: Verbindungszeile auf dem Start, in der Verbindungsansicht, in der Zusammenfassung. Konflikte blockieren das Training nie.
- Fernereignisse vom Handy: spiegeln (siehe A4).

## M. Immer-an-Anzeige
- Mindestens 85 % schwarz. Keine eigene Pixelverschiebung (macht das System). Uhrzeit anzeigen. Elemente an derselben Stelle wie aktiv, als Umriss in Grau.
- Sich schnell ändernde Werte (Sekunden-Countdown, Puls) werden durch Platzhalter oder Minutenwerte ersetzt, weil sie nur etwa einmal pro Minute aktualisiert werden ("ca. 1 min", Puls "–"). Satzseite: Name, "Satz n/m", Werte. Pause: "Pause", Countdown in Minuten, "Als Nächstes".
- Die laufende Aktivität zeigt "Satz n/m" bzw. den Pause-Chronometer.

## N. Zusammenfassung und Gewichtssteigerung
Nach dem letzten Satz pro Übung mit gestiegenem Arbeitsgewicht eine Kurzszene ("Neues Gewicht", Name, altes → neues Gewicht, bei unterstützten Übungen weniger Hilfe). Tippen geht weiter. Dann Zusammenfassung: Volumen, Sätze, Dauer, Puls Ø und max, Sync-Status in Worten, "Fertig".

## O. Navigations-Recorder (nur eine Version, hinter Schalter)
Auf Uhr und Handy. Aufzeichnung: Zeit, Bildschirm, Element, automatische Wechsel mit Ursache (zum Beispiel `auto:remote_event`), Screenshots nur bei Bildschirmwechsel (höchstens einer alle 2 Sekunden, verkleinert). Dateien lokal auf dem Handy (Uhr sendet ihre über den Data Layer), in der Handy-App ein Export-Knopf, der alles als ZIP über die Teilen-Funktion ausgibt. Nie automatisch, nie über den Sync-Server. Wird in der Folgeversion komplett entfernt (Paket `recorder/`, Hooks, Schalter, Data-Layer-Pfad, Ordner `navrec` löschen). Keine Gewichte oder Notiztexte aufnehmen.

## P. Zu prüfen (nicht als gegeben annehmen)
Echte dp und sichere Fläche der 40 mm. Wischen nach rechts am Rand gegen die Systemgeste (`HorizontalPager` und `SwipeDismissableBox`, Randzone etwa 20 %). Lünetten-Verhalten und Haptik. Health Services (Puls, Berechtigungen auf Wear OS 6), Health Connect auf der Uhr. Ambient-Update-Rate. Spracheingabe für Notizen. Ton und Vibration bei Pausenende (Lautlos, Nicht stören).

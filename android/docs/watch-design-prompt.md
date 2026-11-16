# Startprompt für den separaten Design-Chat

Den folgenden Text vollständig in den Chat kopieren, der das bestehende
GymApp-Design bereits kennt. Die technische Grundlage steht in
[watch-backend.md](watch-backend.md); die endgültige Design-Anleitung kommt danach
zurück zum implementierenden Agenten.

---

Entwirf die Wear-OS-Version meiner bestehenden GymApp. Du kennst das Design der
Handy-App bereits; übertrage dessen Stil auf eine kleine, runde Galaxy Watch 8
(normales Modell, Bluetooth/WLAN, digitale Touch-Lünette).

Ziel: Ein komplettes Krafttraining ohne Handy durchführen können. Mit verbundenem
Handy soll die Uhr zusätzlich als Bediengerät funktionieren; ich möchte während
einer Sitzung zwischen Uhr und Handy wechseln können. Offline aufgezeichnete
Änderungen werden später synchronisiert.

Entwickle die Gestaltung gemeinsam mit mir. Liefere am Ende eine konkrete,
zusammenhängende und kopierbare Design-Anleitung für den implementierenden Agenten:
Navigation, Ansichten, Komponenten, Zustände, Farben, Typografie, Abstände,
Interaktionen und Animationen. Prüfe die Bedienbarkeit auf dem kleinen runden
Display. Nutze das bestehende GymApp-Design als Grundlage.

## Funktionen und Abläufe

- Vorbereitung mit Studio, Packliste und Auswahl aller Pläne, Tagesworkouts sowie
  spontanen Trainings. Studio ausdrücklich bestätigen; Gewichte gelten pro Studio.
- Training auf Uhr oder Handy starten und auf dem jeweils anderen Gerät fortsetzen.
- Im Training: Übungsname, Satznummer, Gewicht, Ziel- und tatsächliche
  Wiederholungen, bisherige Sätze, Notizen und Live-Puls. Weitere Details dürfen in
  Unteransichten liegen.
- Sätze schnell mit vorbelegten Werten bestätigen; Gewicht, Wiederholungen und
  weitere relevante Werte bearbeiten, möglichst mit der Touch-Lünette.
- Alle protokollierten Sätze bearbeiten und Fehlbedienungen rückgängig machen.
- Erst auf meinen Befehl zur nächsten Übung wechseln. Übungen umsortieren,
  vorziehen, überspringen und später wieder aufnehmen.
- Die vorhandenen Satz- und Übungsvarianten der Handy-App erhalten, darunter
  Aufwärmsätze, Supersatzgruppen, Zusatzgewichte sowie Wiederholungs-, Zeit- und
  Distanzübungen. Unbekannte Funktionen nicht als bereits vorhanden darstellen.
- Pause mit großem Countdown und nächstem Satz beziehungsweise nächster Übung samt
  Gewicht und Wiederholungen. Pause ändern oder überspringen; Ende mit Vibration
  und Ton. Countdown und laufende Pause müssen einen Gerätewechsel überstehen.
- Trainingsanzeige während der Sitzung gut sichtbar halten und eine sparsame
  Always-on-Darstellung berücksichtigen.
- Trainingsende, Zusammenfassung und bestehende Gamification auf die Uhr
  übertragen. Bereits vorhandene Gewichtssteigerungsfeiern mitdenken.
- Live-Puls und aufgezeichneter Pulsverlauf. Auf dem Handy eine Pulskurve im
  zeitlichen Zusammenhang mit Übungen, Sätzen und Zeit dazwischen.
- Gesundheitsintegration mit Samsung Health/Health Connect: verständliche
  Berechtigungen und Status für Aufzeichnung, fehlende Messwerte, ausstehenden
  Export und fehlgeschlagenen Export.
- Offlinebetrieb, Verbindungswechsel, laufende Synchronisation und Konflikte
  verständlich darstellen. Ein ungeklärter Übergabezustand darf keine falsche
  erfolgreiche Übernahme vortäuschen.
- Einen schnellen Einstieg über eine Komplikation entwerfen; eine Kachel bei
  Bedarf mit mir besprechen.
- Einen späteren Bluetooth-Trainingssensor mitdenken, dessen Bedienung aber noch
  nicht voraussetzen.

## Technische Rahmenbedingungen

Die Daten- und Gesundheitsgrundlage entsteht parallel, unabhängig von deiner
Gestaltung. Halte je Ansicht die benötigten Daten, Aktionen sowie Lade-, Leer-,
Fehler- und Wiederherstellungszustände fest.

Health Connect läuft auf dem Android-Handy; Live-Messwerte erfasst die Uhr über
Health Services. Ob Daten in Samsung Health erscheinen, hängt von dessen
Einstellungen und unterstützten Datentypen ab. Behaupte keine garantierte
Plattformfunktion, bevor der Implementierungsagent sie geprüft hat.

Die Aufzeichnung kann Messlücken und unzuverlässige Werte enthalten. Ohne
ausreichende Satzzeitstempel lässt sich der Puls nicht exakt einem Satz oder
einer Pause zuordnen; diese Unsicherheit muss sichtbar bleiben. Das Testprofil
bleibt lokal und wird nicht nach Health Connect exportiert.

Für Health Connect sind Schreibrechte für Trainings und Puls erforderlich.
Die App braucht außerdem Puls-Leserechte, um ausschließlich eigene zuvor
exportierte Einträge nach Korrekturen abzugleichen. Für die Uhr braucht es einen
bewusst gestarteten Aufzeichnungs- und Berechtigungsablauf.

Die erste Erprobung erfolgt per selbst installierter APK auf einer echten Uhr.
Es ist kein Play-Store-Release geplant.

Nicht Bestandteil dieses Auftrags ist der separat vorgemerkte Knopf
„To-do erstellen“, der später eine Trainingsaufgabe mit Packliste anlegen soll.

Bitte liefere am Ende die vollständige Implementierungsanleitung. Noch keine App
implementieren.

# Abgeschlossenes Training in Samsung Health prüfen

Ziel: Ein in GymApp abgeschlossenes persönliches Training soll nach dem Export
auch in der Samsung-Health-Trainingshistorie des Handys erscheinen. Dafür wird
kein zweites Training auf der Uhr gestartet. Dieser Ablauf ist ein Gerätetest;
ein vorhandener Pulswert allein beweist weder den Export der Trainingseinheit
noch deren Übernahme in Samsung Health.

## Voraussetzungen

- Handy und Galaxy Watch sind verbunden; GymApp ist auf beiden Geräten installiert
  und synchronisiert. GymApp und Samsung Health auf Handy und Uhr sowie Health
  Connect auf dem Handy sind aktuell. Versionsnummern für die Auswertung notieren.
- Auf dem Handy in GymApp das persönliche Profil wählen und unter Gesundheit den
  automatischen Health-Connect-Export einschalten und alle angeforderten Rechte
  erteilen. GymApp exportiert nur persönliche Trainings, die **nach** Aktivierung
  abgeschlossen wurden; das Testprofil bleibt lokal. Keine vorhandenen Trainings
  oder App-Daten löschen.
- Auf dem Handy Samsung Health > Einstellungen > Health Connect öffnen und die
  **Leseberechtigung für Training/Übungen und Herzfrequenz** erlauben. Bezeichnungen
  können je nach Version abweichen. Samsung unterscheidet Lese- und Schreibrechte;
  Samsung Health muss die GymApp-Daten lesen dürfen. Nach einer Änderung Samsung
  Health öffnen. Samsung empfiehlt außerdem die Synchronisierung mit Samsung Cloud.
- Wenn GymApp meldet, dass der Abgleich beim Öffnen erfolgt, die Handy-App nach
  dem Abschluss im Vordergrund öffnen. Der Hintergrundzugriff ist optional.

## Test und Auswertung

1. In GymApp ein neues, kurzes persönliches Training abschließen; kein Samsung-
   Health-Training parallel starten. Uhr und Handy verbunden lassen. Uhrzeit und
   ungefähre Dauer merken, bis der Abschluss auf dem Handy angekommen ist. Die
   Handy-App öffnen und den Export abwarten.
2. Auf dem Handy in Health Connect unter „Daten und Zugriff“ bzw. „Daten
   durchsuchen“ die Trainings-/Übungsdaten öffnen. Den Eintrag zur gemerkten
   Zeit suchen und Quelle GymApp, Beginn und Ende prüfen. Menübezeichnungen
   unterscheiden sich nach Android-Version. Zusätzlich die Herzfrequenzdaten
   kontrollieren, soweit für dieses Training Puls aufgezeichnet wurde.
3. Samsung Health auf dem Handy öffnen, die Startseite nach unten ziehen und die
   Trainingshistorie zum selben Zeitpunkt prüfen. Auch nach kurzer Wartezeit
   erneut prüfen; die Übernahme ist asynchron und eine feste Frist ist nicht
   dokumentiert. Samsung nennt das Öffnen und Aktualisieren der Startseite als
   Synchronisationsanlässe. Eine Anzeige auf der Uhr ist gesondert zu prüfen und
   kann aus der Anzeige auf dem Handy nicht zugesichert werden.

| Ergebnis | Nächster Schritt |
| --- | --- |
| GymApp-Training fehlt bereits in Health Connect | Exportfreigabe und Status in GymApp, persönliches Profil, Abschluss nach Freigabe und Ankunft auf dem Handy prüfen; die Handy-App erneut öffnen. Pulsdaten anderer Quellen belegen keinen GymApp-Export. |
| GymApp-Training ist in Health Connect vorhanden, fehlt aber in Samsung Health | Samsung-Health-Leserecht für Training prüfen, App öffnen und aktualisieren; installierte Versionen und Ergebnis festhalten. Damit ist die Grenze beim Import bzw. der Darstellung in Samsung Health eingegrenzt, die genaue Ursache aber noch offen. |
| Training erscheint in Samsung Health | Uhrzeit und Dauer mit GymApp vergleichen; anschließend separat prüfen, ob und wann es auf der Uhr erscheint. |

Für eine Fehlermeldung reichen App-/Android-/Wear-OS-Versionen, die gewählte
Zeitzone, ob das Training nach der Exportfreigabe abgeschlossen wurde, der
GymApp-Exportstatus und die Ergebnisse der drei Schritte. Keine Pulswerte,
Trainingsnamen, Screenshots mit Gesundheitsdaten oder Datenbankkopien öffentlich
veröffentlichen. Vor der Weitergabe solche Angaben entfernen.

## Technischer Hintergrund

Der Handy-Exporter erstellt `ExerciseSessionRecord` mit Typ `STRENGTH_TRAINING`,
Zeitstempeln, Zeitzonen-Offsets, Titel und Geräte-Metadaten. Pulswerte sind eigene
`HeartRateRecord`-Einträge. Stabile IDs erlauben Wiederholungen und spätere
Korrekturen; ein Exportstatus bestätigt die Annahme durch Health Connect, nicht
die Übernahme durch Samsung Health. Auch Trainings ohne Puls können exportiert
werden. Es werden keine Kalorien erfunden und keine direkte Samsung-Schnittstelle
verwendet. Ob Samsung die konkrete Kombination verarbeitet und anzeigt, muss auf
der installierten Version und Hardware geprüft werden.

Quelle: [Samsung Developer: Accessing Samsung Health Data through Health Connect](https://developer.samsung.com/health/blog/en/accessing-samsung-health-data-through-health-connect)
(bidirektionaler Abgleich auf dem Smartphone, Berechtigungen, Auslöser und
unterstützte Datentypen; der Umfang kann sich je nach Samsung-Health-Version ändern).

### Möglicher späterer Direktzugang

Falls der Gerätetest einen ausbleibenden Samsung-Health-Import trotz vorhandenem
Health-Connect-Training bestätigt, kann der direkte Samsung Health Data SDK als
Alternative untersucht werden. Samsungs [Code Lab](https://developer.samsung.com/codelab/health/write-exercise-data.html)
beschreibt das Schreiben abgeschlossener Trainings auf dem Handy. Laut Samsungs
[App-Erstellungsprozess](https://developer.samsung.com/health/data/process.html)
braucht das Schreiben einen nach Partnerschaftsgenehmigung vergebenen Zugangscode;
für eine verteilte App sind außerdem Registrierung von Paketname und Signatur
sowie die Zustimmung der Nutzer erforderlich. Das ist noch nicht implementiert
oder auf diesem Gerät bestätigt. Bei einer späteren Implementierung müssen
Doppeleinträge durch gleichzeitigen Health-Connect- und direkten Export vermieden
werden.

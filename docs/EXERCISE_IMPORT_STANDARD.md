# GymApp Standard fuer den Uebungsimport

Dieses Dokument beschreibt das CSV-Format und den Importprozess fuer
selbst definierte Uebungen. Es ist gleichzeitig eine Arbeitsanweisung fuer
Menschen oder andere KI-Systeme, die eine GymApp-kompatible Datei erstellen.

Der Import ist in **My exercises** ueber **Import CSV** erreichbar. Dort kann
auch eine aktuelle Vorlagendatei heruntergeladen werden.

## Grundsaetze

- Jede importierte Uebung gehoert nur zum aktuell ausgewaehlten Profil.
- GymApp erratet die Messart niemals aus dem Uebungsnamen.
- Die CSV beschreibt Uebungen, keine Trainingsplaene und keine absolvierten Sets.
- Ein Import ueberschreibt bestehende Uebungen niemals ohne eine sichtbare
  Bestaetigung in der Importvorschau.
- Studiospezifische Gewichtsabstufungen und Maschinenparameter werden nicht
  importiert. Sie werden nach dem Import je Studio und Uebung eingerichtet.
- Fotos werden nicht in der CSV gespeichert. Ein lokales Foto kann nach dem
  Import in **My exercises** hinzugefuegt werden.

## Dateiformat

Die Datei muss eine UTF-8-kodierte, komma-separierte `.csv` sein. Jede Zeile
beschreibt genau eine Uebung.

Aktuelle Formatversion: `1`

Die Kopfzeile muss exakt so lauten:

```csv
format_version,exercise_id,name,equipment,measurement_type,load_basis,primary_muscles,secondary_muscles,aliases,instructions
```

## Spalten

| Spalte | Pflicht | Bedeutung |
| --- | --- | --- |
| `format_version` | Ja | Immer `1`. |
| `exercise_id` | Nein | Exakte GymApp-ID einer bereits vorhandenen Uebung. Bei neuen Uebungen leer lassen; GymApp erzeugt eine ID. |
| `name` | Ja | Eindeutiger Anzeigename innerhalb des aktuellen Profils. |
| `equipment` | Ja | Frei waehlbare Bezeichnung, zum Beispiel `Brustpresse Maschine` oder `Kurzhanteln`. |
| `measurement_type` | Ja | Legt fest, welche Werte im Training erfasst werden. Zulaessige Werte stehen unten. |
| `load_basis` | Ja | Beschreibt, wie ein eingetragenes Gewicht zu verstehen ist. Zulaessige Kombinationen stehen unten. |
| `primary_muscles` | Nein | Mehrere Werte werden mit `|` getrennt. |
| `secondary_muscles` | Nein | Mehrere Werte werden mit `|` getrennt. |
| `aliases` | Nein | Alternative Namen fuer die Importsuche, mit `|` getrennt. |
| `instructions` | Nein | Kurze Erklaerungsschritte, mit `|` getrennt. |

Leere optionale Felder bleiben leer. Listen verwenden innerhalb einer Zelle den
senkrechten Strich `|`, damit sie nicht mit den CSV-Kommas kollidieren.

## Messarten

| `measurement_type` | Anzeige in der App | Fortschrittslogik |
| --- | --- | --- |
| `load_reps` | Gewicht und Wiederholungen | Hoeheres Gewicht kann Fortschritt sein. |
| `bodyweight_reps` | Koerpergewicht und Wiederholungen | Es wird kein Maschinengewicht eingetragen. |
| `added_weight_reps` | Zusatzgewicht und Wiederholungen | Nur das zusaetzliche Gewicht wird eingetragen. |
| `assisted_reps` | Unterstuetzung und Wiederholungen | Weniger Unterstuetzung ist schwieriger. |
| `reps_only` | Nur Wiederholungen | Kein Gewichtsfeld. |
| `load_duration` | Gewicht und Dauer | Gewicht und Sekunden werden erfasst. |
| `load_distance_duration` | Gewicht, Distanz und Dauer | Gewicht, Meter und Sekunden werden erfasst. |
| `duration_only` | Nur Dauer | Kein Gewichtsfeld. |

Die Klimmzugmaschine mit Gegengewicht muss beispielsweise
`assisted_reps` verwenden. Dadurch versteht GymApp, dass `35 kg`
Unterstuetzung schwerer als `40 kg` Unterstuetzung ist.

## Gewichtsbasis

| `load_basis` | Bedeutung | Erlaubte Messarten |
| --- | --- | --- |
| `stack` | Angezeigtes Gewicht eines Maschinenstapels | `load_reps`, `load_duration`, `load_distance_duration` |
| `total` | Gesamtgewicht der Uebung | `load_reps`, `load_duration`, `load_distance_duration` |
| `per_hand` | Gewicht pro Hand | `load_reps`, `load_duration`, `load_distance_duration` |
| `added` | Zusatzgewicht zum Koerpergewicht | Nur `added_weight_reps` |
| `assistance` | Unterstuetzungsgewicht | Nur `assisted_reps` |
| `none` | Kein Gewicht wird erfasst | `bodyweight_reps`, `reps_only`, `duration_only` |

Eine unzulaessige Kombination ist ein Validierungsfehler. GymApp soll sie nicht
automatisch korrigieren.

## Beispiel

```csv
format_version,exercise_id,name,equipment,measurement_type,load_basis,primary_muscles,secondary_muscles,aliases,instructions
1,,Brustpresse Maschine,Brustpresse,load_reps,stack,Brust,Trizeps|Vordere Schulter,Chest Press,Sitz einstellen|Schulterblaetter anlehnen|Griffe kontrolliert nach vorne druecken
1,,Klimmzugmaschine,Klimmzugmaschine mit Gegengewicht,assisted_reps,assistance,Ruecken|Latissimus,Bizeps,Assisted Pull-up|Unterstuetzte Klimmzuege,Knie oder Fuesse auf die Auflage setzen|Koerper kontrolliert hochziehen
1,,Kurzhantel-Curls,Kurzhanteln,load_reps,per_hand,Bizeps,Unterarme,Dumbbell Curl,Ellenbogen ruhig halten|Hanteln kontrolliert heben und senken
1,,Plank,Matte,duration_only,none,Bauch,Ruecken,Unterarmstuetz,Koerper in einer geraden Linie halten
```

## Regeln fuer Menschen und KI-Systeme

1. Keine fehlende Messart, Gewichtsbasis oder Uebungsidentitaet erfinden.
2. Bei einer Klimmzug- oder Dipmaschine zuerst klaeren, ob ein Gegengewicht
   unterstuetzt. In diesem Fall `assisted_reps` und `assistance` verwenden.
3. Bei Kurzhanteln klaeren, ob das notierte Gewicht pro Hand oder insgesamt
   gemeint ist.
4. `exercise_id` bei neuen Uebungen leer lassen.
5. Originalnamen erhalten. Alternative deutsche oder englische Namen in
   `aliases` eintragen.
6. Keine URLs, Bilder, Gewichtsabstufungen, Sitzpositionen oder Trainingswerte
   in die CSV aufnehmen.
7. Felder mit Kommas, Anfuehrungszeichen oder Zeilenumbruechen nach normalem
   CSV-Standard in doppelte Anfuehrungszeichen setzen.
8. Wenn eine KI mit der Erstellung beauftragt wird, soll sie als Endergebnis
   nur den CSV-Inhalt ausgeben, ohne Markdown-Codeblock oder Erklaerung.

## Ablauf in GymApp

1. Unter **My exercises** die Aktion **Import CSV** waehlen.
2. Die App liest die gesamte Datei ein, ohne bereits Daten zu speichern.
3. Eine Vorschau ordnet jede Zeile einem Status zu:
   - **Neu**: Name und ID existieren noch nicht.
   - **Vorhanden**: ID oder normalisierter Name passt eindeutig.
   - **Konflikt**: Eine passende Uebung besitzt andere Kerndaten.
   - **Fehler**: Pflichtfeld, Wert oder Kombination ist ungueltig.
4. Bei vorhandenen Uebungen ist **Ueberspringen** die Voreinstellung.
5. Bei Konflikten waehlt der Nutzer pro Zeile:
   - bestehende Uebung behalten,
   - bestehende Uebung aktualisieren,
   - als neue Kopie importieren.
6. Vor dem Speichern zeigt die App besonders deutlich `measurement_type` und
   `load_basis`, weil diese Werte die Eingabefelder und Fortschrittslogik
   bestimmen.
7. **Importieren** wird erst aktiv, wenn alle Fehler und Konflikte geloest sind.
8. Alle bestaetigten Zeilen werden gemeinsam gespeichert. Schlaegt eine Zeile
   fehl, wird keine Zeile importiert.
9. Danach erscheinen die Uebungen sofort in **My exercises**, im Planeditor und
   in der Zuordnung von Trainings- und Verlaufsimporten.

## Konflikt- und Identitaetsregeln

Die Zuordnung erfolgt in dieser Reihenfolge:

1. Exakte `exercise_id`, sofern vorhanden.
2. Exakter normalisierter Name. Gross-/Kleinschreibung, fuehrende Leerzeichen
   und mehrfach gesetzte Leerzeichen werden ignoriert.
3. Aliase dienen nur als Hinweis in der Vorschau. Sie duerfen kein
   automatisches Ueberschreiben ausloesen.

Zwei Zeilen derselben Datei mit demselben normalisierten Namen sind ein Fehler.
So wird verhindert, dass eine Uebung versehentlich doppelt oder widerspruechlich
angelegt wird.

## Technische Umsetzung

1. Das Modul `src/lib/exerciseCsv.ts` definiert Schema, Parser, Normalisierung
   und Validierungsfehler.
2. Der Importdialog in `ExercisesScreen` bietet Dateiauswahl,
   Voransicht, Konfliktentscheidungen und eine Vorlagendatei.
3. Der Parser verwendet die vorhandenen CSV- und Schema-Bibliotheken.
4. Beim Speichern erzeugt GymApp fuer neue Zeilen interne IDs und setzt
   `profileId` auf das aktive Profil.
5. Aktualisierungen verwenden denselben Mechanismus wie der manuelle
   Uebungseditor, damit Namen und Messdaten in vorhandenen Sitzungen konsistent
   aktualisiert werden.
6. Der Import wird in einer lokalen Transaktion gespeichert. Es gibt keinen
   Server-Upload.
7. Automatisierte Tests bleiben waehrend der aktiven Entwicklung zurueckgestellt
   und werden spaeter fuer Parser, Konflikte und Transaktionsverhalten ergaenzt.

## Bewusst nicht Teil von Version 1

- Bilder oder andere Binaerdateien
- Download und Speicherung externer Bild-URLs
- Studiospezifische Maschinenparameter
- Studiospezifische Gewichtsabstufungen
- Trainingsplaene oder historische Sets
- Automatische Klassifizierung anhand des Namens
- Zusammengefuehrte ZIP-Pakete

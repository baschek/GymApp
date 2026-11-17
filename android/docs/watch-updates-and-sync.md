# Uhr-Updates, Synchronisierung und Gesundheit

## Installation und Updates

Die bereits veröffentlichte Uhr-App 2.6.0 enthält diesen Updater noch nicht. Die erste Version mit Updater muss deshalb einmal manuell installiert werden. Danach prüft WorkManager regelmäßig mit Netzwerkverbindung auf neue GitHub-Releases und lädt ausschließlich die passende Uhr-APK. Android bestimmt den genauen Ausführungszeitpunkt; eine sofortige Aktualisierung ist damit nicht garantiert. In den Uhr-Einstellungen lässt sich zusätzlich manuell prüfen.

Vor der Installation werden Downloadgröße, SHA-256, Paketname, Versionscode und die Übereinstimmung der APK-Signatur mit der installierten App geprüft. Das Installieren startet der Nutzer in den Einstellungen außerhalb eines laufenden Trainings. Eine Android-Systembestätigung bleibt erforderlich. Sind die Freigabe für unbekannte Quellen oder die Bestätigungsoberfläche auf der jeweiligen Wear-OS-Version nicht verfügbar, zeigt die App den nötigen manuellen Installationsweg an. Ein erfolgreicher Download bedeutet noch keine erfolgreiche Installation.

Die Installation auf einer Galaxy Watch muss auf echter Hardware geprüft werden. Die offizielle Wear-OS-Verteilung mit automatischen App-Updates erfolgt über Google Play; diese sideloadbare Variante kann keine stille Installation versprechen.

Quellen: [Wear OS packaging](https://developer.android.com/training/wearables/packaging), [PackageInstaller confirmation](https://developer.android.com/reference/android/content/pm/PackageInstaller#STATUS_PENDING_USER_ACTION).

## Datenabgleich

App-Updates sind vom Trainingsdatenabgleich getrennt. Handy und Uhr bewahren ausgehende Änderungen dauerhaft auf und bestätigen nur angewendete oder bereits bekannte Ereignisse. Fehlgeschlagene Veröffentlichungen werden auch ohne weitere Datenänderung erneut versucht. Aktualisierte Gegenstellen handeln die Reihenfolge noch nicht bestätigter Ereignisse aus; ältere Apps erhalten weiterhin das bisherige Nachrichtenformat.

Das Handy überträgt zusätzlich eine vollständige Liste vorhandener Sitzungen. Die Uhr entfernt daraus verschwundene abgeschlossene Einträge erst, wenn keine lokalen offenen Änderungen oder Konflikte mehr daran hängen. Aktive Trainings bleiben erhalten. Beide Apps sollten aktualisiert werden, um die vollständige neue Synchronisierung zu nutzen.

## Automatischer Health-Connect-Export

Nach einer einmaligen Freigabe in den Handy-Einstellungen werden neue abgeschlossene persönliche Trainings automatisch exportiert, auch ohne Pulsdaten. Später eintreffende Pulswerte aktualisieren denselben Export. Alte Trainings werden beim Einschalten nicht pauschal nachgetragen. Das Testprofil bleibt lokal und wird nicht exportiert.

Ohne unterstützten und freigegebenen Health-Connect-Hintergrundzugriff wird beim nächsten Öffnen der Handy-App abgeglichen. Die Uhr kann die Freigabe auf dem Handy anfordern, sie aber nicht selbst erteilen. Samsung Health muss Health Connect unterstützen und die passenden Lesefreigaben haben; eine direkte Schreibschnittstelle zu Samsung Health wird nicht verwendet.

import {
  ArchiveRestore,
  DatabaseBackup,
  Download,
  HardDrive,
  MapPin,
  RefreshCcw,
  ShieldCheck,
  SlidersHorizontal,
  TestTube2,
  Upload
} from "lucide-react";
import { useRef, useState } from "react";
import {
  MachineProfileEditor,
  type MachineProfileValues
} from "../components/MachineProfileEditor";
import { Modal, Segmented } from "../components/ui";
import { useApp } from "../context/AppContext";
import { useQuery } from "../hooks/useQuery";
import {
  backupFile,
  createPersonalBackup,
  parseBackup,
  replacePersonalData,
  shareOrDownloadBackup
} from "../lib/backup";
import { db, resetTestProfile } from "../lib/db";
import { getCatalog } from "../lib/catalog";
import { getGym, gyms } from "../lib/gyms";
import type {
  BackupPayload,
  EquipmentProfile,
  Exercise,
  ThemeMode,
  WorkoutSession
} from "../types";

function BackupPreview({
  payload,
  onClose,
  onRestored
}: {
  payload: BackupPayload;
  onClose: () => void;
  onRestored: () => void;
}) {
  const [saving, setSaving] = useState(false);
  async function restore() {
    setSaving(true);
    try {
      const safety = backupFile(await createPersonalBackup());
      await shareOrDownloadBackup(safety);
      await replacePersonalData(payload);
      await db.settings.put({ key: "lastBackupAt", value: payload.exportedAt });
      onRestored();
      onClose();
    } finally {
      setSaving(false);
    }
  }
  return (
    <Modal title="Restore preview" onClose={onClose}>
      <div className="import-summary">
        <span>
          <small>Created</small>
          <strong>{new Date(payload.exportedAt).toLocaleDateString()}</strong>
        </span>
        <span>
          <small>Plans</small>
          <strong>{payload.plans.length}</strong>
        </span>
        <span>
          <small>Workouts</small>
          <strong>{payload.sessions.length}</strong>
        </span>
      </div>
      <div className="callout warning">
        <strong>Personal data will be replaced</strong>
        <p>A safety export of the current Personal profile is required before replacement.</p>
      </div>
      <button className="primary wide-button" disabled={saving} onClick={() => void restore()}>
        <ArchiveRestore size={18} /> {saving ? "Preparing safety backup..." : "Save current data and restore"}
      </button>
    </Modal>
  );
}

export function SettingsScreen() {
  const {
    profileId,
    setProfileId,
    gymId,
    setGymId,
    theme,
    setTheme,
    refresh,
    notify
  } = useApp();
  const active = useQuery(
    () =>
      db.sessions
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((sessions) => sessions.find((session) => session.status === "active")),
    undefined as WorkoutSession | undefined
  );
  const equipmentProfiles = useQuery(
    () =>
      db.equipmentProfiles
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((profiles) =>
          profiles.filter((profile) => (profile.gymId ?? "basic_fit") === gymId)
        ),
    [] as EquipmentProfile[],
    [gymId]
  );
  const catalog = useQuery(() => getCatalog(profileId), [] as Exercise[]);
  const lastBackup = useQuery(
    () => db.settings.get("lastBackupAt").then((setting) => setting?.value as string | undefined),
    undefined as string | undefined
  );
  const [restorePayload, setRestorePayload] = useState<BackupPayload | null>(null);
  const [editingEquipment, setEditingEquipment] = useState<EquipmentProfile | null>(null);
  const restoreRef = useRef<HTMLInputElement>(null);

  async function backup() {
    const payload = await createPersonalBackup();
    await shareOrDownloadBackup(backupFile(payload));
    await db.settings.put({ key: "lastBackupAt", value: payload.exportedAt });
    refresh();
    notify("Backup created");
  }

  async function switchProfile(next: "personal" | "test") {
    if (active) {
      notify("Finish the active workout before switching profiles");
      return;
    }
    setProfileId(next);
    notify(next === "test" ? "Test profile active" : "Personal profile active");
  }

  async function resetTest() {
    const confirmation = window.prompt('Type "RESET TEST" to permanently clear the Test profile.');
    if (confirmation !== "RESET TEST") return;
    await resetTestProfile();
    refresh();
    notify("Test profile reset");
  }

  async function saveEquipment(values: MachineProfileValues) {
    if (!editingEquipment) return;
    await db.equipmentProfiles.put({
      ...editingEquipment,
      ...values,
      id: `${profileId}:${gymId}:${editingEquipment.exerciseId}`,
      gymId,
      prompted: true,
      updatedAt: new Date().toISOString()
    });
    setEditingEquipment(null);
    refresh();
    notify("Available weights updated");
  }

  return (
    <>
      <div className="page-heading">
        <div>
          <p className="eyebrow">Local app controls</p>
          <h1>Settings</h1>
        </div>
      </div>
      <div className="settings-sections">
        <section className="settings-section">
          <header>
            <TestTube2 size={20} />
            <div>
              <h2>Profile</h2>
              <p>Personal and Test data are fully isolated.</p>
            </div>
          </header>
          <Segmented
            label="Active profile"
            value={profileId}
            onChange={(value) => void switchProfile(value)}
            options={[
              { value: "personal", label: "Personal" },
              { value: "test", label: "Test" }
            ]}
          />
          {profileId === "test" && (
            <button className="secondary danger-text" onClick={() => void resetTest()}>
              <RefreshCcw size={17} /> Reset Test profile
            </button>
          )}
        </section>
        <section className="settings-section">
          <header>
            <MapPin size={20} />
            <div>
              <h2>Current gym</h2>
              <p>Machine settings and weight increments are specific to each gym.</p>
            </div>
          </header>
          <div className="gym-grid">
            {gyms.map((gym) => (
              <button
                className={`gym-option ${gym.id === gymId ? "selected" : ""}`}
                key={gym.id}
                onClick={() => setGymId(gym.id)}
              >
                <span className={`gym-logo-well logo-${gym.logoTone}`}>
                  <img src={gym.logoUrl} alt={`${gym.name} logo`} />
                </span>
                <strong>{gym.name}</strong>
              </button>
            ))}
          </div>
        </section>
        <section className="settings-section">
          <header>
            <ShieldCheck size={20} />
            <div>
              <h2>Appearance</h2>
              <p>Dark is the first-run default.</p>
            </div>
          </header>
          <Segmented<ThemeMode>
            label="Theme"
            value={theme}
            onChange={setTheme}
            options={[
              { value: "dark", label: "Dark" },
              { value: "light", label: "Light" },
              { value: "system", label: "System" }
            ]}
          />
        </section>
        {profileId === "personal" && (
          <section className="settings-section">
            <header>
              <DatabaseBackup size={20} />
              <div>
                <h2>Backup and restore</h2>
                <p>
                  {lastBackup
                    ? `Last backup ${new Date(lastBackup).toLocaleDateString()}`
                    : "No Personal backup has been created yet."}
                </p>
              </div>
            </header>
            <div className="button-row">
              <button className="primary" onClick={() => void backup()}>
                <Upload size={17} /> Back up now
              </button>
              <button className="secondary" onClick={() => restoreRef.current?.click()}>
                <Download size={17} /> Restore backup
              </button>
              <input
                ref={restoreRef}
                type="file"
                accept=".json,application/json"
                hidden
                onChange={(event) => {
                  const file = event.target.files?.[0];
                  if (file) {
                    void file
                      .text()
                      .then(parseBackup)
                      .then(setRestorePayload)
                      .catch(() => notify("This is not a valid GymApp backup"));
                  }
                  event.target.value = "";
                }}
              />
            </div>
          </section>
        )}
        <section className="settings-section">
          <header>
            <SlidersHorizontal size={20} />
            <div>
              <h2>Machine settings</h2>
              <p>Weight increments and setup values for {getGym(gymId).name}.</p>
            </div>
          </header>
          {equipmentProfiles.length ? (
            <div className="settings-list">
              {equipmentProfiles.map((profile) => (
                <button
                  key={profile.id}
                  onClick={() => setEditingEquipment(profile)}
                >
                  <span>
                    {catalog.find((exercise) => exercise.id === profile.exerciseId)?.name ??
                      profile.exerciseId.replaceAll("_", " ")}
                  </span>
                  <small>
                    {profile.availableWeightsKg.length} weights ·{" "}
                    {profile.setupParameters?.length ?? 0} setup fields
                  </small>
                </button>
              ))}
            </div>
          ) : (
            <p className="section-placeholder">
              Machine settings appear after the first use of an exercise at this gym.
            </p>
          )}
        </section>
        <section className="settings-section">
          <header>
            <HardDrive size={20} />
            <div>
              <h2>Storage</h2>
              <p>Workout data is stored only in this browser installation.</p>
            </div>
          </header>
          <p className="storage-note">
            Clearing Chrome site data or uninstalling the PWA may remove local data. GitHub Pages hosts only the app files,
            never your workout history.
          </p>
        </section>
      </div>
      {restorePayload && (
        <BackupPreview
          payload={restorePayload}
          onClose={() => setRestorePayload(null)}
          onRestored={() => {
            refresh();
            notify("Personal backup restored");
          }}
        />
      )}
      {editingEquipment && (
        <MachineProfileEditor
          exerciseName={
            catalog.find((exercise) => exercise.id === editingEquipment.exerciseId)?.name ??
            editingEquipment.exerciseId.replaceAll("_", " ")
          }
          gymName={getGym(gymId).name}
          profile={editingEquipment}
          onClose={() => setEditingEquipment(null)}
          onSave={saveEquipment}
        />
      )}
    </>
  );
}

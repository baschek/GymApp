import {
  BarChart3,
  ClipboardList,
  DatabaseBackup,
  Dumbbell,
  History,
  MapPin,
  Settings,
  ShieldAlert
} from "lucide-react";
import type { ReactNode } from "react";
import { useApp } from "../context/AppContext";
import { getGym, gyms } from "../lib/gyms";

const tabs = [
  { path: "workouts", label: "Workouts", icon: ClipboardList },
  { path: "history", label: "History", icon: History },
  { path: "exercises", label: "Exercises", icon: Dumbbell },
  { path: "progress", label: "Progress", icon: BarChart3 },
  { path: "settings", label: "Settings", icon: Settings }
];

// eslint-disable-next-line react-refresh/only-export-components
export function navigate(path: string) {
  window.location.hash = `#/${path}`;
}

export function AppShell({
  route,
  children,
  updateReady,
  applyUpdate,
  backupDue,
  storageAtRisk
}: {
  route: string;
  children: ReactNode;
  updateReady: boolean;
  applyUpdate: () => void;
  backupDue: boolean;
  storageAtRisk: boolean;
}) {
  const { profileId, gymId, setGymId, toast } = useApp();
  const activeGym = getGym(gymId);
  return (
    <div className="app-shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark">
            <Dumbbell size={22} />
          </span>
          <strong>GymApp</strong>
        </div>
        <label className="gym-switcher">
          <MapPin size={16} />
          <img
            className={`gym-switcher-logo logo-${activeGym.logoTone}`}
            src={activeGym.logoUrl}
            alt=""
          />
          <select
            aria-label="Current gym"
            value={gymId}
            onChange={(event) => setGymId(event.target.value as typeof gymId)}
          >
            {gyms.map((gym) => (
              <option key={gym.id} value={gym.id}>
                {gym.name}
              </option>
            ))}
          </select>
        </label>
        {profileId === "test" && <span className="test-banner">TEST PROFILE</span>}
      </header>
      {updateReady && route !== "workout" && (
        <div className="update-banner">
          <span>A new version is ready.</span>
          <button onClick={applyUpdate}>Reload to update</button>
        </div>
      )}
      {backupDue && route !== "settings" && (
        <button className="backup-reminder" onClick={() => navigate("settings")}>
          <DatabaseBackup size={18} /> Personal backup is due
        </button>
      )}
      {storageAtRisk && route !== "settings" && route !== "workout" && (
        <button className="storage-reminder" onClick={() => navigate("settings")}>
          <ShieldAlert size={18} /> Protect local data from automatic cleanup
        </button>
      )}
      <main className="page">{children}</main>
      <nav className="bottom-nav" aria-label="Primary navigation">
        {tabs.map(({ path, label, icon: Icon }) => (
          <button
            key={path}
            className={
              route === path ||
              (route === "workout" && path === "workouts") ||
              (route.startsWith("plan/") && path === "workouts")
                ? "active"
                : ""
            }
            onClick={() => navigate(path)}
          >
            <Icon size={21} />
            <span>{label}</span>
          </button>
        ))}
      </nav>
      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}

import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import type { GymId, ProfileId, ThemeMode } from "../types";

interface AppContextValue {
  profileId: ProfileId;
  setProfileId: (profile: ProfileId) => void;
  gymId: GymId;
  setGymId: (gym: GymId) => void;
  theme: ThemeMode;
  setTheme: (theme: ThemeMode) => void;
  revision: number;
  refresh: () => void;
  toast: string | null;
  notify: (message: string) => void;
}

const AppContext = createContext<AppContextValue | null>(null);

function storedGym(profileId: ProfileId): GymId {
  const stored = localStorage.getItem(`gymapp-gym-${profileId}`);
  return stored === "john_reed" ||
    stored === "ai_lahnstein" ||
    stored === "ai_koblenz"
    ? stored
    : "basic_fit";
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [profileId, setProfileState] = useState<ProfileId>(() =>
    localStorage.getItem("gymapp-profile") === "test" ? "test" : "personal"
  );
  const [theme, setThemeState] = useState<ThemeMode>(() => {
    const stored = localStorage.getItem("gymapp-theme");
    return stored === "light" || stored === "system" ? stored : "dark";
  });
  const [gymId, setGymState] = useState<GymId>(() => storedGym(profileId));
  const [revision, setRevision] = useState(0);
  const [toast, setToast] = useState<string | null>(null);

  useEffect(() => {
    const root = document.documentElement;
    const resolved =
      theme === "system"
        ? window.matchMedia("(prefers-color-scheme: dark)").matches
          ? "dark"
          : "light"
        : theme;
    root.dataset.theme = resolved;
    localStorage.setItem("gymapp-theme", theme);
  }, [theme]);

  const value = useMemo<AppContextValue>(
    () => ({
      profileId,
      setProfileId(profile) {
        localStorage.setItem("gymapp-profile", profile);
        setProfileState(profile);
        setGymState(storedGym(profile));
        setRevision((value) => value + 1);
      },
      gymId,
      setGymId(next) {
        localStorage.setItem(`gymapp-gym-${profileId}`, next);
        setGymState(next);
        setRevision((value) => value + 1);
      },
      theme,
      setTheme(next) {
        setThemeState(next);
      },
      revision,
      refresh() {
        setRevision((value) => value + 1);
      },
      toast,
      notify(message) {
        setToast(message);
        window.setTimeout(() => setToast(null), 3200);
      }
    }),
    [gymId, profileId, revision, theme, toast]
  );

  return <AppContext.Provider value={value}>{children}</AppContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useApp(): AppContextValue {
  const context = useContext(AppContext);
  if (!context) throw new Error("useApp must be used inside AppProvider");
  return context;
}

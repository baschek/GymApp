import { lazy, Suspense, useEffect, useRef, useState } from "react";
import { useRegisterSW } from "virtual:pwa-register/react";
import { AppShell, navigate } from "./components/AppShell";
import { MilestoneCelebration } from "./components/MilestoneCelebration";
import { useApp } from "./context/AppContext";
import { useQuery } from "./hooks/useQuery";
import { db } from "./lib/db";
import type { Milestone, WorkoutSession } from "./types";

const ActiveWorkoutScreen = lazy(() =>
  import("./screens/ActiveWorkoutScreen").then((module) => ({ default: module.ActiveWorkoutScreen }))
);
const ExercisesScreen = lazy(() =>
  import("./screens/ExercisesScreen").then((module) => ({ default: module.ExercisesScreen }))
);
const HistoryScreen = lazy(() =>
  import("./screens/HistoryScreen").then((module) => ({ default: module.HistoryScreen }))
);
const PlanEditorScreen = lazy(() =>
  import("./screens/PlanEditorScreen").then((module) => ({ default: module.PlanEditorScreen }))
);
const ProgressScreen = lazy(() =>
  import("./screens/ProgressScreen").then((module) => ({ default: module.ProgressScreen }))
);
const SettingsScreen = lazy(() =>
  import("./screens/SettingsScreen").then((module) => ({ default: module.SettingsScreen }))
);
const WorkoutsScreen = lazy(() =>
  import("./screens/WorkoutsScreen").then((module) => ({ default: module.WorkoutsScreen }))
);

function currentRoute() {
  return window.location.hash.replace(/^#\//, "") || "workouts";
}

export default function App() {
  const { profileId, notify } = useApp();
  const [route, setRoute] = useState(currentRoute);
  const redirected = useRef(false);
  const activeSession = useQuery(
    () =>
      db.sessions
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((sessions) => sessions.find((session) => session.status === "active")),
    undefined as WorkoutSession | undefined
  );
  const unseenMilestone = useQuery(
    () =>
      db.milestones
        .where("profileId")
        .equals(profileId)
        .toArray()
        .then((items) =>
          items
            .filter((item) => !item.seen)
            .sort((a, b) => b.occurredAt.localeCompare(a.occurredAt))
            .at(0)
        ),
    undefined as Milestone | undefined
  );
  const backupState = useQuery(
    async () => {
      if (profileId !== "personal") return { due: false };
      const [sessions, setting] = await Promise.all([
        db.sessions.where("profileId").equals("personal").toArray(),
        db.settings.get("lastBackupAt")
      ]);
      const completed = sessions.filter((session) => session.status === "completed");
      const last = typeof setting?.value === "string" ? setting.value : undefined;
      const since = last
        ? completed.filter((session) => (session.completedAt ?? session.startedAt) > last).length
        : completed.length;
      const ageDue = last ? Date.now() - new Date(last).getTime() >= 30 * 24 * 60 * 60 * 1000 : false;
      return { due: since >= 5 || ageDue };
    },
    { due: false }
  );
  const {
    needRefresh: [updateReady],
    updateServiceWorker
  } = useRegisterSW({
    onRegistered() {
      // Updates are checked by the service worker and activated only after confirmation.
    },
    onRegisterError() {
      notify("Offline installation could not be initialized");
    }
  });

  useEffect(() => {
    const listener = () => setRoute(currentRoute());
    window.addEventListener("hashchange", listener);
    return () => window.removeEventListener("hashchange", listener);
  }, []);

  useEffect(() => {
    if (activeSession && !redirected.current) {
      redirected.current = true;
      navigate("workouts");
    }
  }, [activeSession]);

  let content;
  let shellRoute = route.split("/")[0] || "workouts";
  if (route === "workouts") {
    if (activeSession) {
      content = <ActiveWorkoutScreen session={activeSession} />;
      shellRoute = "workout";
    } else {
      content = <WorkoutsScreen />;
    }
  } else if (route === "history") {
    content = <HistoryScreen />;
  } else if (route === "exercises") {
    content = <ExercisesScreen />;
  } else if (route === "progress") {
    content = <ProgressScreen />;
  } else if (route === "settings") {
    content = <SettingsScreen />;
  } else if (route.startsWith("plan/")) {
    content = <PlanEditorScreen planId={route.slice("plan/".length)} />;
    shellRoute = route;
  } else {
    content = <WorkoutsScreen />;
    shellRoute = "workouts";
  }

  return (
    <AppShell
      route={shellRoute}
      updateReady={updateReady}
      applyUpdate={() => void updateServiceWorker(true)}
      backupDue={backupState.due}
    >
      <Suspense fallback={<div className="loading">Loading GymApp...</div>}>{content}</Suspense>
      {unseenMilestone && <MilestoneCelebration milestone={unseenMilestone} />}
    </AppShell>
  );
}

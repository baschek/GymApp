import type { WorkoutSession } from "../types";

const preferenceKey = "gymapp-timer-notifications-enabled";
const notificationTag = "gymapp-active-timer";

export function timerNotificationsEnabled(): boolean {
  return (
    localStorage.getItem(preferenceKey) === "true" &&
    "Notification" in window &&
    Notification.permission === "granted"
  );
}

export async function enableTimerNotifications(): Promise<boolean> {
  if (!("Notification" in window) || !("serviceWorker" in navigator)) return false;
  const registration = await navigator.serviceWorker.getRegistration(
    import.meta.env.BASE_URL
  );
  if (!registration) return false;
  const permission =
    Notification.permission === "granted"
      ? "granted"
      : await Notification.requestPermission();
  if (permission !== "granted") return false;
  localStorage.setItem(preferenceKey, "true");
  return true;
}

export async function disableTimerNotifications(): Promise<void> {
  localStorage.setItem(preferenceKey, "false");
  await closeTimerNotification();
}

export async function closeTimerNotification(): Promise<void> {
  if (!("serviceWorker" in navigator)) return;
  const registration = await navigator.serviceWorker.getRegistration(
    import.meta.env.BASE_URL
  );
  if (!registration) return;
  const notifications = await registration.getNotifications({ tag: notificationTag });
  notifications.forEach((notification) => notification.close());
}

export async function showTimerNotification(
  session: WorkoutSession,
  exerciseName: string,
  suggestedRestSeconds: number
): Promise<void> {
  if (!timerNotificationsEnabled() || !("serviceWorker" in navigator)) return;
  const activeSince = session.setStartedAt ?? session.restStartedAt;
  if (!activeSince) {
    await closeTimerNotification();
    return;
  }

  const phase = session.setStartedAt
    ? "Current set"
    : session.timerPhase === "machine_setup"
      ? "Machine change & setup"
      : "Rest";
  const startTime = new Date(activeSince).toLocaleTimeString([], {
    hour: "2-digit",
    minute: "2-digit"
  });
  const suggested =
    phase === "Rest"
      ? ` · suggested ${Math.floor(suggestedRestSeconds / 60)}:${String(
          suggestedRestSeconds % 60
        ).padStart(2, "0")}`
      : "";
  const registration = await navigator.serviceWorker.getRegistration(
    import.meta.env.BASE_URL
  );
  if (!registration) return;
  await registration.showNotification(`GymApp · ${phase}`, {
    body: `${exerciseName} · started ${startTime}${suggested}`,
    icon: `${import.meta.env.BASE_URL}icons/icon-192.png`,
    badge: `${import.meta.env.BASE_URL}icons/icon-192.png`,
    tag: notificationTag,
    requireInteraction: true,
    silent: true,
    data: { url: `${location.origin}${import.meta.env.BASE_URL}#/workouts` }
  });
}

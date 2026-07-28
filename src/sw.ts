/// <reference lib="webworker" />

import { clientsClaim } from "workbox-core";
import {
  cleanupOutdatedCaches,
  createHandlerBoundToURL,
  precacheAndRoute
} from "workbox-precaching";
import { NavigationRoute, registerRoute } from "workbox-routing";

declare const self: ServiceWorkerGlobalScope & {
  __WB_MANIFEST: Array<{ revision: string | null; url: string }>;
};

cleanupOutdatedCaches();
precacheAndRoute(self.__WB_MANIFEST);
registerRoute(new NavigationRoute(createHandlerBoundToURL("index.html")));
clientsClaim();

self.addEventListener("message", (event) => {
  if (event.data?.type === "SKIP_WAITING") void self.skipWaiting();
});

self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url =
    typeof event.notification.data?.url === "string"
      ? event.notification.data.url
      : `${self.location.origin}/GymApp/#/workouts`;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true
      });
      const existing = windows.at(0);
      if (existing) {
        existing.postMessage({ type: "OPEN_ACTIVE_WORKOUT" });
        await existing.focus();
        return;
      }
      await self.clients.openWindow(url);
    })()
  );
});

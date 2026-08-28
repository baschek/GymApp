import { useEffect, useState } from "react";
import App from "../App";
import { AppProvider } from "../context/AppContext";
import { db } from "../lib/db";

export function DatabaseGate() {
  const [state, setState] = useState<"opening" | "ready" | "error">("opening");

  useEffect(() => {
    let active = true;
    void db
      .open()
      .then(() => {
        if (active) setState("ready");
      })
      .catch(() => {
        if (active) setState("error");
      });
    return () => {
      active = false;
    };
  }, []);

  if (state === "opening") return <main className="startup-state">Opening local workout data...</main>;
  if (state === "error") {
    return (
      <main className="startup-state error">
        <h1>Local workout data could not be opened</h1>
        <p>Do not clear Chrome storage or reinstall the app. Close GymApp, reopen it, and retry first.</p>
        <button className="primary" onClick={() => window.location.reload()}>
          Retry
        </button>
      </main>
    );
  }

  return (
    <AppProvider>
      <App />
    </AppProvider>
  );
}

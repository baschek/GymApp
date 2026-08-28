import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { DatabaseGate } from "./components/DatabaseGate";
import "./styles.css";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <DatabaseGate />
  </StrictMode>
);

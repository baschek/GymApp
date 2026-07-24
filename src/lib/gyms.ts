import type { GymId } from "../types";

export interface GymDefinition {
  id: GymId;
  name: string;
  shortName: string;
  logoUrl: string;
  logoTone: "light" | "dark";
}

export const gyms: GymDefinition[] = [
  {
    id: "basic_fit",
    name: "Basic Fit",
    shortName: "Basic Fit",
    logoUrl: `${import.meta.env.BASE_URL}gyms/basic-fit.png`,
    logoTone: "light"
  },
  {
    id: "john_reed",
    name: "John Reed",
    shortName: "John Reed",
    logoUrl: `${import.meta.env.BASE_URL}gyms/john-reed.png`,
    logoTone: "dark"
  },
  {
    id: "ai_lahnstein",
    name: "AI Fitness Lahnstein",
    shortName: "AI Lahnstein",
    logoUrl: `${import.meta.env.BASE_URL}gyms/ai-fitness.png`,
    logoTone: "light"
  },
  {
    id: "ai_koblenz",
    name: "AI Fitness Koblenz",
    shortName: "AI Koblenz",
    logoUrl: `${import.meta.env.BASE_URL}gyms/ai-fitness.png`,
    logoTone: "light"
  }
];

export function getGym(gymId: GymId): GymDefinition {
  return gyms.find((gym) => gym.id === gymId) ?? gyms[0];
}

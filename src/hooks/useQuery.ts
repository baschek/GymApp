import { useEffect, useState } from "react";
import { useApp } from "../context/AppContext";

export function useQuery<T>(query: () => Promise<T>, initial: T, dependencies: unknown[] = []): T {
  const { revision, profileId } = useApp();
  const [value, setValue] = useState(initial);

  useEffect(() => {
    let active = true;
    void query().then((result) => {
      if (active) setValue(result);
    });
    return () => {
      active = false;
    };
    // query is intentionally supplied inline and refreshed via revision.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [revision, profileId, ...dependencies]);

  return value;
}

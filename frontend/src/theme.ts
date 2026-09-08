import { useCallback, useEffect, useState } from "react";

// Light by default, and deliberately not "whatever the operating system says".
//
// The panel is read in daylight next to the bench it measures, so light is the
// working default rather than a fallback. Following prefers-color-scheme would
// make the first visit differ from machine to machine for a reason that has
// nothing to do with the room — and the toggle exists precisely so the reader
// decides once and the choice sticks.

export type Theme = "light" | "dark";

const KEY = "psychron.theme";

const read = (): Theme => {
  try {
    const v = localStorage.getItem(KEY);
    return v === "dark" || v === "light" ? v : "light";
  } catch {
    // Private windows and blocked site data throw on access rather than
    // returning null. A theme is not worth a blank page.
    return "light";
  }
};

// The attribute on the root element is what the palette actually hangs off, so
// it is the single source of truth about what is currently painted.
const apply = (t: Theme) => {
  document.documentElement.dataset.theme = t;
  try { localStorage.setItem(KEY, t); } catch { /* nothing to do */ }
};

export function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(read);

  // Reconciles with the inline script in index.html, which sets the attribute
  // before the first paint so a dark reader never gets a white flash.
  useEffect(() => { apply(theme); }, [theme]);

  const toggle = useCallback(() => {
    // The DOM is written first, before React is told anything. Child effects
    // run before parent effects, so a component that reads the palette out of
    // computed styles — the chart does, to stroke its axes — would otherwise
    // run while the root still carries the theme being left behind, and paint
    // itself in the ink of the wrong ground.
    const next: Theme = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    apply(next);
    setTheme(next);
  }, []);

  return [theme, toggle];
}

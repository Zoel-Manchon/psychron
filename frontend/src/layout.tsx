import { useId, type ReactNode } from "react";

// The sheet's structure, as components: a node, its numbered sections, and the
// tiles inside them. The CSS decides the grid; these decide that every node, every
// section and every tile is built the same way, so the page reads as one document
// instead of a series of panels that each invented their own spacing.

/** One of the two nodes: the largest unit on the page. */
export function Node({ id, eyebrow, title, status, children }: {
  id: string; eyebrow: string; title: string; status?: ReactNode; children: ReactNode;
}) {
  const headingId = useId();
  return (
    <section className="node" id={id} aria-labelledby={headingId}>
      <header className="node-head">
        <div>
          <span className="eyebrow">{eyebrow}</span>
          <h2 id={headingId}>{title}</h2>
        </div>
        {status && <div className="node-status">{status}</div>}
      </header>
      {children}
    </section>
  );
}

/** A numbered subject inside a node, with what it is measured from set small on the right. */
export function Section({ index, title, aside, children }: {
  index: string; title: string; aside?: ReactNode; children: ReactNode;
}) {
  const headingId = useId();
  return (
    <section className="section" aria-labelledby={headingId}>
      <div className="section-head">
        <span className="section-index">{index}</span>
        <h3 id={headingId}>{title}</h3>
        {aside && <span className="section-aside">{aside}</span>}
      </div>
      {children}
    </section>
  );
}

type Size = "hero" | "major" | "normal";

/** A labelled number with its unit, and what must be said beside it so it is not misread. */
export function Tile({ label, value, unit, size = "normal", span, children }: {
  label: string; value: string; unit?: string; size?: Size; span?: 2 | 3 | 4; children?: ReactNode;
}) {
  return (
    <div className={`tile${span ? ` span${span}` : ""}`}>
      <span className="label">{label}</span>
      <div className={`value${size === "hero" ? " value-hero" : size === "major" ? " value-major" : ""}`}>
        {value}
        {unit && <span className="unit">{unit}</span>}
      </div>
      {children}
    </div>
  );
}

/** A block that is not a number: a table, a drawing, a list. Labelled like a tile. */
export function Panel({ label, aside, span, children }: {
  label: string; aside?: ReactNode; span?: 2 | 3 | 4; children: ReactNode;
}) {
  return (
    <div className={`figure${span ? ` span${span}` : ""}`}>
      <div className="figure-head">
        <span className="label">{label}</span>
        {aside && <span className="readout">{aside}</span>}
      </div>
      {children}
    </div>
  );
}

/** The square pip the phone's own screen uses: filled while the node is reporting. */
export function Pip({ on }: { on: boolean }) {
  return <span className={`pip${on ? " pip-on" : ""}`} aria-hidden="true" />;
}

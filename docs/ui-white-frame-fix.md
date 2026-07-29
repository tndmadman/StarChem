# UI white-frame fix

The game-style scrollbar now paints its entire component area with the StarChem dark track color before drawing the inset rail and thumb. Hover details no longer use Swing's heavyweight `PopupFactory` container; they use a borderless `JWindow` with a transparent background when supported and a dark fallback background otherwise.

A pixel-level validator renders both components and fails if near-white framing pixels remain.

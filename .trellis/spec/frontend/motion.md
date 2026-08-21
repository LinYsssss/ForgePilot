# Frontend motion contract

The Precision Review Console uses restrained CSS transitions for navigation and
surface feedback only. Motion cannot be the sole carrier of status, hierarchy,
Finding state, confidence, Requirement status, or human decision.

Under `@media (prefers-reduced-motion: reduce)`, disable continuous animation,
large movement, parallax, and animated scrolling. Essential feedback becomes an
immediate state change or negligible opacity transition; content and focus order
remain unchanged.

Do not add per-frame Vue state updates or ambient animation to the Phase 1 shell.
Future JavaScript motion must stop for reduced motion, hidden documents,
unfocused windows, and coarse pointers.

Verification is covered by `frontend/tests/motion.spec.ts` plus manual review of
normal/reduced-motion states at 1440, 768, and 390 CSS px.

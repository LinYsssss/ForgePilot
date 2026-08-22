# Frontend motion contract

The Precision Review Console uses a complete cyber-motion language in normal-
motion mode: interactive canvas particles, floating neon orbs, moving grid and
scanline atmosphere, radar and laser sweeps, pulse glow, holographic borders,
shimmer, light sweeps, and route/page entrance choreography. Motion cannot be
the sole carrier of status, hierarchy, Finding state, confidence, Requirement
status, or human decision, and it cannot imply fake engine activity or data.

Under `@media (prefers-reduced-motion: reduce)`, disable continuous animation,
large movement, parallax, and animated scrolling. Essential feedback becomes an
immediate state change or negligible opacity transition; content and focus order
remain unchanged.

Per-frame canvas state stays outside Vue reactivity. JavaScript motion must stop
for reduced motion, hidden documents, and unfocused windows, and it must remove
every global listener and pending animation frame when unmounted. Device pixel
ratio and particle count stay bounded; coarse pointers retain ambient motion
without pointer interaction.

Verification is covered by `frontend/tests/motion.spec.ts`, focused particle
helper tests, and manual review of normal/reduced-motion states at 1440, 768,
and 390 CSS px.

# Component Guidelines

Components are Vue 3 single-file components using `<script setup lang="ts">`.
`AppShell` owns document landmarks, navigation, and keyed route transitions.
The shared `components/motion/` boundary owns presentation-only ambient canvas
behavior and deterministic motion helpers; it does not own business state or
requests. Components communicate through typed props and events and must not
reach across feature boundaries or encode server calls in templates.

## Component structure

Use this order unless a component has a documented reason to differ:

```vue
<script setup lang="ts">
import { computed } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();
const title = computed(() => String(route.meta.title ?? "ForgePilot"));
</script>

<template>
  <section aria-labelledby="foundation-title">
    <h1 id="foundation-title">{{ title }}</h1>
  </section>
</template>
```

Keep setup logic before the template and keep templates declarative. Prefer
native elements and slots over wrapper components. `RouterLink` and
`RouterView` are used directly for navigation and route composition.

## Props and events

- Declare props with `defineProps<T>()`; use `withDefaults` only when the
  default is part of the component contract.
- Declare emitted events with `defineEmits<T>()` and use stable, verb-like
  event names (`submit`, `dismiss`, `select`).
- Keep props immutable. Derive display values with `computed` instead of
  mutating incoming objects.
- Do not pass untyped bags (`Record<string, any>`) or route objects through
  generic component props. Define the smallest useful interface at the
  boundary.
- A component that needs server data receives typed data or a callback from
  its owner; it does not silently create a second request abstraction.

## Styling

The selected direction is **B — Precision Review Console**. Use semantic CSS
custom properties from `src/styles/tokens.css` for color, type, and reusable
spacing, radius, shadow, and motion. Global layout and responsive foundations
live in `src/styles/base.css`, where one-off structural dimensions and fluid
ratios may be local. A component may use `<style scoped>` for genuinely local
selectors, but reusable visual values still come from tokens. Raw hex/rgb/hsl
values outside `tokens.css` and forbidden framework dependencies are rejected
by the foundation policy lint. One-off theme values and inline style objects
are also forbidden by review; reusable visual values require named tokens.

Keep operational reading order in DOM order. Long evidence, paths, and diffs
may scroll inside a bounded region, but a component must not create page-wide
horizontal overflow.

Bound a long region with the property group `FindingCard.vue`'s
`.narrative-body` established — max-height, `overflow: auto`,
`white-space: pre-wrap`, `word-break: break-word` — rather than inventing a
second one. Two rules make it behave:

- Bound the whole result region, not each block inside it. Nesting a list's
  scrollbar inside a report's scrollbar is worse to use than a long page.
- Never put `white-space: pre-wrap` on a `<ul>`/`<ol>`/`<table>`: template
  indentation between child elements then renders as visible blank lines. Put
  it only on the elements that actually carry multi-line prose.

## Disclosure and popover behavior

A native `<details>` does **not** close when the user clicks elsewhere; that is
the element's own behavior, not a bug you can style away. A menu-like popover
built on `<details>` (see `AppShell.vue`) must therefore add all three closes
by setting `open = false`:

- a `document` `pointerdown` whose target is outside the element,
- `Escape`, which must also return focus to the `<summary>` so a keyboard user
  does not lose their place,
- a route watch, so navigating from inside the popover does not leave it open.

Remove both `document` listeners in `onBeforeUnmount`. Do not reach for a
second popover runtime or a focus trap to get this.

## Mirroring backend constraints

When a form feeds an endpoint with numeric or shape constraints, encode the
**same** numbers in the view and block before the request — a silent early
`return` or an unexplained 422 both read as "the button is broken". The member
batch flow mirrors three: `@Size(max = 50)` on the batch, the two-character
search minimum (digits exempt), and `@NotEmpty` roles per row. Copy the
constraint, do not invent a stricter one, and do not drop the server-side check
because the client now guards it.

## Accessibility contract

- Use `header`, `nav`, `main`, `section`, headings, lists, and native controls
  according to their meaning.
- Every navigation region has an accessible label when more than one landmark
  could be present. Preserve the visible skip link to `#app-main`.
- Focus must remain visible via `:focus-visible`; do not remove the outline
  without providing an equivalent token-based indicator.
- Labels, instructions, and error text must be programmatically associated
  with controls. Do not use a clickable `div` in place of a button or link.
- Status is never communicated by color alone. Pair semantic color with text,
  an icon/shape, or an explicit state label.
- Check keyboard order, narrow layouts, long text, empty/error/disabled/focus
  states, and reduced motion at 1440, 768, and 390 CSS pixels.

## Common mistakes to avoid

- Adding a seventh top-level navigation item or a general Assistant/Agent/Patch/
  Metrics/AI Logs page beyond D017's six approved product entries.
- Putting placeholder business data, forms, buttons, or review conclusions in
  a Phase 1 view.
- Hiding important content behind animation, hover-only affordances, or a
  color-only badge.
- Recreating header/navigation markup in each view instead of using
  `AppShell` and the router shell.
- Placing both brand images on one surface or offsetting the desktop navigation
  from the horizontal center to compensate for unequal brand/account widths.

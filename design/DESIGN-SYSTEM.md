# Styli

Handwrite on a tablet, type anywhere, and let a local AI turn it all into plain Markdown on your own devices. Styli's look borrows from the Roman wax tablet: bone and ink neutrals, one honey accent, inscription capitals for titles, and a clean modern sans for everything you read and tap.

Styli is the working name. This system covers the Android tablet app (Kotlin), the desktop app (Wails) and the web UI (iPhone and hub).

## Content fundamentals

- **Voice:** calm, plain and direct, like a good study partner. Say what happened and what to do next.
- **Sentence case** everywhere: buttons, menus, headings. "Convert now", not "Convert Now".
- **Name things by what they are to the user:** "your hub", "this device", "everywhere". Avoid sync jargon in the UI ("replica", "tombstone").
- **Deletes and data loss are explicit.** Say exactly what goes and where: "Delete from this device only" or "Delete everywhere".
- **No emoji** in the UI. The pairing emoji check is the only exception, because the emoji are the security code.
- Examples: "Converted 2 min ago" · "Waiting for your hub to come online" · "Pinned: the AI won't move this note".

## Visual foundations

- **Neutrals carry the interface.** `bg` for the page, `surface` for cards and the note body, `sidebar` for navigation, `border` for hairlines. Resting cards use a `border`, not a shadow; `shadow-card` is for things that float (menus, popovers).
- **Honey is the only accent, and it's used sparingly:** the primary action on a screen, active toggles, selection, links (`honey-text`) and highlights (`honey-tint`). One honey button per screen at most.
- **Text on honey is always `on-honey`.** Links and focus rings use `honey-text`, which passes 4.5:1 on `bg` and `surface` in both themes. Plain `honey` is never used for text.
- **Status colours** `danger` and `success` are for state only, never decoration.
- **Wood and bone** belong to the logo. Don't use them for UI.
- **Type:** Marcellus (`display`, `title`, `heading`) for note titles and section headings only; Inter for body and all UI; JetBrains Mono for code and Mermaid. Marcellus has one weight; never fake bold.
- **Shape:** `radius-md` for controls, `radius-lg` for cards and sheets, `radius-pill` for toggles.
- **Spacing** runs on a 4 px base (`space-1` to `space-7`). Touch targets are at least 44 px on the tablet and phone.
- **Themes:** the apps follow the system setting. Light is bone and ink; dark is deep ink with lighter honey.

## Iconography

Line icons, 1.5 px stroke at 20 px, rounded caps and joins, drawn in `ink` or `ink-muted`. [Lucide](https://lucide.dev) (ISC licence) matches this style and covers the app's needs: pen, highlighter, eraser, pin, lock, folder, search, share, trash, sync. Active icons stay `ink`; honey shows the active state through the background (`honey-tint`), not the icon colour.

## Logo

A tilted wax tablet with a wooden frame and glossy honey wax, and an aged bone stylus resting on it. It's used without a tile.

- On light backgrounds: `styli-mark-light-bg.svg` (dark brown outline).
- On dark backgrounds: `styli-mark-dark-bg.svg` (bone outline).
- Next to the name, the wordmark is STYLI in Marcellus capitals with 0.06em letter spacing.
- Minimum size 32 px. A simplified version for favicons and 16 px is still to come.

## Fonts

All three faces are on Google Fonts: Marcellus, Inter (400, 500, 600) and JetBrains Mono (400). Bundle them in the Android and desktop apps so they work offline.

## Components

Components are plain CSS classes in `components/styli.css` (prefix `sty-`), so the web UI and the Wails app can use them directly. The Kotlin app mirrors them using the same tokens. Each component's page shows its states.

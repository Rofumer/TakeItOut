## 1.1.29

### Added

- **Litematica Printer fluid removal refill.** When the printer (1.3-beta.4) runs out of the configured fill blocks (sand by default) while drying fluids, TakeItOut now takes more from a shulker in your inventory or from linked world containers, trying every configured fill item.

### Fixed

- **Return To Container When Full now works with Pick Block.** Previously the stack in your hand was never chosen for return, so when you picked a new material it was pushed into the container the new material came from instead of going back home.
  - The held stack is now returned to the container it was taken from, and the new material lands in your hand.
  - If that container is full only because TakeItOut earlier put something else into it (for example the stack you were holding when you took from it), the two swap places, and that item moves on into the container the new material comes from.

### Requirements

- Minecraft 26.2
- `Return To Container When Full` needs the server to run TakeItOut 1.1.29 as well; the setting is off by default (TakeItOut settings screen).

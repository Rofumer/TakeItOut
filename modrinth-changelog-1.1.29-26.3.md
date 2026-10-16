## 1.1.29

### Added

- **Return To Container When Full** (off by default, TakeItOut settings screen). When your inventory is completely full and TakeItOut needs one more material, the material you took longest ago goes back into the exact linked container it came from, and the new material is taken in the same server-side operation.
  - Works with Pick Block: the stack in your hand goes back to its own container and the new material lands in your hand.
  - If that container is full only because TakeItOut earlier put something else into it (for example the stack you were holding when you took from it), the two swap places, and that item moves on into the container the new material comes from.
  - The item being requested, tools, shulker boxes, and ender chests are never returned. Only stacks that fit completely are moved; nothing is ever dropped on the ground.
  - Origins are remembered per session only; nothing is written to disk.
- **Litematica Printer fluid removal refill.** When the printer (1.3-beta.4) runs out of the configured fill blocks (sand by default) while drying fluids, TakeItOut takes more from a shulker in your inventory or from linked world containers, trying every configured fill item.

### Fixed

- `Box Select Creates New Group` is now saved and survives a restart.

### Requirements

- Minecraft 26.3
- `Return To Container When Full` needs the server to run TakeItOut 1.1.29 as well. Against an older server the client simply keeps the feature off, and everything else keeps working.

## 1.1.28

### Fixed

- **Litematica 0.29.1 compatibility — material lists could not be opened.** Litematica 0.29.1 added stackable-entity counts to its material list, which changed the signature of `MaterialListUtils.getMaterialList`. TakeItOut's material list integration still expected the old signature, so with both mods installed opening any material list failed.

  The integration now works with both the old and the new signature, so material lists open again and linked world containers are still counted as available materials.

### Changed

- Built against Litematica `26.3-0.29.1` (previously `0.29.0`).

### Requirements

- Minecraft 26.3
- Works with Litematica 0.29.0 and 0.29.1.

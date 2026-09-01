# Changelog

## 1.1.28

### Added

- Added `Return To Container When Full` (off by default): when the inventory is completely full and a new
  material is needed, the material taken longest ago is sent back into the exact linked container it came
  from, and the new material is taken in the same server-side operation, so vanilla inventory syncing
  cannot race between the two.
  - The origin container of every taken item is tracked per session only; nothing is written to disk and
    the tracking is dropped on world/server change.
  - Items in hand, the item being requested, tools, shulker boxes, and ender chests are never returned.
  - Only stacks that fit completely into the target container are moved, so a slot is really freed;
    anything that does not fit stays in the inventory and is never dropped on the ground.
  - The server re-validates the target container (dimension rules, block type, loaded chunk) and
    recomputes the amount itself instead of trusting the client.
  - Items with custom components and shulker boxes are matched including their components, so the wrong
    item is never sent back.
- Added a server-features handshake packet. The server announces support on join; an updated client
  against an older server simply keeps the feature switched off and uses the original request packet, and
  an older client against an updated server is unaffected.

### Fixed

- `Box Select Creates New Group` is now saved to `config/takeitout.json` and survives a restart. It was
  only listed in the settings screen, never in the list the config handler reads and writes.

## 1.1.18

### Added

- Added a `Sort: Name` / `Sort: Count` toggle to the TakeItOut item list screen.
- Applied the selected item sorting mode to both `All Items` and linked-container contents popups.
- Persisted the selected item sorting mode in the client settings.
- Added Litematica material list integration for linked world containers:
  - linked container contents are requested when the material list opens or refreshes;
  - returned container item counts are added to Litematica's available material counts;
  - the material list refreshes after the server response arrives.
- Added cross-dimension linked container support within the same world/server.
- Added a server config file, `takeitout-server.json`, for controlling linked-container exchange:
  - `linked_container_exchange_mode`: `disabled`, `same_dimension`, or `cross_dimension`;
  - `linked_container_scan_limit`: maximum linked containers scanned per server request;
  - `allowed_exchange_dimensions`: optional dimension allowlist for linked-container exchange.
  - Example:
    ```json
    {
      "linked_container_exchange_mode": "cross_dimension",
      "linked_container_scan_limit": 64,
      "allowed_exchange_dimensions": [
        "minecraft:overworld",
        "minecraft:the_nether"
      ]
    }
    ```
    An empty `allowed_exchange_dimensions` list allows all dimensions for the selected mode.

### Changed

- `Count` sorting now shows the most abundant items first and falls back to item name for stable ordering.
- `Name` sorting is case-insensitive and falls back to count for stable ordering.
- Linked container network requests now include the source dimension instead of only block coordinates.

### Fixed

- Improved compatibility with Carpet/debug renderers so linked container outlines are less likely to be hidden or overwritten.
- Fixed overlap between the TakeItOut screen toolbar buttons and the world/container status text.

### Notes

- Litematica already counts items stored in shulker boxes in the player's inventory; TakeItOut only adds linked world-container counts on top.
- Linked container material counts and extraction use the configured server-side scan limit per request.
- Linked container outlines are still rendered only in the player's current dimension.

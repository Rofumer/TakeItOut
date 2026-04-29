# Changelog

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

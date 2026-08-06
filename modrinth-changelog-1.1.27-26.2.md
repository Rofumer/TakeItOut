## 1.1.27

### Fixed

- **Litematica Printer (BiliXWhite) 1.3-beta.4 compatibility.** The printer refactored its tick handling — `ClientPlayerTickManager` was replaced by the new module system (`ModuleManager`), which made TakeItOut's integration mixin fail to apply with:

  ```
  @Mixin target me.aleksilassila.litematica.printer.handler.ClientPlayerTickManager was not found
  takeitout.client.mixins.json:NewPrinterMixin from mod takeitout
  ```

  Auto Take Out now hooks into `ModuleManager` instead, so automatic item pulling works again while the printer is running.

### Changed

- Built against litematica-printer `26.2-1.3-beta.4` (previously `1.3-beta.3`).

### Requirements

- Minecraft 26.2
- Litematica Printer users: update to **1.3-beta.4** — earlier beta builds are no longer supported by this integration.

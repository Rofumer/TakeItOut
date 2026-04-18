# TakeItOut

TakeItOut is a Fabric utility mod that pulls the block you need out of shulker boxes when Pick Block, Litematica, Litematica Printer, or Tweakeroo asks for an item that is not already available in your inventory.

## What it does

- Picks the requested block from a shulker box when you press Pick Block on a real block.
- Works with Litematica schematics: when Auto Take Out is enabled, Pick Block on a schematic block can extract the required item from a shulker.
- Lets you right-click a Litematica schematic block to request the needed item before placing it.
- Extends Litematica Easy Place: if the required block is missing from your hand/inventory, the mod requests it from a shulker, waits for it to arrive, and retries placement.
- Supports Litematica Printer by feeding missing blocks from shulkers while the printer is running.
- Supports Tweakeroo restock: if Tweakeroo cannot restock a stack from the inventory, TakeItOut can pull it from a shulker.
- Can extract either a full stack or a single item from the shulker.
- Saves client settings between launches in `config/takeitout-client.json`.
- Skips non-empty stacked shulkers to avoid unsafe extraction from stacked container items.
- Avoids replacing tools, shulker boxes, and ender chests when it has to move the item currently in your hand.

## Controls

Default keybinds:

- `R` - toggle Auto Take Out.
- `B` - toggle shulker extract mode.
- Pick Block, usually the mouse wheel - request the looked-at block from a shulker when needed.
- Right mouse button - with Litematica loaded and Auto Take Out enabled, request the schematic block you are pointing at.

The keybinds are registered under the `TakeItOut` controls category. If Mod Menu is installed, the mod also exposes a keybind screen entry there.

## Extract Modes

TakeItOut has two extraction modes:

- `FULL STACK` - moves the whole stack from the shulker slot.
- `SINGLE ITEM` - moves only one item from the shulker slot.

Use `B` by default to switch between them. The current mode is saved together with the Auto Take Out toggle.

## Litematica Integration

With Litematica installed, TakeItOut hooks into schematic Pick Block and Easy Place behavior.

When Auto Take Out is enabled and the target schematic block is different from the block currently placed in the world, the mod checks whether the required item is already available. If it is missing, TakeItOut searches the player's shulkers and requests the item from the server.

Easy Place support respects Litematica's `PICK_BLOCKABLE_SLOTS` setting for the selected hotbar slot. The mod also treats some placement states as equivalent when checking whether placement succeeded, including common dynamic properties such as `lit`, `powered`, and `open`, plus fence/wall connection properties.

Right-click schematic picking is disabled while Litematica Easy Place mode is enabled, because Easy Place has its own flow.

## Litematica Printer Integration

If Litematica Printer is installed, TakeItOut can automatically provide missing blocks from shulkers while the printer is running. While the mod is waiting for a requested stack to arrive, it temporarily blocks further printer ticks to prevent repeated or conflicting extraction requests.

## Tweakeroo Integration

If Tweakeroo is installed, TakeItOut extends `restockNewStackToHand`. When Tweakeroo cannot find the requested stack in the normal inventory, TakeItOut searches shulkers and extracts the matching item.

## Server Requirement

TakeItOut sends a serverbound packet to move items out of shulkers. Because of that, extraction requires server-side support:

- In singleplayer, install the mod normally.
- On a Fabric server, install the mod on the server too.
- On Paper, Purpur, Spigot, or Bukkit servers, use the TakeItOut companion plugin if you do not run Fabric server-side.

Without server-side support, the client can detect the needed item, but it cannot actually move it from the shulker.

## Compatibility

Required:

- Minecraft `26.1.x`
- Fabric Loader `0.18.4` or newer
- Fabric API
- Java `25` or newer

Optional integrations are enabled only when the corresponding mod is installed:

- Litematica
- Litematica Printer
- Tweakeroo
- Mod Menu

## Usage

1. Put building blocks inside shulker boxes in your inventory.
2. Enable Auto Take Out with `R`.
3. Choose `FULL STACK` or `SINGLE ITEM` mode with `B`.
4. Use Pick Block, Litematica Easy Place, Litematica Printer, or Tweakeroo restock as usual.
5. When the requested item is missing from your inventory, TakeItOut searches your shulkers and moves it into your hand.

## Guide

Small YouTube guide: https://www.youtube.com/watch?v=ZIMq0n-fFDM

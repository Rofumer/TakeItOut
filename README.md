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

## Linked World Containers

Besides shulkers in your inventory, TakeItOut can pull items out of chests, barrels, and shulker boxes
placed in the world. Link a container by looking at it and using the link hotkey, or manage links in the
TakeItOut screen (`Containers` tab). Linked containers can be organized into groups, and groups can be
shared with other players on the server.

Containers can also be marked as **dump** containers. Dump containers are a manual, unaddressed
mechanism: the dump hotkey sweeps your whole inventory into them, and they are also used as an overflow
target when the mod has nowhere else to put the item it is displacing.

### Return To Container When Full

`Return To Container When Full` (off by default, in the TakeItOut settings screen) is the automatic,
addressed counterpart to dumping. When your inventory is completely full and the mod needs one more
material, it sends the material you took longest ago back into the exact container it originally came
from, and takes the new material in the same server-side operation - so nothing can slip in between the
two halves.

Details:

- The mod remembers, per session only, which linked container every item came from. Nothing is saved to
  disk, and the memory is dropped when you change world or server.
- Items in your hand, the item you are currently requesting, tools, shulker boxes, and ender chests are
  never chosen for return.
- Only whole stacks that fit completely into the target container are moved, so a slot is really freed.
  Anything that does not fit stays in your inventory; nothing is ever dropped on the ground.
- The server re-validates the target container and recomputes the amount itself, so the feature cannot be
  abused by a modified client.
- This feature needs a server running the same TakeItOut version (see below). Against an older server the
  client silently keeps it switched off, and everything else keeps working.

## Server Requirement

TakeItOut sends a serverbound packet to move items out of shulkers. Because of that, extraction requires server-side support:

- In singleplayer, install the mod normally.
- On a Fabric server, install the mod on the server too.
- On Paper, Purpur, Spigot, or Bukkit servers, use the TakeItOut companion plugin if you do not run Fabric server-side.

Without server-side support, the client can detect the needed item, but it cannot actually move it from the shulker.

`Return To Container When Full` needs a server with TakeItOut `1.1.28` or newer. The server announces
support on join; if that announcement never arrives, the client keeps sending the original request packet
and simply never returns anything, so an older server is not broken by an updated client (and vice versa).

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

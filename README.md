# ZenithProxy Auto Enchant

ZenithProxy Java plugin for planning and automating anvil enchanting from configured jobs and nearby stations.

## Current implementation

The plugin keeps one **job per item type**. A job stores the item, its enchantment tree (a wiki preset or a hand-written list), its rename name, the pickup chest it takes items from, and the chest finished items are unloaded into.

- Plugin entry point, persistent configuration, and the `autoEnchant` command.
- Bundled best-enchantment plans loaded from the resource matching the build's target Minecraft version.
- Parsing of the guide's nested merge expressions, previewed as merge steps when a preset is selected.
- Nearby anvil/grindstone discovery, with optional explicit station coordinates.
- Item-frame discovery: a frame showing a piece of equipment is the **template** for that item type — its enchantments become the job's target set and its custom name becomes the rename target. The first container below the frame becomes that job's pickup chest, and a redstone lamp above the frame switches the job on (lit) or off (unlit).
- The other containers are declared by their own frames too: a redstone block marks the output chest and redstone dust the semi-finished chest, both resolved as the first container below the frame. Book frames and an anvil frame are recognized by their displayed item.

The tick-driven transaction worker handles one equipment item at a time and rotates through the pickup chests of all enabled jobs: it takes a matching item, skips curses, optionally uses a grindstone, fetches the required book copies, performs each merge against the live anvil preview and cost, obtains XP through the configured KillAura integration (walking to the configured kill spot first when levels are short), and unloads the item into that job's output container. It verifies inventory and window transitions and skips a failed item after attempting recovery. Optional renaming is attached to the first (default) or the final equipment merge, depending on `renameOrder`.

This is a live-operation implementation. Robust recovery after process restart and in-game validation against a real server still need work. Keep `enabled` off while configuring; use a test world and expendable materials before enabling live operation.

## Commands

Typical first run:

```
autoEnchant scan                              # find stations, book frames, and the equipment frames
autoEnchant list                              # what the scan created, and what each entry still needs
autoEnchant set diamond_sword output 122 64 -30   # if the scan could not find an output chest
autoEnchant on
autoEnchant                                   # watch the live status
```

- `autoEnchant on|off` — enable or disable scanning and work.
- `autoEnchant scan` — scan immediately and generate jobs from the frames. A frame showing equipment is read as a template: its enchantments are turned into the target set (an exact match with a wiki preset's final set selects `PRESET` with that preset, anything else becomes a `CUSTOM` list) and its custom name becomes the rename target. The first container **below** the frame is the job's pickup chest, and a redstone lamp above it is the switch — lit = on, unlit = off, no lamp = the stored setting is kept. Only items with durability are accepted, so decorative frames are ignored. The redstone-block frame binds the output chest and the redstone-dust frame the semi-finished chest (first container below each), and both are copied into the job entries. A template is only re-imported when its enchantments or name actually changed, so `set <item> enchants ...` edits survive rescans. Two frames for the same item are reported instead of fighting over the chest.
- `autoEnchant list` — every job with its on/off state, mode, preset or enchantment count, pickup/output chest, rename name, and any config problem.
- `autoEnchant add <item> [input <x> <y> <z>] [output <x> <y> <z>]` — create a job by hand. Omitted coordinates fall back to the pickup chest the last scan found for that item's frame, then to the global container settings.
- `autoEnchant del <item>` — remove a job.
- `autoEnchant set <item>` — show one job.
- `autoEnchant set <item> on|off` — switch the job on or off. A lamp above its frame overrides this on every scan.
- `autoEnchant set <item> input|output|semiFinished <x> <y> <z>` — bind the job's containers. Input is the pickup chest, output is where finished items go, and semi-finished is where failures and rejected items go.
- `autoEnchant set <item> mode custom|presets|none` — a hand-written enchantment list (`custom`), the wiki merge-tree preset (`presets`), or `none` to park the job with no target set (a parked job never starts a transaction).
- `autoEnchant set <item> preset <variant>` — select the preset variant, e.g. `set diamond_sword preset all` or `no_fire_knockback`. Only variants the item supports are offered; selecting one switches the job to `presets`, re-copies the set into the custom list, and prints the guide's merge steps.
- `autoEnchant set <item> enchants add <enchantment> [level]` — add or update one enchantment, e.g. `enchants add mending` or `enchants add sharpness 5`. Switches the job to `CUSTOM`. Levels default to the current level, or 1 on a new entry, capped at the enchantment's max.
- `autoEnchant set <item> enchants del|clear|list` — remove one, empty the list, or show it with levels, max levels, the preset it was copied from, and the planned merge tree (books, merges, predicted levels, final prior-work penalty).
- `autoEnchant set <item> rename set <name>` — set the display name and turn renaming on for that job. Maximum 50 characters.
- `autoEnchant set <item> rename clear` — remove the name and turn renaming off for that job.
- `autoEnchant renameOrder [first|last]` — where a rename rides along (policy shared by every job). `first` (default, recommended) pays the 1-level rename fee while the item still has no prior-work penalty, because a rename itself is not an anvil use. `last` pays 1 level plus whatever penalty the finished merges already built up (1, 3, 7, 15, 31 …), and can push a near-limit merge past 39.
- `autoEnchant presets [list]` — print the whole bundled preset library, grouped by equipment slot.
- `autoEnchant killSpot [<x> <y> <z>|clear]` — show, set, or clear the XP grind spot. When an anvil operation needs more levels than the BOT has, it paths to this block first and only then enables KillAura, so farming happens at the configured spot instead of wherever the anvil is. Without a spot it keeps the old behaviour and farms in place.
- `autoEnchant radius [blocks] [vertical]` — show the scan range, or set it. `blocks` is the horizontal radius (1-64), the optional `vertical` radius is 1-32. A change takes effect on the next scan; run `autoEnchant scan` to apply it immediately.
- `autoEnchant stations` — show the layout of the last scan without rescanning: stations, marker frames, jobs, and any marker that was recognized but could not be resolved to a container.
- `autoEnchant awaitIntactTimeout [ticks]` — show or set how long one interaction (open/click/close) may take to settle before the step is retried, in ticks (1-1200, default 100 = 5 s).
- `autoEnchant anvilRestock [stacks]` — show or set how many anvil stacks to pull from the anvil-supply container when the anvil breaks (1-8, default 1).
- `autoEnchant supplyDepth [blocks]` — show or set how far below a marker frame the container search goes (1-16, default 8); a change rescans immediately.
- `autoEnchant debug` — log BOT position/rotation, nearby containers, and every nearby item frame plus its displayed item, inferred role, support block, and acceptance result.
- `autoEnchant debug on|off` — allow `scan` and `debug` to also write a full snapshot (every nearby container and item frame) to `latest.log`. Periodic scans never write it, so an idle BOT stays quiet.
- `autoEnchant` — show module status, how many jobs are enabled and runnable, and the job list.

Marker listings use one line per hit: `role | item [enchantments] | frame (x,y,z) -> container block (x,y,z) | distance`. The layout is also written to `latest.log` (prefix `Auto-enchant scan layout`) whenever it changes, so it is not repeated every scan interval.

## Configuration

The config is registered as `plugins/config/auto-enchant.json` and holds a `jobs` array. Each entry has `item`, `enabled`, `mode` (`NONE`, `PRESET` or `CUSTOM`), `preset`, `enchantments`, `renameEnabled`, `renameName`, `inputItemChest`, `outputChest`, `semiFinishedChest`, `frame`, and `frameSpec` (the template signature last imported from the frame). The commands edit exactly these fields, so the JSON never has to be touched by hand.

Chests are stored as block positions (`{"x":120,"y":64,"z":-30}`), not as `"x,y,z"` strings; `{"x":0,"y":0,"z":0}` means "not set". A job's own `inputItemChest`/`outputChest`/`semiFinishedChest` win, and a job that leaves `outputChest` or `semiFinishedChest` unset inherits the station-level chest that `scan` discovered from its marker: `outputChest` (redstone-block frame), `semiFinishedChest` (redstone-dust frame), `anvilChest` (anvil frame). `anvilPositions` and `grindstonePositions` are position arrays, and `killSpot` is the XP grind spot.

```json
{
  "jobs": [
    {
      "item": "minecraft:diamond_sword",
      "enabled": true,
      "mode": "PRESET",
      "preset": "sword.all",
      "enchantments": [],
      "renameEnabled": true,
      "renameName": "Blade",
      "inputChest": { "x": 120, "y": 64, "z": -30 },
      "outputChest": { "x": 0, "y": 0, "z": 0 },
      "semiFinishedChest": { "x": 0, "y": 0, "z": 0 },
      "frame": "(119, 66, -30)",
      "frameSpec": "sharpness=5,looting=3,...|Blade"
    }
  ],
  "outputChest": { "x": 122, "y": 64, "z": -30 },
  "semiFinishedChest": { "x": 124, "y": 64, "z": -30 },
  "anvilSupplyChest": { "x": 0, "y": 0, "z": 0 },
  "killSpot": { "x": 110, "y": 63, "z": -25 },
  "renameOrder": "FIRST"
}
```

A config from an older version is migrated automatically on first use, and the file is rewritten in the new shape: a single `target` (or a `targets` array) becomes the first job with its global rename settings, `containerPositions` is copied into the station-level chests and the jobs, and positions written as `"x,y,z"` strings are converted on load by the plugin's config serializer.


The plugin selects a version-specific preset dataset from the build target. Java 1.21.4 uses `best-enchantments-1.21.4.json`; the separate 1.21.11 dataset retains spear and Lunge entries. Each dataset's declared data version is checked before loading. If a variant refers to an enchantment the game does not know (for example the guide's pre-1.21 `sweeping` versus the registry's `sweeping_edge`), the variant is skipped and the reason is written to the log instead of disappearing silently. Semi-finished item-frame markers use `semiFinishedMarkerItem` (default `minecraft:redstone`, the redstone dust); older configs still holding the previous `minecraft:gray_dye` default are migrated on startup.

Every marker resolves its container the same way: the first chest, trapped chest, barrel, or shulker box found by scanning straight **down** from the frame's own block, within `supplyScanDepth`. One rule for the pickup chest, the output chest (redstone block), the semi-finished chest (redstone dust), book chests and the anvil supply means a frame on a wall, a shelf, or two blocks above a chest all work without a fixed offset. An anvil marker may show a normal, chipped, or damaged anvil item. `autoEnchant stations` lists any frame whose role was recognized but whose container could not be resolved.

The worker rotates through the pickup chests of all enabled jobs, so a chest that holds nothing it can use is skipped instead of being retried forever. Each item, once taken, is tracked by its own job: its output chest, its enchantment tree, and its rename name all come from that entry.

The reset decision is made exactly once, at the moment an item is taken from the input container, and never again during the transaction:

| What was fetched | What happens |
| --- | --- |
| Blank item | Enchanted from scratch, no grindstone pass |
| PRESET target, any enchantment present | Ground blank once, then built from the guide tree |
| Item already equal to the target set | Unloaded untouched, no books are spent |
| Grindstone disabled and the item is enchanted | Sent to the semi-finished container with a reason |

An item that is already in the BOT inventory is treated as an interrupted transaction: it is resumed as-is and never reset, so enchantments the plugin already paid for are kept. In PRESET mode the remaining work must line up with the wiki's optimised merge tree — every branch whose result already exists in the inventory is reused unchanged, so only the missing branches are merged and the anvil costs stay those of the guide. An item that cannot be aligned with any tree node (for example an externally modified item carrying a partial subset of a merged pair) is not re-ordered by hand: it is reported and moved to the semi-finished container. CUSTOM jobs have no guide tree, so the plugin plans one: it charges each merge exactly as the anvil does (the sacrificed stack's enchantment value plus both prior-work penalties, `2^uses - 1`, with the result taking `max(uses) + 1`), and searches every way of grouping the missing books. Books are pre-merged among themselves whenever that is cheaper than reusing the equipment, which is what keeps a 6-7 enchantment target under the 39-level limit instead of blowing past it after four books. `autoEnchant set <item> enchants list` shows the planned tree's book count, merge count, predicted levels, and the item's final penalty. An item whose enchantments fall outside the plan is moved to the semi-finished container instead of being reset.

The grindstone flow belongs to the equipment pickup flow only: it is decided once, when an item is taken from the input container. Book-chest pickups and the enchanting steps never clean an item, and an item resumed from the BOT inventory keeps its progress.

Renaming follows the same rules: the name is applied on the first equipment merge (`renameOrder FIRST`), only when the item does not already carry it. A rename is not an anvil use, so it never raises the item's prior-work penalty — doing it first costs exactly 1 level, while the same rename after the tree would cost 1 level plus the penalty the item has accumulated (1, 3, 7, 15, 31 …). An item that needs no merge but still needs the name gets a rename-only anvil pass after the books are done, so a finished item is never unloaded unnamed.

The transaction item is never dropped: if the output container is missing or full, it stays in the BOT inventory and is retried after a back-off. An item that fails three times is parked for five minutes before it is picked up again.

XP is obtained on demand. Before every anvil operation the live level is compared with the operation cost plus `reserveLevels`; when it is short, the BOT paths to `killSpot` (when set), enables KillAura once it arrives, and waits — the merge continues as soon as the level is enough. KillAura is only switched off again if this plugin enabled it. Farming is abandoned with a reason when the BOT dies, is teleported, drops below `minimumHealth`, changes dimension, gains no XP for `noXpTimeoutMs`, or exceeds `killAuraMaxSeconds`; a kill spot that cannot be reached within 60 s also aborts the wait, and the current item is unloaded instead of being retried forever.

When the anvil block disappears (durability exhausted), the worker checks its inventory first: with an anvil in hand it places one at the same position, otherwise it opens the `anvil_supply` container, takes `anvilRestockStacks` stacks, and places one there. The current item is retried up to three times before it is unloaded as semi-finished.

A hand-written target set starts from the guide: creating a job copies its default preset's final enchantment set into the custom list, so only the differences have to be edited.

```
autoEnchant set diamond_helmet preset all      # helmet.all selected, its enchantments copied to the custom list
autoEnchant set diamond_helmet mode custom     # use that copy as the target
autoEnchant set diamond_helmet enchants del thorns    # step away from the guide where needed
autoEnchant set diamond_helmet enchants list   # what the worker will build
```

`enchantments` entries are `{ "id": "minecraft:sharpness", "level": 5 }` in JSON; the command writes the short id. After editing the JSON by hand, restart the proxy because plugins load at startup; then verify with `autoEnchant`, `autoEnchant list`, and `autoEnchant debug`.

## Build

Use the repository Gradle wrapper with Java 25 available:

```powershell
$env:GRADLE_USER_HOME = 'C:\Users\Cat\.gradle'
.\gradlew.bat build
```

The produced plugin jar is under `build/libs/`.

# ZenithProxy Auto Enchant

A ZenithProxy plugin that runs an anvil enchanting workshop unattended: it reads its jobs from item frames, withdraws equipment in batches, cleans off-order enchantments on a grindstone, fetches exactly the enchantment books the batch still needs, merges them against the live anvil preview, farms XP with the proxy's own KillAura, and unloads the finished pieces.

Target: Minecraft Java 1.21.4 (`gradle.properties: mc`, a 1.21.11 preset dataset is also bundled) on ZenithProxy 3.8.1+, built with Java 25.

## How the workshop is laid out

Everything is declared by item frames; no coordinates have to be typed in. Each frame resolves to the first container **below** it (chest, trapped chest, barrel or shulker box, up to 8 blocks down).

| Frame holds | Meaning |
| --- | --- |
| A damageable piece of equipment | A job template for that item type. Its enchantments become the target set, its name becomes the rename target, and the container below it receives finished **and** half-done pieces. |
| An enchanted book | The supply chest for that enchantment (`sharpness`, `mending`, …). |
| An anvil (any damage stage) | The anvil restock chest. |
| Redstone dust | The shared pickup chest for equipment. |
| A redstone lamp above any frame | Job switch: lit = on, unlit = off (no lamp = the stored setting wins). |

A frame that is empty or holds anything else is decoration and is ignored; a leftover redstone-block frame from older versions is skipped silently.

## Quick start

```
autoEnchant scan                 # find stations, book frames and job frames
autoEnchant list                 # what was found, and what each job still needs
autoEnchant batchOrder step      # advance the whole batch one merge step at a time
autoEnchant on
autoEnchant                      # live status
```

Start with `enabled` off and a test world: this plugin moves real items through real anvils.

## Commands

- `autoEnchant on|off`, `scan`, `list`, `stations`, `stats`, `stats reset`, `debug`
- `autoEnchant add <id> <item> <inputPos> <outputPos>`, `del <id>`, `clear`
- `autoEnchant set <id> on|off | inputItem <item> | inputItemChest <x> <y> <z> | outputChest <x> <y> <z> | postEnchantStore <none|to_restock|to_overflow>`
- `autoEnchant set <id> outputEnchants add|del|clear|list`
- `autoEnchant batchSize <n>`, `batchOrder <row|step>`
- `autoEnchant experienceSource <none|kill|exp>`, `killWindow <ticks>`, `killGap <ticks>`, `killMinKills <n>`, `ExpFarmSpot <x> <y> <z>`
- `autoEnchant grindstone <x> <y> <z>`, `anvil <x> <y> <z>`, `anvilChest <x> <y> <z>`, `anvilRestock <stacks>`
- `autoEnchant scanRange <horizontal> <vertical>`
- `autoEnchant actionDelay|restartDelay|openRetry|waitForInteractTimeout <ticks>`
- `autoEnchant renameOnly on|off`, `logTradeStatusToDiscord on|off`

## Configuration

`plugins/config/Auto Enchant.json`, edited through the commands above (the proxy loads plugin config at startup). Jobs live in an `enchants` map keyed by item id; book chests in `enchantBook`; the batch, XP and timing knobs sit at the top level. See [design.md](design.md) §3 for the full field table and an example.

## How it works, in one paragraph

A batch is topped up to `batchSize` counting every piece already carried. All pieces are compared with the template first, and those carrying anything outside it are ground blank in a single grindstone window. Books are then withdrawn per step of the wiki merge tree — only the enchantment types the batch's current step still needs, at most `free slots - 1`, one whole type or none. Every merge is checked against the server's preview (cost, result enchantments, name); a piece that cannot be aligned with the tree goes to the semi-finished side instead of being re-ordered by hand. XP comes from the proxy's KillAura in short kill windows, and only its `enabled` flag is touched. Finished pieces stay in the bag until the batch is done and are then unloaded in one pass.

The reasoning behind these rules, the measured numbers (15.4 s per piece, where the time goes, why an open click sometimes needs a retry) and the full state machine are in [design.md](design.md).

## Build

```
./gradlew build          # Linux / macOS
gradlew.bat build        # Windows
```

The plugin jar lands in `build/libs/`. Deployment is not part of the repository: `deploy.sh` reads `ZP_HOST` / `ZP_PLUGINS_DIR` from the environment or from a git-ignored `deploy.local.sh`.

## License

GNU Affero General Public License v3.0 (AGPL-3.0), see [LICENSE](LICENSE).

This plugin runs as part of a Minecraft proxy that users interact with over a network. Under AGPL-3.0, if you modify it and let others use the modified version over a network, you must offer them the corresponding source code.

package org.example.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.brigadier.CustomStringArgumentType;
import com.zenith.feature.player.World;
import org.example.AutoEnchantConfig;
import org.example.module.AutoEnchantModule;

import java.util.List;

import static com.mojang.brigadier.arguments.IntegerArgumentType.getInteger;
import static com.mojang.brigadier.arguments.IntegerArgumentType.integer;
import static com.mojang.brigadier.arguments.StringArgumentType.getString;
import static com.zenith.Globals.MODULE;
import static com.zenith.command.brigadier.BlockPosArgument.blockPos;
import static com.zenith.command.brigadier.BlockPosArgument.getBlockPos;
import static com.zenith.command.brigadier.CustomStringArgumentType.wordWithChars;
import static com.zenith.command.brigadier.RegistryDataArgument.enchantment;
import static com.zenith.command.brigadier.RegistryDataArgument.getEnchantment;
import static com.zenith.command.brigadier.TimeArgument.time;
import static com.zenith.command.brigadier.ToggleArgumentType.getToggle;
import static com.zenith.command.brigadier.ToggleArgumentType.toggle;
import static org.example.AutoEnchantPlugin.PLUGIN_CONFIG;
import static org.example.module.AutoEnchantModule.EquipmentType;

public class AutoEnchantCommand extends Command {
    @Override
    public CommandUsage commandUsage() {
        return CommandUsage.builder()
            .name("autoEnchant")
            .category(CommandCategory.MODULE)
            .description("Enchant the items displayed in frames, one job per enchantItem")
            .usageLines(
                "on/off",
                "add <id> <enchantItem> <inputItemChestPos> <outputChestPos>",
                "del <id>",
                "clear",
                "list",
                "set help",
                "waitForInteractTimeout <ticks>",
                "actionDelay <ticks>",
                "restartDelay <ticks>",
                "openRetry <ticks>",
                "experienceSource <none/kill/exp>",
                "logTradeStatusToDiscord on/off",
                "renameOnly on/off",
                "grindstone <x> <y> <z>",
                "ExpFarmSpot <x> <y> <z>",
                "anvil <x> <y> <z>",
                "anvilChest <x> <y> <z>",
                "killWindow <ticks>",
                "killGap <ticks>",
                "killMinKills <kills>",
                "scan",
                "scanRange <horizontal> <vertical>",
                "anvilRestock <stacks>",
                "batchSize <count>",
                "batchOrder <row/step>",
                "stats",
                "stats reset"
            )
            .build();
    }

    @Override
    public LiteralArgumentBuilder<CommandContext> register() {
        return command("autoEnchant")
            .then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.enabled = getToggle(c, "toggle");
                MODULE.get(AutoEnchantModule.class).syncEnabledFromConfig();
                c.getSource().getEmbed()
                    .title("Auto Enchant " + toggleStrCaps(PLUGIN_CONFIG.enabled));
            }))
                .then(literal("add").then(argument("id", wordWithChars())
                    .then(argument("enchantItem", enumStrings(EquipmentType.values())).then(argument("inputItemChestPos", blockPos())).then(argument("storeChestPos", blockPos())).executes(c -> {
                        var id = getString(c, "id");
                        var enchantItem = getString(c, "enchantItem");
                        var inputItemPos = getBlockPos(c, "inputItemChestPos");
                        if (World.isChunkLoadedBlockPos(inputItemPos.x(), inputItemPos.z())) {
                            var inputChestBlock = World.getBlock(inputItemPos);
                            c.getSource().getEmbed()
                                .addField("Block At Input Pos", inputChestBlock.name());
                        }
                        var storeChestPos = getBlockPos(c, "storeChestPos");
                        if (World.isChunkLoadedBlockPos(storeChestPos.x(), storeChestPos.z())) {
                            var storeChestBlock = World.getBlock(storeChestPos);
                            c.getSource().getEmbed()
                                .addField("Block At Output Pos", storeChestBlock.name());
                        }
                        var enchant = new AutoEnchantConfig.Enchant();
                        enchant.item = AutoEnchantConfig.shortId(enchantItem);
                        enchant.inputItemChest = inputItemPos;
                        enchant.outputChest = storeChestPos;
                        inEventLoop(() -> {
                            PLUGIN_CONFIG.enchants.put(id, enchant);
                            MODULE.get(AutoEnchantModule.class).onTradeListChange();
                        });
                        c.getSource().getEmbed()
                            .title("Enchant Added")
                            .description(printEnchant(id, enchant));
            }))))
            .then(literal("set")
                .then(literal("help").executes(c-> {
                    List<String> setCommands = List.of(
                        "set <id> on/off",
                        "set <id> <enchantItem>",
                        "set <id> inputItemChest <x> <y> <z>",
                        "set <id> outputChest <x> <y> <z>",
                        "set <id> renameName set <name> | clear",
                        "set <id> outputEnchants add <enchantment> <level>",
                        "set <id> outputEnchants del <enchantment>",
                        "set <id> outputEnchants clear | list",
                        "set <id> postEnchantStore <none/to_restock/to_overflow>"
                    );
                    c.getSource().getEmbed()
                        .title("Enchant Settings")
                        .description(setCommands.stream().map(a -> "`" + a + "`").reduce((a, b) -> a + "\n" + b).orElse(""));
                    c.getSource().getData().put("list", true);
                }))
                .then(argument("id", wordWithChars())
                    .then(argument("enchantToggle", toggle()).executes(c-> {
                        var id = CustomStringArgumentType.getString(c, "id");
                        if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                            c.getSource().getEmbed()
                                .title("Enchant ID Not Found")
                                .addField("Enchant ID", id)
                                .description(printAllEnchants());
                            c.getSource().getData().put("list", true);
                            return ERROR;
                        }
                        var enchant = PLUGIN_CONFIG.enchants.get(id);
                        enchant.enabled = getToggle(c, "enchantToggle");
                        c.getSource().getEmbed()
                            .title("Trade " + toggleStrCaps(enchant.enabled))
                            .description(printEnchant(id, enchant));
                        inEventLoop(() -> {
                            MODULE.get(AutoEnchantModule.class).onTradeListChange();
                        });
                        return OK;
                    }))
                    .then(argument("enchantItem", enumStrings(EquipmentType.values())).executes(c-> {
                        var id = CustomStringArgumentType.getString(c, "id");
                        if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                            c.getSource().getEmbed()
                                .title("Enchant ID Not Found")
                                .addField("Enchant ID", id)
                                .description(printAllEnchants());
                            c.getSource().getData().put("list", true);
                            return ERROR;
                        }
                        var enchant = PLUGIN_CONFIG.enchants.get(id);
                        var enchantItem = getString(c, "enchantItem");
                        enchant.item = AutoEnchantConfig.shortId(enchantItem);
                        inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                        c.getSource().getEmbed()
                            .title("Enchant Item Set")
                            .description(printEnchant(id, enchant));
                        return OK;
                    }))
                    .then(literal("inputItemChest").then(argument("inputItemChest", blockPos()).executes(c -> {
                        var id = CustomStringArgumentType.getString(c, "id");
                        if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                            c.getSource().getEmbed()
                                .title("Enchant ID Not Found")
                                .addField("Enchant ID", id)
                                .description(printAllEnchants());
                            c.getSource().getData().put("list", true);
                            return ERROR;
                        }
                        var enchant = PLUGIN_CONFIG.enchants.get(id);
                        var inputItemChest = getBlockPos(c, "inputItemChest");
                        if (World.isChunkLoadedBlockPos(inputItemChest.x(), inputItemChest.z())) {
                            var inputChestBlock = World.getBlock(inputItemChest);
                            c.getSource().getEmbed()
                                .addField("Block At Input Pos", inputChestBlock.name());
                        }
                        enchant.inputItemChest = inputItemChest;
                        inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                        c.getSource().getEmbed()
                            .title("Input Item Chest Set")
                            .description(printEnchant(id, enchant));
                        return OK;
                    })))
                    .then(literal("outputChest").then(argument("outputChest", blockPos()).executes(c -> {
                        var id = CustomStringArgumentType.getString(c, "id");
                        if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                            c.getSource().getEmbed()
                                .title("Enchant ID Not Found")
                                .addField("Enchant ID", id)
                                .description(printAllEnchants());
                            c.getSource().getData().put("list", true);
                            return ERROR;
                        }
                        var enchant = PLUGIN_CONFIG.enchants.get(id);
                        var outputChest = getBlockPos(c, "outputChest");
                        if (World.isChunkLoadedBlockPos(outputChest.x(), outputChest.z())) {
                            var inputChestBlock = World.getBlock(outputChest);
                            c.getSource().getEmbed()
                                .addField("Block At Input Pos", inputChestBlock.name());
                        }
                        enchant.outputChest = outputChest;
                        inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                        c.getSource().getEmbed()
                            .title("Output Chest Set")
                            .description(printEnchant(id, enchant));
                        return OK;
                    })))
                    .then(literal("renameName")
                        .then(literal("set").then(argument("name", wordWithChars()).executes(c-> {
                        var id = CustomStringArgumentType.getString(c, "id");
                        if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                            c.getSource().getEmbed()
                                .title("Enchant ID Not Found")
                                .addField("Enchant ID", id)
                                .description(printAllEnchants());
                            c.getSource().getData().put("list", true);
                            return ERROR;
                        }
                        var enchant = PLUGIN_CONFIG.enchants.get(id);
                        var renameName = CustomStringArgumentType.getString(c, "name");
                        enchant.renameName = renameName;
                        inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                        c.getSource().getEmbed()
                            .title("Name Set")
                            .addField("Rename", renameName)
                            .description(printEnchant(id, enchant));
                        return OK;
                        })))
                        .then(literal("clear").executes(c -> {
                            var id = CustomStringArgumentType.getString(c, "id");
                            if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                                c.getSource().getEmbed()
                                    .title("Enchant ID Not Found")
                                    .addField("Enchant ID", id)
                                    .description(printAllEnchants());
                                c.getSource().getData().put("list", true);
                                return ERROR;
                            }
                            var enchant = PLUGIN_CONFIG.enchants.get(id);
                            enchant.renameName = "";
                            inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                            c.getSource().getEmbed()
                                .title("Name clear")
                                .description(printEnchant(id, enchant));
                            c.getSource().getData().put("list", true);
                            return OK;
                        }))
                    )
                    .then(literal("outputEnchants")
                        .then(literal("add").then(argument("enchant", enchantment()).then(argument("level", integer(1)).executes(c -> {
                            var id = CustomStringArgumentType.getString(c, "id");
                            if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                                c.getSource().getEmbed()
                                    .title("Enchant ID Not Found")
                                    .addField("ID", id)
                                    .description(printAllEnchants());
                                c.getSource().getData().put("list", true);
                                return ERROR;
                            }
                            var enchant = PLUGIN_CONFIG.enchants.get(id);
                            var enchantment = getEnchantment(c, "enchant");
                            var level = getInteger(c, "level");
                            enchant.itemEnchantments.put(AutoEnchantConfig.shortId(enchantment.name()), level);
                            inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                            c.getSource().getEmbed()
                                .title("Output Enchantment Added")
                                .description(printEnchantEnchantments(enchant));
                            c.getSource().getData().put("list", true);
                            return OK;
                        }))))
                        .then(literal("del").then(argument("enchant", enchantment()).executes(c -> {
                            var id = CustomStringArgumentType.getString(c, "id");
                            if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                                c.getSource().getEmbed()
                                    .title("Enchant ID Not Found")
                                    .addField("ID", id)
                                    .description(printAllEnchants());
                                c.getSource().getData().put("list", true);
                                return ERROR;
                            }
                            var enchant = PLUGIN_CONFIG.enchants.get(id);
                            var enchantment = getEnchantment(c, "enchant");
                            enchant.itemEnchantments.remove(AutoEnchantConfig.shortId(enchantment.name()));
                            inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                            c.getSource().getEmbed()
                                .title("Output Enchantment Removed")
                                .description(printEnchantEnchantments(enchant));
                            c.getSource().getData().put("list", true);
                            return OK;
                        })))
                        .then(literal("clear").executes(c -> {
                            var id = CustomStringArgumentType.getString(c, "id");
                            if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                                c.getSource().getEmbed()
                                    .title("Enchant ID Not Found")
                                    .addField("ID", id)
                                    .description(printAllEnchants());
                                c.getSource().getData().put("list", true);
                                return ERROR;
                            }
                            var enchant = PLUGIN_CONFIG.enchants.get(id);
                            enchant.itemEnchantments.clear();
                            inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                            c.getSource().getEmbed()
                                .title("Enchantments Cleared")
                                .description(printEnchantEnchantments(enchant));
                            c.getSource().getData().put("list", true);
                            return OK;
                        }))
                        .then(literal("list").executes(c -> {
                            var id = CustomStringArgumentType.getString(c, "id");
                            if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                                c.getSource().getEmbed()
                                    .title("Enchant ID Not Found")
                                    .addField("ID", id);
                                return ERROR;
                            }
                            var enchant = PLUGIN_CONFIG.enchants.get(id);
                            c.getSource().getEmbed()
                                .title("Enchantment List")
                                .description(printEnchantEnchantments(enchant));
                            c.getSource().getData().put("list", true);
                            return OK;
                        })))
                    .then(literal("postEnchantStore").then(argument("postEnchantStoreMode", enumStrings(AutoEnchantConfig.Enchant.PostEnchantStoreMode.values())).executes(c -> {
                        var id = CustomStringArgumentType.getString(c, "id");
                        if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                            c.getSource().getEmbed()
                                .title("Trade ID Not Found")
                                .addField("ID", id)
                                .description(printAllEnchants());
                            c.getSource().getData().put("list", true);
                            return ERROR;
                        }
                        var mode = AutoEnchantConfig.Enchant.PostEnchantStoreMode.valueOf(getString(c, "postEnchantStoreMode").toUpperCase());
                        var enchant = PLUGIN_CONFIG.enchants.get(id);
                        enchant.postEnchantStoreMode = mode;
                        inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                        c.getSource().getEmbed()
                            .title("Post Enchant Store Mode Set");
                        return OK;
                    })))
                )
            )
            .then(literal("del").then(argument("id", wordWithChars()).executes(c -> {
                var id = CustomStringArgumentType.getString(c, "id");
                if (!PLUGIN_CONFIG.enchants.containsKey(id)) {
                    c.getSource().getEmbed()
                        .title("Enchant ID Not Found")
                        .addField("ID", id)
                        .description(printAllEnchants());
                    c.getSource().getData().put("list", true);
                    return ERROR;
                }
                inEventLoop(() -> {
                    PLUGIN_CONFIG.enchants.remove(id);
                    MODULE.get(AutoEnchantModule.class).onTradeListChange();
                });
                c.getSource().getEmbed()
                    .title("Enchant Removed")
                    .description(printAllEnchants());
                c.getSource().getData().put("list", true);
                return OK;
            })))
            .then(literal("clear").executes(c -> {
                inEventLoop(() -> {
                    PLUGIN_CONFIG.enchants.clear();
                    MODULE.get(AutoEnchantModule.class).onTradeListChange();
                });
                c.getSource().getEmbed()
                    .title("Enchant Cleared");
            }))
            .then(literal("list").executes(c -> {
                c.getSource().getEmbed()
                    .title("Enchant List")
                    .description(printAllEnchants());
                c.getSource().getData().put("list", true);
            }))
            .then(literal("waitForInteractTimeout").then(argument("ticks", time()).executes(c -> {
                PLUGIN_CONFIG.waitForInteractTimeoutTicks = getInteger(c, "ticks");
                c.getSource().getEmbed()
                    .title("Wait For Interact Timeout Set");
            })))
            .then(literal("actionDelay").then(argument("ticks", time()).executes(c -> {
                // Delay between the actions of one submitted request: the server sees the clicks of a stack move as
                // separate, human-paced interactions instead of one burst.
                PLUGIN_CONFIG.actionDelay = getInteger(c, "ticks");
                c.getSource().getEmbed()
                    .title("Operation Delay Set")
                    .addField("Delay", PLUGIN_CONFIG.actionDelay + " ticks between queued actions");
            })))
            .then(literal("restartDelay").then(argument("ticks", time()).executes(c -> {
                // Backoff after a failed step or a stuck transaction before the next item is picked up.
                PLUGIN_CONFIG.restartDelay = getInteger(c, "ticks");
                c.getSource().getEmbed()
                    .title("Restart Delay Set")
                    .addField("Delay", PLUGIN_CONFIG.restartDelay + " ticks before retrying after a failure");
            })))
            .then(literal("openRetry").then(argument("ticks", time()).executes(c -> {
                // Round trip of one open click, not the failure backoff: how long a right click on a chest / anvil /
                // grindstone waits for the window before the click is sent again. Tune it against the
                // "open latency ... misses" line in the log.
                PLUGIN_CONFIG.openRetryTicks = Math.max(1, getInteger(c, "ticks"));
                c.getSource().getEmbed()
                    .title("Open Retry Set")
                    .addField("Delay", PLUGIN_CONFIG.openRetryTicks + " ticks before an open click is re-sent")
                    .addField("Note", "After 3 attempts at one block the interaction timeout takes over");
            })))
            .then(literal("experienceSource")
                .then(argument("source", enumStrings(AutoEnchantConfig.ExperienceSource.values())).executes(c -> {
                    var source = AutoEnchantConfig.ExperienceSource.valueOf(getString(c, "source").toUpperCase());
                    PLUGIN_CONFIG.experienceSource = source;
                    inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                    c.getSource().getEmbed()
                        .title("Experience Source Set")
                        .addField("Source", source.name().toLowerCase());
                })))
            .then(literal("logTradeStatusToDiscord").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.logTradeStatusToDiscord = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Log Trade Status To Discord " + toggleStrCaps(PLUGIN_CONFIG.logTradeStatusToDiscord));
            })))
            .then(literal("renameOnly").then(argument("toggle", toggle()).executes(c -> {
                // "不附魔仅改名": fetch, rename, unload - without a single merge. Existing enchantments are left alone,
                // and a job needs no target enchantments while this is on.
                PLUGIN_CONFIG.renameOnly = getToggle(c, "toggle");
                inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                c.getSource().getEmbed()
                    .title("Rename Only " + toggleStrCaps(PLUGIN_CONFIG.renameOnly))
                    .addField("Mode", PLUGIN_CONFIG.renameOnly
                        ? "items are only renamed; no enchantment is applied"
                        : "items are enchanted to their target set", false);
            })))
            .then(literal("grindstone").then(argument("grindstonePos", blockPos()).executes(c -> {
                // Where a piece carrying enchantments outside the target is ground blank. The block has to be a real
                // grindstone when a piece needs cleaning; the position is checked before every open, so a missing block
                // costs one warning and a backoff instead of a tick-rate retry loop.
                var grindstonePos = getBlockPos(c, "grindstonePos");
                var embed = c.getSource().getEmbed();
                if (World.isChunkLoadedBlockPos(grindstonePos.x(), grindstonePos.z())) {
                    embed.addField("Block At Grindstone Pos", World.getBlock(grindstonePos).name());
                }
                PLUGIN_CONFIG.grindstone = grindstonePos;
                embed.title("Grindstone Set")
                    .addField("Position", grindstonePos)
                    .addField("Applies", "Where an item with extra enchantments is cleaned", false);
                return OK;
            })))
            .then(literal("ExpFarmSpot").then(argument("ExpFarmSpot", blockPos()).executes(c -> {
                var killSpotPos = getBlockPos(c, "ExpFarmSpot");
                if (World.isChunkLoadedBlockPos(killSpotPos.x(), killSpotPos.z())) {
                    var killSpotBlock = World.getBlock(killSpotPos);
                    c.getSource().getEmbed()
                        .addField("Block At ExpFarmSpot Pos", killSpotBlock.name());
                }
                PLUGIN_CONFIG.ExpFarmSpot = killSpotPos.addY(1);
                c.getSource().getEmbed()
                    .title("ExpFarmSpot Set");
            })))
            .then(literal("anvil").then(argument("anvilPos", blockPos()).executes(c -> {
                // The station the anvil is placed on when it breaks. It stays the authority even while no anvil
                // stands there, because that is exactly the position the replacement is built on.
                var anvilPos = getBlockPos(c, "anvilPos");
                if (World.isChunkLoadedBlockPos(anvilPos.x(), anvilPos.z())) {
                    c.getSource().getEmbed()
                        .addField("Block At Anvil Pos", World.getBlock(anvilPos).name());
                }
                PLUGIN_CONFIG.anvil = anvilPos;
                inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                c.getSource().getEmbed()
                    .title("Anvil Set")
                    .addField("Position", anvilPos)
                    .addField("Applies", "Where a replacement is placed when the anvil disappears", false);
                return OK;
            })))
            .then(literal("anvilChest").then(argument("anvilChestPos", blockPos()).executes(c -> {
                // The container the replacement anvil is withdrawn from; without it a broken anvil cannot be restored.
                var anvilChestPos = getBlockPos(c, "anvilChestPos");
                if (World.isChunkLoadedBlockPos(anvilChestPos.x(), anvilChestPos.z())) {
                    c.getSource().getEmbed()
                        .addField("Block At AnvilChest Pos", World.getBlock(anvilChestPos).name());
                }
                PLUGIN_CONFIG.anvilChest = anvilChestPos;
                inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                c.getSource().getEmbed()
                    .title("AnvilChest Set")
                    .addField("Position", anvilChestPos)
                    .addField("Applies", "Supply chest for a replacement anvil", false);
                return OK;
            })))
            .then(literal("killWindow").then(argument("ticks", time()).executes(c -> {
                // One kill window: KillAura on for this long at the exp spot, a few silverfish die, and the aura goes
                // off again so the enchant work continues. 0 disables the windows.
                long ticks = Math.max(0L, Math.min(1200L, getInteger(c, "ticks")));
                PLUGIN_CONFIG.killWindowTicks = ticks;
                c.getSource().getEmbed()
                    .title("Kill Window Set")
                    .addField("Duration", ticks == 0
                        ? "0 - windows disabled, the aura is only used while farming a level"
                        : String.format(java.util.Locale.ROOT, "%.1fs", ticks * 50L / 1000.0) + " (" + ticks + " ticks)")
                    .addField("Applies", "kill experience source: the BOT clears some silverfish, then goes back to "
                        + "the anvil and the chests", false);
                return OK;
            })))
            .then(literal("killGap").then(argument("ticks", time()).executes(c -> {
                // Minimum gap between two window starts: the farm deletes a mob that was left alive for about nine
                // seconds, so a window has to come around well inside that.
                long ticks = Math.max(20L, Math.min(1200L, getInteger(c, "ticks")));
                PLUGIN_CONFIG.killWindowIntervalTicks = ticks;
                c.getSource().getEmbed()
                    .title("Kill Gap Set")
                    .addField("Gap", String.format(java.util.Locale.ROOT, "%.1fs", ticks * 50L / 1000.0)
                        + " (" + ticks + " ticks)")
                    .addField("Applies", "kill experience source: a window starts at most every this many ticks, and "
                        + "one more before a container is opened", false);
                return OK;
            })))
            .then(literal("killMinKills").then(argument("kills", integer(0, 64)).executes(c -> {
                // A window ends early once this many silverfish died in it, so a productive farm is not waited on.
                int kills = Math.max(0, Math.min(64, getInteger(c, "kills")));
                PLUGIN_CONFIG.killWindowMinKills = kills;
                c.getSource().getEmbed()
                    .title("Kill Minimum Set")
                    .addField("Kills per window", kills == 0
                        ? "0 - every window runs its full duration"
                        : kills + " silverfish")
                    .addField("Applies", "kill experience source only", false);
                return OK;
            })))
            .then(literal("anvilRestock")
                .executes(context -> {
                    context.getSource().getEmbed().title("Anvil Restock")
                        .addField("Stacks per restock", Integer.toString(PLUGIN_CONFIG.anvilRestockStacks), true)
                        .addField("Usage", "autoEnchant anvilRestock <1-8>", false);
                    return OK;
                })
                .then(argument("stacks", integer(1, 8)).executes(context -> {
                    int stacks = getInteger(context, "stacks");
                    PLUGIN_CONFIG.anvilRestockStacks = stacks;
                    context.getSource().getEmbed().title("Anvil Restock Updated")
                        .addField("Stacks per restock", Integer.toString(stacks), true)
                        .addField("Applies", "When the anvil block disappears and the BOT has none", false);
                    return OK;
            })))
            .then(literal("batchSize")
                .executes(context -> { showBatchSize(context); return OK; })
                .then(argument("count", integer(1, 32)).executes(context -> {
                    int count = getInteger(context, "count");
                    PLUGIN_CONFIG.batchSize = count;
                    context.getSource().getEmbed().title("Batch Size Updated")
                        .addField("Pieces per batch", Integer.toString(count))
                        .addField("Applies", count + " pieces of one equipment per batch (bag is topped up to this "
                            + "number, counting what is already carried); " + batchOrderHint(), false);
                    return OK;
                })))
            // Old name kept as a hidden alias so existing macros keep working.
            .then(literal("inputItemRestockStacks")
                .executes(context -> { showBatchSize(context); return OK; })
                .then(argument("count", integer(1, 32)).executes(context -> {
                    int count = getInteger(context, "count");
                    PLUGIN_CONFIG.batchSize = count;
                    context.getSource().getEmbed().title("Batch Size Updated")
                        .addField("Pieces per batch", Integer.toString(count))
                        .addField("Applies", count + " pieces of one equipment per batch (bag is topped up to this "
                            + "number, counting what is already carried); " + batchOrderHint(), false);
                    return OK;
                })))
            .then(literal("batchOrder")
                .executes(context -> {
                    context.getSource().getEmbed().title("Batch Order")
                        .addField("Order", PLUGIN_CONFIG.batchOrder.name().toLowerCase(), true)
                        .addField("Usage", "autoEnchant batchOrder <row/step>", false)
                        .addField("Applies", batchOrderHint(), false);
                    return OK;
                })
                .then(argument("order", enumStrings(AutoEnchantConfig.BatchOrder.values())).executes(context -> {
                    var order = AutoEnchantConfig.BatchOrder.valueOf(getString(context, "order").toUpperCase());
                    PLUGIN_CONFIG.batchOrder = order;
                    inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                    context.getSource().getEmbed().title("Batch Order Set")
                        .addField("Order", order.name().toLowerCase(), true)
                        .addField("Applies", batchOrderHint(), false);
                    return OK;
                })))
            .then(literal("stats")
                .executes(context -> { showStats(context); return OK; })
                .then(literal("reset").executes(context -> {
                    MODULE.get(AutoEnchantModule.class).resetStats();
                    context.getSource().getEmbed().title("Enchant Stats Reset")
                        .addField("Counters", "cleared - the next finished piece starts a fresh measurement", false);
                    return OK;
                })))
            .then(literal("scanRange")
                .then(argument("horizontal", integer(1, 256)).executes(c -> {
                    PLUGIN_CONFIG.scanHorizontalBlocks = getInteger(c, "horizontal");
                    c.getSource().getEmbed()
                        .title("Scan Range Set")
                        .addField("Horizontal", PLUGIN_CONFIG.scanHorizontalBlocks + " blocks")
                        .addField("Vertical", PLUGIN_CONFIG.scanVerticalBlocks + " blocks");
                    return OK;
                }))
                .then(argument("horizontal", integer(1, 256)).then(argument("vertical", integer(1, 64)).executes(c -> {
                    PLUGIN_CONFIG.scanHorizontalBlocks = getInteger(c, "horizontal");
                    PLUGIN_CONFIG.scanVerticalBlocks = getInteger(c, "vertical");
                    c.getSource().getEmbed()
                        .title("Scan Range Set")
                        .addField("Horizontal", PLUGIN_CONFIG.scanHorizontalBlocks + " blocks")
                        .addField("Vertical", PLUGIN_CONFIG.scanVerticalBlocks + " blocks");
                    return OK;
                })))
            )
            .then(literal("scan").executes(context -> {
                AutoEnchantModule module = MODULE.get(AutoEnchantModule.class);
                module.scanNow();
                var embed = context.getSource().getEmbed().title("Auto Enchant Scan").addField("Result", module.status(), false);
                if (!module.markerProblems().isEmpty()) embed.addField("Marker issues", String.join("\n", module.markerProblems()), false);
                embed.addField("Note", "A frame showing equipment is the template for that enchantItem: its enchantments "
                    + "become the target set (a wiki leaf tree match = PRESET, otherwise CUSTOM) and its custom name "
                    + "becomes the rename target. The first container below it is the unload chest, used for finished "
                    + "and half-done pieces alike, and the first "
                    + "redstone lamp in the column above it (up to 8 blocks, it need not touch the frame) switches the "
                    + "job on (lit) or off (unlit). The pickup chest is the first container "
                    + "below the redstone-dust frame. Only chests, barrels and shulker boxes count as containers; a "
                    + "redstone-block frame is ignored.", false);
                return OK;
            }));
    }

    @Override
    public void defaultHandler(CommandContext ctx) {
        if (!ctx.getData().containsKey("list")) {
            ctx.getEmbed()
                .addField("AutoEnchant", toggleStr(PLUGIN_CONFIG.enabled))
                .addField("Wait For Interact Timeout", PLUGIN_CONFIG.waitForInteractTimeoutTicks + " ticks")
                .addField("Action Delay", PLUGIN_CONFIG.actionDelay + " ticks")
                .addField("Restart Delay", PLUGIN_CONFIG.restartDelay + " ticks")
                .addField("Open Retry", PLUGIN_CONFIG.openRetryTicks + " ticks per open click")
                .addField("Experience Source", PLUGIN_CONFIG.experienceSource.name().toLowerCase())
                .addField("Rename Only", PLUGIN_CONFIG.renameOnly)
                .addField("ExpFarmSpot", PLUGIN_CONFIG.ExpFarmSpot)
                .addField("Kill Window", String.format(java.util.Locale.ROOT, "%.1fs on / %.1fs gap",
                    PLUGIN_CONFIG.killWindowTicks * 50L / 1000.0, PLUGIN_CONFIG.killWindowIntervalTicks * 50L / 1000.0))
                .addField("Kill Min Kills", PLUGIN_CONFIG.killWindowMinKills + " per window")
                .addField("Batch Size (pieces/books)", PLUGIN_CONFIG.batchSize)
                .addField("Batch Order", PLUGIN_CONFIG.batchOrder.name().toLowerCase())
                .addField("Throughput", MODULE.get(AutoEnchantModule.class).statsSummary())
                .addField("Grindstone", PLUGIN_CONFIG.grindstone)
                .addField("Anvil", PLUGIN_CONFIG.anvil)
                .addField("AnvilChest", PLUGIN_CONFIG.anvilChest)
                .addField("Item Frame Scan Range", "h:" + PLUGIN_CONFIG.scanHorizontalBlocks + "/n" + "v:" + PLUGIN_CONFIG.scanVerticalBlocks + " blocks")
                .addField("Log Trade Status To Discord", PLUGIN_CONFIG.logTradeStatusToDiscord);
        }
        ctx.getEmbed()
            .primaryColor();
    }

    /** Throughput view: per-piece rate and the time one full shulker box (27 pieces) takes. */
    private void showStats(com.mojang.brigadier.context.CommandContext<CommandContext> context) {
        var embed = context.getSource().getEmbed().title("Enchant Throughput");
        for (var field : MODULE.get(AutoEnchantModule.class).statsFields().entrySet()) {
            embed.addField(field.getKey(), field.getValue(), false);
        }
        embed.addField("Reset", "autoEnchant stats reset", false);
    }

    /** Shared view for the new batchSize command and its old inputItemRestockStacks alias. */
    private void showBatchSize(com.mojang.brigadier.context.CommandContext<CommandContext> context) {
        context.getSource().getEmbed().title("Batch Size")
            .addField("Pieces per batch", Integer.toString(PLUGIN_CONFIG.batchSize))
            .addField("Usage", "autoEnchant batchSize <1-32>", false)
            .addField("Applies", "Global: how many pieces of one equipment are enchanted per batch. Restocks top the "
                + "bag up to this number, counting pieces already carried. " + batchOrderHint(), false);
    }

    /** One line that tells the operator what the current batch order costs in book chest visits. */
    private String batchOrderHint() {
        return PLUGIN_CONFIG.batchOrder == AutoEnchantConfig.BatchOrder.STEP
            ? "step order: every piece advances by one merge step, so only that step's one or two book types are "
                + "carried and the batch can be much larger than in row order (10+ pieces are fine)"
            : "row order: one piece is finished before the next, so all of its books are carried at once "
                + "(keep the batch at 4 or less - each piece needs 7 book slots)";
    }

    void inEventLoop(Runnable task) {
        try {
            var client = Proxy.getInstance().getClient();
            if (client != null) {
                var eventLoop = client.getClientEventLoop();
                if (eventLoop.inEventLoop() || eventLoop.isShuttingDown()) {
                    task.run();
                    return;
                }
                eventLoop.submit(task).await();
            } else {
                task.run();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public String printEnchant(String id, AutoEnchantConfig.Enchant enchant) {
        StringBuilder sb = new StringBuilder();
        sb
            .append("`")
            .append(id)
            .append("`: [")
            .append(AutoEnchantConfig.shortId(enchant.item))
            .append("] ")
            .append("`").append(enchant.inputItemChest).append("`");
        sb.append(" -> ");
        sb.append("`").append(enchant.outputChest).append("`");
        // The anvil name field content: a real name, or (clear) when an empty name is written to strip it.
        sb.append(" name=");
        sb.append(enchant.getRenameName().isEmpty() ? "(clear)" : "\"" + enchant.getRenameName() + "\"");
        if (!enchant.itemEnchantments.isEmpty()) {
            sb.append(' ').append('[').append(printEnchantEnchantments(enchant)).append(']');
        }
        if (!enchant.enabled) {
            sb.append(" (disabled)");
        }
        return sb.toString();
    }

    public String printEnchantEnchantments(AutoEnchantConfig.Enchant enchant) {
        StringBuilder sb = new StringBuilder();
        if (!enchant.itemEnchantments.isEmpty()) {
            for (var entry : enchant.itemEnchantments.object2IntEntrySet()) {
                sb.append(entry.getKey());
                sb.append(" -> ");
                sb.append(entry.getIntValue());
                sb.append(", ");
            }
            sb.delete(sb.length() - 2, sb.length());
        }
        return sb.toString();
    }

    public String printAllEnchants() {
        StringBuilder sb = new StringBuilder();
        var trades = PLUGIN_CONFIG.enchants;
        int index = 1;
        for (var entry : trades.entrySet()) {
            var id = entry.getKey();
            var trade = entry.getValue();
            sb
                .append(index++)
                .append(". ")
                .append(printEnchant(id, trade))
                .append("\n");
        }
        return sb.toString();
    }
}

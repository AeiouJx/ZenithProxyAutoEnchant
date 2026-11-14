package org.example.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.zenith.Proxy;
import com.zenith.command.api.Command;
import com.zenith.command.api.CommandCategory;
import com.zenith.command.api.CommandContext;
import com.zenith.command.api.CommandUsage;
import com.zenith.command.brigadier.CustomStringArgumentType;
import com.zenith.discord.Embed;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import org.example.AutoEnchantConfig;
import org.example.EnchantPresetCatalog;
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
                "logTradeStatusToDiscord on/off",
                "killSpot <x> <y> <z>",
                "scan",
                "scanRange <horizontal> <vertical>",
                "anvilRestock <stacks>"
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
                        enchant.item = EnchantPresetCatalog.shortId(enchantItem);
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
                        "set <id> inputItem <item>",
                        "set <id> outputItem <item>",
                        "set <id> inputItemChest <x> <y> <z>",
                        "set <id> outputChest <x> <y> <z>",
                        "set <id> outputItemStoreCountThreshold <count>",
                        "set <id> enchants add <enchantment> <level>",
                        "set <id> enchants del <enchantment>",
                        "set <id> enchants clear",
                        "set <id> enchants list",
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
                        enchant.item = EnchantPresetCatalog.shortId(enchantItem);
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
                    .then(literal("inputItemRestockStacks").then(argument("inputItemRestockStacks", integer(1, 35)).executes(c -> {
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
                        enchant.inputItemRestockStacks = getInteger(c, "inputItemRestockStacks");
                        inEventLoop(() -> MODULE.get(AutoEnchantModule.class).onTradeListChange());
                        c.getSource().getEmbed()
                            .title("Input Item Restock Stacks Set")
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
                            enchant.itemEnchantments.put(EnchantPresetCatalog.shortId(enchantment.name()), level);
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
                            enchant.itemEnchantments.remove(EnchantPresetCatalog.shortId(enchantment.name()));
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
            .then(literal("logTradeStatusToDiscord").then(argument("toggle", toggle()).executes(c -> {
                PLUGIN_CONFIG.logTradeStatusToDiscord = getToggle(c, "toggle");
                c.getSource().getEmbed()
                    .title("Log Trade Status To Discord " + toggleStrCaps(PLUGIN_CONFIG.logTradeStatusToDiscord));
            })))
            .then(literal("killSpot").then(argument("killSpot", blockPos()).executes(c -> {
                var killSpotPos = getBlockPos(c, "killSpot");
                if (World.isChunkLoadedBlockPos(killSpotPos.x(), killSpotPos.z())) {
                    var killSpotBlock = World.getBlock(killSpotPos);
                    c.getSource().getEmbed()
                        .addField("Block At killSpot Pos", killSpotBlock.name());
                }
                PLUGIN_CONFIG.killSpot = killSpotPos.addY(1);
                c.getSource().getEmbed()
                    .title("killSpot Set");
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
                .addField("KillSpot", PLUGIN_CONFIG.killSpot)
                .addField("AnvilChest", PLUGIN_CONFIG.anvilChest)
                .addField("Item Frame Scan Range", "h" + PLUGIN_CONFIG.scanHorizontalBlocks + "v" + PLUGIN_CONFIG.scanVerticalBlocks + " blocks")
                .addField("Log Trade Status To Discord", PLUGIN_CONFIG.logTradeStatusToDiscord);
        }
        ctx.getEmbed()
            .primaryColor();
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
            .append(EnchantPresetCatalog.shortId(enchant.item))
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

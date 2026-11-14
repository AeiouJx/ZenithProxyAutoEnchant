package org.example;

import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AutoEnchantConfig {
    public boolean enabled = false;
    public long waitForInteractTimeoutTicks = 20L;
    public boolean logTradeStatusToDiscord = false;

    public BlockPos killSpot = BlockPos.ZERO;
    public BlockPos grindstone = BlockPos.ZERO;
    public BlockPos anvil = BlockPos.ZERO;
    public BlockPos anvilChest = BlockPos.ZERO;

    public boolean allowGrindstone = false;
    public int anvilRestockStacks = 1;

    public long operationDelay = 5L;
    public long restartDelay = 5L;

    public int scanHorizontalBlocks = 8;
    public int scanVerticalBlocks = 8;

    public ExperienceSource experienceSource = ExperienceSource.KILL;
    public enum ExperienceSource {
        NONE,
        KILL,
        EXP
    }

    public LinkedHashMap<String, BlockPos> enchantBook = new LinkedHashMap<>();
    public LinkedHashMap<String, Enchant> enchants = new LinkedHashMap<>();

    public static class Enchant {
        public boolean enabled = true;
        public String item = ItemRegistry.AIR.name();
        public BlockPos inputItemChest = BlockPos.ZERO;
        public BlockPos outputChest = BlockPos.ZERO;
        public int inputItemRestockStacks = 1;

        /**
         * Text written into the anvil's name field, exactly like a player typing in the "Repair & Name" box:
         * an empty string clears an existing name, any other value renames the item, and a value equal to the
         * item's current name changes nothing (and costs nothing).
         */
        public String renameName = "";

        public PostEnchantStoreMode postEnchantStoreMode = PostEnchantStoreMode.NONE;
        public enum PostEnchantStoreMode {
            /** Keep a failed piece in the BOT inventory and retry it instead of storing it. */
            NONE,
            /** Failed (half-done) pieces go back to the shared pickup chest. */
            TO_RESTOCK,
            /** Failed (half-done) pieces go to the unload chest, i.e. the job's output chest / equipment frame. */
            TO_OVERFLOW
        }
        public Object2IntLinkedOpenHashMap<String> itemEnchantments = new Object2IntLinkedOpenHashMap<>();

        public String displayName() {
            return item == null ? "" : item.replace("minecraft:", "");
        }

        public ItemData getEnchantItem() {
            return ItemRegistry.REGISTRY.get(item);
        }

        public String getRenameName() {
            return renameName == null ? "" : renameName;
        }
    }
}

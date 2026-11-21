package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.plugin.api.ConfigSerializer;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;

import java.io.Reader;
import java.io.Writer;
import java.util.LinkedHashMap;
import java.util.List;

public class AutoEnchantConfig {
    public boolean enabled = false;
    public long waitForInteractTimeoutTicks = 20L;
    public boolean logTradeStatusToDiscord = false;

    public BlockPos ExpFarmSpot = BlockPos.ZERO;
    public BlockPos grindstone = BlockPos.ZERO;
    public BlockPos anvil = BlockPos.ZERO;
    public BlockPos anvilChest = BlockPos.ZERO;

    public int anvilRestockStacks = 1;
    /**
     * How many pieces of one equipment are enchanted per batch. A restock tops the bag up to this number, counting
     * every piece already carried (raw, half-done or finished), so it is also the number of pieces withdrawn in one
     * trip when the bag is empty. Global on purpose: sword, pickaxe, axe and shovel all share it, and one batch is
     * fully enchanted and unloaded before the next job is picked.
     *
     * <p>How many books that batch needs depends on {@link #batchOrder}: a row batch needs one book of every target
     * enchantment per piece (7N books for N swords, so N is capped at 4 by the 36-slot bag), while a step batch only
     * ever fetches the one or two book types of the step it is on (N is capped at about 11).
     *
     * <p>Was named {@code inputItemRestockStacks}; migrated in {@link #normalize(JsonObject)}.
     */
    public int batchSize = 10;
    /**
     * How one batch is advanced.
     *
     * <p>{@code ROW} enchants one piece from its first merge to its last and only then picks the next piece, so the
     * whole book set of the batch has to be carried at once. {@code STEP} takes the same batch but advances every
     * piece by one merge step at a time: it fetches the books of one step, applies that step to all pieces, and only
     * then fetches the books of the next step. Keeping the pieces in the bag until the batch is done is what allows a
     * much larger batch in the same inventory.
     */
    public BatchOrder batchOrder = BatchOrder.ROW;
    public enum BatchOrder {
        /** One whole piece at a time: all its merges, then the next piece. */
        ROW,
        /** One merge step at a time: that step for every piece of the batch, then the next step. */
        STEP
    }
    /**
     * Rename-only mode ("不附魔仅改名"): an item is fetched, named at the anvil and unloaded again, without a single
     * merge. Jobs need no target enchantments in this mode, and an item that already carries enchantments is never
     * ground blank for not matching a target set.
     */
    public boolean renameOnly = false;

    public int actionDelay = 5;
    public long restartDelay = 5L;
    /**
     * Ticks to wait after a right click on a chest, anvil or grindstone before the click is sent again.
     *
     * <p>This is not {@link #restartDelay}: that one is the backoff after a failed step. This one only covers the round
     * trip of the open click itself. Measured on the live bot over 500+ windows: when the server accepts a click the
     * window answers in under 100ms, and no window in that sample took more than 150ms. Four ticks is therefore already
     * twice the round trip.
     *
     * <p>The misses that remain do not come from a short budget: they arrive in bursts of five to ten minutes in which
     * the first click of nearly every open is dropped outright and the re-sent click opens the window inside the same
     * hundred milliseconds. A longer budget cannot rescue a dropped click, it only adds that wait to every open in the
     * burst (about 45 opens a batch, so eight ticks cost roughly nine seconds a batch); it was raised to eight on a
     * single clean phase and the next burst showed the same miss rate as before, so four is the default. After three
     * attempts at the same block the full {@link #waitForInteractTimeoutTicks} budget takes over.
     */
    public long openRetryTicks = 4L;
    /**
     * KILL experience source only. Length of one kill window: the BOT stands at {@code ExpFarmSpot}, KillAura is
     * switched on for this long, a few silverfish die, and the aura is switched off again so the enchant work can
     * continue. 40 ticks = 2 seconds.
     *
     * <p>The farm spot is the place the BOT already works from (anvil, pickup chest and book chests are all within
     * reach), so a window is a short interruption of the enchant loop, not a trip: the silverfish farm is culled by
     * the server when a mob is not killed for about nine seconds, which is exactly what these windows prevent.
     */
    public long killWindowTicks = 40L;
    /**
     * KILL experience source only: minimum gap between the start of two kill windows. 60 ticks = 3 seconds, i.e. one
     * window per three seconds of enchant work, and one more before a container is opened.
     */
    public long killWindowIntervalTicks = 60L;
    /**
     * KILL experience source only: a window ends as soon as this many silverfish died in it, without waiting for the
     * timer. 0 makes every window run its full length.
     */
    public int killWindowMinKills = 2;

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
            /** Failed (half-done) pieces go to the shared deposit chest (the job's output chest). */
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

    /** Registry short id, e.g. {@code minecraft:diamond_sword} -> {@code diamond_sword}. */
    public static String shortId(String id) {
        if (id == null) return "";
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}


final class AutoEnchantConfigSerializer implements ConfigSerializer {
    public static final AutoEnchantConfigSerializer INSTANCE = new AutoEnchantConfigSerializer();

    /** Single position fields at the root. */
    private static final List<String> ROOT_POSITIONS = List.of("ExpFarmSpot", "grindstone", "anvil", "anvilChest");
    /** Position fields of one enchant entry. */
    private static final List<String> ENCHANT_POSITIONS =
        List.of("inputItemChest", "outputChest");
    /** Legacy job lists, migrated into the keyed {@code enchants} map. */
    private static final List<String> LEGACY_JOB_LISTS = List.of("jobs", "targets");

    /** Mirrors the proxy's own config Gson: no HTML escaping, pretty printed, records supported. */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

    private AutoEnchantConfigSerializer() { }

    @Override
    public void write(Object config, Writer writer) {
        GSON.toJson(config, writer);
    }

    @Override
    public <T> T read(Class<T> configClass, Reader reader) {
        JsonElement root = JsonParser.parseReader(reader);
        if (root != null && root.isJsonObject()) normalize(root.getAsJsonObject());
        return GSON.fromJson(root, configClass);
    }

    private void normalize(JsonObject root) {
        for (String field : ROOT_POSITIONS) convertPosition(root, field);

        // The batch size used to live on each job under "inputItemRestockStacks", then globally under the same name.
        // Keep an existing install's setting: prefer the new name, then the old global one, then the first per-job
        // copy. The old keys are removed below / by convertJob so the file is rewritten in the new form.
        JsonElement batch = root.get("batchSize");
        if ((batch == null || !batch.isJsonPrimitive() || !batch.getAsJsonPrimitive().isNumber())) {
            JsonElement oldGlobal = root.get("inputItemRestockStacks");
            if (oldGlobal != null && oldGlobal.isJsonPrimitive() && oldGlobal.getAsJsonPrimitive().isNumber()) {
                root.add("batchSize", oldGlobal.deepCopy());
            } else {
                JsonObject configured = root.getAsJsonObject("enchants");
                if (configured != null) {
                    for (String key : configured.keySet()) {
                        JsonElement job = configured.get(key);
                        if (job == null || !job.isJsonObject()) continue;
                        JsonElement old = job.getAsJsonObject().get("inputItemRestockStacks");
                        if (old != null && old.isJsonPrimitive() && old.getAsJsonPrimitive().isNumber()) {
                            root.add("batchSize", old.deepCopy());
                            break;
                        }
                    }
                }
            }
        }
        root.remove("inputItemRestockStacks");

        JsonObject books = root.getAsJsonObject("enchantBook");
        if (books != null) {
            JsonObject normalizedBooks = new JsonObject();
            for (String key : List.copyOf(books.keySet())) {
                JsonElement value = books.get(key);
                if (isLegacyString(value)) {
                    BlockPos pos = toPos(value);
                    if (pos == null) continue;
                    value = toJson(pos);
                }
                normalizedBooks.add(bookKey(key), value);
            }
            root.add("enchantBook", normalizedBooks);
        }

        JsonObject enchants = root.getAsJsonObject("enchants");
        if (enchants != null) {
            for (String key : List.copyOf(enchants.keySet())) convertJob(enchants.get(key));
        }

        // Older versions kept jobs in an array. Move them into the keyed map so the work is not silently lost.
        boolean migrated = false;
        if (enchants == null) {
            enchants = new JsonObject();
            migrated = true;
        }
        for (String field : LEGACY_JOB_LISTS) {
            JsonElement list = root.get(field);
            if (list == null || !list.isJsonArray()) continue;
            for (JsonElement entry : list.getAsJsonArray()) {
                if (entry == null || !entry.isJsonObject()) continue;
                convertJob(entry);
                JsonObject job = entry.getAsJsonObject();
                JsonElement item = job.get("item");
                if (item == null || item.isJsonNull()) continue;
                String key = shortId(item.getAsString());
                if (enchants.has(key)) key = key + "_2";
                enchants.add(key, job);
            }
            root.remove(field);
            migrated = true;
        }
        JsonElement single = root.get("target");
        if (single != null && single.isJsonObject()) {
            convertJob(single);
            JsonObject job = single.getAsJsonObject();
            JsonElement item = job.get("item");
            if (item != null && !item.isJsonNull()) {
                String key = shortId(item.getAsString());
                if (!enchants.has(key)) enchants.add(key, job);
            }
            root.remove("target");
            migrated = true;
        }
        if (migrated) root.add("enchants", enchants);

        // Fields that no longer exist are dropped instead of being written back on the next save.
        root.remove("outputChest");
        root.remove("semiFinishedChest");
        root.remove("grindstoneChest");
        root.remove("containerPositions");
        root.remove("anvilPositions");
        root.remove("grindstonePositions");
        root.remove("renameOrder");
        root.remove("supplyScanDepth");
        root.remove("semiFinishedMarkerItem");
        root.remove("operationDelayMs");
        root.remove("restartDelayMs");
        root.remove("interactDelay");
        root.remove("windowTimeoutMs");
        root.remove("windowSettleDelayMs");
        root.remove("scanIntervalTicks");
        root.remove("reserveLevels");
        root.remove("minimumHealth");
        root.remove("killAuraMaxSeconds");
        root.remove("noXpTimeoutMs");
        // Superseded by the kill windows (killWindowTicks / killWindowIntervalTicks / killWindowMinKills): the farm
        // is no longer a burst with a level check between fights.
        root.remove("xpBurstTicks");
        root.remove("xpAccumulateLevels");
        root.remove("allowedXpDimensions");
        root.remove("customImpurityPolicy");
        root.remove("grindstoneThreshold");
        root.remove("retryLimit");
        root.remove("maxRecoveryAttempts");
    }

    /**
     * Rewrites one {@code {"x":..}} position when the stored value is still an {@code "x,y,z"} string. An empty or
     * unreadable legacy value means "no coordinate", so the field is dropped and the default applies.
     */
    private void convertPosition(JsonObject owner, String field) {
        if (owner == null || !owner.has(field)) return;
        JsonElement value = owner.get(field);
        if (!isLegacyString(value)) return;
        BlockPos pos = toPos(value);
        if (pos == null) {
            owner.remove(field);
            return;
        }
        owner.add(field, toJson(pos));
    }

    private JsonObject toJson(BlockPos pos) {
        JsonObject object = new JsonObject();
        object.addProperty("x", pos.x());
        object.addProperty("y", pos.y());
        object.addProperty("z", pos.z());
        return object;
    }

    /** True for a JSON string, which is how older versions stored every position. */
    private boolean isLegacyString(JsonElement element) {
        return element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString();
    }

    /** Renames the old job fields and converts its positions; the target set moves from a list to a keyed map. */
    private void convertJob(JsonElement job) {
        if (job == null || !job.isJsonObject()) return;
        JsonObject object = job.getAsJsonObject();
        rename(object, "enchantItem", "item");
        rename(object, "inputChest", "inputItemChest");
        // The separate semi-finished chest is gone: finished and half-done pieces share the output chest, so an old
        // coordinate is dropped instead of being migrated into a role that no longer reads it.
        object.remove("semiFinishedChest");
        object.remove("overflowChestPos");
        object.remove("inputItemRestockStacks"); // now a global setting, migrated in normalize()
        rename(object, "postJobStoreMode", "postEnchantStoreMode");
        convertEnchantmentList(object);
        normalizeEnchantmentMap(object);
        // The config stores ItemRegistry short names; older files may still carry the "minecraft:" prefix.
        JsonElement item = object.get("item");
        if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
            object.addProperty("item", shortId(item.getAsString()));
        }
        for (String field : ENCHANT_POSITIONS) convertPosition(object, field);
    }

    /** {@code enchantments:[{id,level}]} becomes {@code itemEnchantments:{id:level}} when the new map is absent. */
    private void convertEnchantmentList(JsonObject object) {
        if (object.has("itemEnchantments")) return;
        JsonElement list = object.get("enchantments");
        if (list == null || !list.isJsonArray()) return;
        JsonObject map = new JsonObject();
        for (JsonElement entry : list.getAsJsonArray()) {
            if (entry == null || !entry.isJsonObject()) continue;
            JsonObject enchant = entry.getAsJsonObject();
            JsonElement id = enchant.get("id");
            if (id == null || id.isJsonNull()) continue;
            JsonElement level = enchant.get("level");
            map.addProperty(shortId(id.getAsString()), level == null || level.isJsonNull() ? 1 : level.getAsInt());
        }
        object.add("itemEnchantments", map);
        object.remove("enchantments");
    }

    /** Enchantment ids in the target map are short registry names, even when an older file stored the prefixed form. */
    private void normalizeEnchantmentMap(JsonObject object) {
        JsonElement map = object.get("itemEnchantments");
        if (map == null || !map.isJsonObject()) return;
        JsonObject source = map.getAsJsonObject();
        JsonObject normalized = new JsonObject();
        for (String key : List.copyOf(source.keySet())) {
            JsonElement level = source.get(key);
            normalized.addProperty(shortId(key), level != null && level.isJsonPrimitive() ? level.getAsInt() : 1);
        }
        object.add("itemEnchantments", normalized);
    }

    /** Moves a legacy field to its current name unless the current name is already present. */
    private void rename(JsonObject object, String oldName, String newName) {
        if (!object.has(oldName) || object.has(newName)) return;
        object.add(newName, object.get(oldName));
        object.remove(oldName);
    }

    /** The position an {@code "x,y,z"} or {@code "(x, y, z)"} string describes, or null when it cannot be read. */
    private BlockPos toPos(JsonElement element) {
        if (!isLegacyString(element)) return null;
        String text = element.getAsString().replace("(", "").replace(")", "").trim();
        if (text.isEmpty()) return null;
        String[] parts = text.split("[,\\s]+");
        if (parts.length < 3) return null;
        try {
            return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Registry short id; the one implementation lives in {@link AutoEnchantConfig#shortId(String)}. */
    private String shortId(String id) {
        return AutoEnchantConfig.shortId(id);
    }

    /** Book chest key without a legacy {@code book:} prefix or {@code :level} suffix; the level is not part of the key. */
    private String bookKey(String key) {
        String normalized = key;
        if (normalized.startsWith("book:")) normalized = normalized.substring("book:".length());
        int level = normalized.lastIndexOf(':');
        if (level > 0 && normalized.substring(level + 1).matches("\\d+")) normalized = normalized.substring(0, level);
        return shortId(normalized);
    }
}

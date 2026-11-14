package org.example;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.api.ConfigSerializer;

import java.io.Reader;
import java.io.Writer;
import java.util.List;

/**
 * Config serializer that keeps a config written by an older version loadable.
 *
 * <p>Positions used to be stored as {@code "x,y,z"} strings; they are now {@link BlockPos} records, which Gson writes
 * as {@code {"x":1,"y":64,"z":-3}}. The proxy shuts down when a config cannot be parsed, so the old string form is
 * converted to the record form on read instead of asking for a hand-edited file. Everything else is plain Gson.
 */
public final class AutoEnchantConfigSerializer implements ConfigSerializer {
    public static final AutoEnchantConfigSerializer INSTANCE = new AutoEnchantConfigSerializer();

    /** Single position fields at the root. */
    private static final List<String> ROOT_POSITIONS = List.of("killSpot", "grindstone", "anvil", "anvilChest");
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

    private String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
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
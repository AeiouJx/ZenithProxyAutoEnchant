package org.example;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import dev.zenithproxy.autoenchant.BuildConstants;

/** Loads the bundled, versioned wiki preset table. */
public final class EnchantPresetCatalog {
    public record Preset(String id, String display, String targetPlaceholder, int totalLevels, int minXp,
                         String expression, EnchantExpressionParser.Node tree) { }
    public record Enchant(String id, int level) { }
    public record Operation(EnchantExpressionParser.Node left, EnchantExpressionParser.Node right,
                            EnchantExpressionParser.Node result, int expectedCost) { }

    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private final List<List<String>> conflicts = new ArrayList<>();
    private String dataVersion = "unknown";
    private List<String> loadWarnings = List.of();
    private final EnchantExpressionParser expressionParser = new EnchantExpressionParser();
    private static final Set<String> BASE_ITEMS = Set.of("helmet", "chestplate", "leggings", "boots", "sword", "axe", "bow", "crossbow", "trident", "mace", "spear", "pickaxe", "shovel", "hoe", "fishing_rod", "misc_item", "shears");
    /**
     * Wiki wording to registry id. The guide still uses pre-1.21 names in a few places; the registry only has the
     * renamed id, so a mismatch would otherwise drop the whole variant (silently, before loadWarnings existed).
     * Key = wiki token, value = registry name.
     */
    private static final Map<String, String> ENCHANT_ALIASES = Map.of("sweeping", "sweeping_edge");

    public void load() {
        load(BuildConstants.MC_VERSION);
    }

    public void load(String targetVersion) {
        presets.clear();
        conflicts.clear();
        List<String> warnings = new ArrayList<>();
        String resource = "/best-enchantments-" + targetVersion + ".json";
        try (InputStream stream = EnchantPresetCatalog.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IllegalStateException("Missing " + resource + " resource");
            JsonObject root = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            dataVersion = root.has("dataVersion") ? root.get("dataVersion").getAsString() : "unknown";
            if (!targetVersion.equals(dataVersion)) {
                throw new IllegalStateException("Preset dataVersion " + dataVersion + " does not match target " + targetVersion);
            }
            JsonObject plans = root.getAsJsonObject("plans");
            for (var slotEntry : plans.entrySet()) {
                JsonObject variants = slotEntry.getValue().getAsJsonObject().getAsJsonObject("variants");
                for (var variantEntry : variants.entrySet()) {
                    JsonObject value = variantEntry.getValue().getAsJsonObject();
                    String id = slotEntry.getKey() + "." + variantEntry.getKey();
                    if (!value.has("expression") || value.get("expression").isJsonNull()) continue;
                    String expression = value.get("expression").getAsString();
                    String reason = unsupportedReason(expression);
                    if (reason != null) {
                        warnings.add(id + " — " + reason);
                        continue;
                    }
                    String targetPlaceholder = slotEntry.getValue().getAsJsonObject().get("targetPlaceholder").getAsString();
                    if (!BASE_ITEMS.contains(targetPlaceholder)) throw new IllegalStateException("Invalid targetPlaceholder for " + id);
                    int totalLevels = value.has("totalLevels") && !value.get("totalLevels").isJsonNull() ? value.get("totalLevels").getAsInt() : -1;
                    var tree = normalize(expressionParser.parse(expression));
                    int parsedCost = annotatedCost(tree);
                    if (totalLevels >= 0 && parsedCost >= 0 && totalLevels != parsedCost) {
                        throw new IllegalStateException("Expression cost mismatch for " + id + ": expected " + totalLevels + ", parsed " + parsedCost);
                    }
                    presets.put(id, new Preset(id,
                        value.has("display") ? value.get("display").getAsString() : id,
                        targetPlaceholder,
                        totalLevels,
                        value.has("minXp") && !value.get("minXp").isJsonNull() ? value.get("minXp").getAsInt() : -1,
                        expression,
                        tree));
                }
            }
            for (JsonElement group : root.getAsJsonArray("conflicts")) {
                List<String> ids = new ArrayList<>();
                for (JsonElement id : group.getAsJsonArray()) ids.add(shortId(id.getAsString()));
                conflicts.add(List.copyOf(ids));
            }
            loadWarnings = List.copyOf(warnings);
        } catch (Exception e) {
            loadWarnings = List.of();
            throw new IllegalStateException("Failed to load best enchantment presets", e);
        }
    }

    /** Variants that were skipped while loading, with the reason. Empty when every variant loaded. */
    public List<String> loadWarnings() { return loadWarnings; }

    public boolean isValidPreset(String id) { return presets.containsKey(id); }
    public Preset get(String id) { return presets.get(id); }
    public List<String> presetIds() { return List.copyOf(presets.keySet()); }
    public List<List<String>> conflicts() { return List.copyOf(conflicts); }
    public String dataVersion() { return dataVersion; }

    public List<Operation> operations(String presetId) {
        Preset preset = presets.get(presetId);
        if (preset == null) return List.of();
        List<Operation> operations = new ArrayList<>();
        flatten(preset.tree(), operations);
        return List.copyOf(operations);
    }

    /** Resolves the dataset's target placeholder to the concrete configured equipment enchantItem. */
    public List<Operation> operations(String presetId, String targetItem) {
        Preset preset = presets.get(presetId);
        if (preset == null) return List.of();
        if (!isValidTargetItem(preset.targetPlaceholder(), targetItem)) {
            throw new IllegalArgumentException(targetItem + " is not compatible with preset " + presetId);
        }
        List<Operation> operations = new ArrayList<>();
        flatten(replacePlaceholder(preset.tree(), preset.targetPlaceholder(), shortId(targetItem)), operations);
        return List.copyOf(operations);
    }

    /** One resume plan: the remaining anvil operations in execution order, plus the books they consume. */
    public record ResumePlan(List<Operation> operations, List<Enchant> books) { }

    /**
     * Preset ids the given enchantItem can use, e.g. {@code minecraft:diamond_sword} -> {@code [sword.all, sword.no_fire_knockback]}.
     * Empty when the enchantItem has no guide at all (a stick, a block, a book).
     */
    public List<String> compatiblePresets(String itemId) {
        if (itemId == null || itemId.isBlank()) return List.of();
        String normalized = itemId.startsWith("minecraft:") ? itemId : "minecraft:" + itemId;
        List<String> ids = new ArrayList<>();
        for (Preset preset : presets.values()) {
            if (isValidTargetItem(preset.targetPlaceholder(), normalized)) ids.add(preset.id());
        }
        return List.copyOf(ids);
    }

    /** Mutable holder for the subtree the physical enchantItem currently stands for. */
    private static final class EquipmentMatch { Map<String, Integer> expected; }

    /**
     * The compatible preset whose final enchantment set is exactly this set, or {@code null} when the set has no
     * guide tree. This is how a scanned template enchantItem is classified: a match means the job can follow the wiki merge
     * tree, no match means it is a hand-made CUSTOM set.
     */
    public String matchPreset(String itemId, Map<String, Integer> enchantments) {
        if (enchantments == null || enchantments.isEmpty()) return null;
        String best = null;
        for (String id : compatiblePresets(itemId)) {
            if (!finalEnchantments(id).equals(enchantments)) continue;
            if (id.endsWith(".all")) return id;
            if (best == null) best = id;
        }
        return best;
    }

    /**
     * Rebuilds the remaining work for a partially enchanted enchantItem by walking the wiki's optimised merge tree.
     *
     * <p>Every node whose enchantment set is already present on the enchantItem is treated as done and reused as-is, so a
     * finished branch is never repeated and the anvil costs stay those of the guide. Only the missing branches are
     * built. Returns {@code null} when the enchantItem cannot be aligned with the tree (for example it carries a partial
     * subset of a merged leaf pair); PRESET targets treat that as a failure instead of inventing a different order.
     */
    public ResumePlan resumePlan(String presetId, Map<String, Integer> current,
                                 List<Map<String, Integer>> availableBooks) {
        Preset preset = presets.get(presetId);
        if (preset == null) return null;
        if (current == null || current.isEmpty()) return null;
        List<Operation> operations = new ArrayList<>();
        List<Enchant> books = new ArrayList<>();
        EquipmentMatch equipment = new EquipmentMatch();
        try {
            resumeNode(preset.tree(), current, availableBooks, operations, books, equipment);
        } catch (RuntimeException e) {
            return null;
        }
        // The enchantItem itself stands in for exactly one finished subtree; it has to have been found, otherwise the
        // carried set is not a node of this tree and no merge input could ever be located.
        if (equipment.expected == null) return null;
        return new ResumePlan(List.copyOf(operations), List.copyOf(books));
    }

    /**
     * Returns the node that will be available once the subtree is satisfied, appending the anvil operations and the
     * books needed to get there. A satisfied subtree is returned untouched, which is what keeps finished branches.
     */
    private EnchantExpressionParser.Node resumeNode(EnchantExpressionParser.Node node,
                                                    Map<String, Integer> current,
                                                    List<Map<String, Integer>> availableBooks,
                                                    List<Operation> operations, List<Enchant> books,
                                                    EquipmentMatch equipment) {
        if (containsBase(node)) {
            // Only an exact enchantment-set match means the enchantItem itself stands for this subtree. A different
            // base-containing stack in the inventory (another sword waiting its turn) is a separate piece of equipment
            // and must never be mistaken for a reusable intermediate: doing that made every partially enchanted piece
            // look like it belonged to a bigger node and be dumped as "not aligned with the merge tree".
            if (equipment.expected == null && expectedEnchants(node).equals(current)) {
                equipment.expected = current;
                return node;
            }
        } else if (available(node, availableBooks)) {
            // Book-only branches may be reused from the inventory: a carried (sweeping + unbreaking) book is a real
            // intermediate that saves an anvil operation.
            return node;
        }
        if (node instanceof EnchantExpressionParser.Leaf leaf) {
            if (!BASE_ITEMS.contains(leaf.name())) books.add(new Enchant(leaf.name(), leaf.level()));
            return leaf;
        }
        var merge = (EnchantExpressionParser.Merge) node;
        var left = resumeNode(merge.left(), current, availableBooks, operations, books, equipment);
        var right = resumeNode(merge.right(), current, availableBooks, operations, books, equipment);
        var result = new EnchantExpressionParser.Merge(left, right, merge.expectedCost());
        operations.add(new Operation(left, right, result, merge.expectedCost()));
        return result;
    }

    private boolean containsBase(EnchantExpressionParser.Node node) {
        if (node instanceof EnchantExpressionParser.Leaf leaf) return BASE_ITEMS.contains(leaf.name());
        var merge = (EnchantExpressionParser.Merge) node;
        return containsBase(merge.left()) || containsBase(merge.right());
    }

    /**
     * True when some inventory stack already carries exactly this subtree's result, so the node can be reused instead
     * of rebuilt. Exact matching mirrors how merge inputs are located, which compares enchantment sets strictly.
     */
    private boolean available(EnchantExpressionParser.Node node, List<Map<String, Integer>> availableItems) {
        if (node instanceof EnchantExpressionParser.Leaf leaf && BASE_ITEMS.contains(leaf.name())) return true;
        Map<String, Integer> expected = expectedEnchants(node);
        for (Map<String, Integer> item : availableItems) if (item.equals(expected)) return true;
        return false;
    }

    /** Enchantment set a subtree produces, mirroring the anvil merge rule. */
    public Map<String, Integer> expectedEnchants(EnchantExpressionParser.Node node) {
        if (node instanceof EnchantExpressionParser.Leaf leaf) {
            if (BASE_ITEMS.contains(leaf.name())) return Map.of();
            return Map.of(leaf.name(), leaf.level());
        }
        var merge = (EnchantExpressionParser.Merge) node;
        Map<String, Integer> combined = new HashMap<>(expectedEnchants(merge.left()));
        expectedEnchants(merge.right()).forEach((id, level) -> combined.merge(id, level, (a, b) -> {
            if (!a.equals(b)) return Math.max(a, b);
            var data = EnchantmentRegistry.REGISTRY.get(id);
            int cap = data == null ? Integer.MAX_VALUE : data.maxLevel();
            return Math.min(a + 1, cap);
        }));
        return Map.copyOf(combined);
    }

    /**
     * Final enchantment set a preset produces, using the same anvil merge rule the workflow verifies against
     * (equal levels combine to one higher level, otherwise the higher one wins).
     */
    public Map<String, Integer> finalEnchantments(String presetId) {
        Preset preset = presets.get(presetId);
        if (preset == null) return Map.of();
        return finalEnchantments(preset.tree(), preset.targetPlaceholder());
    }

    private Map<String, Integer> finalEnchantments(EnchantExpressionParser.Node node, String placeholder) {
        if (node instanceof EnchantExpressionParser.Leaf leaf) {
            if (leaf.name().equals(placeholder)) return Map.of();
            return Map.of(leaf.name(), leaf.level());
        }
        var merge = (EnchantExpressionParser.Merge) node;
        Map<String, Integer> combined = new HashMap<>(finalEnchantments(merge.left(), placeholder));
        finalEnchantments(merge.right(), placeholder).forEach((id, level) ->
            combined.merge(id, level, (a, b) -> {
                if (!a.equals(b)) return Math.max(a, b);
                var data = EnchantmentRegistry.REGISTRY.get(id);
                int cap = data == null ? Integer.MAX_VALUE : data.maxLevel();
                return Math.min(a + 1, cap);
            }));
        return Map.copyOf(combined);
    }

    /** Returns source book leaves in first-use order, including duplicates required by the merge tree. */
    public List<Enchant> requiredBooks(String presetId) {
        Preset preset = presets.get(presetId);
        if (preset == null) return List.of();
        List<Enchant> books = new ArrayList<>();
        collectBookLeaves(preset.tree(), preset.targetPlaceholder(), books);
        return List.copyOf(books);
    }

    private void collectBookLeaves(EnchantExpressionParser.Node node, String targetPlaceholder, List<Enchant> output) {
        if (node instanceof EnchantExpressionParser.Merge merge) {
            collectBookLeaves(merge.left(), targetPlaceholder, output);
            collectBookLeaves(merge.right(), targetPlaceholder, output);
        } else {
            EnchantExpressionParser.Leaf leaf = (EnchantExpressionParser.Leaf) node;
            if (!leaf.name().equals(targetPlaceholder)) output.add(new Enchant(leaf.name(), leaf.level()));
        }
    }

    private EnchantExpressionParser.Node replacePlaceholder(EnchantExpressionParser.Node node, String placeholder, String itemId) {
        if (node instanceof EnchantExpressionParser.Merge merge) {
            return new EnchantExpressionParser.Merge(
                replacePlaceholder(merge.left(), placeholder, itemId),
                replacePlaceholder(merge.right(), placeholder, itemId),
                merge.expectedCost());
        }
        EnchantExpressionParser.Leaf leaf = (EnchantExpressionParser.Leaf) node;
        return leaf.name().equals(placeholder) ? new EnchantExpressionParser.Leaf(itemId, 1) : leaf;
    }

    private boolean isValidTargetItem(String placeholder, String itemId) {
        String item = shortId(itemId);
        if (!itemId.contains(":") || !itemId.startsWith("minecraft:")) return false;
        if (placeholder.equals("misc_item")) {
            return Set.of("flint_and_steel", "shield", "carrot_on_a_stick", "warped_fungus_on_a_stick", "brush", "elytra").contains(item);
        }
        return item.endsWith("_" + placeholder);
    }

    private void flatten(EnchantExpressionParser.Node node, List<Operation> operations) {
        if (!(node instanceof EnchantExpressionParser.Merge merge)) return;
        flatten(merge.left(), operations);
        flatten(merge.right(), operations);
        operations.add(new Operation(merge.left(), merge.right(), merge, merge.expectedCost()));
    }

    /** Returns null when every leaf resolves to a base enchantItem or a registry enchantment within its max level. */
    private String unsupportedReason(String expression) {
        try {
            return unsupportedReason(normalize(expressionParser.parse(expression)));
        } catch (RuntimeException e) {
            return "expression parse error: " + e.getMessage();
        }
    }

    private String unsupportedReason(EnchantExpressionParser.Node node) {
        if (node instanceof EnchantExpressionParser.Merge merge) {
            String left = unsupportedReason(merge.left());
            if (left != null) return left;
            return unsupportedReason(merge.right());
        }
        EnchantExpressionParser.Leaf leaf = (EnchantExpressionParser.Leaf) node;
        if (BASE_ITEMS.contains(leaf.name())) return null;
        var enchantment = EnchantmentRegistry.REGISTRY.get(leaf.name());
        if (enchantment == null) return "unknown enchantment '" + leaf.name() + "' (add an alias if the wiki name differs from the registry id)";
        if (leaf.level() > enchantment.maxLevel()) return "'" + leaf.name() + "' level " + leaf.level() + " exceeds max " + enchantment.maxLevel();
        return null;
    }

    /** Rewrites wiki-only enchantment names to registry ids so every downstream comparison uses one vocabulary. */
    private EnchantExpressionParser.Node normalize(EnchantExpressionParser.Node node) {
        if (node instanceof EnchantExpressionParser.Merge merge) {
            return new EnchantExpressionParser.Merge(normalize(merge.left()), normalize(merge.right()), merge.expectedCost());
        }
        EnchantExpressionParser.Leaf leaf = (EnchantExpressionParser.Leaf) node;
        String resolved = ENCHANT_ALIASES.getOrDefault(leaf.name(), leaf.name());
        return resolved.equals(leaf.name()) ? leaf : new EnchantExpressionParser.Leaf(resolved, leaf.level());
    }

    private int annotatedCost(EnchantExpressionParser.Node node) {
        if (node instanceof EnchantExpressionParser.Leaf) return 0;
        EnchantExpressionParser.Merge merge = (EnchantExpressionParser.Merge) node;
        int left = annotatedCost(merge.left());
        int right = annotatedCost(merge.right());
        if (left < 0 || right < 0 || merge.expectedCost() < 0) return -1;
        return left + right + merge.expectedCost();
    }

    public boolean hasConflict(List<Enchant> enchantments) {
        for (List<String> group : conflicts) {
            int found = 0;
            for (Enchant enchant : enchantments) if (group.contains(shortId(enchant.id()))) found++;
            if (found > 1) return true;
        }
        return false;
    }

    public boolean hasCurse(ItemStack item) {
        var component = item.getDataComponentsOrEmpty().get(
            org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes.ENCHANTMENTS);
        if (component == null) return false;
        var enchants = component.getEnchantments();
        return enchants.containsKey(EnchantmentRegistry.BINDING_CURSE.get().id())
            || enchants.containsKey(EnchantmentRegistry.VANISHING_CURSE.get().id());
    }

    public Map<String, Integer> enchantments(ItemStack item) {
        var component = item.getDataComponentsOrEmpty().get(
            org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes.ENCHANTMENTS);
        if (component == null) return Map.of();
        Map<String, Integer> result = new LinkedHashMap<>();
        component.getEnchantments().forEach((id, level) -> {
            var data = EnchantmentRegistry.REGISTRY.get(id);
            if (data != null) result.put(data.name(), level);
        });
        return Map.copyOf(result);
    }

    /** Reads the authoritative 1.21+ repair_cost component; no history-based guessing. */
    public int priorWorkPenalty(ItemStack item) {
        return item.getDataComponentsOrEmpty().getOrDefault(
            org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes.REPAIR_COST, 0);
    }

    /** Plain-text custom name, or an empty string when the enchantItem has none. */
    public String customName(ItemStack item) {
        var component = item.getDataComponentsOrEmpty().get(
            org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes.CUSTOM_NAME);
        return component == null ? "" : com.zenith.util.ComponentSerializer.serializePlain(component);
    }

    public static String shortId(String id) {
        return id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
    }
}

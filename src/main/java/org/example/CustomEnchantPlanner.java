package org.example;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plans a CUSTOM target as a merge tree that minimises anvil levels.
 *
 * <p>CUSTOM targets have no guide tree, but the anvil rules still apply: every operation charges the enchantment
 * value of the sacrificed stack at its final level, plus the prior-work penalty of both inputs ({@code 2^uses - 1}),
 * and the result's use count is {@code max(left, right) + 1}. Applying books one at a time therefore raises the
 * equipment's penalty to 1, 3, 7, 15, 31 ... and the next merge hits the 39-level limit after a handful of books.
 * This planner instead searches every way of grouping the missing books, merging each group into a single book
 * (books can be combined among themselves for far less penalty than the equipment can be reused) and then applying
 * the groups to the equipment in the cheapest order. It is the custom counterpart of the wiki tree.
 *
 * <p>The equipment must always sit in the left, target slot: the anvil result is the left enchantItem, so the equipment can
 * only ever grow along the left spine. That is why the search is "partition the books into groups, then order the
 * groups", not a free binary tree.
 */
public final class CustomEnchantPlanner {
    /** A finished plan: executable operations, the books they consume, and the predicted level total. */
    public record Plan(List<EnchantPresetCatalog.Operation> operations, List<EnchantPresetCatalog.Enchant> books,
                       int predictedLevels, int finalPenalty) { }

    private static final int MAX_LEVEL = 39;
    private static final int INF = Integer.MAX_VALUE / 4;
    private static final int MAX_EXACT_BOOKS = 10;
    /** Books per balanced pile in the long-list fallback. */
    private static final int FALLBACK_CHUNK = 4;
    /** Highest anvil-use count the search tracks; 15 already costs more than the survival limit allows. */
    private static final int MAX_USES = 6;

    /** Anvil multiplier charged per level of an enchantment that arrives on a book, from the wiki's table. */
    private static final Map<String, Integer> BOOK_MULTIPLIER = Map.ofEntries(
        Map.entry("protection", 1), Map.entry("fire_protection", 1), Map.entry("feather_falling", 1),
        Map.entry("blast_protection", 2), Map.entry("projectile_protection", 1), Map.entry("thorns", 4),
        Map.entry("respiration", 2), Map.entry("depth_strider", 2), Map.entry("aqua_affinity", 2),
        Map.entry("sharpness", 1), Map.entry("smite", 1), Map.entry("bane_of_arthropods", 1),
        Map.entry("knockback", 1), Map.entry("fire_aspect", 2), Map.entry("looting", 2),
        Map.entry("efficiency", 1), Map.entry("silk_touch", 4), Map.entry("unbreaking", 1),
        Map.entry("fortune", 2), Map.entry("power", 1), Map.entry("punch", 2), Map.entry("flame", 2),
        Map.entry("infinity", 4), Map.entry("luck_of_the_sea", 2), Map.entry("lure", 2),
        Map.entry("frost_walker", 2), Map.entry("mending", 2), Map.entry("binding_curse", 4),
        Map.entry("vanishing_curse", 4), Map.entry("impaling", 2), Map.entry("riptide", 2),
        Map.entry("loyalty", 1), Map.entry("channeling", 4), Map.entry("multishot", 2),
        Map.entry("piercing", 1), Map.entry("quick_charge", 1), Map.entry("soul_speed", 4),
        Map.entry("swift_sneak", 4), Map.entry("wind_burst", 2), Map.entry("density", 1),
        Map.entry("breach", 2), Map.entry("sweeping_edge", 2)
    );

    /** Levels one merge costs when the right-hand stack is sacrificed: its enchantment value plus both penalties. */
    public static int mergeCost(Map<String, Integer> sacrifice, int leftUses, int rightUses) {
        return enchantValue(sacrifice) + penalty(leftUses) + penalty(rightUses);
    }

    public static int penalty(int uses) {
        return uses <= 0 ? 0 : (1 << Math.min(uses, 30)) - 1;
    }

    /** Prior-work penalty expressed as an anvil-use count, the inverse of {@code 2^n - 1}. */
    public static int usesFromPenalty(int priorWorkPenalty) {
        int uses = 0;
        while (uses < MAX_USES && penalty(uses) < priorWorkPenalty) uses++;
        return uses;
    }

    public static int enchantValue(Map<String, Integer> enchantments) {
        int value = 0;
        for (var entry : enchantments.entrySet()) {
            value += entry.getValue() * BOOK_MULTIPLIER.getOrDefault(EnchantPresetCatalog.shortId(entry.getKey()), 2);
        }
        return value;
    }

    /**
     * Builds the cheapest plan that applies {@code wanted} to the enchantItem.
     *
     * @param current         enchantments the enchantItem already carries, used to skip finished entries
     * @param currentPenalty  the enchantItem's live {@code repair_cost}, so the search starts from its real use count
     * @return the plan, or {@code null} when no ordering fits under the 39-level survival limit
     */
    public static Plan plan(String itemId, Map<String, Integer> current, int currentPenalty,
                     List<EnchantPresetCatalog.Enchant> wanted) {
        List<EnchantPresetCatalog.Enchant> books = new ArrayList<>();
        Map<String, Integer> alreadyThere = new LinkedHashMap<>();
        if (current != null) alreadyThere.putAll(current);
        for (EnchantPresetCatalog.Enchant enchant : wanted) {
            String id = EnchantPresetCatalog.shortId(enchant.id());
            Integer have = alreadyThere.get(id);
            if (have != null && have >= enchant.level()) continue;
            books.add(new EnchantPresetCatalog.Enchant(id, enchant.level()));
        }
        int baseUses = usesFromPenalty(Math.max(0, currentPenalty));
        if (books.isEmpty()) {
            return new Plan(List.of(), List.of(), 0, penalty(baseUses));
        }
        if (books.size() > MAX_EXACT_BOOKS) return chunkedPlan(itemId, current, baseUses, books);
        return exactPlan(itemId, current, baseUses, books);
    }

    /** Exact search: subset DP over the books, then a second DP that applies the groups to the equipment. */
    private static Plan exactPlan(String itemId, Map<String, Integer> current, int baseUses,
                           List<EnchantPresetCatalog.Enchant> books) {
        int n = books.size();
        int full = 1 << n;
        int all = full - 1;
        int[] value = new int[n];
        Map<String, Integer> single = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            single.clear();
            single.put(books.get(i).id(), books.get(i).level());
            value[i] = enchantValue(single);
        }
        int[] subsetValue = new int[full];
        for (int mask = 1; mask < full; mask++) {
            int lowest = Integer.numberOfTrailingZeros(mask);
            subsetValue[mask] = subsetValue[mask & (mask - 1)] + value[lowest];
        }

        // Stage 1: cheapest way to merge each subset into one stack for every reachable anvil-use count.
        int[][] pileCost = new int[full][MAX_USES + 1];
        int[][] splitA = new int[full][MAX_USES + 1];
        int[][] splitLeftUses = new int[full][MAX_USES + 1];
        int[][] splitRightUses = new int[full][MAX_USES + 1];
        for (int mask = 0; mask < full; mask++) {
            java.util.Arrays.fill(pileCost[mask], INF);
            java.util.Arrays.fill(splitA[mask], -1);
        }
        for (int mask = 1; mask < full; mask++) {
            if (Integer.bitCount(mask) == 1) { pileCost[mask][0] = 0; continue; }
            int low = Integer.lowestOneBit(mask);
            for (int a = (mask - 1) & mask; a > 0; a = (a - 1) & mask) {
                if ((a & low) == 0) continue;
                int b = mask ^ a;
                if (b == 0) continue;
                int opCost = Math.min(subsetValue[a], subsetValue[b]);
                for (int leftUses = 0; leftUses <= MAX_USES; leftUses++) {
                    if (pileCost[a][leftUses] >= INF) continue;
                    for (int rightUses = 0; rightUses <= MAX_USES; rightUses++) {
                        if (pileCost[b][rightUses] >= INF) continue;
                        int uses = Math.max(leftUses, rightUses) + 1;
                        if (uses > MAX_USES) continue;
                        int total = opCost + penalty(leftUses) + penalty(rightUses);
                        if (total > MAX_LEVEL) continue;
                        int candidate = pileCost[a][leftUses] + pileCost[b][rightUses] + total;
                        if (candidate < pileCost[mask][uses]) {
                            pileCost[mask][uses] = candidate;
                            splitA[mask][uses] = a;
                            splitLeftUses[mask][uses] = leftUses;
                            splitRightUses[mask][uses] = rightUses;
                        }
                    }
                }
            }
        }

        // Stage 2: cheapest order to feed those groups to the equipment, starting from its real use count.
        int[][] applyCost = new int[full][MAX_USES + 1];
        int[][] groupOf = new int[full][MAX_USES + 1];
        int[][] groupUses = new int[full][MAX_USES + 1];
        // Secondary objective: among the cheapest orders, keep the enchantItem's final penalty as low as possible, so a
        // later top-up still has room under the anvil limit.
        int[][] endUses = new int[full][MAX_USES + 1];
        for (int mask = 0; mask < full; mask++) {
            java.util.Arrays.fill(applyCost[mask], INF);
            java.util.Arrays.fill(groupOf[mask], -1);
            java.util.Arrays.fill(endUses[mask], MAX_USES + 1);
        }
        for (int uses = 0; uses <= MAX_USES; uses++) { applyCost[0][uses] = 0; endUses[0][uses] = uses; }
        for (int mask = 1; mask < full; mask++) {
            for (int uses = 0; uses <= MAX_USES; uses++) {
                for (int group = mask; group > 0; group = (group - 1) & mask) {
                    int rest = mask ^ group;
                    for (int groupUsesValue = 0; groupUsesValue <= MAX_USES; groupUsesValue++) {
                        if (pileCost[group][groupUsesValue] >= INF) continue;
                        int opCost = subsetValue[group] + penalty(uses) + penalty(groupUsesValue);
                        if (opCost > MAX_LEVEL) continue;
                        int nextUses = Math.max(uses, groupUsesValue) + 1;
                        if (nextUses > MAX_USES) continue;
                        if (applyCost[rest][nextUses] >= INF) continue;
                        int candidate = pileCost[group][groupUsesValue] + opCost + applyCost[rest][nextUses];
                        boolean cheaper = candidate < applyCost[mask][uses];
                        boolean lighter = candidate == applyCost[mask][uses]
                            && endUses[rest][nextUses] < endUses[mask][uses];
                        if (cheaper || lighter) {
                            applyCost[mask][uses] = candidate;
                            groupOf[mask][uses] = group;
                            groupUses[mask][uses] = groupUsesValue;
                            endUses[mask][uses] = endUses[rest][nextUses];
                        }
                    }
                }
            }
        }
        if (applyCost[all][baseUses] >= INF) return null;

        List<EnchantPresetCatalog.Operation> operations = new ArrayList<>();
        EnchantExpressionParser.Node equipment = currentNode(itemId, current);
        int mask = all;
        int uses = baseUses;
        while (mask != 0) {
            int group = groupOf[mask][uses];
            int groupUsesValue = groupUses[mask][uses];
            if (group < 0) return null;
            EnchantExpressionParser.Node pile = emitPile(books, group, groupUsesValue,
                splitA, splitLeftUses, splitRightUses, subsetValue, operations);
            int opCost = subsetValue[group] + penalty(uses) + penalty(groupUsesValue);
            var merged = new EnchantExpressionParser.Merge(equipment, pile, opCost);
            operations.add(new EnchantPresetCatalog.Operation(equipment, pile, merged, opCost));
            equipment = merged;
            mask ^= group;
            uses = Math.max(uses, groupUsesValue) + 1;
        }
        return new Plan(List.copyOf(operations), List.copyOf(books), applyCost[all][baseUses], penalty(endUses[all][baseUses]));
    }

    /**
     * Emits the merges that build one group's stack and returns the stacked node. Children are emitted before their
     * merge, so the worker can execute the list front to back.
     */
    private static EnchantExpressionParser.Node emitPile(List<EnchantPresetCatalog.Enchant> books, int mask, int uses,
                                                  int[][] splitA, int[][] splitLeftUses,
                                                  int[][] splitRightUses, int[] subsetValue,
                                                  List<EnchantPresetCatalog.Operation> operations) {
        if (Integer.bitCount(mask) == 1) {
            int index = Integer.numberOfTrailingZeros(mask);
            EnchantPresetCatalog.Enchant book = books.get(index);
            return new EnchantExpressionParser.Leaf(book.id(), book.level());
        }
        int a = splitA[mask][uses];
        if (a < 0) throw new IllegalStateException("Missing split for a planned book merge");
        int b = mask ^ a;
        int leftUses = splitLeftUses[mask][uses];
        int rightUses = splitRightUses[mask][uses];
        var nodeA = emitPile(books, a, leftUses, splitA, splitLeftUses, splitRightUses, subsetValue, operations);
        var nodeB = emitPile(books, b, rightUses, splitA, splitLeftUses, splitRightUses, subsetValue, operations);
        // The sacrifice is charged for its enchantments; make the cheaper stack the one that gets consumed.
        boolean aIsSacrifice = subsetValue[a] <= subsetValue[b];
        var left = aIsSacrifice ? nodeB : nodeA;
        var right = aIsSacrifice ? nodeA : nodeB;
        int opCost = Math.min(subsetValue[a], subsetValue[b]) + penalty(leftUses) + penalty(rightUses);
        var merged = new EnchantExpressionParser.Merge(left, right, opCost);
        operations.add(new EnchantPresetCatalog.Operation(left, right, merged, opCost));
        return merged;
    }

    /**
     * Fallback for lists too long for the exact search: split the books into small balanced piles, then apply those
     * piles to the equipment largest value first, so the smallest value lands on the most expensive merge.
     */
    private static Plan chunkedPlan(String itemId, Map<String, Integer> current, int baseUses,
                                    List<EnchantPresetCatalog.Enchant> books) {
        List<EnchantPresetCatalog.Enchant> sorted = new ArrayList<>(books);
        sorted.sort((a, b) -> Integer.compare(valueOf(b), valueOf(a)));
        List<EnchantPresetCatalog.Operation> operations = new ArrayList<>();
        List<Pile> piles = new ArrayList<>();
        try {
            for (int start = 0; start < sorted.size(); start += FALLBACK_CHUNK) {
                piles.add(buildPile(sorted.subList(start, Math.min(start + FALLBACK_CHUNK, sorted.size())), operations));
            }
        } catch (IllegalStateException e) {
            return null;
        }
        int total = 0;
        int uses = baseUses;
        EnchantExpressionParser.Node equipment = currentNode(itemId, current);
        for (Pile pile : piles) {
            int opCost = pile.value() + penalty(uses) + penalty(pile.uses());
            if (opCost > MAX_LEVEL) return null;
            total += opCost;
            var merged = new EnchantExpressionParser.Merge(equipment, pile.node(), opCost);
            operations.add(new EnchantPresetCatalog.Operation(equipment, pile.node(), merged, opCost));
            equipment = merged;
            uses = Math.max(uses, pile.uses()) + 1;
        }
        return new Plan(List.copyOf(operations), List.copyOf(books), total, penalty(uses));
    }

    private record Pile(EnchantExpressionParser.Node node, int value, int uses) { }

    /** Balanced merge of a small book chunk: pairs at each level, every child emitted before its own merge. */
    private static Pile buildPile(List<EnchantPresetCatalog.Enchant> chunk,
                                  List<EnchantPresetCatalog.Operation> operations) {
        List<Pile> level = new ArrayList<>();
        for (EnchantPresetCatalog.Enchant book : chunk) {
            level.add(new Pile(new EnchantExpressionParser.Leaf(book.id(), book.level()), valueOf(book), 0));
        }
        while (level.size() > 1) {
            List<Pile> next = new ArrayList<>();
            for (int i = 0; i + 1 < level.size(); i += 2) {
                Pile a = level.get(i);
                Pile b = level.get(i + 1);
                Pile target = a.value() <= b.value() ? b : a;
                Pile sacrifice = target == a ? b : a;
                int opCost = sacrifice.value() + penalty(target.uses()) + penalty(sacrifice.uses());
                if (opCost > MAX_LEVEL) throw new IllegalStateException("Chunk does not fit the anvil limit");
                var merged = new EnchantExpressionParser.Merge(target.node(), sacrifice.node(), opCost);
                operations.add(new EnchantPresetCatalog.Operation(target.node(), sacrifice.node(), merged, opCost));
                next.add(new Pile(merged, a.value() + b.value(), Math.max(a.uses(), b.uses()) + 1));
            }
            if (level.size() % 2 == 1) next.add(level.getLast());
            level = next;
        }
        return level.getFirst();
    }

    private static int valueOf(EnchantPresetCatalog.Enchant book) {
        return book.level() * BOOK_MULTIPLIER.getOrDefault(EnchantPresetCatalog.shortId(book.id()), 2);
    }

    /**
     * The virtual tree the physical enchantItem stands for: the bare enchantItem plus every enchantment it already carries, so the
     * worker's exact-set matching can locate it in the inventory.
     */
    private static EnchantExpressionParser.Node currentNode(String itemId, Map<String, Integer> current) {
        EnchantExpressionParser.Node node = new EnchantExpressionParser.Leaf(
            EnchantPresetCatalog.shortId(itemId), 1);
        if (current == null) return node;
        for (var entry : new java.util.TreeMap<>(current).entrySet()) {
            node = new EnchantExpressionParser.Merge(node,
                new EnchantExpressionParser.Leaf(entry.getKey(), entry.getValue()), -1);
        }
        return node;
    }
}

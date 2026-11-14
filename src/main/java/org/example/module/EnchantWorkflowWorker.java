package org.example.module;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.RenameItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.player.World;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.discord.Embed;
import com.zenith.util.math.MathHelper;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.item.ContainerTypeInfoRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import org.example.AutoEnchantConfig;
import org.example.AutoEnchantPlugin;
import org.example.CustomEnchantPlanner;
import org.example.EnchantExpressionParser;
import org.example.EnchantPresetCatalog;
import org.example.module.AutoEnchantModule.State;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.zenith.Globals.*;

/** Tick-driven, single-enchantItem transaction runner. All progress is gated by server cache observations. */
final class EnchantWorkflowWorker {
    private enum Origin { NONE, INPUT_CHEST, INVENTORY }

    private final AutoEnchantModule module;
    private State state = State.ENTRYPOINT;
    private AutoEnchantConfig.Enchant target;
    private ItemStack itemSnapshot;
    private List<EnchantPresetCatalog.Operation> operations = List.of();
    private List<EnchantPresetCatalog.Enchant> books = List.of();
    private int operationIndex;
    private int bookIndex;
    private int activeContainerId;
    private int activeContainerTopSlots;
    private int sourceSlot = -1;
    private int playerSlot = -1;
    private long deadline;
    private long nextActionAt;
    private boolean windowSettlePending;
    private long windowSettledUntil;
    private RequestFuture inventoryFuture;
    private State afterInventoryState;
    private State afterCloseState;
    private PathingRequestFuture pathFuture;
    /** The OPEN_* state a failed or stale window returns to, mirroring the trader's chest states. */
    private State openState = State.ENTRYPOINT;
    private BlockPos openingPosition;
    private String failureReason;
    private boolean grindRequired;
    private int waitingCost;
    private BlockPos anvilPosition;
    private int anvilRecoveryAttempts;
    /** Consecutive failed opens of the same position; without it a vanished block turns into a per-tick loop. */
    private int openAttempts;
    private static final int MAX_OPEN_ATTEMPTS = 4;
    private Map<String, Integer> targetEnchants = Map.of();
    /** The wiki preset this target matches, or null when the target is a hand-written set. */
    private String activePreset;
    /** True while the enchantItem is only being renamed, i.e. no enchantment merge is left to perform. */
    private boolean renamePass;
    /** Set when an anvil step finds its inputs still unfetched, so closing it returns to the book phase. */
    private boolean backToBooks;
    /** Rename passes whose preview never showed the requested name; a cosmetic name is never worth blocking the item. */
    private int renameTries;
    /** Set when the server refuses to apply the name, so the item is unloaded as it is instead of retrying forever. */
    private boolean renameGivenUp;
    /** Guards the per-merge preview/inventory trace so a tick loop logs each step once, not every tick. */
    private boolean stepLogged;
    /** What the BOT is currently waiting on at a supply chest, so the same restock warning is not repeated. */
    private String supplyWarnedFor = "";
    private long supplyWarnedAt;
    /**
     * Containers that turned out to be out of stock, keyed by the resource they were supposed to hold (a book id and
     * level, a pickup chest position, a container role) and carrying the next time they may be looked at again.
     *
     * <p>An empty container never stops the workflow: the piece that needs it steps aside until the recheck, the
     * pickup branch runs again immediately, and every other chest, job and carried item keeps its turn. There is no
     * minute-long freeze - the first recheck is a couple of seconds away and the interval only doubles while the same
     * container keeps coming up empty.
     */
    private final Map<String, Stockout> stockouts = new LinkedHashMap<>();
    /** First recheck of an out-of-stock container; doubled per consecutive miss, capped at the maximum. */
    private static final long STOCKOUT_BASE_MS = 2_000L;
    private static final long STOCKOUT_MAX_MS = 30_000L;
    /** A stockout nobody reported for this long is forgotten, so the status line stays current. */
    private static final long STOCKOUT_EXPIRY_MS = 600_000L;
    /** How often the same restock warning may be repeated. */
    private static final long SUPPLY_WARN_INTERVAL_MS = 600_000L;
    /** True once the finished enchantItem left the BOT inventory, i.e. the output step really completed. */
    private boolean outputStored;
    /**
     * True while the BOT is not enchanting but only freeing bag space: a finished product that is still sitting in
     * the inventory is carried to the output container. Without this branch a full bag has no way out - every fetch
     * and every book withdrawal bounces off it and the item is retried forever instead of being unloaded.
     */
    private boolean flushing;
    /** Set once a flush fell back to the overflow container because the output container would not take the piece. */
    private boolean flushOverflow;
    /** Free player slots a transaction wants: the equipment piece itself, one book, and the anvil result. */
    private static final int FREE_SLOTS_FOR_TRANSACTION = 3;
    /**
     * Consecutive unload trips that did not free enough bag space. Borrowed from the trader's
     * {@code storeTripAttempts}: a bag that stays full after a few trips must not turn into an endless
     * open-close-open loop - the workflow warns once and continues with whatever room it has.
     */
    private int flushTrips;
    private static final int MAX_FLUSH_TRIPS = 4;
    /** Player slots the deposit click was built for; verification waits until every one of them is empty. */
    private List<Integer> depositSlots = List.of();
    /** Re-clicks of a deposit the chest only partly accepted, inside the same window (the trader allows two). */
    private int depositRetries;
    private static final int MAX_DEPOSIT_RETRIES = 2;
    /** Set once a close click was re-sent for the same window, so a lost echo is re-asked once, not forever. */
    private boolean closeResent;
    /**
     * Carried enchantItems paused by a supply gap (an empty book chest, a missing book marker), keyed like
     * {@code skipUntil}. The pause is per item on purpose: a missing book blocks the one piece that needs it, not the
     * whole workflow, so another job's carried item still gets its turn while a human restocks the chest.
     */
    private final Map<String, Long> supplyPausedUntil = new HashMap<>();
    /** Signature of the last dumped plan, so a minute-long supply gap does not re-print the same tree every retry. */
    private String lastPlanSignature = "";
    /** Position in the pickup-chest rotation, so an empty chest is skipped instead of retried forever. */
    private int inputCursor;
    private static final int MAX_ANVIL_RECOVERY = 3;
    /** The one genuine enchant failure: the server refuses the operation because its level cost is too high. */
    private static final int TOO_EXPENSIVE_LEVEL = 40;
    /** Consecutive transient retries of the same enchantItem before it is parked in the inventory for a while. */
    private static final int MAX_TRANSIENT_RETRIES = 5;
    /** Re-clicks of an anvil result slot whose pickup click was lost in flight. */
    private static final int MAX_TAKE_ATTEMPTS = 3;
    /** Human-readable reason for the current transient retry; never treated as an enchant failure. */
    private String retryReason;
    /** Consecutive transient retries; reset whenever a merge completes. */
    private int transientRetries;
    /** Pickup clicks issued for the current merge, so a lost click is re-sent instead of aborting the merge. */
    private int takeAttempts;
    /** Earliest time the BACKOFF state may hand control back, independent of the last submit deadline. */
    private long nextRetryAt;
    private EnchantPresetCatalog.Operation activeOperation;
    /** Where the current enchantItem came from: fetched items are reset once, carried items are never reset. */
    private Origin origin = Origin.NONE;
    private final Map<String, Integer> itemAttempts = new HashMap<>();
    private final Map<String, Long> skipUntil = new HashMap<>();
    /**
     * Equipment pieces still in the BOT inventory that were withdrawn from a restock chest as part of a batch,
     * keyed by item registry id. They are not interrupted carried work: the next transaction treats them like a
     * piece taken directly from the chest, including the grindstone decision. The counter is consumed per adoption.
     */
    private final Map<String, Integer> restockBatch = new HashMap<>();
    /** Item id and carried-stack count captured before a restock withdrawal, used to size the batch. */
    private int restockItemId = -1;
    private int restockBefore;
    private static final int MAX_ITEM_ATTEMPTS = 3;
    private static final long SKIP_COOLDOWN_MS = 300_000L;
    /** When the current enchantItem was picked up, for the Discord completion log. */
    private long transactionStartedAt;

    EnchantWorkflowWorker(AutoEnchantModule module) {
        this.module = module;
    }

    /**
     * True while an enchantItem is claimed, i.e. from the pickup until it is stored again. The BOT has to stand
     * still and keep its rotation on a container for all of it, which is what the module's combat pause keys off.
     */
    boolean busy() {
        return target != null && state != State.ENTRYPOINT && state != State.NEXT_ENCHANT;
    }

    void tick() {
        // Same idea as the reference plugin's "state A -> B" debug line: the whole transaction can be replayed from
        // the log, so a stuck item is diagnosed from the transitions instead of from the final failure alone.
        State before = state;
        try {
            tickStateMachine();
        } finally {
            if (state != before) trace("state {} -> {}", before, state);
        }
    }

    /** One iteration of the state machine; {@link #tick()} only wraps it with the transition trace. */
    private void tickStateMachine() {
        if (!AutoEnchantPlugin.PLUGIN_CONFIG.enabled) return;
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null || !player.isAlive() || Proxy.getInstance().isInQueue()) {
            // Same contract as the trader: a queued or dead client cannot own a window, so the
            // in-flight transaction is dropped instead of resuming against a stale screen.
            if (state != State.ENTRYPOINT) abort();
            module.setWorkflowStatus("Waiting for the BOT to be alive and out of the queue");
            return;
        }
        if (inventoryFuture != null) {
            if (!inventoryFuture.isCompleted()) {
                if (System.currentTimeMillis() > deadline) { inventoryFuture = null; fail("Inventory action timed out"); }
                return;
            }
            if (!inventoryFuture.isAccepted()) { inventoryFuture = null; fail("Inventory action was rejected"); return; }
            inventoryFuture = null;
            state = afterInventoryState;
            nextActionAt = System.currentTimeMillis() + Math.max(0, AutoEnchantPlugin.PLUGIN_CONFIG.operationDelay) * 50L;
            return;
        }
        if (System.currentTimeMillis() < nextActionAt) return;
        if (pathFuture != null) {
            int openedId = openContainerId();
            if (openedId != 0 && openedId != activeContainerId) {
                activeContainerId = openedId;
                pathFuture = null;
                openAttempts = 0;
                deadline = System.currentTimeMillis() + awaitIntactMs();
            } else if (!pathFuture.isCompleted()) {
                // Keep walking; the future resolves once the click was actually executed.
                return;
            } else if (!pathFuture.isAccepted() || System.currentTimeMillis() > deadline) {
                pathFuture = null;
                // No counter and no retry bookkeeping: the open state is simply entered again.
                reopenContainer("could not open " + openingPosition);
                return;
            } else return;
        }
        if (isWaitingForWindow()) {
            if (openContainerId() != activeContainerId || activeContainerId == 0) {
                if (System.currentTimeMillis() > deadline) reopenContainer("no usable container window");
                return;
            }
            // Let the server finish sending window contents before any slot is read or clicked.
            // Like the trader's playerSectionHasItems check, the player section carries data only
            // once the contents packet arrived; the timeout lets a genuinely empty inventory through.
            if (windowSettlePending) {
                windowSettlePending = false;
                windowSettledUntil = System.currentTimeMillis() + awaitIntactMs();
            }
            if (!windowContentsReady() && System.currentTimeMillis() < windowSettledUntil) return;
        }
        try {
            switch (state) {
                case ENTRYPOINT, NEXT_ENCHANT -> beginInput();
                case EVAL_RESTOCK -> evalRestock();
                case READY_NEXT_ENCHANT -> reset();
                case AWAIT_RESTOCK -> { if (System.currentTimeMillis() >= deadline) state = State.ENTRYPOINT; }
                case RESTOCK_INPUT_GO_TO_CHEST, RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST, GRINDSTONE_GO_TO_CHEST, ENCHANT_INTERACT_WITH_ANVIL, STORE_GO_TO_CHEST, RESTOCK_ANVIL_GO_TO_CHEST, POST_ENCHANT_INPUT_GO_TO, POST_ENCHANT_OVERFLOW_GO_TO -> beginOpen();
                case RESTOCK_INPUT_WITHDRAW -> handleInputWindow();
                case RESTOCK_INPUT_TAKE -> takeInput();
                case RESTOCK_INPUT_AWAIT_WITHDRAW -> verifyInputTransfer();
                case CLOSE_INPUT -> closeContainer(failureReason != null ? State.POST_ENCHANT : grindRequired ? State.EVAL_UNCHANT : State.NEXT_BOOK);
                case EVAL_UNCHANT -> prepareGrindstone();
                case GRINDSTONE_AWAIT_INTERACT -> handleGrindstoneWindow();
                case GRINDSTONE_INSERT -> insertIntoGrindstone();
                case GRINDSTONE_AWAIT_RESULT -> verifyGrindstonePreview();
                case GRINDSTONE_TAKE -> takeGrindstoneResult();
                case GRINDSTONE_AWAIT_TAKE -> verifyGrindstoneResult();
                case GRINDSTONE_CLOSE -> closeContainer(State.NEXT_BOOK);
                case NEXT_BOOK -> nextBook();
                case RESTOCK_ENCHANTED_BOOK_WITHDRAW -> handleBookWindow();
                case RESTOCK_ENCHANTED_BOOK_TAKE -> takeBook();
                case RESTOCK_ENCHANTED_BOOK__AWAIT_WITHDRAW -> verifyBookTransfer();
                case RESTOCK_ENCHANTED_BOOK_CLOSE -> closeContainer(State.NEXT_BOOK);
                case ENCHANT_AWAIT_INTERACT_WITH_ANVIL -> handleAnvilWindow();
                case ENCHANT_TRY_START -> placeAnvilInputs();
                case PLACE_RENAME -> placeRenameInput();
                case AWAIT_ENCHANT -> verifyAnvilPreview();
                case CLOSE_FOR_XP -> closeContainer(State.WAIT_XP);
                case WAIT_XP -> waitForExperience();
                case TAKE_RESULT -> takeAnvilResult();
                case VERIFY_RESULT -> verifyAnvilResult();
                case CLOSE_ANVIL -> closeContainer(failureReason != null ? State.POST_ENCHANT
                    : backToBooks ? State.NEXT_BOOK
                    : operationIndex >= operations.size()
                        ? (needsRename() ? State.NEXT_BOOK : State.POST_ENCHANT)
                        // A merge whose books are still in a chest is fetched first: opening the anvil for it only
                        // produces the "input not found" round trip. Everything else re-checks the station, because
                        // the anvil can break between two merges.
                        : (bookIndex < books.size() && !inputsReady()) ? State.NEXT_BOOK : State.CHECK_ANVIL);
                case STORE_AWAIT_INTERACT, POST_ENCHANT_INPUT_AWAIT_INTERACT, POST_ENCHANT_OVERFLOW_AWAIT_INTERACT -> handleOutputWindow();
                case STORE_DEPOSIT, POST_ENCHANT_INPUT_DEPOSIT, POST_ENCHANT_OVERFLOW_DEPOSIT -> unloadItem();
                case STORE_AWAIT_DEPOSIT, POST_ENCHANT_INPUT_AWAIT_DEPOSIT, POST_ENCHANT_OVERFLOW_AWAIT_DEPOSIT -> verifyUnloaded();
                case CLOSE_OUTPUT -> closeOutput();
                case DROP_ITEM -> dropCurrentItem();
                case BACKOFF -> { if (System.currentTimeMillis() >= Math.max(deadline, nextRetryAt)) state = State.READY_NEXT_ENCHANT; }
                case WAIT_CLOSE -> waitForClose();
                case POST_ENCHANT -> beginOutput();
                case CHECK_ANVIL -> checkAnvil();
                case RESTOCK_ANVIL_WITHDRAW -> handleSupplyWindow();
                case RESTOCK_ANVIL_TAKE -> takeSupplyAnvils();
                case RESTOCK_ANVIL_AWAIT_WITHDRAW -> verifySupplyTransfer();
                case CLOSE_SUPPLY -> closeContainer(State.CRAFT_ANVIL_BLOCKS);
                case CRAFT_ANVIL_BLOCKS -> placeAnvil();
                case AWAIT_CRAFT_ANVIL_BLOCKS -> waitForPlacement();
                default -> { }
            }
        } catch (Exception e) {
            fail("Transaction error: " + e.getMessage());
        }
    }

    private void beginInput() {
        List<AutoEnchantConfig.Enchant> runnable = module.runnableJobs();
        if (runnable.isEmpty()) {
            module.setWorkflowStatus(module.configProblems().isEmpty()
                ? "No enabled job - light the lamp above a frame, or run 'autoEnchant add <id> <item> <inputChest> <outputChest>'"
                : "Waiting for a valid job configuration: " + module.configProblems().getFirst());
            return;
        }
        if (openContainerId() != 0) {
            // A command reset, another module or the user can leave a window open; the trader
            // funnels through awaitContainerClosed before every chest, so do the same here.
            module.setWorkflowStatus("Closing an open container before the next transaction");
            closeContainer(State.ENTRYPOINT);
            return;
        }
        // A target enchantItem already in the BOT inventory is an interrupted transaction, not a fresh fetch: it is resumed
        // as-is and never reset, so work the BOT already paid for is kept.
        int carried = findCarriedEquipment();
        if (carried >= 0) { adoptCarried(carried); return; }
        // Nothing to resume because every carried piece is waiting for a restock: fetching a fresh item now would only
        // put a second half-finished piece in the bag while the first one still waits for the same book. Only jobs with
        // a supply gap are held back though - a second job that is not waiting for anything keeps working.
        var supplyBlocked = supplyBlockedJobs();
        List<AutoEnchantConfig.Enchant> fetchable = supplyBlocked.isEmpty() ? runnable
            : runnable.stream().filter(job -> !supplyBlocked.contains(job)).toList();
        if (fetchable.isEmpty()) {
            String gap = supplyPauseSummary();
            module.setWorkflowStatus(gap != null ? gap : "Waiting for a restock before the next enchantItem");
            state = State.AWAIT_RESTOCK;
            deadline = nextStockoutCheckAt();
            return;
        }
        // Nothing of ours to resume, but the bag is too full to fetch another piece: unload finished products that are
        // still waiting in the inventory (the output container was full or its marker was missing at the time) so the
        // next cycle has room again. A full bag used to dead-end here: the withdraw click silently did nothing and the
        // piece was retried forever, because the only unload path was the end of a successful transaction.
        //
        // Same split as the trader's EVAL_STORE: no free slot at all is a hard stop, while a bag that is merely
        // comfortable-but-tight is worked with instead of stalled, so a single free slot never blocks the queue.
        int freeSlots = freeInventorySlots();
        if (freeSlots < 1) {
            if (flushFinishedProduct("no free slot left in the bag")) return;
            // Nothing unloadable is left in the bag. Opening the pickup chest would only bounce the withdraw click off
            // a full inventory, so the bag is reported as the thing that is out of stock and the loop keeps cycling
            // (other jobs included) instead of standing still.
            if (isOutOfStock("bag")) {
                module.setWorkflowStatus(idleStatus());
                state = State.AWAIT_RESTOCK;
                deadline = nextStockoutCheckAt();
                return;
            }
            reportOutOfStock("bag", "bag space for the next enchantItem (the bag is full and no finished product is "
                + "left to unload)");
            return;
        }
        clearStockouts("bag");
        if (freeSlots < FREE_SLOTS_FOR_TRANSACTION && flushFinishedProduct("only " + freeSlots + " free slot(s) left")) return;
        if (module.stations().getOrDefault("anvil", List.of()).isEmpty()) {
            module.setWorkflowStatus("Waiting for a loaded anvil");
            state = State.AWAIT_RESTOCK;
            deadline = System.currentTimeMillis() + Math.max(500L, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay * 50L);
            return;
        }
        List<BlockPos> chests = inputChests(fetchable);
        if (chests.isEmpty()) {
            module.setWorkflowStatus("Enabled jobs have no reachable input chest");
            state = State.AWAIT_RESTOCK;
            deadline = System.currentTimeMillis() + Math.max(500L, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay * 50L);
            return;
        }
        // Chests that were just found empty wait for their recheck; the rotation continues with the others instead of
        // reopening the same empty container, and only when every chest is out of stock does the workflow idle.
        List<BlockPos> inStock = chests.stream().filter(pos -> !isOutOfStock("input@" + pos)).toList();
        if (inStock.isEmpty()) {
            module.setWorkflowStatus(idleStatus());
            state = State.AWAIT_RESTOCK;
            deadline = nextStockoutCheckAt();
            return;
        }
        origin = Origin.INPUT_CHEST;
        // Rotate through the pickup chests so one empty chest never starves the other jobs.
        openingPosition = inStock.get(Math.floorMod(inputCursor, inStock.size()));
        activeContainerId = 0;
        state = State.EVAL_RESTOCK;
    }

    /** Confirms the pickup chest chosen by the rotation is still there before walking to it. */
    private void evalRestock() {
        if (openingPosition == null) { state = State.ENTRYPOINT; return; }
        state = State.RESTOCK_INPUT_GO_TO_CHEST;
    }

    /** Distinct pickup chests of the runnable jobs, in job order; the rotation visits each one in turn. */
    private List<BlockPos> inputChests(List<AutoEnchantConfig.Enchant> runnable) {
        List<BlockPos> chests = new ArrayList<>();
        for (AutoEnchantConfig.Enchant job : runnable) {
            module.containerPosition(job, "input").ifPresent(pos -> { if (!chests.contains(pos)) chests.add(pos); });
        }
        return chests;
    }

    /**
     * Finds the carried enchantItem that belongs to an enabled job, skipping items that just failed repeatedly. The
     * most advanced piece wins: with several half-done swords in the bag, resuming the one closest to the target
     * spends the fewest levels, and the barely-started ones wait their turn instead of being picked first forever.
     */
    private int findCarriedEquipment() {
        int bestSlot = -1;
        int bestScore = -1;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            if (module.runnableJobFor(stack).isEmpty()) continue;
            if (isCoolingDown(stack)) continue;
            // A piece waiting for a book that is out of stock steps aside for other carried pieces instead of
            // monopolising the loop; it comes back in by itself once the pause runs out.
            if (isSupplyPaused(stack)) continue;
            int score = progressScore(stack);
            if (score > bestScore) { bestScore = score; bestSlot = slot; }
        }
        return bestSlot;
    }

    /** Rough "how far along the merge tree" rank: enchantments carried, renamed pieces one step ahead. */
    private int progressScore(ItemStack stack) {
        int enchants = module.catalog().enchantments(stack).size();
        boolean named = !module.catalog().customName(stack).isEmpty();
        return enchants * 2 + (named ? 1 : 0);
    }

    /** Resumes a carried enchantItem without any grindstone pass; only the missing books are applied. */
    private void adoptCarried(int slot) {
        ItemStack stack = playerItem(slot);
        var match = module.runnableJobFor(stack);
        if (match.isEmpty()) { state = State.ENTRYPOINT; return; }
        // A piece withdrawn together with the previous transaction is still a fresh chest item, so it gets the
        // same reset decision instead of being resumed as if the BOT had already worked on it.
        boolean fromRestock = consumeRestockBatch(stack);
        origin = fromRestock ? Origin.INPUT_CHEST : Origin.INVENTORY;
        playerSlot = slot;
        sourceSlot = -1;
        itemSnapshot = stack.clone();
        target = match.get();
        transactionStartedAt = System.currentTimeMillis();
        grindRequired = false;
        if (fromRestock && module.catalog().hasCurse(stack)) {
            failureReason = "Cursed enchantItem skipped";
            state = State.POST_ENCHANT;
            return;
        }
        preparePlan(stack);
        if (failureReason != null) { trace("plan failed: {}", failureReason); markAttempt(); state = State.POST_ENCHANT; return; }
        trace("resumed carried slot {}: {} (fromRestock={})", slot, module.describeItem(stack), fromRestock);
        // The full preset tree; the effective (resumed) plan is dumped by applyResumePlan once the item's own
        // enchantments and the carried intermediates have been taken into account.
        trace("preset {} tree: {} book(s), {} merge(s)", activePreset, books.size(), operations.size());
        if (fromRestock) {
            decideOnHandoff(stack);
            if (!grindRequired && failureReason == null && !operations.isEmpty()
                && !applyResumePlan(module.catalog().enchantments(stack))) {
                // Same rule as for a carried piece: a half-done item taken out of a supply chest is kept and retried,
                // not put back the moment one merge order does not line up.
                parkCarried(failureReason == null ? "no merge order fits the fetched set" : failureReason);
                return;
            }
            state = grindRequired ? State.EVAL_UNCHANT : State.NEXT_BOOK;
            return;
        }
        Map<String, Integer> current = module.catalog().enchantments(stack);
        trace("carried enchantments: {} | target: {}", describeEnchants(current), describeEnchants(targetEnchants));
        if (current.isEmpty()) {
            module.setWorkflowStatus("Carried enchantItem is blank; enchanting without fetching a new one");
            state = State.NEXT_BOOK;
            return;
        }
        if (current.equals(targetEnchants)) {
            operations = List.of();
            books = List.of();
            module.setWorkflowStatus("Carried enchantItem already matches the plan; unloading");
            state = State.NEXT_BOOK;
            return;
        }
        if (!withinPlan(current)) {
            failureReason = "Carried enchantItem has enchantments outside the target";
            markAttempt();
            module.setWorkflowStatus("Carried enchantItem does not match the target; sending it to the output container");
            state = State.POST_ENCHANT;
            return;
        }
        if (!applyResumePlan(current)) {
            // A carried piece is work the BOT already paid for: unless it is genuinely too expensive to touch again,
            // it stays in the bag and cools down for a while instead of being dropped into the overflow container.
            // Only a piece that came out of a supply chest (another job's property) is unloaded on a mismatch.
            parkCarried(failureReason == null ? "not a node of the preset tree" : failureReason);
            return;
        }
        state = State.NEXT_BOOK;
    }

    /** True when every enchantment the enchantItem carries is part of the plan and no higher than the planned level. */
    private boolean withinPlan(Map<String, Integer> current) {
        for (var entry : current.entrySet()) {
            Integer wanted = targetEnchants.get(EnchantPresetCatalog.shortId(entry.getKey()));
            if (wanted == null || entry.getValue() > wanted) return false;
        }
        return true;
    }

    private void beginOpen() {
        openState = state;
        if (openingPosition == null) { fail("No container/workstation position to open"); return; }
        if (openContainerId() != 0) {
            // The trader funnels every chest interaction through awaitContainerClosed: never stack
            // a station screen on a window that is already open, close it and retry OPEN_*.
            module.setWorkflowStatus("Closing an open container before opening " + openingPosition);
            closeContainer(openState);
            return;
        }
        activeContainerId = 0;
        deadline = System.currentTimeMillis() + awaitIntactMs();
        windowSettlePending = true;
        pathFuture = BARITONE.rightClickBlock(openingPosition.x(), openingPosition.y(), openingPosition.z());
        // As in the trader, the window wait restarts when the click actually ran, not when the path did.
        pathFuture.addExecutedListener(f -> deadline = System.currentTimeMillis() + awaitIntactMs());
        state = switch (state) {
            case RESTOCK_INPUT_GO_TO_CHEST -> State.RESTOCK_INPUT_WITHDRAW;
            case RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST -> State.RESTOCK_ENCHANTED_BOOK_WITHDRAW;
            case GRINDSTONE_GO_TO_CHEST -> State.GRINDSTONE_AWAIT_INTERACT;
            case ENCHANT_INTERACT_WITH_ANVIL -> State.ENCHANT_AWAIT_INTERACT_WITH_ANVIL;
            case STORE_GO_TO_CHEST -> State.STORE_AWAIT_INTERACT;
            case POST_ENCHANT_INPUT_GO_TO -> State.POST_ENCHANT_INPUT_AWAIT_INTERACT;
            case POST_ENCHANT_OVERFLOW_GO_TO -> State.POST_ENCHANT_OVERFLOW_AWAIT_INTERACT;
            case RESTOCK_ANVIL_GO_TO_CHEST -> State.RESTOCK_ANVIL_WITHDRAW;
            default -> state;
        };
    }

    private void handleInputWindow() {
        if (!isStorageWindow()) { fail("Input position did not open a storage container"); return; }
        int top = topSlots();
        int selected = -1;
        ItemStack chosen = null;
        AutoEnchantConfig.Enchant chosenTarget = null;
        // A job whose carried piece waits for a book is skipped: fetching another piece of it would just stack up
        // half-finished items behind the same empty chest.
        var supplyBlocked = supplyBlockedJobs();
        for (int slot = 0; slot < top; slot++) {
            ItemStack stack = openContainer().getItemStack(slot);
            Optional<AutoEnchantConfig.Enchant> match = module.runnableJobFor(stack);
            if (match.isEmpty() || supplyBlocked.contains(match.get())) continue;
            selected = slot; chosen = stack; chosenTarget = match.get();
            break;
        }
        if (selected < 0) {
            // Nothing this chest can serve. The rotation moves on at once so the next chest (and the next job) still
            // gets its turn; only this chest is marked out of stock, and even that is revisited within seconds.
            inputCursor++;
            reportOutOfStock("input@" + openingPosition,
                "an enchantItem for an enabled job in the pickup chest at " + openingPosition);
            return;
        }
        clearStockouts("input@");
        // Stay on the chest that just worked instead of re-probing the empty ones in front of it.
        int workedAt = inputChests(module.runnableJobs()).indexOf(openingPosition);
        if (workedAt > 0) inputCursor = workedAt;
        sourceSlot = selected;
        origin = Origin.INPUT_CHEST;
        itemSnapshot = chosen.clone();
        target = chosenTarget;
        transactionStartedAt = System.currentTimeMillis();
        module.setWorkflowStatus("Fetched " + module.describeItem(chosen) + " for job " + target.displayName());
        trace("fetched from slot {}: {} (job {}, penalty {})", selected, module.describeItem(chosen),
            target.displayName(), module.catalog().priorWorkPenalty(chosen));
        if (module.catalog().hasCurse(chosen)) {
            failureReason = "Cursed enchantItem skipped";
            state = State.RESTOCK_INPUT_TAKE;
            return;
        }
        preparePlan(chosen);
        if (failureReason != null) { trace("plan failed: {}", failureReason); state = State.RESTOCK_INPUT_TAKE; return; }
        trace("item enchantments on pickup: {}", describeEnchants(module.catalog().enchantments(chosen)));
        tracePlan();
        decideOnHandoff(chosen);
        trace("handoff: grindRequired={} current={}", grindRequired, describeEnchants(module.catalog().enchantments(chosen)));
        // An empty plan here means the enchantItem already matches the target; it is unloaded untouched (or renamed only).
        if (!grindRequired && failureReason == null && !operations.isEmpty()) {
            if (!applyResumePlan(module.catalog().enchantments(chosen))) markAttempt();
        }
        state = State.RESTOCK_INPUT_TAKE;
    }

    /**
     * The single reset decision, made once per item fetched from the equipment input container. Nothing else in the
     * transaction may decide to clean an item: book-chest pickups and the enchanting steps never enter the grindstone
     * flow, and an item taken from the BOT inventory keeps the work already paid for.
     *
     * <p>A blank item is enchanted as is; an item carrying enchantments the target does not want is ground blank
     * exactly once when grindstone processing is enabled; an item that already equals the target is unloaded untouched.
     */
    private void decideOnHandoff(ItemStack item) {
        grindRequired = false;
        if (origin != Origin.INPUT_CHEST) return;
        Map<String, Integer> current = module.catalog().enchantments(item);
        if (current.isEmpty()) {
            module.setWorkflowStatus("Fetched a blank item; enchanting from scratch");
            return;
        }
        if (current.equals(targetEnchants)) {
            operations = List.of();
            books = List.of();
            module.setWorkflowStatus("Fetched item already matches the target; unloading unchanged");
            return;
        }
        if (!withinPlan(current)) {
            if (!AutoEnchantPlugin.PLUGIN_CONFIG.allowGrindstone) {
                module.setWorkflowStatus("Fetched item has extra enchantments but grindstone processing is disabled; continuing without a reset");
                return;
            }
            grindRequired = true;
            module.setWorkflowStatus("Fetched an enchanted item; resetting on the grindstone once");
        }
    }

    /** Reads the configured target set into the short-id form the catalog and planner use. */
    private void deriveTargetEnchants() {
        targetEnchants = java.util.Collections.unmodifiableMap(targetEnchantsOf(target));
    }

    /**
     * Builds the plan for the configured target. A set that exactly matches a wiki preset's final enchantments uses
     * that preset's optimised merge tree; any other set is planned by {@link CustomEnchantPlanner}. The mode is derived
     * here and never stored in the config.
     */
    private void preparePlan(ItemStack item) {
        try {
            deriveTargetEnchants();
            operations = List.of();
            books = List.of();
            activePreset = targetEnchants.isEmpty() ? null : module.catalog().matchPreset(target.item, targetEnchants);
            if (targetEnchants.isEmpty()) return;
            if (activePreset != null) {
                operations = module.catalog().operations(activePreset, fullItemId(target.item));
                books = module.catalog().requiredBooks(activePreset);
                if (operations.isEmpty()) failureReason = "Target has no enchant operations";
                return;
            }
            List<EnchantPresetCatalog.Enchant> wanted = customEnchantList();
            if (wanted.isEmpty()) { failureReason = "Target has no enchantments"; return; }
            var plan = CustomEnchantPlanner.plan(target.item, Map.of(), 0, wanted);
            if (plan == null) {
                failureReason = "Target set cannot be applied within the 39-level anvil limit";
                return;
            }
            operations = plan.operations();
            books = plan.books();
        } catch (Exception e) {
            failureReason = "Could not build plan: " + e.getMessage();
        }
    }

    /** The target's enchantment list, in the order the operator entered it. */
    private List<EnchantPresetCatalog.Enchant> customEnchantList() {
        List<EnchantPresetCatalog.Enchant> wanted = new ArrayList<>();
        targetEnchants.forEach((id, level) -> wanted.add(new EnchantPresetCatalog.Enchant(id, level)));
        return wanted;
    }

    /**
     * Re-plans a partially enchanted item.
     *
     * <p>A target that matches a wiki preset follows that preset's optimised merge tree: every finished branch is
     * reused and only the missing branches are built, so the anvil costs stay those of the guide. When the carried set
     * is no node of that tree (a hand-mixed intermediate), the same target is planned again from the live set with the
     * general planner instead of being unloaded: the piece already paid for its enchantments, and the planner also
     * merges book pairs before touching the equipment, which keeps the prior-work penalty low.
     *
     * @return false when the item must be unloaded instead of enchanted
     */
    private boolean applyResumePlan(Map<String, Integer> current) {
        if (current.isEmpty() || targetEnchants.isEmpty()) return true;
        int penalty = itemSnapshot == null ? 0 : module.catalog().priorWorkPenalty(itemSnapshot);
        if (activePreset != null) {
            var plan = module.catalog().resumePlan(activePreset, current, availableEnchantSets());
            if (plan == null) {
                trace("resume plan: carried {} is not a node of preset {}; planning from the live set",
                    describeEnchants(current), activePreset);
                var fallback = CustomEnchantPlanner.plan(target.item, current, penalty, customEnchantList());
                if (fallback == null) {
                    failureReason = "Item does not align with the wiki merge tree and fits no order under 39 levels";
                    module.setWorkflowStatus("Item cannot be enchanted any further; sending it to the output container");
                    return false;
                }
                return adoptPlan(fallback, current, "general planner (carried set is no tree node)");
            }
            operations = plan.operations();
            books = plan.books();
            if (operations.isEmpty()) {
                module.setWorkflowStatus("Item already matches the plan; unloading");
                return true;
            }
            module.setWorkflowStatus("Resuming on the wiki merge tree: keeping " + current.size()
                + " enchantment(s), " + books.size() + " book(s) and " + operations.size() + " merge(s) left");
            tracePlan();
            return true;
        }
        var plan = CustomEnchantPlanner.plan(target.item, current, penalty, customEnchantList());
        if (plan == null) {
            failureReason = "No custom merge order fits the 39-level limit from penalty " + penalty;
            module.setWorkflowStatus("Custom set has no ordering under the anvil limit; sending it to the output container");
            return false;
        }
        return adoptPlan(plan, current, "custom merge tree");
    }

    /** Applies a non-preset plan to the current transaction and reports it in the status line. */
    private boolean adoptPlan(CustomEnchantPlanner.Plan plan, Map<String, Integer> current, String kind) {
        operations = plan.operations();
        books = plan.books();
        if (operations.isEmpty()) {
            module.setWorkflowStatus("Item already matches the plan; unloading");
            return true;
        }
        module.setWorkflowStatus("Resuming on the " + kind + ": keeping " + current.size()
            + " enchantment(s), " + books.size() + " book(s), " + operations.size() + " merge(s), about "
            + plan.predictedLevels() + " levels");
        tracePlan();
        return true;
    }

    /**
     * Enchantment sets of the carried enchanted books only. Book branches of the merge tree may be reused from the
     * inventory, but equipment stacks must not: another sword that happens to carry the same enchantments is a
     * separate piece, not an intermediate this item can consume. Including them made a partially enchanted sword match
     * a larger tree node, after which the whole piece was reported as "not aligned with the merge tree".
     */
    private List<Map<String, Integer>> availableEnchantSets() {
        List<Map<String, Integer>> sets = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != ItemRegistry.ENCHANTED_BOOK.id()) continue;
            // An enchanted book keeps its enchantments in STORED_ENCHANTMENTS, not ENCHANTMENTS. Reading only the
            // latter made every carried book look blank, so a merged intermediate such as (sweeping + unbreaking)
            // was never reused and the worker fetched the same books again and again.
            Map<String, Integer> stored = readEnchants(stack, true);
            if (!stored.isEmpty()) sets.add(stored);
        }
        return sets;
    }

    /**
     * Withdraws the current equipment piece plus, when the job asks for it, more pieces of the
     * same equipment from the same chest in one inventory request. {@code inputItemRestockStacks}
     * is the number of pieces taken per restock, so the following pieces are processed straight
     * from the BOT inventory without reopening this chest.
     */
    private void takeInput() {
        // Never withdraw more pieces than the bag can take: the extra stacks of a batch are dropped on the ground by
        // the server when the inventory fills up, which silently loses equipment.
        int free = freeInventorySlots();
        if (free < 1) {
            if (flushFinishedProduct("no free slot for the withdrawal")) return;
            reportOutOfStock("bag", "bag space to take the withdrawal from the pickup chest");
            return;
        }
        int wanted = Math.max(1, Math.min(target.inputItemRestockStacks, free));
        restockItemId = configuredItemId();
        restockBefore = countCarriedById(restockItemId);
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = new ArrayList<>(
            InventoryActionMacros.withdraw(openContainerId(), stack -> stack.getId() == configuredItemId(), wanted));
        if (actions.isEmpty()) {
            restockItemId = -1;
            restockBefore = 0;
            actions.add(new ShiftClick(openContainerId(), sourceSlot, ShiftClickItemAction.LEFT_CLICK));
        }
        module.setWorkflowStatus("Withdrawing " + actions.size() + " equipment piece(s) for job " + target.displayName());
        submit(actions, State.RESTOCK_INPUT_AWAIT_WITHDRAW);
    }

    private void verifyInputTransfer() {
        playerSlot = findPlayerItem(itemSnapshot);
        if (playerSlot < 0) {
            if (System.currentTimeMillis() > deadline) {
                // A full bag is the reason the shift-click silently did nothing: free a slot instead of retrying the
                // same withdrawal. The unarrived piece is simply left in the chest.
                if (!hasFreeInventorySlot() && flushFinishedProduct("the withdrawal did not fit in the bag")) return;
                fail("Input enchantItem was not transferred to BOT inventory");
            }
            return;
        }
        // Record the extra pieces of the batch before any failure handling: they are in the inventory now and
        // must keep their freshly-fetched treatment even when this first piece turns out cursed or unplannable.
        recordRestockBatch();
        if (failureReason != null) { state = State.CLOSE_INPUT; return; }
        state = State.CLOSE_INPUT;
    }

    /** Sizes the restock batch from the inventory difference the withdrawal actually produced. */
    private void recordRestockBatch() {
        if (restockItemId < 0) return;
        int withdrawn = countCarriedById(restockItemId) - restockBefore;
        int extra = withdrawn - 1; // the first piece is the current transaction
        String key = Integer.toString(restockItemId);
        if (extra > 0) restockBatch.put(key, extra); else restockBatch.remove(key);
        restockItemId = -1;
        restockBefore = 0;
    }

    /** Consumes one batch marker for this stack; true when it was withdrawn as part of a restock batch. */
    private boolean consumeRestockBatch(ItemStack stack) {
        if (stack == null) return false;
        String key = Integer.toString(stack.getId());
        Integer remaining = restockBatch.get(key);
        if (remaining == null || remaining <= 0) return false;
        if (remaining == 1) restockBatch.remove(key); else restockBatch.put(key, remaining - 1);
        return true;
    }

    private void handleGrindstoneWindow() {
        if (openContainer().getType() != ContainerType.GRINDSTONE) { fail("Configured grindstone position did not open a grindstone"); return; }
        state = State.GRINDSTONE_INSERT;
    }

    private void prepareGrindstone() {
        // Hard guard: the grindstone belongs to the equipment pickup flow only. Book pickups, anvil merges and any
        // other step must never reset an enchantItem, and a carried enchantItem is resumed as-is.
        if (origin != Origin.INPUT_CHEST) {
            grindRequired = false;
            module.setWorkflowStatus("Reset skipped: only items taken from the equipment input chest are cleaned");
            state = State.NEXT_BOOK;
            return;
        }
        BlockPos configured = AutoEnchantPlugin.PLUGIN_CONFIG.grindstone;
        if (configured == null || BlockPos.ZERO.equals(configured)) {
            keepAndRetry("Grindstone is required but not configured");
            return;
        }
        openingPosition = configured;
        state = State.GRINDSTONE_GO_TO_CHEST;
    }

    private void insertIntoGrindstone() {
        playerSlot = findPlayerItem(itemSnapshot);
        if (playerSlot < 0) { fail("Cannot find current equipment before grindstone"); return; }
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = clickPlayerItemIntoStation(playerSlot, topSlots(), 0);
        submit(actions, State.GRINDSTONE_AWAIT_RESULT);
    }

    private void verifyGrindstonePreview() {
        if (openContainer().getItemStack(2) != Container.EMPTY_STACK) { state = State.GRINDSTONE_TAKE; return; }
        if (System.currentTimeMillis() > deadline) fail("Grindstone produced no output");
    }

    private void takeGrindstoneResult() {
        if (openContainer().getItemStack(2) == Container.EMPTY_STACK) { fail("Grindstone output disappeared"); return; }
        submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.GRINDSTONE_AWAIT_TAKE);
    }

    private void verifyGrindstoneResult() {
        playerSlot = findPlayerItemById(itemSnapshot.getId());
        if (playerSlot < 0) {
            if (System.currentTimeMillis() > deadline) fail("Grindstone output not received");
            return;
        }
        ItemStack cleaned = playerItem(playerSlot);
        if (cleaned != null && !module.catalog().enchantments(cleaned).isEmpty()) { fail("Grindstone output still has enchantments"); return; }
        itemSnapshot = cleaned.clone();
        state = State.GRINDSTONE_CLOSE;
    }

    /**
     * Records that a container (or the bag) had nothing to give, and keeps the workflow moving instead of freezing it.
     *
     * <p>An out-of-stock chest is not the BOT's failure: the item keeps every bit of progress it has, nothing is
     * counted as a retry (so nothing escalates towards cooling the item down or dumping it out) and nothing is dropped.
     * The difference to the old behaviour is that the workflow is <b>not</b> parked for a minute: the empty container is
     * remembered with a short recheck, the piece that needs it steps aside until then, and control goes straight back
     * to the pickup branch, where every other chest, job and carried item still gets its turn. When nothing else is
     * left the status line names exactly what is missing and when it will be looked at again.
     *
     * @param key  stable identity of the missing resource (book id+level, chest position, container role)
     * @param what human readable description, phrased to follow "out of stock: ..."
     * @return when this resource will be looked at again
     */
    private long reportOutOfStock(String key, String what) {
        return reportOutOfStock(key, what, null);
    }

    /**
     * Same as {@link #reportOutOfStock(String, String)}, additionally parking every finished product of one job until
     * the recheck. A full unload container refuses all of that job's products, so retrying them one by one would only
     * reopen the same chest once per piece.
     */
    private long reportOutOfStock(String key, String what, AutoEnchantConfig.Enchant parkingJob) {
        long now = System.currentTimeMillis();
        Stockout gap = stockouts.computeIfAbsent(key, k -> new Stockout());
        gap.what = what;
        gap.misses++;
        gap.lastSeenAt = now;
        gap.nextCheckAt = now + stockoutRecheckMs(gap.misses);
        long wait = Math.max(1, (gap.nextCheckAt - now) / 1000);
        // Only the piece that needs the missing thing steps aside, and only until the recheck: work the BOT can do
        // right now is never held back by a chest a human still has to fill.
        pauseForSupply(itemSnapshot, what, gap.nextCheckAt);
        pauseJobProducts(parkingJob, gap.nextCheckAt);
        trace("out of stock ({}): {} | recheck in {}s (miss {})", key, what, wait, gap.misses);
        module.setWorkflowStatus("Out of stock: " + what + " - looking again in " + wait + "s; other work continues");
        if (!key.equals(supplyWarnedFor) || now - supplyWarnedAt > SUPPLY_WARN_INTERVAL_MS) {
            supplyWarnedFor = key;
            supplyWarnedAt = now;
            module.warn("Auto-enchant is out of stock: {}; the bot keeps cycling the pickup branch and looks again in {}s",
                what, wait);
        }
        // No long back-off: the interrupted transaction is dropped (the piece stays in the bag), the window is closed
        // and the pickup branch runs again at once. That re-entry is what lets another job, another chest or another
        // carried item continue while this container is empty.
        clearTransaction();
        deadline = gap.nextCheckAt;
        nextRetryAt = gap.nextCheckAt;
        state = State.ENTRYPOINT;
        if (openContainerId() != 0) closeContainer(State.ENTRYPOINT);
        return gap.nextCheckAt;
    }

    /** Doubling recheck of a container that keeps coming up empty, capped so a restocked chest is noticed quickly. */
    private long stockoutRecheckMs(int misses) {
        long base = Math.max(STOCKOUT_BASE_MS, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay * 50L);
        return Math.min(STOCKOUT_MAX_MS, base << Math.max(0, Math.min(misses - 1, 5)));
    }

    /** True while this resource is still considered out of stock, i.e. its recheck is not due yet. */
    private boolean isOutOfStock(String key) {
        Stockout gap = stockouts.get(key);
        return gap != null && System.currentTimeMillis() < gap.nextCheckAt;
    }

    /** Earliest time any known stockout may be looked at again, used as the poll interval of an idle workflow. */
    private long nextStockoutCheckAt() {
        long now = System.currentTimeMillis();
        long earliest = now + STOCKOUT_MAX_MS;
        for (Stockout gap : stockouts.values()) {
            if (gap.nextCheckAt < earliest) earliest = gap.nextCheckAt;
        }
        return Math.max(now + 500L, earliest);
    }

    /**
     * Forgets containers of one family once they delivered again, so the recheck interval starts over instead of
     * carrying the doubling of an outage that is already over.
     */
    private void clearStockouts(String prefix) {
        stockouts.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Parks one carried piece until the supply it needs is restocked, so the workflow can work on something else in
     * the meantime. This is the per-item counterpart of a global freeze: a missing {@code fire_aspect 2} only ever
     * blocks the sword that needs it.
     */
    private void pauseForSupply(ItemStack stack, String what, long until) {
        if (stack == null) return;
        supplyPausedUntil.put(identityKey(stack), until);
        trace("pausing this enchantItem for {}s: waiting for {}",
            Math.max(1, (until - System.currentTimeMillis()) / 1000), what);
    }

    /** Parks every finished product of one job, so a full unload container cannot be retried piece after piece. */
    private void pauseJobProducts(AutoEnchantConfig.Enchant job, long until) {
        if (job == null) return;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            if (module.runnableJobFor(stack).orElse(null) != job) continue;
            if (!module.catalog().enchantments(stack).equals(targetEnchantsOf(job))) continue;
            supplyPausedUntil.put(identityKey(stack), until);
        }
    }

    private boolean isSupplyPaused(ItemStack stack) {
        if (stack == null) return false;
        Long until = supplyPausedUntil.get(identityKey(stack));
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * Jobs that currently hold a carried piece paused by a supply gap. Their pickup chest stays out of the rotation and
     * their items are not adopted from a shared chest, so a missing book costs exactly one item's progress.
     */
    private java.util.Set<AutoEnchantConfig.Enchant> supplyBlockedJobs() {
        java.util.Set<AutoEnchantConfig.Enchant> blocked =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        long now = System.currentTimeMillis();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            Long until = supplyPausedUntil.get(identityKey(stack));
            if (until == null || until < now) continue;
            module.runnableJobFor(stack).ifPresent(blocked::add);
        }
        return blocked;
    }

    /** Status line for an idle workflow; never null, so a blank status cannot hide why the BOT is waiting. */
    private String idleStatus() {
        String gap = supplyPauseSummary();
        return gap != null ? gap : "Out of stock: waiting for a restock; checking again shortly";
    }

    /**
     * Status text for "nothing can be done until a container is refilled", or null when work is available. Names the
     * missing things (at most two, so the line stays readable) and how soon the next look is due.
     */
    private String supplyPauseSummary() {
        long now = System.currentTimeMillis();
        supplyPausedUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        stockouts.entrySet().removeIf(entry -> now - entry.getValue().lastSeenAt > STOCKOUT_EXPIRY_MS);
        if (stockouts.isEmpty()) return null;
        String missing = stockouts.values().stream().limit(2).map(gap -> gap.what)
            .collect(java.util.stream.Collectors.joining("; "));
        long wait = Math.max(1, (nextStockoutCheckAt() - now) / 1000);
        return "Out of stock: " + missing + "; next check in " + wait + "s";
    }

    private void nextBook() {
        if (failureReason != null) { state = State.POST_ENCHANT; return; }
        if (bookIndex >= books.size()) {
            // Every merge is done. Only the name can still need the anvil, and it never replays a finished merge:
            // operationIndex keeps its progress because merges may run while books are still being restocked.
            if (operations.isEmpty() || operationIndex >= operations.size()) {
                if (needsRename()) { beginRenamePass(); return; }
                state = State.POST_ENCHANT;
                return;
            }
            backToBooks = false;
            state = State.CHECK_ANVIL;
            return;
        }
        // A merge whose inputs are already in the inventory is done first: restocking the remaining books would only
        // fill the inventory and delay work the BOT can perform right now (e.g. a merged book waiting for the sword).
        if (inputsReady()) {
            trace("next merge inputs are already carried; enchanting before restocking more books");
            backToBooks = false;
            state = State.CHECK_ANVIL;
            return;
        }
        EnchantPresetCatalog.Enchant book = books.get(bookIndex);
        long requiredCopies = books.subList(0, bookIndex + 1).stream().filter(book::equals).count();
        trace("need book {}/{}: {} {} (carried {}, required {})", bookIndex + 1, books.size(),
            book.id(), book.level(), countBooks(book), requiredCopies);
        if (countBooks(book) >= requiredCopies) { bookIndex++; return; }
        // Already on the item: merging can run ahead of the restock phase, so a book whose enchantment the equipment
        // carries at the planned level is not fetched again.
        Integer onItem = itemSnapshot == null ? null
            : module.catalog().enchantments(itemSnapshot).get(book.id());
        if (onItem != null && onItem >= book.level()) { bookIndex++; return; }
        // A merge result that already contains this book means the work is done: fetching another copy would only
        // fill the inventory with duplicates of an intermediate the plan can reuse. Only trusted when the plan needs
        // a single copy of this book, so a second branch that really needs one is still supplied.
        long copiesNeeded = books.stream().filter(other -> other.id().equals(book.id())).count();
        if (copiesNeeded <= 1 && carriedAsIntermediate(book)) {
            trace("book {} {} already merged into a carried result; skipping the restock", book.id(), book.level());
            bookIndex++;
            return;
        }
        if (!hasFreeInventorySlot()) {
            // Opening the chest only to have the shift-click bounce off a full inventory spins forever. A finished
            // product carried by the bag is unloaded first; only when there is nothing to unload is the transaction
            // parked with the reason (the item stays untouched in the inventory). A single free slot is enough here:
            // the merge that follows moves both inputs into the anvil and puts only the result back.
            if (flushFinishedProduct("no room for " + book.id() + " " + book.level())) return;
            keepAndRetry("BOT inventory is full; no room for " + book.id() + " " + book.level());
            return;
        }
        var chest = module.bookContainerPosition(book.id(), book.level());
        if (chest.isEmpty()) {
            // A missing book marker is a supply problem, not a failed enchant: the piece keeps its progress, the gap is
            // named in the status/warning and the pickup branch keeps running for the other jobs.
            reportOutOfStock("bookchest:" + book.id() + " " + book.level(),
                book.id() + " " + book.level() + " (no book chest is bound to it)");
            return;
        }
        openingPosition = chest.get();
        trace("book chest for {} {} is {}", book.id(), book.level(), openingPosition);
        state = State.RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST;
    }

    private void handleBookWindow() {
        if (!isStorageWindow()) { fail("Book marker position did not open a storage container"); return; }
        EnchantPresetCatalog.Enchant required = books.get(bookIndex);
        int selected = findBookInTop(required);
        if (selected < 0) {
            trace("book chest {} holds no {} {}; the chest is out of stock", openingPosition, required.id(), required.level());
            // The chest position is part of the message so the operator knows which container to refill, and part of the
            // key so the same empty chest shares one recheck interval instead of one per book it should hold.
            reportOutOfStock("book:" + required.id() + " " + required.level() + "@" + openingPosition,
                required.id() + " " + required.level() + " in the chest at " + openingPosition);
            return;
        }
        clearStockouts("book:");
        trace("book chest slot {} holds {} (want {} {})", selected,
            module.describeItem(openContainer().getItemStack(selected)), required.id(), required.level());
        sourceSlot = selected;
        state = State.RESTOCK_ENCHANTED_BOOK_TAKE;
    }

    private void takeBook() {
        submit(new ShiftClick(openContainerId(), sourceSlot, ShiftClickItemAction.LEFT_CLICK), State.RESTOCK_ENCHANTED_BOOK__AWAIT_WITHDRAW);
    }

    private void verifyBookTransfer() {
        EnchantPresetCatalog.Enchant book = books.get(bookIndex);
        long requiredCopies = books.subList(0, bookIndex + 1).stream().filter(book::equals).count();
        if (countBooks(book) >= requiredCopies) {
            trace("book taken: {} {} now carried x{}", book.id(), book.level(), countBooks(book));
            bookIndex++;
            state = State.RESTOCK_ENCHANTED_BOOK_CLOSE;
            return;
        }
        if (System.currentTimeMillis() > deadline) {
            // A full inventory is the reason a shift-click silently does nothing; say so instead of "not transferred".
            keepAndRetry(hasFreeInventorySlot()
                ? "Book was not transferred to BOT inventory"
                : "BOT inventory is full; cannot take " + book.id() + " " + book.level());
        }
    }

    private void handleAnvilWindow() {
        if (openContainer().getType() != ContainerType.ANVIL) { fail("Configured anvil position did not open an anvil"); return; }
        if (renamePass) { trace("anvil opened for the rename pass"); state = State.PLACE_RENAME; return; }
        if (operationIndex >= operations.size()) { trace("anvil opened but no merge is left"); state = State.CLOSE_ANVIL; return; }
        activeOperation = operations.get(operationIndex);
        takeAttempts = 0;
        stepLogged = false;
        trace("anvil opened for merge[{}]: {} + {} => {}", operationIndex,
            describeNode(activeOperation.left()), describeNode(activeOperation.right()),
            describeNode(activeOperation.result()));
        state = State.ENCHANT_TRY_START;
    }

    private void placeAnvilInputs() {
        activeOperation = operations.get(operationIndex);
        int left = findProduct(activeOperation.left());
        int right = findProduct(activeOperation.right(), left);
        if (left < 0 || right < 0) {
            trace("merge[{}] input not found: left slot={} want {} | right slot={} want {} | expected {}",
                operationIndex, left, describeNode(activeOperation.left()), right,
                describeNode(activeOperation.right()),
                describeEnchants(expectedEnchants(activeOperation.result(), targetPlaceholder())));
            traceInventory("missing merge inputs");
            // The books for this merge are simply not fetched yet: close the anvil and continue the book phase
            // without paying a retry penalty for it.
            if (bookIndex < books.size()) {
                backToBooks = true;
                state = State.CLOSE_ANVIL;
                return;
            }
            // Anything else (a consumed intermediate, a lost withdrawal) is a real retry: keep the item and replan.
            keepAndRetry("Missing one or more merge inputs for merge[" + operationIndex + "]");
            return;
        }
        trace("merge[{}] placing slot {} {} + slot {} {}", operationIndex,
            left, module.describeItem(playerItem(left)), right, module.describeItem(playerItem(right)));
        if (containsTarget(activeOperation.result(), targetPlaceholder())) {
            // Renames are worth one log line each: the name decides whether the passenger rename has to ride on this
            // merge, and a name that a merge silently drops is only visible by comparing the two lines.
            trace("merge[{}] equipment name before merge: '{}' (target '{}')", operationIndex,
                itemSnapshot == null ? "" : module.catalog().customName(itemSnapshot), desiredRename());
        }
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = new ArrayList<>();
        actions.add(new ClickItem(openContainerId(), inventoryContainerSlot(left, topSlots()), ClickItemAction.LEFT_CLICK));
        actions.add(new ClickItem(openContainerId(), 0, ClickItemAction.LEFT_CLICK));
        actions.add(new ClickItem(openContainerId(), inventoryContainerSlot(right, topSlots()), ClickItemAction.LEFT_CLICK));
        actions.add(new ClickItem(openContainerId(), 1, ClickItemAction.LEFT_CLICK));
        // The rename rides on the first merge that involves the equipment. Nothing is sent when the name would not
        // change: the anvil shows a red X for a rename that changes nothing (and an empty field cannot clear a name
        // the item does not have), so only a real change is ever requested. Not sending a name at all leaves the
        // item's existing name alone, which is why later merges need no packet.
        if (operationIndex == renameOperationIndex() && needsRename()) {
            trace("merge[{}] name field <- '{}'", operationIndex, desiredRename());
            actions.add(new RenameItem(openContainerId(), desiredRename()));
        }
        deadline = System.currentTimeMillis() + awaitIntactMs();
        stepLogged = false;
        module.clearAnvilCost();
        submit(actions, State.AWAIT_ENCHANT);
        // The preview is a second server round-trip after the clicks, so it gets the result budget, not the
        // plain interaction budget: a slow preview is a retry, not an enchant failure.
        deadline = System.currentTimeMillis() + awaitResultMs();
    }

    /** An enchantItem that needs no merge but still needs its name: place it alone in the anvil and rename it there. */
    private void placeRenameInput() {
        String name = desiredRename();
        if (name == null) { state = State.CLOSE_ANVIL; return; }
        playerSlot = findCurrentEquipment();
        if (playerSlot < 0) { keepAndRetry("Cannot find the enchantItem to rename"); return; }
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = new ArrayList<>();
        actions.add(new ClickItem(openContainerId(), inventoryContainerSlot(playerSlot, topSlots()), ClickItemAction.LEFT_CLICK));
        actions.add(new ClickItem(openContainerId(), 0, ClickItemAction.LEFT_CLICK));
        actions.add(new RenameItem(openContainerId(), name));
        deadline = System.currentTimeMillis() + awaitIntactMs();
        stepLogged = false;
        trace("rename pass placing slot {} {} (want '{}')", playerSlot, module.describeItem(playerItem(playerSlot)), name);
        module.clearAnvilCost();
        submit(actions, State.AWAIT_ENCHANT);
        deadline = System.currentTimeMillis() + awaitResultMs();
    }

    /** Opens the anvil for a rename-only pass, which is the only work left for an already finished enchantItem. */
    private void beginRenamePass() {
        renamePass = true;
        renameTries = 0;
        String name = desiredRename();
        module.setWorkflowStatus("No merge left; " + (name == null || name.isEmpty()
            ? "clearing the enchantItem's name" : "renaming the enchantItem to \"" + name + "\""));
        state = State.CHECK_ANVIL;
    }

    /**
     * Index of the last plan operation whose result is the equipment; the name travels with that merge.
     *
     * <p>A merge costs the same with or without a name (renaming adds exactly 1 level and does not accumulate
     * penalty), but only the last equipment merge is guaranteed to be followed by nothing that could drop the name
     * again. A rename performed as its own anvil pass, on the other hand, is charged the item's whole prior-work
     * penalty - 16 levels on a finished sword instead of 1 - which is what this index exists to avoid.
     */
    private int renameOperationIndex() {
        String placeholder = targetPlaceholder();
        for (int i = operations.size() - 1; i >= 0; i--) {
            if (containsTarget(operations.get(i).result(), placeholder)) return i;
        }
        return -1;
    }

    /**
     * Name to write into the anvil's name field, or null when this job leaves names alone. Forcing "" is a real
     * instruction (it clears a custom name) but the anvil only accepts it when the item actually has one - see
     * {@link #needsRename()} - so it is never sent as a no-op.
     */
    private String desiredRename() {
        if (target == null) return null;
        String name = target.renameName;
        return name == null ? null : name.trim();
    }

    /**
     * True only when the anvil would really apply a rename: the item must already have a name to clear, and the new
     * name must differ from the current one. Anything else paints a red X on the anvil and yields no output at all,
     * so it must never be requested - not even while merging.
     */
    private boolean needsRename() {
        String name = desiredRename();
        if (name == null || itemSnapshot == null || renameGivenUp) return false;
        return !name.equals(module.catalog().customName(itemSnapshot));
    }

    private void verifyAnvilPreview() {
        if (renamePass) { verifyRenamePreview(); return; }
        ItemStack result = openContainer().getItemStack(2);
        OptionalIntCost price = currentCost();
        // Survival servers keep reporting the level cost but blank the result slot once the operation passes the
        // 39-level limit - the same property the vanilla screen turns into the red "Too Expensive!". That - and only
        // that - is a real "this item can no longer be enchanted" outcome.
        if (price.value >= TOO_EXPENSIVE_LEVEL) {
            trace("merge[{}] anvil reports 'Too Expensive': cost={} >= {} (planned={}, result={})", operationIndex,
                price.value, TOO_EXPENSIVE_LEVEL, activeOperation.expectedCost(), module.describeItem(result));
            failPermanently("Too expensive: merge[" + operationIndex + "] would cost " + price.value
                + " levels (anvil limit " + (TOO_EXPENSIVE_LEVEL - 1) + ")");
            return;
        }
        boolean empty = result == null || result == Container.EMPTY_STACK;
        if (empty || price.value < 0) {
            // No result yet: the window or the preview packet is still in flight, which is not a failure.
            if (System.currentTimeMillis() > deadline) {
                trace("merge[{}] preview never showed a result (result={} cost={})", operationIndex,
                    module.describeItem(result), price.value);
                keepAndRetry("Anvil preview timed out for merge[" + operationIndex + "]");
            }
            return;
        }
        String mismatch = productMismatch(result, activeOperation.result(), false);
        if (!stepLogged) {
            stepLogged = true;
            // Penalty is the wiki's prior-work penalty (2^uses - 1): it is what pushes the last merges towards the
            // 39-level limit, so both the old and the resulting value are printed next to the cost.
            trace("merge[{}] preview: result={} cost={} planned={} penalty {} -> {} expected={} match={}", operationIndex,
                module.describeItem(result), price.value,
                activeOperation.expectedCost() + (operationIndex == renameOperationIndex() && needsRename() ? 1 : 0),
                itemSnapshot == null ? 0 : module.catalog().priorWorkPenalty(itemSnapshot),
                module.catalog().priorWorkPenalty(result),
                describeEnchants(expectedEnchants(activeOperation.result(), targetPlaceholder())),
                mismatch == null);
            if (mismatch != null) trace("merge[{}] result rejected: {}", operationIndex, mismatch);
        }
        if (mismatch != null) {
            // The anvil shows something other than the planned merge: close it and let the item be re-planned from
            // its live state instead of spending the inputs.
            keepAndRetry("Anvil result did not match merge[" + operationIndex + "]");
            return;
        }
        waitingCost = price.value;
        if (!module.ensureXpForCost(price.value)) { state = State.CLOSE_FOR_XP; return; }
        state = State.TAKE_RESULT;
    }

    /** A rename-only preview must show the new name; the enchantments are not part of this check. */
    private void verifyRenamePreview() {
        String name = desiredRename();
        ItemStack result = openContainer().getItemStack(2);
        OptionalIntCost price = currentCost();
        if (name == null) { keepAndRetry("Rename name disappeared before the anvil preview"); return; }
        // A name is cosmetic: a rename that grew past the level limit is skipped instead of costing the item.
        if (price.value >= TOO_EXPENSIVE_LEVEL) {
            trace("rename skipped: cost={} >= {}", price.value, TOO_EXPENSIVE_LEVEL);
            renamePass = false;
            module.setWorkflowStatus("Rename skipped: it would cost " + price.value + " levels");
            state = State.CLOSE_ANVIL;
            return;
        }
        boolean empty = result == null || result == Container.EMPTY_STACK;
        if (empty || price.value < 0) {
            if (System.currentTimeMillis() > deadline) {
                // A red X (no output at all) means the anvil refused the rename: never let a label hold the item back.
                trace("rename preview produced no output (result={} cost={}); rename refused?", module.describeItem(result), price.value);
                if (++renameTries >= 2) {
                    renameGivenUp = true;
                    renamePass = false;
                    module.setWorkflowStatus("Rename skipped: the anvil refused to rename to \"" + name + "\"");
                    state = State.CLOSE_ANVIL;
                    return;
                }
                keepAndRetry("Anvil rename preview timed out");
            }
            return;
        }
        if (!name.equals(module.catalog().customName(result))) {
            trace("rename preview shows '{}' instead of '{}'", module.catalog().customName(result), name);
            // A name is cosmetic. If the server will not apply it after a couple of tries the item is unloaded as it
            // is, instead of being retried forever over a label.
            if (++renameTries >= 2) {
                renameGivenUp = true;
                renamePass = false;
                module.setWorkflowStatus("Rename skipped: the anvil did not apply \"" + name + "\"");
                state = State.CLOSE_ANVIL;
                return;
            }
            keepAndRetry("Anvil rename preview did not apply the new name");
            return;
        }
        if (!stepLogged) { stepLogged = true; trace("rename preview ok: '{}' cost={}", name, price.value); }
        waitingCost = price.value;
        if (!module.ensureXpForCost(price.value)) { state = State.CLOSE_FOR_XP; return; }
        state = State.TAKE_RESULT;
    }

    private void waitForExperience() {
        // Not enough levels yet is a waiting condition, not a failure: the item keeps its progress and is resumed
        // once XP farming can start again (or a later cycle tries again).
        if (module.consumeXpInterruption()) { keepAndRetry("XP farming is unavailable right now"); return; }
        if (module.ensureXpForCost(waitingCost)) state = State.CHECK_ANVIL;
    }

    /**
     * Takes the anvil output with a shift-click, i.e. a quick move straight into the BOT inventory.
     *
     * <p>A plain left-click only puts the stack on the cursor: the result then lives in the mouse stack, no inventory
     * slot contains it, and {@link #verifyAnvilResult()} times out after a few seconds even though the merge already
     * happened. The window then closes, the server returns the cursor stack to the inventory, and the next cycle sees a
     * completed merge it never recorded - which is what made the BOT dump good pieces and start over.
     */
    private void takeAnvilResult() {
        ItemStack result = openContainer().getItemStack(2);
        if (renamePass) {
            String name = desiredRename();
            if (name == null || result == null || result == Container.EMPTY_STACK
                || !name.equals(module.catalog().customName(result))) {
                keepAndRetry("Anvil rename output changed before pickup");
                return;
            }
            if (findEmptyPlayerSlot(topSlots()) < 0) { keepAndRetry("No empty BOT inventory slot for the anvil result"); return; }
            takeAttempts = 0;
            submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.VERIFY_RESULT);
            deadline = System.currentTimeMillis() + awaitResultMs();
            return;
        }
        if (!matchesProduct(result, activeOperation.result())) { keepAndRetry("Anvil output changed before pickup"); return; }
        if (findEmptyPlayerSlot(topSlots()) < 0) { keepAndRetry("No empty BOT inventory slot for the anvil result"); return; }
        takeAttempts = 0;
        submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.VERIFY_RESULT);
        deadline = System.currentTimeMillis() + awaitResultMs();
    }

    private void verifyAnvilResult() {
        if (renamePass) {
            int renamed = findRenamedItem(desiredRename());
            if (renamed >= 0) {
                itemSnapshot = playerItem(renamed).clone();
                trace("rename done: slot {} {}", renamed, module.describeItem(itemSnapshot));
                renamePass = false;
                state = State.CLOSE_ANVIL;
                return;
            }
            if (System.currentTimeMillis() > deadline) keepAndRetry("Renamed enchantItem was not received");
            return;
        }
        int resultSlot = findProduct(activeOperation.result());
        if (resultSlot >= 0) {
            boolean equipment = containsTarget(activeOperation.result(), targetPlaceholder());
            if (equipment) itemSnapshot = playerItem(resultSlot).clone();
            trace("merge[{}] done: took slot {} {}", operationIndex, resultSlot, module.describeItem(playerItem(resultSlot)));
            if (equipment) trace("merge[{}] equipment name after merge: '{}'", operationIndex,
                module.catalog().customName(itemSnapshot));
            operationIndex++;
            takeAttempts = 0;
            transientRetries = 0;
            state = State.CLOSE_ANVIL;
            return;
        }
        if (System.currentTimeMillis() <= deadline) return;
        // The pickup click can be swallowed in flight while the server keeps the result in slot 2: re-click it a
        // couple of times before treating the merge as unfinished.
        ItemStack still = openContainer().getItemStack(2);
        if (still != null && still != Container.EMPTY_STACK && matchesProduct(still, activeOperation.result())
            && takeAttempts < MAX_TAKE_ATTEMPTS) {
            takeAttempts++;
            trace("merge[{}] result still in the anvil slot; re-clicking (attempt {})", operationIndex, takeAttempts);
            submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.VERIFY_RESULT);
            deadline = System.currentTimeMillis() + awaitResultMs();
            return;
        }
        trace("merge[{}] result not in inventory; wanted {} => {}", operationIndex,
            describeNode(activeOperation.result()),
            describeEnchants(expectedEnchants(activeOperation.result(), targetPlaceholder())));
        traceInventory("anvil result missing");
        keepAndRetry("Anvil result was not received for merge[" + operationIndex + "]");
    }

    /**
     * Locates the renamed equipment in the BOT inventory by enchantItem id, exact name (an empty name means the item
     * has none) and its enchantments, so a look-alike stack is never mistaken for the one that was just renamed.
     */
    private int findRenamedItem(String name) {
        if (name == null) return -1;
        int itemId = configuredItemId();
        Map<String, Integer> wanted = itemSnapshot == null ? null : readEnchants(itemSnapshot, false);
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != itemId) continue;
            if (!name.equals(module.catalog().customName(stack))) continue;
            if (wanted != null && !wanted.equals(readEnchants(stack, false))) continue;
            return slot;
        }
        return -1;
    }

    private void handleOutputWindow() {
        if (!isStorageWindow()) { fail("Output marker did not open a storage container"); return; }
        state = switch (openState) {
            case POST_ENCHANT_INPUT_GO_TO -> State.POST_ENCHANT_INPUT_DEPOSIT;
            case POST_ENCHANT_OVERFLOW_GO_TO -> State.POST_ENCHANT_OVERFLOW_DEPOSIT;
            default -> State.STORE_DEPOSIT;
        };
    }

    /**
     * Unloads into the open chest, modelled on the trader's store trip: every stack that belongs in this chest goes in
     * one window with one click queue.
     *
     * <p>A normal transaction stores exactly its own enchantItem. A flush trip - the trip that exists only to free bag
     * space - stores every finished piece of that job at once, so a bag with ten finished swords needs one chest trip
     * instead of ten. The deposit clicks run at one per tick (not the configured action delay), because a queue of
     * twenty shift clicks at five ticks each would be five seconds of standing at the chest.
     */
    private void unloadItem() {
        List<Integer> slots = depositTargets();
        if (slots.isEmpty()) {
            trace("nothing to deposit: no matching stack is left in the inventory");
            state = State.CLOSE_OUTPUT;
            return;
        }
        depositSlots = slots;
        depositRetries = 0;
        playerSlot = slots.getFirst();
        trace("depositing {} stack(s) in one window: {} | target={}", slots.size(),
            slots.stream().map(slot -> slot + "=" + module.describeItem(playerItem(slot)))
                .collect(java.util.stream.Collectors.joining(", ")),
            describeEnchants(targetEnchants));
        submitDeposit(slots);
    }

    /** Sends one shift-click per target slot and waits for every one of them to be empty. */
    private void submitDeposit(List<Integer> slots) {
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = new ArrayList<>();
        for (int slot : slots) {
            actions.add(new ShiftClick(openContainerId(), inventoryContainerSlot(slot, topSlots()),
                ShiftClickItemAction.LEFT_CLICK));
        }
        // Covers both the first click and a re-click from the await state: a deposit retry must land back in the same
        // await state, otherwise it would return to the deposit state, rebuild the list and click the same stacks again.
        State await = switch (state) {
            case POST_ENCHANT_INPUT_DEPOSIT, POST_ENCHANT_INPUT_AWAIT_DEPOSIT -> State.POST_ENCHANT_INPUT_AWAIT_DEPOSIT;
            case POST_ENCHANT_OVERFLOW_DEPOSIT, POST_ENCHANT_OVERFLOW_AWAIT_DEPOSIT -> State.POST_ENCHANT_OVERFLOW_AWAIT_DEPOSIT;
            default -> State.STORE_AWAIT_DEPOSIT;
        };
        submitBurst(actions, await);
    }

    /**
     * Slots this window should store.
     *
     * <p>The transaction item is picked by identity; a flush trip takes every carried stack of the same item whose
     * enchantment set already equals the job's target, which also covers pieces that were renamed by an earlier pass.
     */
    private List<Integer> depositTargets() {
        List<Integer> slots = new ArrayList<>();
        if (!flushing) {
            playerSlot = findCurrentEquipment();
            if (playerSlot >= 0) slots.add(playerSlot);
            return slots;
        }
        for (int slot = 9; slot <= 44; slot++) if (isDepositTarget(playerItem(slot))) slots.add(slot);
        return slots;
    }

    /** True when this stack is what a flush trip wants to store: the job's item, already at its target set. */
    private boolean isDepositTarget(ItemStack stack) {
        if (stack == null || itemSnapshot == null) return false;
        if (stack.getId() != itemSnapshot.getId()) return false;
        if (!flushing) return sameStackIdentity(itemSnapshot, stack);
        // The same job, not merely the same item: two jobs can share an item type (and even a target set) while
        // pointing at different output chests, and a batch must never file one job's products into the other's chest.
        if (module.runnableJobFor(stack).orElse(null) != target) return false;
        return module.catalog().enchantments(stack).equals(targetEnchants);
    }

    private void verifyUnloaded() {
        List<Integer> remaining = remainingDepositSlots();
        if (remaining.isEmpty()) {
            outputStored = true;
            trace("deposit confirmed: {} stack(s) left the inventory", depositSlots.size());
            state = State.CLOSE_OUTPUT;
            return;
        }
        if (System.currentTimeMillis() <= deadline) return;
        // The chest took only part of the trip (a slot filled between the click and the deposit): re-collect what is
        // still carried and click it again in the same window, exactly like the trader's store retry.
        if (depositRetries < MAX_DEPOSIT_RETRIES) {
            depositRetries++;
            trace("chest did not take every stack; depositing the remaining {} again ({}/{})",
                remaining.size(), depositRetries, MAX_DEPOSIT_RETRIES);
            submitDeposit(remaining);
            return;
        }
        if (flushing) { flushBlocked("the output container is full"); return; }
        // A full unload container is a supply problem, not a failed enchant: never drop the piece, never burn a
        // transient retry on it. The job's finished products step aside until the container is emptied, the workflow
        // keeps cycling and the status line says which container is full.
        reportOutOfStock("store:" + target.displayName(),
            "an unload container for job " + target.displayName() + " (the container is full)", target);
    }

    /** Target slots that still hold a depositable stack; slots are stable while the window stays open. */
    private List<Integer> remainingDepositSlots() {
        List<Integer> left = new ArrayList<>();
        for (int slot : depositSlots) if (isDepositTarget(playerItem(slot))) left.add(slot);
        return left;
    }

    /**
     * A flush could not store its piece. The overflow container is the natural fallback (a full output chest is
     * exactly the situation where the finished pieces piled up in the bag in the first place); when that is missing or
     * was already tried, the workflow waits for a human to empty or restock the output container.
     */
    private void flushBlocked(String reason) {
        trace("flush could not store the finished product: {}", reason);
        if (!flushOverflow) {
            var overflow = module.containerPosition(target, "overflow");
            if (overflow.isPresent()) {
                flushOverflow = true;
                module.setWorkflowStatus("Output container refuses the finished product (" + reason
                    + "); unloading it to the overflow container instead");
                if (openContainerId() != 0) closeContainer(State.POST_ENCHANT); else state = State.POST_ENCHANT;
                return;
            }
        }
        // No container takes the piece. The finished products of this job step aside together (a full chest would
        // otherwise be retried once per product) and the workflow keeps cycling, so another job can still work while
        // somebody empties the chest.
        reportOutOfStock("store:" + target.displayName(),
            "an unload container for job " + target.displayName() + " (" + reason + ")", target);
    }

    private void dropCurrentItem() {
        if (openContainerId() != 0) { closeContainer(State.DROP_ITEM); return; }
        int slot = findCurrentEquipment();
        if (slot >= 0) submit(new DropItem(0, slot, org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction.DROP_SELECTED_STACK), State.BACKOFF);
        else state = State.BACKOFF;
        deadline = System.currentTimeMillis() + AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay * 50L;
    }

    private void beginOutput() {
        if (itemSnapshot == null) {
            deadline = System.currentTimeMillis() + Math.max(0, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay) * 50L;
            if (openContainerId() != 0) closeContainer(State.BACKOFF); else state = State.BACKOFF;
            return;
        }
        if (openContainerId() != 0) { closeContainer(State.POST_ENCHANT); return; }
        // A successful item goes to the output chest. A failed item goes to the overflow chest, or
        // back to the input chest when the job's post-enchant mode asks for it; either falls back to
        // the output chest when its own container is not configured.
        State goTo;
        String role;
        if (failureReason == null) {
            goTo = State.STORE_GO_TO_CHEST;
            role = "output";
        } else if (target.postEnchantStoreMode == AutoEnchantConfig.Enchant.PostEnchantStoreMode.TO_RESTOCK) {
            goTo = State.POST_ENCHANT_INPUT_GO_TO;
            role = "input";
        } else {
            goTo = State.POST_ENCHANT_OVERFLOW_GO_TO;
            role = "overflow";
        }
        Optional<BlockPos> output = module.containerPosition(target, role);
        if (output.isEmpty() && role.equals("input")) {
            output = module.containerPosition(target, "overflow");
            if (output.isPresent()) goTo = State.POST_ENCHANT_OVERFLOW_GO_TO;
        }
        if (output.isEmpty() && !role.equals("output")) {
            output = module.containerPosition(target, "output");
            if (output.isPresent()) goTo = State.STORE_GO_TO_CHEST;
        }
        if (flushing) {
            // A flush exists to free a slot, so it takes whatever container accepts the piece: the output chest first
            // (that is where finished products belong) and the overflow chest when the output one is missing or was
            // just found full.
            role = flushOverflow ? "overflow" : "output";
            goTo = State.STORE_GO_TO_CHEST;
            output = module.containerPosition(target, role);
            if (output.isEmpty() && !flushOverflow) {
                output = module.containerPosition(target, "overflow");
                if (output.isPresent()) flushOverflow = true;
            }
        }
        if (output.isEmpty()) {
            if (flushing) {
                // Nothing can take the piece: report it, park the finished products of this job and keep cycling.
                reportOutOfStock("store:" + target.displayName(),
                    "an unload container for job " + target.displayName()
                        + " (no output or overflow container is bound)", target);
                return;
            }
            // Keep the enchantItem in the BOT inventory instead of dropping it; the next cycle resumes it once the
            // unload container exists again.
            reportOutOfStock("store:" + target.displayName() + ":" + role,
                "an unload container for job " + target.displayName() + " (no " + role + " container is bound)", target);
            return;
        }
        openingPosition = output.get();
        trace("unload via {} container {} (failure={}, item={})", role, openingPosition,
            failureReason == null ? "none" : failureReason, module.describeItem(itemSnapshot));
        state = goTo;
    }

    private void closeOutput() {
        closeContainer(State.BACKOFF);
        deadline = System.currentTimeMillis() + Math.max(0, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay) * 50L;
    }

    /**
     * A step failed, which is <b>not</b> the same as the enchantItem failing. Opening a chest, moving a stack, a lost
     * click or a slow window are all transient: the item stays in the BOT inventory, keeps every enchantment it
     * already paid for, and the transaction is resumed later. Only {@link #failPermanently(String)} ends an item.
     */
    private void fail(String reason) {
        keepAndRetry(reason);
    }

    /**
     * The only genuine enchant failure: the anvil refuses the operation because its level cost is too high. Anything
     * else - a chest that did not open, a book that was not transferred, a level that is merely not reached yet - must
     * never be reported as a failed enchant and must never push the item to the overflow container.
     */
    private void failPermanently(String reason) {
        State from = state;
        trace("giving up on the enchantItem in state {}: {}", from, reason);
        failureReason = reason;
        markAttempt();
        module.setWorkflowStatus("Skipping enchantItem: " + reason);
        state = State.POST_ENCHANT;
    }

    /**
     * Parks the current transaction without touching the enchantItem. The piece is left where it is (in the BOT
     * inventory), the window is closed and the next cycle adopts it again, so an interrupted merge or a chest that
     * would not open costs a delay instead of the item.
     */
    private void keepAndRetry(String reason) {
        State from = state;
        trace("retry in state {}: {}{}", from, reason, itemSnapshot == null ? "" : " | item=" + module.describeItem(itemSnapshot));
        retryReason = reason;
        // A vanished anvil block explains most anvil-step failures; recover the station instead of dropping the enchantItem.
        if (from != State.CHECK_ANVIL && from != State.CRAFT_ANVIL_BLOCKS && from != State.AWAIT_CRAFT_ANVIL_BLOCKS
            && anvilRecoveryAttempts < MAX_ANVIL_RECOVERY && anvilPosition != null && !isAnvilBlock(anvilPosition)) {
            anvilRecoveryAttempts++;
            retryReason = null;
            module.setWorkflowStatus("Anvil broke at " + anvilPosition + "; fetching a replacement ("
                + anvilRecoveryAttempts + "/" + MAX_ANVIL_RECOVERY + ")");
            if (openContainerId() != 0) closeContainer(State.CHECK_ANVIL); else state = State.CHECK_ANVIL;
            return;
        }
        transientRetries++;
        if (itemSnapshot != null && transientRetries >= MAX_TRANSIENT_RETRIES) {
            // A stubborn step must not monopolise the loop, but the item is still never unloaded: it stays in the BOT
            // inventory and drops out of the rotation for a cooldown instead.
            String key = identityKey(itemSnapshot);
            skipUntil.put(key, System.currentTimeMillis() + SKIP_COOLDOWN_MS);
            transientRetries = 0;
            module.setWorkflowStatus("Parked in inventory for " + (SKIP_COOLDOWN_MS / 1000) + "s after repeated "
                + "retries: " + reason);
        } else {
            module.setWorkflowStatus("Keeping the enchantItem and retrying: " + reason);
        }
        long pause = retryDelayMs();
        nextRetryAt = System.currentTimeMillis() + pause;
        if (openContainerId() != 0) closeContainer(State.BACKOFF); else state = State.BACKOFF;
        deadline = nextRetryAt;
    }

    /**
     * Leaves a carried piece where it is instead of unloading it: the bag slot is kept, the piece drops out of the
     * rotation for {@code SKIP_COOLDOWN_MS}, and the next carried/chest item gets its turn in the meantime. This is
     * the "no half-finished piece is thrown into the overflow container while it can still be worked on" rule.
     */
    private void parkCarried(String reason) {
        if (itemSnapshot != null) {
            String key = identityKey(itemSnapshot);
            skipUntil.put(key, System.currentTimeMillis() + SKIP_COOLDOWN_MS);
            itemAttempts.remove(key);
        }
        transientRetries = 0;
        failureReason = null;
        module.setWorkflowStatus("Keeping the carried enchantItem in the bag for "
            + (SKIP_COOLDOWN_MS / 1000) + "s: " + reason);
        trace("carried enchantItem parked in the bag: {}{}", reason,
            itemSnapshot == null ? "" : " | item=" + module.describeItem(itemSnapshot));
        state = State.ENTRYPOINT;
    }

    /** Growing pause between transient retries, capped so a healthy item is not stalled for minutes. */
    private long retryDelayMs() {
        long base = Math.max(1_000L, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay * 50L);
        return Math.min(30_000L, base * Math.max(1, Math.min(transientRetries, 5)));
    }

    /** Counts consecutive failures for the current enchantItem; a repeated offender is parked for a while. */
    private void markAttempt() {
        if (itemSnapshot == null) return;
        String key = identityKey(itemSnapshot);
        int attempts = itemAttempts.merge(key, 1, Integer::sum);
        if (attempts >= MAX_ITEM_ATTEMPTS) {
            skipUntil.put(key, System.currentTimeMillis() + SKIP_COOLDOWN_MS);
            itemAttempts.remove(key);
            module.setWorkflowStatus("Item parked for " + (SKIP_COOLDOWN_MS / 1000) + "s after " + attempts + " failed attempts");
        }
    }

    private boolean isCoolingDown(ItemStack stack) {
        Long until = skipUntil.get(identityKey(stack));
        return until != null && System.currentTimeMillis() < until;
    }

    private String identityKey(ItemStack stack) {
        if (stack == null) return "none";
        return stack.getId() + "|" + module.catalog().enchantments(stack) + "|" + module.catalog().priorWorkPenalty(stack)
            + "|" + module.catalog().customName(stack);
    }

    private void closeContainer(State next) {
        int id = openContainerId();
        if (id == 0) { state = next; return; }
        activeContainerId = id;
        afterCloseState = next;
        closeResent = false;
        submit(new CloseContainer(id), State.WAIT_CLOSE);
    }

    /**
     * Waits for the close to be echoed back, the way the trader's {@code awaitContainerClosed} does.
     *
     * <p>The inventory cache (id 0) stays frozen until the server confirms the close, so any state entered on the
     * plain timeout reads a stale bag. A lost close click therefore earns one re-send instead of a blind jump into the
     * next state; only a second timeout gives up and continues.
     */
    private void waitForClose() {
        int id = openContainerId();
        if (id == 0) { closeResent = false; state = afterCloseState; return; }
        if (System.currentTimeMillis() < deadline) return;
        if (!closeResent) {
            closeResent = true;
            trace("window {} is still open; sending the close again", id);
            submit(new CloseContainer(id), State.WAIT_CLOSE);
            return;
        }
        closeResent = false;
        trace("window {} refused to close; continuing anyway", id);
        state = afterCloseState;
    }

    /**
     * Re-enters the matching OPEN_* state after a click that produced no usable window.
     *
     * <p>Two guards sit on top of the trader's plain "enter the open state again":
     * a station that no longer exists is restored instead of re-clicked, and a click that keeps failing backs off
     * instead of spinning. Both matter for a working anvil: it degrades (chipped, damaged) and finally breaks, and
     * the position then holds plain air. Re-clicking it resolves in the same tick - "No block is at ...", no path,
     * no window - so the plain transition turned into a tight INTERACT/AWAIT_INTERACT loop that no other state could
     * ever interrupt.
     */
    private void reopenContainer(String reason) {
        if (stationMissing()) {
            trace("{} no longer holds an anvil ({}); restoring the station", openingPosition, reason);
            openAttempts = 0;
            anvilRecoveryAttempts = 0;
            if (openContainerId() != 0) closeContainer(State.CHECK_ANVIL); else state = State.CHECK_ANVIL;
            return;
        }
        if (++openAttempts >= MAX_OPEN_ATTEMPTS) {
            openAttempts = 0;
            if (flushing) {
                // A missing or full unload container is exactly what strands the finished products in the bag: try the
                // overflow container, then tell the operator instead of re-clicking the same spot forever.
                flushBlocked("could not open " + openingPosition + ": " + reason);
                return;
            }
            if (isStoreState(openState)) {
                // The unload chest itself is gone: that is a stockout of the container, not a broken step, so it is
                // reported and the workflow keeps cycling for the other jobs instead of burning retries on it.
                reportOutOfStock("store:" + (target == null ? "?" : target.displayName()),
                    "an unload container for job " + (target == null ? "?" : target.displayName())
                        + " (could not open " + openingPosition + ": " + reason + ")", target);
                return;
            }
            keepAndRetry("Could not open " + openingPosition + " after " + MAX_OPEN_ATTEMPTS + " attempts: " + reason);
            return;
        }
        module.setWorkflowStatus("Reopening " + openingPosition + ": " + reason);
        if (openContainerId() != 0) closeContainer(openState); else state = openState;
    }

    /**
     * True when the state is part of the unload trip, i.e. the window being opened is the container a finished product
     * is meant to go into. A container that will not open there is out of stock, not a broken step.
     */
    private boolean isStoreState(State state) {
        return switch (state) {
            case STORE_GO_TO_CHEST, STORE_AWAIT_INTERACT, STORE_DEPOSIT, STORE_AWAIT_DEPOSIT,
                 POST_ENCHANT_INPUT_GO_TO, POST_ENCHANT_INPUT_AWAIT_INTERACT, POST_ENCHANT_INPUT_DEPOSIT,
                 POST_ENCHANT_INPUT_AWAIT_DEPOSIT, POST_ENCHANT_OVERFLOW_GO_TO, POST_ENCHANT_OVERFLOW_AWAIT_INTERACT,
                 POST_ENCHANT_OVERFLOW_DEPOSIT, POST_ENCHANT_OVERFLOW_AWAIT_DEPOSIT -> true;
            default -> false;
        };
    }

    /**
     * True when the workflow is clicking the anvil station and no anvil stands there. An unloaded chunk is treated as
     * "unknown", never as "gone", so a distant station is not rebuilt by mistake.
     */
    private boolean stationMissing() {
        if (anvilPosition == null || !anvilPosition.equals(openingPosition)) return false;
        if (!World.isChunkLoadedBlockPos(anvilPosition.x(), anvilPosition.z())) return false;
        return !isAnvilBlock(anvilPosition);
    }

    /** Interaction budget in milliseconds, derived from the tick setting so it can follow server lag. */
    private long awaitIntactMs() {
        return Math.max(100L, AutoEnchantPlugin.PLUGIN_CONFIG.waitForInteractTimeoutTicks * 50L);
    }

    /**
     * Budget for an anvil result to reach the BOT inventory after the output slot was clicked. The result travels as
     * a separate slot update, so this is deliberately more generous than a plain interaction: a short wait here used
     * to look like a failed enchant even though the merge had gone through on the server.
     */
    private long awaitResultMs() {
        return Math.max(3_000L, awaitIntactMs());
    }

    /**
     * Guarantees an anvil exists at the remembered position before every anvil step. A broken anvil leaves air where
     * it stood, so the station is restored from the BOT inventory or from the bound anvil supply container.
     */
    private void checkAnvil() {
        if (anvilPosition == null) anvilPosition = firstAnvil();
        if (anvilPosition == null) {
            module.setWorkflowStatus("Waiting for a loaded anvil");
            return;
        }
        if (isAnvilBlock(anvilPosition)) {
            anvilRecoveryAttempts = 0;
            openAttempts = 0;
            openingPosition = anvilPosition;
            state = State.ENCHANT_INTERACT_WITH_ANVIL;
            return;
        }
        if (countStacks(ItemRegistry.ANVIL) > 0) {
            state = State.CRAFT_ANVIL_BLOCKS;
            return;
        }
        var supply = module.containerPosition("anvil_supply");
        if (supply.isEmpty()) {
            fail("Anvil is gone and no anvil_supply container is bound");
            return;
        }
        openingPosition = supply.get();
        state = State.RESTOCK_ANVIL_GO_TO_CHEST;
    }

    private void handleSupplyWindow() {
        if (!isStorageWindow()) { fail("Anvil supply position did not open a storage container"); return; }
        state = State.RESTOCK_ANVIL_TAKE;
    }

    private void takeSupplyAnvils() {
        if (countStacks(ItemRegistry.ANVIL) >= restockStacks()) { state = State.CLOSE_SUPPLY; return; }
        int slot = findAnvilInTop();
        if (slot < 0) {
            // Same rule as an empty book chest: an empty barrel is a supply gap that is reported and revisited, not a
            // failed enchant and not a reason to park the piece.
            reportOutOfStock("anvil_supply@" + openingPosition, "an anvil in the anvil supply container at " + openingPosition);
            return;
        }
        submit(new ShiftClick(openContainerId(), slot, ShiftClickItemAction.LEFT_CLICK), State.RESTOCK_ANVIL_AWAIT_WITHDRAW);
    }

    private void verifySupplyTransfer() {
        if (countStacks(ItemRegistry.ANVIL) >= restockStacks()) { state = State.CLOSE_SUPPLY; return; }
        if (System.currentTimeMillis() > deadline) { fail("Anvil was not transferred to the BOT inventory"); return; }
        state = State.RESTOCK_ANVIL_TAKE;
    }

    private void placeAnvil() {
        if (anvilPosition == null) { state = State.CHECK_ANVIL; return; }
        if (countStacks(ItemRegistry.ANVIL) <= 0) {
            // No point retrying the recovery loop when nothing can be placed.
            anvilRecoveryAttempts = MAX_ANVIL_RECOVERY;
            fail("No anvil in the BOT inventory and the supply container is empty");
            return;
        }
        // Fire and forget: the placement is verified by polling the block, not by a window opening.
        pathFuture = null;
        BARITONE.placeBlock(anvilPosition.x(), anvilPosition.y(), anvilPosition.z(), ItemRegistry.ANVIL);
        deadline = System.currentTimeMillis() + awaitIntactMs();
        module.setWorkflowStatus("Placing an anvil at " + anvilPosition);
        state = State.AWAIT_CRAFT_ANVIL_BLOCKS;
    }

    private void waitForPlacement() {
        if (isAnvilBlock(anvilPosition)) {
            anvilRecoveryAttempts = 0;
            openAttempts = 0;
            module.setWorkflowStatus("Anvil placed at " + anvilPosition);
            state = State.CHECK_ANVIL;
            return;
        }
        if (System.currentTimeMillis() > deadline) fail("Could not place an anvil at " + anvilPosition);
    }

    private int restockStacks() {
        return Math.max(1, Math.min(AutoEnchantPlugin.PLUGIN_CONFIG.anvilRestockStacks, 8));
    }

    private BlockPos firstAnvil() {
        BlockPos configured = AutoEnchantPlugin.PLUGIN_CONFIG.anvil;
        return configured == null || BlockPos.ZERO.equals(configured) ? null : configured;
    }

    private boolean isAnvilBlock(BlockPos pos) {
        if (pos == null || !World.isChunkLoadedBlockPos(pos.x(), pos.z())) return false;
        int id = World.getBlock(pos.x(), pos.y(), pos.z()).id();
        return id == BlockRegistry.ANVIL.id() || id == BlockRegistry.CHIPPED_ANVIL.id() || id == BlockRegistry.DAMAGED_ANVIL.id();
    }

    /** Counts stacks (not items) of one enchantItem type in the BOT inventory, including while a window is open. */
    private int countStacks(com.zenith.mc.item.ItemData item) {
        int stacks = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack != null && stack.getId() == item.id()) stacks++;
        }
        return stacks;
    }

    /** Counts carried stacks of one registry id, used to size how many pieces a restock withdrawal delivered. */
    private int countCarriedById(int itemId) {
        if (itemId < 0) return 0;
        int stacks = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack != null && stack.getId() == itemId) stacks++;
        }
        return stacks;
    }

    private int findAnvilInTop() {
        for (int slot = 0; slot < topSlots(); slot++) {
            ItemStack stack = openContainer().getItemStack(slot);
            if (stack != null && stack.getId() == ItemRegistry.ANVIL.id()) return slot;
        }
        return -1;
    }

    private void submit(com.zenith.feature.inventory.actions.InventoryAction action, State after) { submit(List.of(action), after); }

    /**
     * Priority used for every chest/anvil click: the module's own, which sits above KillAura and Baritone (see
     * {@link AutoEnchantModule#getPriority()}). Computed per submit, so a live {@code modulePriority} change applies at
     * once and a click can never be replaced by a combat action in the same tick.
     */
    private int actionPriority() {
        return module.getPriority();
    }

    private void submit(List<com.zenith.feature.inventory.actions.InventoryAction> actions, State after) {
        int delayTicks = Math.max(0, (int) AutoEnchantPlugin.PLUGIN_CONFIG.operationDelay);
        inventoryFuture = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this).priority(actionPriority()).actionDelayTicks(delayTicks).actions(actions).build());
        afterInventoryState = after;
        deadline = System.currentTimeMillis() + awaitIntactMs();
        if (inventoryFuture == RequestFuture.rejected) { inventoryFuture = null; fail("Inventory action queue is busy"); }
    }

    /**
     * Submits a single burst of clicks at one click per tick, the way the trader's deposit queue does.
     *
     * <p>The configured action delay exists so a human does not see machine-gun clicking; inside one window a run of
     * shift clicks is exactly what a player does, and paying 5 ticks per stack turns a full bag into seconds of
     * standing still. The budget grows with the queue, so a long trip is not mistaken for a failed one.
     */
    private void submitBurst(List<com.zenith.feature.inventory.actions.InventoryAction> actions, State after) {
        inventoryFuture = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this).priority(actionPriority()).actionDelayTicks(1).actions(actions).build());
        afterInventoryState = after;
        deadline = System.currentTimeMillis() + awaitIntactMs() + actions.size() * 100L;
        if (inventoryFuture == RequestFuture.rejected) { inventoryFuture = null; fail("Inventory action queue is busy"); }
    }

    private void reset() {
        // Attempt counters survive a failed round so a repeatedly failing enchantItem eventually gets parked. A round
        // only counts as a success once the item really left the inventory: a parked retry is not a success, otherwise
        // the counter would be zeroed on every loop and the item would retry forever.
        boolean succeeded = outputStored && failureReason == null && !flushing;
        // Log only real endings: a stored enchantItem or a failed one. A transient step failure keeps the
        // enchantItem in the inventory and is retried silently instead of spamming Discord. A flush is neither: it only
        // freed a slot, so it gets a trace line instead of an "Enchant Completed" embed.
        if (retryReason != null) trace("transaction parked for a retry: {} (retries={})", retryReason, transientRetries);
        if (flushing) trace("flush finished: bag space freed, back to the enchant queue");
        if ((outputStored || failureReason != null) && !flushing) logTransaction(succeeded);
        retryReason = null;
        openAttempts = 0;
        clearTransaction();
        if (succeeded) {
            itemAttempts.clear();
            transientRetries = 0;
            flushTrips = 0;
            // Round-robin: the next enchantItem is fetched from the next job's pickup chest, so one busy chest cannot
            // starve every other enchantItem.
            inputCursor++;
        }
        long now = System.currentTimeMillis();
        skipUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        supplyPausedUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        state = State.NEXT_ENCHANT;
        // Never print "ready" while every carried piece is waiting for a restock: that line is what made a supply gap
        // look like a frozen bot.
        String supplyGap = supplyPauseSummary();
        module.setWorkflowStatus(supplyGap != null ? supplyGap : "Ready for next input enchantItem");
    }

    /**
     * One completion entry per enchantItem, modeled on the trader's finished-trade log: the Discord embed carries the
     * job, the finished stack, the target set and the duration, and the console gets the same line as plain text.
     */
    private void logTransaction(boolean succeeded) {
        if (target == null || itemSnapshot == null) return;
        String duration = MathHelper.formatDuration(Duration.ofMillis(
            Math.max(0L, System.currentTimeMillis() - transactionStartedAt)));
        if (AutoEnchantPlugin.PLUGIN_CONFIG.logTradeStatusToDiscord) {
            var result = Embed.builder()
                .title(succeeded ? "Enchant Completed" : "Enchant Failed")
                .addField("Enchant ID", target.displayName())
                .addField("Item", module.describeItem(itemSnapshot))
                .addField("Target", targetEnchantmentsText())
                .addField("Duration", duration);
            if (!succeeded && failureReason != null) result.addField("Reason", failureReason);
            module.discordNotification(result);
        } else if (succeeded) {
            module.info("Enchant completed: {} [{}] in {}", module.describeItem(itemSnapshot),
                targetEnchantmentsText(), duration);
        } else {
            module.info("Enchant failed: {} [{}] in {}: {}", module.describeItem(itemSnapshot),
                targetEnchantmentsText(), duration, String.valueOf(failureReason));
        }
    }

    private String targetEnchantmentsText() {
        StringBuilder sb = new StringBuilder();
        targetEnchants.forEach((id, level) -> {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(id).append(' ').append(level);
        });
        return sb.isEmpty() ? "none" : sb.toString();
    }

    /**
     * Drops the in-flight transaction without rotating the pickup chest. Used for a dead or queued
     * client, where the server has already torn the window down and replaying the screen would be wrong.
     */
    void abort() {
        retryReason = null;
        clearTransaction();
        module.setWorkflowStatus("Ready for next input enchantItem");
    }

    /** Clears every per-transaction field; attempt counters and skip cooldowns survive on purpose. */
    private void clearTransaction() {
        state = State.ENTRYPOINT; target = null; itemSnapshot = null; operations = List.of(); books = List.of();
        operationIndex = bookIndex = 0; activeContainerId = 0; sourceSlot = playerSlot = -1;
        failureReason = null; grindRequired = false; pathFuture = null; inventoryFuture = null;
        anvilPosition = null; anvilRecoveryAttempts = 0; targetEnchants = Map.of();
        activePreset = null;
        renamePass = false;
        renameTries = 0;
        renameGivenUp = false;
        backToBooks = false;
        stepLogged = false;
        origin = Origin.NONE;
        openingPosition = null;
        openState = State.ENTRYPOINT;
        afterInventoryState = State.ENTRYPOINT; afterCloseState = State.ENTRYPOINT;
        waitingCost = 0;
        transactionStartedAt = 0;
        outputStored = false;
        flushing = false;
        flushOverflow = false;
        depositSlots = List.of();
        depositRetries = 0;
        closeResent = false;
        // The transient-retry counter is NOT cleared here: it has to survive a parked round, otherwise a step that
        // fails on every attempt would spin forever instead of parking the item (reset() clears it on success).
        takeAttempts = 0;
        nextRetryAt = 0;
        restockItemId = -1;
        restockBefore = 0;
        windowSettlePending = false; windowSettledUntil = 0;
    }

    private int openContainerId() { return CACHE.getPlayerCache().getInventoryCache().getOpenContainerId(); }
    private Container openContainer() { return CACHE.getPlayerCache().getInventoryCache().getOpenContainer(); }

    /**
     * Whether the open window already carries item data. An all-empty player section right after
     * opening means the contents packet has not arrived yet; clicking then builds actions from a
     * frozen window. Mirrors the trader's {@code playerSectionHasItems}.
     */
    private boolean windowContentsReady() {
        Container container = openContainer();
        if (container == null) return false;
        int start = Math.max(0, container.getSize() - 36);
        for (int slot = start; slot < container.getSize(); slot++) {
            ItemStack stack = container.getItemStack(slot);
            if (stack != null && stack != Container.EMPTY_STACK) return true;
        }
        return false;
    }
    private int topSlots() { return ContainerTypeInfoRegistry.REGISTRY.get(openContainer().getType()).topSlots(); }
    private boolean isStorageWindow() { return openContainer().getType() != ContainerType.ANVIL && openContainer().getType() != ContainerType.GRINDSTONE && topSlots() >= 9; }
    private boolean isWaitingForWindow() { return state == State.RESTOCK_INPUT_WITHDRAW || state == State.RESTOCK_ENCHANTED_BOOK_WITHDRAW || state == State.GRINDSTONE_AWAIT_INTERACT || state == State.ENCHANT_AWAIT_INTERACT_WITH_ANVIL || state == State.STORE_AWAIT_INTERACT || state == State.POST_ENCHANT_INPUT_AWAIT_INTERACT || state == State.POST_ENCHANT_OVERFLOW_AWAIT_INTERACT || state == State.RESTOCK_ANVIL_WITHDRAW; }
    private int inventoryContainerSlot(int playerInventorySlot, int top) { return top + playerInventorySlot - 9; }
    private int findEmptyPlayerSlot(int top) {
        for (int slot = 9; slot <= 44; slot++) if (openContainer().getItemStack(inventoryContainerSlot(slot, top)) == Container.EMPTY_STACK) return slot;
        return -1;
    }
    private int findPlayerItem(ItemStack reference) {
        if (reference == null) return -1;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack candidate = playerItem(slot);
            if (sameStackIdentity(reference, candidate)) return slot;
        }
        return -1;
    }
    private int findPlayerItemById(int itemId) {
        for (int slot = 9; slot <= 44; slot++) { ItemStack stack = playerItem(slot); if (stack != null && stack.getId() == itemId) return slot; }
        return -1;
    }
    private ItemStack playerItem(int slot) {
        if (openContainerId() != 0) {
            int top = topSlots();
            return openContainer().getItemStack(inventoryContainerSlot(slot, top));
        }
        return CACHE.getPlayerCache().getInventoryCache().getPlayerInventory().getItemStack(slot);
    }
    private boolean sameStackIdentity(ItemStack a, ItemStack b) {
        if (a == null || b == null || a.getId() != b.getId()) return false;
        return module.catalog().enchantments(a).equals(module.catalog().enchantments(b))
            && module.catalog().priorWorkPenalty(a) == module.catalog().priorWorkPenalty(b)
            && module.catalog().customName(a).equals(module.catalog().customName(b));
    }

    private int findBook(EnchantPresetCatalog.Enchant required) {
        for (int slot = 9; slot <= 44; slot++) if (matchesBook(playerItem(slot), required)) return slot;
        return -1;
    }
    private long countBooks(EnchantPresetCatalog.Enchant required) {
        long count = 0;
        for (int slot = 9; slot <= 44; slot++) if (matchesBook(playerItem(slot), required)) count++;
        return count;
    }

    /**
     * True when a book the plan would merge this enchantment into already sits in the BOT inventory. Without this the
     * worker re-fetches the same book after every interrupted merge and buries the inventory in duplicate books.
     */
    private boolean carriedAsIntermediate(EnchantPresetCatalog.Enchant book) {
        for (var op : operations) {
            int slot = findProduct(op.result());
            if (slot < 0) continue;
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != ItemRegistry.ENCHANTED_BOOK.id()) continue;
            if (readEnchants(stack, true).containsKey(book.id())) return true;
        }
        return false;
    }

    /** False when every BOT inventory slot is taken, in which case a withdraw click cannot move anything. */
    private boolean hasFreeInventorySlot() {
        return hasFreeSlots(1);
    }

    /** Free player-inventory slots (9..44), i.e. how many stacks a withdraw click could still place. */
    private int freeInventorySlots() {
        int free = 0;
        for (int slot = 9; slot <= 44; slot++) if (playerItem(slot) == null) free++;
        return free;
    }

    private boolean hasFreeSlots(int needed) {
        return freeInventorySlots() >= needed;
    }

    /**
     * Frees bag space by unloading a finished product that is still carried.
     *
     * <p>The normal way a finished piece leaves the bag is the end of its own transaction. That is too late when the
     * output container was full or unreachable at that moment: the piece stays in the inventory, the next fetch or
     * book withdrawal bounces off the full bag, and the workflow keeps retrying the same click. This route is the
     * missing exit - it claims a finished product exactly like a transaction would and drives the ordinary output
     * states (POST_ENCHANT onwards), so nothing about the deposit logic is duplicated.
     *
     * <p>Only pieces whose enchantment set already equals their job's target are taken: a half-finished piece is never
     * dumped to make room, it stays and is resumed later.
     *
     * @return true when a flush was started and the caller must not continue its own step
     */
    private boolean flushFinishedProduct(String reason) {
        int slot = findFinishedProduct();
        if (slot < 0) return false;
        if (flushTrips >= MAX_FLUSH_TRIPS) {
            // The trader's storeTripAttempts cap: after a few trips that freed nothing the bag is not going to get
            // roomier by itself, so stop opening the chest and carry on with the slots that are left.
            flushTrips = 0;
            module.warn("Auto-enchant unloaded {} times without freeing enough bag space ({} free); continuing",
                MAX_FLUSH_TRIPS, freeInventorySlots());
            return false;
        }
        ItemStack stack = playerItem(slot);
        var job = module.runnableJobFor(stack);
        if (job.isEmpty()) return false;
        target = job.get();
        playerSlot = slot;
        sourceSlot = -1;
        itemSnapshot = stack.clone();
        targetEnchants = targetEnchantsOf(target);
        operations = List.of();
        books = List.of();
        operationIndex = 0;
        bookIndex = 0;
        origin = Origin.INVENTORY;
        grindRequired = false;
        failureReason = null;
        renamePass = false;
        backToBooks = false;
        outputStored = false;
        flushing = true;
        flushOverflow = false;
        flushTrips++;
        depositSlots = List.of();
        depositRetries = 0;
        // A flush is worth starting right away: it must not inherit the back-off of the step that ran out of space.
        nextRetryAt = 0;
        transactionStartedAt = System.currentTimeMillis();
        trace("bag low on space ({}); unloading the finished slot {} {} to free a slot", reason, slot,
            module.describeItem(stack));
        module.setWorkflowStatus("BOT inventory is nearly full; unloading a finished " + module.describeItem(stack)
            + " first");
        state = State.POST_ENCHANT;
        return true;
    }

    /** Slot of a finished product of an enabled job: the stack already carries exactly its job's target set. */
    private int findFinishedProduct() {
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            // A product whose unload container is out of stock was parked: retrying it before its recheck would only
            // reopen the same full chest and, worse, do it once per carried product.
            if (isSupplyPaused(stack)) continue;
            var job = module.runnableJobFor(stack);
            if (job.isEmpty()) continue;
            Map<String, Integer> wanted = targetEnchantsOf(job.get());
            if (wanted.isEmpty()) continue;
            if (!module.catalog().enchantments(stack).equals(wanted)) continue;
            return slot;
        }
        return -1;
    }

    /** The configured target set of one job in the short-id form the planner compares against. */
    private Map<String, Integer> targetEnchantsOf(AutoEnchantConfig.Enchant job) {
        Map<String, Integer> wanted = new LinkedHashMap<>();
        if (job.itemEnchantments != null) {
            job.itemEnchantments.forEach((id, level) -> {
                if (id != null && level > 0) wanted.put(EnchantPresetCatalog.shortId(id), level);
            });
        }
        return wanted;
    }

    /** True when both inputs of the next planned merge are already carried, so the anvil can be used right away. */
    private boolean inputsReady() {
        if (operationIndex >= operations.size()) return false;
        var op = operations.get(operationIndex);
        int left = findProduct(op.left());
        return left >= 0 && findProduct(op.right(), left) >= 0;
    }
    private int findBookInTop(EnchantPresetCatalog.Enchant required) {
        for (int slot = 0; slot < topSlots(); slot++) if (matchesBook(openContainer().getItemStack(slot), required)) return slot;
        return -1;
    }
    private boolean matchesBook(ItemStack stack, EnchantPresetCatalog.Enchant required) {
        if (stack == null || stack.getId() != ItemRegistry.ENCHANTED_BOOK.id()) return false;
        var component = stack.getDataComponentsOrEmpty().get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (component == null || component.getEnchantments().size() != 1) return false;
        var data = component.getEnchantments().entrySet().iterator().next();
        var enchantment = com.zenith.mc.enchantment.EnchantmentRegistry.REGISTRY.get(data.getKey());
        return enchantment != null && EnchantPresetCatalog.shortId(enchantment.name()).equals(EnchantPresetCatalog.shortId(required.id()))
            && data.getValue() == required.level();
    }

    private int findProduct(EnchantExpressionParser.Node node) {
        return findProduct(node, -1);
    }
    private int findProduct(EnchantExpressionParser.Node node, int excludedSlot) {
        // Picking the equipment out of the bag is the one place that must also check the name: a look-alike sword
        // without the configured name is not this job's property.
        for (int slot = 9; slot <= 44; slot++) {
            if (slot == excludedSlot) continue;
            if (productMismatch(playerItem(slot), node, true) == null) return slot;
        }
        return -1;
    }

    private boolean matchesProduct(ItemStack stack, EnchantExpressionParser.Node node) {
        return productMismatch(stack, node, false) == null;
    }

    /**
     * Compares a stack against what a plan node should produce.
     *
     * <p>Anvil previews and freshly picked-up results are compared on item id and enchantments only: they <em>are</em>
     * the item, so demanding the custom name again turns a perfectly good merge into a retry whenever the server
     * returns the result slot without the name (which happens until the rename packet is processed). The name check is
     * only meaningful when choosing the equipment out of the bag - see {@link #findProduct}.
     *
     * <p>Enchantment sets are compared as their printed form rather than with {@link Map#equals}: the same level can
     * arrive boxed as {@code Integer}, {@code Short} or {@code Long} depending on the packet source, and two sets that
     * print identically would then compare unequal - the BOT kept retrying a merge that the anvil had already done.
     *
     * @return null when the stack matches, otherwise a short reason for the log
     */
    private String productMismatch(ItemStack stack, EnchantExpressionParser.Node node, boolean requireName) {
        if (stack == null) return "no item";
        String placeholder = targetPlaceholder();
        boolean equipment = containsTarget(node, placeholder);
        int wantedId = equipment ? configuredItemId() : ItemRegistry.ENCHANTED_BOOK.id();
        if (stack.getId() != wantedId) return "item id " + stack.getId() + " instead of " + wantedId;
        String wantedName = desiredRename();
        if (requireName && equipment && wantedName != null && !renameGivenUp && !needsRename()) {
            String actualName = module.catalog().customName(stack);
            if (!actualName.isEmpty() && !wantedName.equals(actualName)) {
                return "name '" + actualName + "' instead of '" + wantedName + "'";
            }
        }
        String wanted = describeEnchants(expectedEnchants(node, placeholder));
        String actual = describeEnchants(readEnchants(stack, !equipment));
        if (!wanted.equals(actual)) return "enchants " + actual + " instead of " + wanted;
        return null;
    }
    private int configuredItemId() {
        var data = ItemRegistry.REGISTRY.get(EnchantPresetCatalog.shortId(target.item));
        return data == null ? -1 : data.id();
    }
    /** Full registry id the preset catalog expects, e.g. {@code diamond_sword} -> {@code minecraft:diamond_sword}. */
    private String fullItemId(String item) {
        return item == null || item.isBlank() ? "" : "minecraft:" + EnchantPresetCatalog.shortId(item);
    }
    private boolean containsTarget(EnchantExpressionParser.Node node, String placeholder) {
        if (node instanceof EnchantExpressionParser.Leaf leaf) return leaf.name().equals(placeholder)
            || leaf.name().equals(EnchantPresetCatalog.shortId(target.item));
        var merge = (EnchantExpressionParser.Merge) node;
        return containsTarget(merge.left(), placeholder) || containsTarget(merge.right(), placeholder);
    }
    private Map<String, Integer> expectedEnchants(EnchantExpressionParser.Node node, String placeholder) {
        if (node instanceof EnchantExpressionParser.Leaf leaf) {
            if (leaf.name().equals(placeholder) || leaf.name().equals(EnchantPresetCatalog.shortId(target.item))) return Map.of();
            return Map.of(leaf.name(), leaf.level());
        }
        var merge = (EnchantExpressionParser.Merge) node;
        Map<String, Integer> combined = new HashMap<>(expectedEnchants(merge.left(), placeholder));
        expectedEnchants(merge.right(), placeholder).forEach((id, level) -> combined.merge(id, level, (a, b) -> Math.max(a, b) + (a.equals(b) ? 1 : 0)));
        return Map.copyOf(combined);
    }
    private Map<String, Integer> readEnchants(ItemStack stack, boolean book) {
        var type = book ? DataComponentTypes.STORED_ENCHANTMENTS : DataComponentTypes.ENCHANTMENTS;
        var component = stack.getDataComponentsOrEmpty().get(type);
        if (component == null) return Map.of();
        Map<String, Integer> result = new HashMap<>();
        component.getEnchantments().forEach((id, level) -> {
            var data = com.zenith.mc.enchantment.EnchantmentRegistry.REGISTRY.get(id);
            if (data != null) result.put(EnchantPresetCatalog.shortId(data.name()), level);
        });
        return Map.copyOf(result);
    }
    private record OptionalIntCost(int value) { }
    private OptionalIntCost currentCost() {
        var price = module.currentAnvilCost();
        return new OptionalIntCost(price.orElse(-1));
    }
    private List<com.zenith.feature.inventory.actions.InventoryAction> clickPlayerItemIntoStation(int playerSlot, int top, int stationSlot) {
        return List.of(
            new ClickItem(openContainerId(), inventoryContainerSlot(playerSlot, top), ClickItemAction.LEFT_CLICK),
            new ClickItem(openContainerId(), stationSlot, ClickItemAction.LEFT_CLICK)
        );
    }
    private int findCurrentEquipment() { return findPlayerItem(itemSnapshot); }

    // ---------------------------------------------------------------------------------------------
    // Step tracing. Every enchanting step logs what was picked up, which enchantments the book carries,
    // what the anvil preview showed and what came out of it, so a wrong transaction can be read from the
    // proxy log alone instead of being guessed from the final "Enchant failed" line.
    // ---------------------------------------------------------------------------------------------

    private void trace(String msg, Object... args) {
        module.info("[trace] " + msg, args);
    }

    /** Prints a parsed merge node back in the preset's own notation, e.g. {@code (sword + looting_3)}. */
    private String describeNode(EnchantExpressionParser.Node node) {
        if (node instanceof EnchantExpressionParser.Leaf leaf) {
            return leaf.level() > 0 ? leaf.name() + "_" + leaf.level() : leaf.name();
        }
        var merge = (EnchantExpressionParser.Merge) node;
        return "(" + describeNode(merge.left()) + " + " + describeNode(merge.right()) + ")";
    }

    /** Level-sorted {@code id level} list, short ids only; {@code {}} for an empty set. */
    private String describeEnchants(Map<String, Integer> enchants) {
        if (enchants == null || enchants.isEmpty()) return "{}";
        StringBuilder out = new StringBuilder("{");
        new java.util.TreeMap<>(enchants).forEach((id, level) -> {
            if (out.length() > 1) out.append(", ");
            out.append(EnchantPresetCatalog.shortId(id)).append(' ').append(level);
        });
        return out.append('}').toString();
    }

    /** Full plan dump: the tree the preset (or planner) produced and every merge it will perform. */
    private void tracePlan() {
        // A retry after a supply gap rebuilds the same plan: print the full tree once, then a single line, so an
        // hour-long missing book does not bury the log under identical dumps.
        String signature = activePreset + "|" + operations.hashCode() + "|" + books + "|"
            + describeEnchants(targetEnchants) + "|" + desiredRename();
        if (signature.equals(lastPlanSignature)) {
            trace("plan unchanged: {} book(s), {} merge(s)", books.size(), operations.size());
            return;
        }
        lastPlanSignature = signature;
        trace("plan preset={} books={} merges={} target={}", activePreset == null ? "custom" : activePreset,
            books.size(), operations.size(), describeEnchants(targetEnchants));
        for (int i = 0; i < operations.size(); i++) {
            var op = operations.get(i);
            // The planned cost is the wiki's number for this merge (enchantment value + both prior-work penalties),
            // so a plan that would run into the 39-level limit is visible before any XP is spent. The merge that
            // carries the name costs one level more: the anvil charges renaming on top of the merge itself, and that
            // is exactly the level the BOT has to have in hand for that step.
            int planned = op.expectedCost() + (i == renameOperationIndex() && needsRename() ? 1 : 0);
            trace("  merge[{}] {} + {} => {} (~{} levels{})", i, describeNode(op.left()), describeNode(op.right()),
                describeNode(op.result()), planned, i == renameOperationIndex() && needsRename() ? ", name +1" : "");
        }
        for (int i = 0; i < books.size(); i++) {
            trace("  book[{}] {} {}", i, books.get(i).id(), books.get(i).level());
        }
        // The name is written into the anvil's name field together with the inputs of the first merge that involves
        // the equipment, so the renamed piece climbs the rest of the tree and the rename never adds its cost to an
        // already expensive merge.
        String name = desiredRename();
        if (name != null) {
            int renameAt = renameOperationIndex();
            trace("  name field '{}' rides on merge[{}]{} (already applied: {})",
                name.isEmpty() ? "(clear)" : name, renameAt,
                renameAt < 0 ? " - no equipment merge, a separate rename pass will run"
                    : " " + describeNode(operations.get(renameAt).result()),
                !needsRename());
        }
    }

    /** Dumps every stack the BOT carries; used when an anvil result cannot be located. */
    private void traceInventory(String why) {
        trace("inventory dump ({}):", why);
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            trace("  slot {} {} enchants={} penalty={}", slot, module.describeItem(stack),
                describeEnchants(readEnchants(stack, stack.getId() == ItemRegistry.ENCHANTED_BOOK.id())),
                module.catalog().priorWorkPenalty(stack));
        }
    }

    private String targetPlaceholder() {
        if (activePreset != null) {
            var preset = module.catalog().get(activePreset);
            if (preset != null) return preset.targetPlaceholder();
        }
        return EnchantPresetCatalog.shortId(target.item);
    }

    /**
     * One container that came up empty: what it was supposed to hold, when it was last seen empty, when it may be
     * looked at again and how many times in a row it has been empty (which drives the growing recheck interval).
     */
    private static final class Stockout {
        private String what = "";
        private long lastSeenAt;
        private long nextCheckAt;
        private int misses;
    }
}

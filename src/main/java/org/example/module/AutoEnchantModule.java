package org.example.module;

import com.github.rfresh2.EventConsumer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.zenith.Proxy;
import com.zenith.cache.data.entity.Entity;
import com.zenith.cache.data.inventory.Container;
import com.zenith.discord.Embed;
import com.zenith.event.client.ClientBotTick;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.DropItem;
import com.zenith.feature.inventory.actions.RenameItem;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.feature.player.Input;
import com.zenith.feature.player.InputRequest;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ContainerTypeInfoRegistry;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.module.api.Module;
import com.zenith.module.impl.KillAura;
import com.zenith.network.client.ClientSession;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.util.RequestFuture;
import com.zenith.util.math.MathHelper;
import com.zenith.util.timer.Timer;
import com.zenith.util.timer.Timers;
import dev.zenithproxy.autoenchant.BuildConstants;
import org.example.AutoEnchantConfig;
import org.example.AutoEnchantPlugin;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetDataPacket;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static org.example.AutoEnchantPlugin.PLUGIN_CONFIG;

public class AutoEnchantModule extends Module {
    private State state = State.ENTRYPOINT;
    private PathingRequestFuture restockPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture restockWithdrawFuture = RequestFuture.rejected;
    private RequestFuture anvilBlockCraftFuture = RequestFuture.rejected;
    private PathingRequestFuture interactWithGrindstone = PathingRequestFuture.rejected;
    private PathingRequestFuture interactWithAnvil = PathingRequestFuture.rejected;
    private RequestFuture inventoryActionFuture = RequestFuture.rejected;
    private RequestFuture enchantActionFuture = RequestFuture.rejected;
    private RequestFuture shiftResultFuture = RequestFuture.rejected;
    private PathingRequestFuture storePathingFuture = PathingRequestFuture.rejected;
    private RequestFuture storeDepositFuture = RequestFuture.rejected;
    private PathingRequestFuture postEnchantPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture postEnchantDepositFuture = RequestFuture.rejected;
    private final Timer waitForRestockTimer = Timers.tickTimer();
    private final Timer waitForInteractTimer = Timers.tickTimer();
    private final Timer waitForActionTimer = Timers.tickTimer();
    private int preEnchantOutputCount = 0;
    private int preEnchantInputCount = 0;
    private int preEnchantExpCount = 0;
    private long unchantStartTime = System.nanoTime();
    private long enchantStartTime = System.nanoTime();

    public record Marker(String role, BlockPos framePos, BlockPos containerPos, ItemStack displayedItem) { }

    private ClientboundContainerSetDataPacket containerSetDataPacket = null;

    /**
     * An equipment frame that declares one job: the displayed equipment is the template for that item type (its
     * enchantments are the target set, its custom name is the rename target), the first container below it is that
     * job's output chest, and the first redstone lamp in the column above the frame (not necessarily adjacent) is its
     * on/off switch. Blank pieces to enchant come
     * from the shared pickup chest marked by a redstone-dust frame.
     */
    public record JobFrame(String item, BlockPos framePos, BlockPos containerPos, Boolean lampOn,
                           Map<String, Integer> enchantments, String customName) {
        /** Signature of the displayed template; a changed signature re-imports the target set on the next scan. */
        public String spec() {
            String enchants = enchantments.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
            return enchants + "|" + (customName == null ? "" : customName);
        }

        /** True when the template actually states a target: enchantments or a name. A blank enchantItem states nothing. */
        public boolean declaresTarget() {
            return !enchantments.isEmpty() || (customName != null && !customName.isBlank());
        }
    }

    private static final int SCAN_INTERVAL_TICKS = 100;
    private static final int SUPPLY_SCAN_DEPTH = 8;
    /** How far above a frame the column is searched for its redstone lamp; the lamp need not touch the frame. */
    private static final int LAMP_SCAN_HEIGHT = 8;
    private static final float MIN_HEALTH = 12;
    /** Hard ceiling for one XP goal: a farm that runs this long has a problem, whatever the level check says. */
    private static final int KILLAURA_MAX_SECONDS = 900;
    /** A farm that did not gain a single level in this long is not producing: the spot is empty or the aura is stuck. */
    private static final long NO_XP_PROGRESS_MS = 30_000L;

    private final EnchantPresetCatalog catalog = new EnchantPresetCatalog();
    {
        catalog.load();
    }
    private final Map<String, List<BlockPos>> stations = new LinkedHashMap<>();
    private final List<Marker> markers = new ArrayList<>();
    private final List<JobFrame> jobFrames = new ArrayList<>();
    private final AtomicInteger tickCounter = new AtomicInteger();
    private volatile String status = "Not scanned";
    private volatile long lastScanAt;
    private volatile int lastAnvilContainerId = -1;
    private volatile int lastAnvilCost = -1;
    private volatile long lastAnvilCostAt;
    private volatile List<String> configProblems = List.of();
    /** Enabled, complete jobs; refreshed by {@link #revalidateConfig()} instead of on every state-machine tick. */
    private volatile List<AutoEnchantConfig.Enchant> runnableJobs = List.of();
    /** Round-robin over {@link #runnableJobs}, the way the trader rotates its trades. */
    private final EnchantIterator enchantIterator = new EnchantIterator();
    private volatile List<String> markerProblems = List.of();
    private volatile List<String> markerDetails = List.of();
    private volatile List<String> jobDetails = List.of();
    private volatile List<String> stationDetails = List.of();
    private volatile String detailSignature = "";
    /** Last reported marker-problem text; the 5s scan must not repeat an unchanged warning forever. */
    private volatile String problemSignature = "";
    /**
     * True while this module owns {@code CONFIG.client.extra.killAura.enabled}, i.e. has switched the BOT's own
     * KillAura for the XP farm. {@link #killAuraUserSetting} keeps the user's own value and is restored on stop.
     */
    private volatile boolean killAuraOwned;
    /** The user's own KillAura setting, captured when the XP farm takes the aura over. */
    private volatile boolean killAuraUserSetting;
    private volatile boolean xpFarmingActive;
    private volatile boolean xpInterrupted;
    /** Level the current anvil operation needs, taken from the plan or from the cost the anvil window shows. */
    private volatile int xpGoalLevel = -1;
    /** The anvil cost that produced {@link #xpGoalLevel}, kept for the status line. */
    private volatile int xpGoalCost = -1;
    /** Wall-clock start of the running farm; 0 while the BOT is not farming. */
    private volatile long xpFarmStartedAt;
    /** Level when the farm started, so a farm that gains nothing can be told apart from a slow one. */
    private volatile int xpFarmStartLevel;
    private volatile double xpLastX, xpLastY, xpLastZ;
    private volatile String xpStartDimension;
    private volatile PathingRequestFuture killSpotPath;
    private volatile long killSpotPathAt;
    private int killSpotAttempts;
    private static final long KILL_SPOT_TIMEOUT_MS = 60_000L;
    private static final int MAX_KILL_SPOT_ATTEMPTS = 3;
    /** Radius around the BOT that counts as "silverfish at the farm"; the kill window is measured against it. */
    private static final double KILL_WINDOW_RADIUS = 12.0;
    /** Start of the last kill window; the gap in the config is measured from here. */
    private volatile long lastKillWindowAt;
    private volatile long killWindowStartedAt;
    /** Silverfish alive at the start of the window, so kills are counted without a hit hook. */
    private volatile long killWindowStartMobs;
    /** Step the workflow returns to when the window closes. */
    private State afterKillWindowState = State.ENTRYPOINT;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, this::onTick),
            of(ClientBotTick.Starting.class, e -> reset()),
            of(ClientBotTick.Stopped.class, e -> reset())
        );
    }

    @Override
    public void onDisable() {
        reset();
        // The module no longer drives combat: hand the user's own KillAura setting back.
        releaseKillAura();
    }

    @Override
    public void onEnable() {
        // Config is loaded before the first tick; build the runnable-job cache now so a restart
        // with an unchanged layout does not wait for a scan that reports no change.
        revalidateConfig();
        // While the module runs it owns combat: enchanting work comes first, and the EXP farm switches the BOT's own
        // KillAura back on only when an anvil cost cannot be paid (see switchKillAura).
        switchKillAura(false);
    }

    public int getPriority() {
        // Above both Baritone and the BOT's own KillAura. The kill windows switch the aura on and off constantly;
        // KillAura submits high-priority noAction requests while it runs, so a priority based on Baritone alone lets
        // it steal every inventory click and submit() comes back rejected over and over.
        KillAura aura = MODULE.get(KillAura.class);
        return Math.max(Baritone.getPriority(), aura == null ? Integer.MIN_VALUE : aura.getPriority()) + 100;
    }

    private void reset() {
        BARITONE.stop();
        missingContainerUntil.clear();
        missingContainerMisses.clear();
        state =  State.ENTRYPOINT;
        waitForInteractTimer.reset();
        waitForRestockTimer.reset();
        waitForActionTimer.reset();
        stopXpFarming();
        switchKillAura(false);
        xpInterrupted = false;
        abort();
        // Batch markers are per-session: stale counts from a previous run would hand fresh-item treatment to pieces
        // that were already in the bag, and a stale latch would block every future fetch.
        restockBatch.clear();
        closeBatch();
        enchantIterator.reset();
        resetEnchantCounter();
    }

    private void resetEnchantCounter() {
        preEnchantExpCount = 0;
        preEnchantOutputCount = 0;
        preEnchantInputCount = 0;
        enchantStartTime = System.nanoTime();
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("anvil-enchant")
            .setPriority(-5)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundContainerSetDataPacket.class, this::onContainerSetData)
                .build())
            .build();
    }

    private ClientboundContainerSetDataPacket onContainerSetData(ClientboundContainerSetDataPacket packet, ClientSession session) {
        this.containerSetDataPacket = packet;
        debug("ContainerSetData: {}", packet);
        if (packet.getRawProperty() == 0) {
            lastAnvilContainerId = packet.getContainerId();
            lastAnvilCost = packet.getValue();
            lastAnvilCostAt = System.currentTimeMillis();
        }
        return packet;
    }

    private void onTick(ClientBotTick event) {
        if (Proxy.getInstance().isInQueue() || !CACHE.getPlayerCache().getThePlayer().isAlive()) {
            state = State.ENTRYPOINT;
            return;
        }
        if ( !PLUGIN_CONFIG.enabled ) {
            // Turning the module off mid-farm must hand the user's own KillAura setting back.
            stopXpFarming();
            releaseKillAura();
            return;
        }
        if (BOT.isSneaking()) {
            INPUTS.submit(InputRequest.builder()
                .owner(this)
                .priority(getPriority() - 1)
                .input(Input.builder().sneaking(false).build())
                .build());
        }

        monitorXpFarming();
        tick();
        if (tickCounter.incrementAndGet() >= SCAN_INTERVAL_TICKS) {
            tickCounter.set(0);
            scanNow();
        }
    }

    public synchronized void scanNow() {
        AutoEnchantConfig config = AutoEnchantPlugin.PLUGIN_CONFIG;
        if (config == null || CACHE.getPlayerCache().getThePlayer() == null) {
            status = "Waiting for BOT player data";
            return;
        }
        // Manual positions win; any position left unset is discovered here and written back to the config.
        if (autoFillStations(config)) com.zenith.Globals.saveConfigAsync();
        int radius = Math.max(1, Math.min(config.scanHorizontalBlocks, 64));
        Map<String, List<BlockPos>> foundStations = new LinkedHashMap<>();
        foundStations.put("anvil", new ArrayList<>());
        foundStations.put("grindstone", new ArrayList<>());
        addConfiguredStations(config.anvil == null ? List.of() : List.of(config.anvil), "anvil", foundStations);
        addConfiguredStations(config.grindstone == null ? List.of() : List.of(config.grindstone), "grindstone", foundStations);

        List<Marker> foundMarkers = new ArrayList<>();
        List<JobFrame> foundJobFrames = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        int frameCount = 0;
        int unrecognizedMarkers = 0;
        for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType() != EntityType.ITEM_FRAME && entity.getEntityType() != EntityType.GLOW_ITEM_FRAME) continue;
            if (entity.distanceSqTo(CACHE.getPlayerCache().getThePlayer()) > (radius + 3.0) * (radius + 3.0)) continue;
            frameCount++;
            ItemStack shown = entity.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
            // An empty frame is decoration, a placeholder or a frame whose template was taken out: nothing to
            // declare and nothing to fix, so it must never show up as a marker issue.
            if (shown == null) continue;
            String role = markerRole(shown);
            if (role == null) {
                // A redstone-block frame used to mark the semi-finished chest. That chest no longer exists (finished and
                // half-done pieces both leave through the equipment frame's container), so a leftover marker is
                // skipped silently instead of showing up as an unrecognised frame.
                if (shown.getId() == com.zenith.mc.item.ItemRegistry.REDSTONE_BLOCK.id()) continue;
                // Not a book/anvil/redstone marker: a damageable enchantItem here declares a job for that item type.
                JobFrame jobFrame = jobFrame(entity, shown);
                if (jobFrame == null) {
                    unrecognizedMarkers++;
                } else {
                    foundJobFrames.add(jobFrame);
                    if (jobFrame.containerPos() == null) {
                        addProblem(problems, "job frame " + jobFrame.framePos() + " (" + jobFrame.item()
                            + "): no output container at " + candidateText(jobFrame.framePos()));
                    }
                }
                continue;
            }
            BlockPos frame = entity.blockPos();
            BlockPos container = resolveContainer(frame);
            if (container == null) {
                addProblem(problems, role + " frame " + frame + ": no container at " + candidateText(frame));
                continue;
            }
            foundMarkers.add(new Marker(role, frame, container, shown));
        }
        int newJobs = applyJobFrames(foundJobFrames, problems);
        boolean bindingsChanged = applyMarkerBindings(foundMarkers);

        Map<String, List<BlockPos>> bindingsByRole = new LinkedHashMap<>();
        for (Marker marker : foundMarkers) {
            bindingsByRole.computeIfAbsent(marker.role(), key -> new ArrayList<>()).add(marker.containerPos());
        }
        bindingsByRole.forEach((role, positions) -> {
            List<BlockPos> distinct = positions.stream().distinct().toList();
            if (distinct.size() > 1) addProblem(problems, role + " has " + distinct.size() + " conflicting bindings: " + distinct);
        });

        stations.clear();
        stations.putAll(foundStations);
        markers.clear();
        markers.addAll(foundMarkers);
        jobFrames.clear();
        jobFrames.addAll(foundJobFrames);
        markerProblems = List.copyOf(problems);

        List<String> newStationDetails = new ArrayList<>();
        foundStations.forEach((role, positions) -> {
            for (BlockPos pos : positions) newStationDetails.add(role + " | " + blockLabel(pos) + " | " + distanceText(pos));
        });
        List<String> newMarkerDetails = new ArrayList<>();
        for (Marker marker : foundMarkers) newMarkerDetails.add(describeMarker(marker));
        stationDetails = List.copyOf(newStationDetails);
        markerDetails = List.copyOf(newMarkerDetails);
        jobDetails = jobs().stream()
            .map(this::describeJob)
            .toList();

        lastScanAt = System.currentTimeMillis();
        int enabledJobs = (int) jobs().stream()
            .filter(job -> job.enabled).count();
        status = "Scanned: " + stations.get("anvil").size() + " anvils, "
            + stations.get("grindstone").size() + " grindstones, " + markers.size() + " container markers, "
            + foundJobFrames.size() + " job frame(s); " + enabledJobs + " of "
            + jobs().size() + " job(s) enabled"
            + (newJobs > 0 ? ", " + newJobs + " new" : "")
            + (bindingsChanged ? ", chest markers synced" : "")
            + (problems.isEmpty() ? "" : ", " + problems.size() + " marker issue(s)");

        // Only log the layout when it changed; the periodic scan would otherwise repeat the same lines forever.
        String signature = String.join("\n", stationDetails) + "\n--\n" + String.join("\n", markerDetails)
            + "\n--\n" + String.join("\n", jobDetails);
        if (!signature.equals(detailSignature)) {
            detailSignature = signature;
            info("Auto-enchant scan layout ({} stations, {} markers, {} jobs):",
                stationDetails.size(), markerDetails.size(), jobDetails.size());
            for (String line : stationDetails) info("  station {}", line);
            for (String line : markerDetails) info("  marker {}", line);
            for (String line : jobDetails) info("  job {}", line);
        }
        // Warn once per change instead of every scan tick: the layout is only re-logged when it differs.
        String problemKey = problems.isEmpty() ? (foundMarkers.isEmpty() && foundJobFrames.isEmpty() && unrecognizedMarkers > 0
            ? "no-usable-frames" : "") : String.join("; ", problems);
        boolean signatureChanged = !problemKey.equals(problemSignature);
        problemSignature = problemKey;
        if (!problems.isEmpty()) {
            if (signatureChanged) warn("Auto-enchant marker issues ({}): {}", problems.size(), String.join("; ", problems));
        } else if ("no-usable-frames".equals(problemKey) && signatureChanged) {
            warn("Auto-enchant found {} item frame(s) but nothing usable ({} unrecognized display items). "
                + "An equipment frame declares a job and its output chest; book, anvil and redstone-dust frames need a "
                + "container below them. Run 'autoEnchant scan' for the per-frame problem list.", frameCount, unrecognizedMarkers);
        }
    }

    /**
     * Reads one equipment frame as a job declaration. Only equipment from {@link EquipmentType} is accepted: a
     * decorative frame must never create an entry nobody asked for, and the command's add/set choices use the same list.
     *
     * <p>The displayed stack is a template, not the item that will be processed: its enchantments become the target
     * set and its custom name becomes the rename target. The output chest is the first container below the frame.
     */
    private JobFrame jobFrame(Entity entity, ItemStack shown) {
        var itemData = ItemRegistry.REGISTRY.get(shown.getId());
        if (itemData == null) return null;
        if (!EquipmentType.isEquipment(itemData.name())) return null;
        BlockPos frame = entity.blockPos();
        return new JobFrame(EnchantPresetCatalog.shortId(itemData.name()), frame, supplyContainerPos(frame),
            lampState(frame), catalog.enchantments(shown), catalog.customName(shown));
    }

    /**
     * Lit lamp switches the job on, unlit off. The lamp does not have to sit directly on top of the frame: the first
     * redstone lamp anywhere in the same column above it (up to {@link #LAMP_SCAN_HEIGHT} blocks) is used, so a frame
     * mounted on a wall or a pillar with the lamp on the pillar's top still works. Null when the column holds no lamp,
     * in which case the stored on/off value wins.
     */
    private Boolean lampState(BlockPos frame) {
        if (!World.isChunkLoadedBlockPos(frame.x(), frame.z())) return null;
        BlockPos cursor = frame.add(0, 1, 0);
        for (int dy = 1; dy <= LAMP_SCAN_HEIGHT; dy++, cursor = cursor.add(0, 1, 0)) {
            if (!World.isInWorldBounds(cursor.x(), cursor.y(), cursor.z())) return null;
            if (World.getBlock(cursor.x(), cursor.y(), cursor.z()).id() != BlockRegistry.REDSTONE_LAMP.id()) continue;
            return World.getBlockState(cursor).getProperty(com.zenith.mc.block.properties.api.BlockStateProperties.LIT);
        }
        return null;
    }

    /**
     * Creates missing job entries for the scanned frames and keeps existing ones in sync: the finished chest always
     * follows the frame's own container, a lamp (when present) always wins over the stored on/off value, and a
     * template whose enchantments or name changed is re-imported. Two frames for the same enchantItem are reported instead
     * of silently fighting over the finished chest.
     */
    private int applyJobFrames(List<JobFrame> frames, List<String> problems) {
        AutoEnchantConfig config = AutoEnchantPlugin.PLUGIN_CONFIG;
        List<JobFrame> previousFrames = List.copyOf(jobFrames);
        Map<String, BlockPos> boundChests = new LinkedHashMap<>();
        int added = 0;
        boolean changed = false;
        for (JobFrame frame : frames) {
            BlockPos previousChest = boundChests.putIfAbsent(frame.item(), frame.containerPos());
            if (previousChest != null && frame.containerPos() != null && !previousChest.equals(frame.containerPos())) {
                addProblem(problems, "duplicate frame for " + frame.item() + ": " + previousChest + " and "
                    + frame.containerPos() + " — keeping " + previousChest);
                continue;
            }
            AutoEnchantConfig.Enchant job = null;
            for (AutoEnchantConfig.Enchant candidate : config.enchants.values()) {
                if (candidate != null && frame.item().equals(EnchantPresetCatalog.shortId(candidate.item))) {
                    job = candidate;
                    break;
                }
            }
            JobFrame previous = previousFrames.stream()
                .filter(old -> old.item().equals(frame.item()))
                .findFirst().orElse(null);
            boolean templateChanged = previous == null || !previous.spec().equals(frame.spec());
            if (job == null) {
                String key = frame.item();
                if (config.enchants.containsKey(key)) key = key + "_2";
                job = new AutoEnchantConfig.Enchant();
                job.item = frame.item();
                config.enchants.put(key, job);
                if (frame.containerPos() != null) job.outputChest = frame.containerPos();
                if (frame.declaresTarget()) importFrameSpec(job, frame);
                job.enabled = Boolean.TRUE.equals(frame.lampOn());
                added++;
                changed = true;
                continue;
            }
            if (frame.containerPos() != null && !frame.containerPos().equals(job.outputChest)) {
                job.outputChest = frame.containerPos();
                changed = true;
            }
            // A blank template never overwrites an existing target set. A changed template does, but only when it
            // actually states enchantments or a name; otherwise the stored setup wins.
            if (templateChanged && frame.declaresTarget()) {
                importFrameSpec(job, frame);
                changed = true;
            }
            if (frame.lampOn() != null && frame.lampOn() != job.enabled) { job.enabled = frame.lampOn(); changed = true; }
        }
        if (changed) {
            com.zenith.Globals.saveConfigAsync();
            revalidateConfig();
        }
        return added;
    }

    /**
     * Copies the template shown in a frame into the job: its enchantments become {@code itemEnchantments} and its custom
     * name becomes the rename target. The mode (wiki preset or hand-written set) is decided by the state machine at runtime.
     */
    public void importFrameSpec(AutoEnchantConfig.Enchant job, JobFrame frame) {
        job.item = frame.item();
        String name = frame.customName() == null ? "" : frame.customName().trim();
        if (name.length() > 50) name = name.substring(0, 50);
        job.renameName = name;
        Map<String, Integer> scanned = frame.enchantments();
        if (scanned.isEmpty()) return;
        job.itemEnchantments.clear();
        scanned.forEach((id, level) -> job.itemEnchantments.put(EnchantPresetCatalog.shortId(id), level));
    }

    /**
     * Persists the container roles declared by their own frames: the redstone-dust frame binds every job's pickup
     * chest, an anvil frame binds the anvil supply and a book frame binds that enchantment's book container.
     *
     * <p>The unload chest is not bound here: it is the equipment frame's own container (the job's
     * {@code outputChest}), which serves both finished and half-done pieces. A {@code overflowChestPos} from an older
     * config is dropped by {@code AutoEnchantConfigSerializer} so a job cannot keep sending pieces to a chest that is
     * no longer scanned.
     */
    private boolean applyMarkerBindings(List<Marker> foundMarkers) {
        AutoEnchantConfig config = AutoEnchantPlugin.PLUGIN_CONFIG;
        boolean changed = false;
        for (Marker marker : foundMarkers) {
            BlockPos pos = marker.containerPos();
            if (pos == null) continue;
            String role = marker.role();
            if ("input".equals(role)) {
                // The redstone-dust frame declares the shared pickup chest: equipment comes from here, so it always wins.
                for (AutoEnchantConfig.Enchant job : config.enchants.values()) {
                    if (job == null || pos.equals(job.inputItemChest)) continue;
                    job.inputItemChest = pos;
                    changed = true;
                }
            } else if ("anvil_supply".equals(role)) {
                if (!pos.equals(config.anvilChest)) { config.anvilChest = pos; changed = true; }
            } else if (role.startsWith("book:")) {
                String id = role.substring("book:".length());
                if (!pos.equals(config.enchantBook.get(id))) { config.enchantBook.put(id, pos); changed = true; }
            }
        }
        if (changed) {
            com.zenith.Globals.saveConfigAsync();
            revalidateConfig();
        }
        return changed;
    }

    /** One-line summary of a job for scan/list output: switch, target set, resolved plan and chests. */
    public String describeJob(AutoEnchantConfig.Enchant job) {
        return (job.enabled ? "[on] " : "[off] ") + job.displayName() + " | " + planLabel(job) + " "
            + enchantmentSummary(job)
            + " | in " + containerText(job, "input") + " -> out " + containerText(job, "output")
            + (job.getRenameName().isEmpty() ? " | rename (clear)" : " | rename \"" + job.getRenameName() + "\"");
    }

    /** Whether the target set follows a wiki preset tree or the custom minimum-penalty planner. */
    private String planLabel(AutoEnchantConfig.Enchant job) {
        if (job.itemEnchantments == null || job.itemEnchantments.isEmpty()) return "no target";
        Map<String, Integer> target = new LinkedHashMap<>();
        job.itemEnchantments.forEach((id, level) -> target.put(EnchantPresetCatalog.shortId(id), level));
        String preset = catalog.matchPreset(job.item, target);
        return preset == null ? "custom" : "preset " + preset;
    }

    /** The job's own coordinate, or the station-level one it inherits, or why there is none. */
    private String containerText(AutoEnchantConfig.Enchant job, String role) {
        var resolved = containerPosition(job, role);
        return resolved.map(pos -> pos + (jobOwns(job, role) ? "" : " (station)"))
            .orElse("no " + role + " chest");
    }

    /** True when the job pins this role itself instead of inheriting a station-level chest. */
    private boolean jobOwns(AutoEnchantConfig.Enchant job, String role) {
        if (job == null) return false;
        return switch (role) {
            case "input" -> !BlockPos.ZERO.equals(job.inputItemChest);
            // Both unload roles are the job's own equipment-frame chest.
            case "output", "overflow" -> !BlockPos.ZERO.equals(job.outputChest);
            default -> false;
        };
    }

    /** Target set as {@code sharpness 5, looting 3, ...}, shortened so a scan line stays readable. */
    private String enchantmentSummary(AutoEnchantConfig.Enchant job) {
        if (job.itemEnchantments == null || job.itemEnchantments.isEmpty()) return "0 enchant(s)";
        String joined = job.itemEnchantments.object2IntEntrySet().stream()
            .map(entry -> EnchantPresetCatalog.shortId(entry.getKey()) + " " + entry.getIntValue())
            .sorted()
            .collect(java.util.stream.Collectors.joining(", "));
        return joined.length() > 90 ? joined.substring(0, 87) + "..." : joined;
    }

    /**
     * Book and anvil-supply markers resolve to the first container found by walking straight down from the frame's own
     * block, so a frame on a wall, a shelf, or two blocks above a chest all work without a fixed offset.
     */
    private BlockPos supplyContainerPos(BlockPos frame) {
        int depth = Math.max(1, Math.min(SUPPLY_SCAN_DEPTH, 16));
        for (int dy = 0; dy <= depth; dy++) {
            BlockPos candidate = frame.add(0, -dy, 0);
            if (isUsableContainer(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Every marker resolves to the first container below its frame: book chests, anvil supply, the pickup chest
     * (redstone dust) and the unload chest (the equipment frame itself, used for finished and half-done pieces alike).
     * One rule for all roles means a frame on a wall, a shelf, or several blocks above a container all work, and the
     * layout never depends on the frame's facing. Only chests, barrels and shulker boxes are accepted as a target.
     */
    private BlockPos resolveContainer(BlockPos frame) {
        return supplyContainerPos(frame);
    }

    private boolean isUsableContainer(BlockPos pos) {
        return pos != null && World.isChunkLoadedBlockPos(pos.x(), pos.z()) && isContainerBlock(pos);
    }

    /** True when a loaded chest, barrel or shulker box is at this position; used to warn about bad job coordinates. */
    public boolean isContainerAt(BlockPos pos) {
        return isUsableContainer(pos);
    }

    private String blockNameAt(BlockPos pos) {
        if (pos == null) return "no attached block";
        return World.isChunkLoadedBlockPos(pos.x(), pos.z())
            ? World.getBlock(pos.x(), pos.y(), pos.z()).name() + " at " + pos
            : "unloaded at " + pos;
    }

    /** Human-readable list of the depths a marker scanned, so a bad setup can be fixed without guessing. */
    private String candidateText(BlockPos frame) {
        return scanText(frame);
    }

    /** Describes the downward scan result, including what was found at the first few depths. */
    private String scanText(BlockPos frame) {
        int depth = Math.max(1, Math.min(SUPPLY_SCAN_DEPTH, 16));
        StringBuilder out = new StringBuilder("downward scan (depth ").append(depth).append("): ");
        for (int dy = 0; dy <= depth; dy++) {
            BlockPos candidate = frame.add(0, -dy, 0);
            if (!World.isChunkLoadedBlockPos(candidate.x(), candidate.z())) { out.append("unloaded at ").append(candidate).append(' '); break; }
            String name = World.getBlock(candidate.x(), candidate.y(), candidate.z()).name();
            out.append('-').append(dy).append('=').append(shortItemId(name)).append(' ');
        }
        return out.toString();
    }

    private BlockPos attachedContainerPosition(BlockPos frame, Entity entity) {
        if (!(entity.getObjectData() instanceof org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction facing)) return null;
        return attachedBlock(frame, facing);
    }

    private boolean isBookRole(String role) {
        return role.startsWith("book:") || role.equals("book_unknown");
    }

    private void addProblem(List<String> problems, String problem) {
        if (problems.size() < 20) problems.add(problem);
    }

    private boolean addConfiguredStations(List<BlockPos> coordinates, String role, Map<String, List<BlockPos>> target) {
        if (coordinates == null || coordinates.isEmpty()) return false;
        List<BlockPos> positions = target.get(role);
        for (BlockPos pos : coordinates) {
            if (pos == null || !World.isChunkLoadedBlockPos(pos.x(), pos.z())) continue;
            int blockId = World.getBlock(pos.x(), pos.y(), pos.z()).id();
            if ((role.equals("anvil") && isAnvil(blockId))
                || (role.equals("grindstone") && blockId == BlockRegistry.GRINDSTONE.id())) positions.add(pos);
        }
        return !positions.isEmpty();
    }

    /**
     * Fills an unset anvil or grindstone with the nearest loaded block within the configured scan range. A manually
     * configured position is never overwritten; it stays the authority even when its block is currently unloaded.
     */
    private boolean autoFillStations(AutoEnchantConfig config) {
        boolean changed = false;
        if (isUnset(config.anvil)) {
            BlockPos found = findNearestBlock(this::isAnvil);
            if (found != null) {
                config.anvil = found;
                changed = true;
                info("Auto-enchant: scanned anvil at {}", found);
            }
        }
        if (isUnset(config.grindstone)) {
            BlockPos found = findNearestBlock(id -> id == BlockRegistry.GRINDSTONE.id());
            if (found != null) {
                config.grindstone = found;
                changed = true;
                info("Auto-enchant: scanned grindstone at {}", found);
            }
        }
        return changed;
    }

    private boolean isUnset(BlockPos pos) {
        return pos == null || BlockPos.ZERO.equals(pos);
    }

    /** Nearest loaded block around the BOT that matches, or null when the scan range contains none. */
    private BlockPos findNearestBlock(java.util.function.IntPredicate match) {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) return null;
        AutoEnchantConfig config = AutoEnchantPlugin.PLUGIN_CONFIG;
        int radius = Math.max(1, Math.min(config.scanHorizontalBlocks, 32));
        int yRadius = Math.max(1, Math.min(config.scanVerticalBlocks, 16));
        int centerX = (int) Math.floor(player.getX());
        int centerY = (int) Math.floor(player.getY());
        int centerZ = (int) Math.floor(player.getZ());
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = centerX - radius; x <= centerX + radius; x++) {
            for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = centerY - yRadius; y <= centerY + yRadius; y++) {
                    if (!match.test(World.getBlock(x, y, z).id())) continue;
                    double dx = player.getX() - (x + 0.5);
                    double dy = player.getY() - (y + 0.5);
                    double dz = player.getZ() - (z + 0.5);
                    double distance = dx * dx + dy * dy + dz * dz;
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new BlockPos(x, y, z);
                    }
                }
            }
        }
        return best;
    }

    /**
     * Resolves one container role for a job. The job's own coordinate is authoritative (and never silently replaced by
     * another chest when its block is missing), then a station-level chest from config, then a unique marker binding.
     * {@code job} may be null for roles that are not job-specific, such as the anvil supply container.
     *
     * <p>{@code output} and {@code overflow} are the same chest on purpose: the equipment frame's container is the one
     * unload chest, and finished pieces, half-done pieces and failed items all go back there. "overflow" is kept as a
     * role name so the states and command modes that mention it still resolve to something real.
     */
    public java.util.Optional<BlockPos> containerPosition(AutoEnchantConfig.Enchant job, String role) {
        if (job != null) {
            BlockPos owned = switch (role) {
                case "input" -> job.inputItemChest;
                case "output", "overflow" -> job.outputChest;
                default -> null;
            };
            if (owned != null && !BlockPos.ZERO.equals(owned)) return java.util.Optional.of(owned);
        }
        if ("anvil_supply".equals(role)) {
            BlockPos supply = AutoEnchantPlugin.PLUGIN_CONFIG.anvilChest;
            if (supply != null && !BlockPos.ZERO.equals(supply)) return java.util.Optional.of(supply);
        }
        List<BlockPos> matches = markers.stream().filter(marker -> marker.role().equals(role))
            .map(Marker::containerPos).filter(java.util.Objects::nonNull).distinct().toList();
        return matches.size() == 1 ? java.util.Optional.of(matches.getFirst()) : java.util.Optional.empty();
    }

    public java.util.Optional<BlockPos> containerPosition(String role) {
        return containerPosition(null, role);
    }

    public java.util.Optional<BlockPos> bookContainerPosition(String enchantment, int level) {
        BlockPos pos = AutoEnchantPlugin.PLUGIN_CONFIG.enchantBook.get(EnchantPresetCatalog.shortId(enchantment));
        return pos == null || BlockPos.ZERO.equals(pos) ? java.util.Optional.empty() : java.util.Optional.of(pos);
    }

    /** Every configured job, in command order, including the disabled drafts created by scanning. */
    public List<AutoEnchantConfig.Enchant> jobs() {
        return AutoEnchantPlugin.PLUGIN_CONFIG.enchants.values().stream().filter(java.util.Objects::nonNull).toList();
    }

    /** Jobs the state machine may actually pick up: enabled and complete (item, target set, input and output chest). */
    public List<AutoEnchantConfig.Enchant> runnableJobs() {
        return runnableJobs;
    }

    /** The enabled job whose item matches this stack, if any. */
    public java.util.Optional<AutoEnchantConfig.Enchant> jobFor(ItemStack item) {
        if (item == null) return java.util.Optional.empty();
        var actual = ItemRegistry.REGISTRY.get(item.getId());
        if (actual == null) return java.util.Optional.empty();
        for (AutoEnchantConfig.Enchant job : jobs()) {
            if (!job.enabled) continue;
            if (sameItem(job.item, actual)) return java.util.Optional.of(job);
        }
        return java.util.Optional.empty();
    }

    private boolean sameItem(String configuredId, com.zenith.mc.item.ItemData actual) {
        var configured = ItemRegistry.REGISTRY.get(EnchantPresetCatalog.shortId(configuredId));
        return configured != null && configured.name().equals(actual.name());
    }

    /** Like {@link #jobFor} but only for jobs the state machine can finish, so a draft is never started. */
    public java.util.Optional<AutoEnchantConfig.Enchant> runnableJobFor(ItemStack item) {
        var match = jobFor(item);
        if (match.isEmpty()) return match;
        return runnableJobs.contains(match.get()) ? match : java.util.Optional.empty();
    }

    private boolean isContainerBlock(BlockPos pos) {
        return isContainerBlockName(World.getBlock(pos.x(), pos.y(), pos.z()).name());
    }

    /** Storage accepted as a marker target: chests, barrels and every shulker box colour. */
    private boolean isContainerBlockName(String name) {
        return name.equals("chest")
            || name.equals("trapped_chest")
            || name.equals("barrel")
            || name.endsWith("shulker_box");
    }

    private boolean isAnvil(int blockId) {
        return blockId == BlockRegistry.ANVIL.id()
            || blockId == BlockRegistry.CHIPPED_ANVIL.id()
            || blockId == BlockRegistry.DAMAGED_ANVIL.id();
    }

    private BlockPos attachedBlock(BlockPos frame, org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction facing) {
        return switch (facing) {
            case DOWN -> frame.add(0, 1, 0);
            case UP -> frame.add(0, -1, 0);
            case NORTH -> frame.add(0, 0, 1);
            case SOUTH -> frame.add(0, 0, -1);
            case WEST -> frame.add(1, 0, 0);
            case EAST -> frame.add(-1, 0, 0);
        };
    }

    /** Any anvil enchantItem marks the anvil supply container: plain, chipped or damaged. */
    private boolean isAnvilItem(ItemStack itemStack) {
        int id = itemStack.getId();
        return id == ItemRegistry.ANVIL.id()
            || id == ItemRegistry.CHIPPED_ANVIL.id()
            || id == ItemRegistry.DAMAGED_ANVIL.id();
    }

    private String markerRole(ItemStack itemStack) {
        if (itemStack.getId() == ItemRegistry.REDSTONE.id()) return "input";
        if (isAnvilItem(itemStack)) return "anvil_supply";
        if (itemStack.getId() == ItemRegistry.ENCHANTED_BOOK.id()) {
            var component = itemStack.getDataComponentsOrEmpty().get(DataComponentTypes.STORED_ENCHANTMENTS);
            if (component == null || component.getEnchantments().size() != 1) return "book_unknown";
            var enchantment = component.getEnchantments().entrySet().iterator().next();
            var data = EnchantmentRegistry.REGISTRY.get(enchantment.getKey());
            if (data == null) return "book_unknown";
            return "book:" + EnchantPresetCatalog.shortId(data.name());
        }
        return null;
    }

    /** One-line description of a marker: role, displayed enchantItem with enchantments, and both coordinates. */
    public String describeMarker(Marker marker) {
        return marker.role() + " | " + describeItem(marker.displayedItem())
            + " | frame " + marker.framePos() + " -> " + blockLabel(marker.containerPos())
            + " | " + distanceText(marker.framePos());
    }

    /** Item name (custom name included) plus every enchantment it carries, e.g. {@code diamond_sword "钻石剑mx" [sharpness 5]}. */
    public String describeItem(ItemStack stack) {
        if (stack == null) return "none";
        var item = ItemRegistry.REGISTRY.get(stack.getId());
        String base = item == null ? "unknown_item_id=" + stack.getId() : shortItemId(item.name());
        StringBuilder out = new StringBuilder(base);
        if (stack.getAmount() > 1) out.append(" x").append(stack.getAmount());
        // The custom name is printed because "did this piece come out named" is otherwise invisible in the logs.
        String customName = catalog().customName(stack);
        if (!customName.isEmpty()) out.append(" \"").append(customName).append('"');
        String enchantments = enchantmentText(stack);
        if (!enchantments.isEmpty()) out.append(" [").append(enchantments).append(']');
        return out.toString();
    }

    /** Stored (enchanted book) and regular enchantments merged into one readable, level-sorted list. */
    private String enchantmentText(ItemStack stack) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        for (var type : List.of(DataComponentTypes.STORED_ENCHANTMENTS, DataComponentTypes.ENCHANTMENTS)) {
            var component = stack.getDataComponentsOrEmpty().get(type);
            if (component == null || component.getEnchantments().isEmpty()) continue;
            component.getEnchantments().forEach((id, level) -> {
                var data = EnchantmentRegistry.REGISTRY.get(id);
                merged.merge(data == null ? "id=" + id : data.name(), level, Math::max);
            });
        }
        return merged.entrySet().stream()
            .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
            .map(entry -> entry.getKey() + " " + entry.getValue())
            .collect(java.util.stream.Collectors.joining(", "));
    }

    private String shortItemId(String name) {
        return name.startsWith("minecraft:") ? name.substring("minecraft:".length()) : name;
    }

    /** Block name with its coordinate, or the reason it could not be read. */
    private String blockLabel(BlockPos pos) {
        if (pos == null) return "no attached block";
        if (!World.isChunkLoadedBlockPos(pos.x(), pos.z())) return "unloaded " + pos;
        return shortItemId(World.getBlock(pos.x(), pos.y(), pos.z()).name()) + " " + pos;
    }

    /** Distance from the BOT, so a far-away station can be told apart from the one being used. */
    private String distanceText(BlockPos pos) {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null || pos == null) return "?m";
        double dx = player.getX() - (pos.x() + 0.5);
        double dy = player.getY() - (pos.y() + 0.5);
        double dz = player.getZ() - (pos.z() + 0.5);
        return String.format(java.util.Locale.ROOT, "%.1fm", Math.sqrt(dx * dx + dy * dy + dz * dz));
    }

    /** Writes a one-shot view of the nearby player, containers, and every cached enchantItem frame to the proxy log. */
    public synchronized String debugSnapshot() {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) return "BOT player data unavailable; see proxy log after connecting";
        int radius = Math.max(1, Math.min(AutoEnchantPlugin.PLUGIN_CONFIG.scanHorizontalBlocks, 32));
        int yRadius = Math.max(1, Math.min(AutoEnchantPlugin.PLUGIN_CONFIG.scanVerticalBlocks, 16));
        int px = (int) Math.floor(player.getX()), py = (int) Math.floor(player.getY()), pz = (int) Math.floor(player.getZ());
        info("[AutoEnchant DEBUG] BOT pos=({},{},{}) block=({}, {}, {}) yaw={} pitch={} headYaw={} facing={} dimension={}",
            player.getX(), player.getY(), player.getZ(), px, py, pz, player.getYaw(), player.getPitch(), player.getHeadYaw(),
            cardinal(player.getYaw()), World.getCurrentDimension().name());
        info("[AutoEnchant DEBUG] radius={} verticalRadius={} cachedEntities={} scannedStations={} scannedMarkers={}",
            radius, yRadius, CACHE.getEntityCache().getEntities().size(), stations, markers.size());

        int containerCount = 0;
        int containerLines = 0;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                if (!World.isChunkLoadedBlockPos(x, z)) continue;
                for (int y = py - yRadius; y <= py + yRadius; y++) {
                    var block = World.getBlock(x, y, z);
                    if (!isContainerBlockName(block.name())) continue;
                    containerCount++;
                    if (containerLines++ < 100) info("[AutoEnchant DEBUG] container {} at {},{},{} distance={}",
                        block.name(), x, y, z, String.format(java.util.Locale.ROOT, "%.2f", Math.sqrt(distanceSq(px, py, pz, x, y, z))));
                }
            }
        }
        if (containerCount > 100) info("[AutoEnchant DEBUG] container listing capped at 100; total={}", containerCount);

        int frameCount = 0;
        int eligibleCount = 0;
        for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (entity.getEntityType() != EntityType.ITEM_FRAME && entity.getEntityType() != EntityType.GLOW_ITEM_FRAME) continue;
            double distanceSq = entity.distanceSqTo(player);
            if (distanceSq > (radius + 3.0) * (radius + 3.0)) continue;
            frameCount++;
            ItemStack shown = entity.getMetadataValue(8, MetadataTypes.ITEM, ItemStack.class);
            String shownName = shown == null ? "metadata[8]=null" : describeItem(shown);
            String role = shown == null ? null : markerRole(shown);
            if (role == null && shown != null) {
                JobFrame declared = jobFrame(entity, shown);
                if (declared != null) role = "job:" + EnchantPresetCatalog.shortId(declared.item());
            }
            String frameFacing = String.valueOf(entity.getObjectData());
            BlockPos frame = entity.blockPos();
            String backing;
            BlockPos resolved = role == null ? null : resolveContainer(frame);
            if (resolved != null) {
                backing = blockNameAt(resolved);
            } else if (role == null) {
                backing = "unrecognized marker; attached " + blockNameAt(attachedContainerPosition(frame, entity));
            } else {
                backing = "NO CONTAINER; tried " + candidateText(frame);
            }
            boolean recognized = resolved != null;
            if (recognized) eligibleCount++;
            info("[AutoEnchant DEBUG] frame entity={} type={} pos=({},{},{}) block={} yaw={} pitch={} objectData={} enchantItem={} role={} backing={} accepted={}",
                entity.getEntityId(), entity.getEntityType(), String.format(java.util.Locale.ROOT, "%.3f", entity.getX()),
                String.format(java.util.Locale.ROOT, "%.3f", entity.getY()), String.format(java.util.Locale.ROOT, "%.3f", entity.getZ()), frame,
                entity.getYaw(), entity.getPitch(), frameFacing, shownName, role == null ? "unrecognized marker" : role,
                backing, recognized);
        }
        String summary = "Debug snapshot logged: " + frameCount + " enchantItem frames (" + eligibleCount
            + " recognized), " + containerCount + " nearby containers; see latest.log";
        info("[AutoEnchant DEBUG] {}", summary);
        return summary;
    }

    private String cardinal(float yaw) {
        float normalized = (yaw % 360 + 360) % 360;
        if (normalized < 45 || normalized >= 315) return "SOUTH";
        if (normalized < 135) return "WEST";
        if (normalized < 225) return "NORTH";
        return "EAST";
    }

    private double distanceSq(int x, int y, int z, int xx, int yy, int zz) {
        double dx = x - xx, dy = y - yy, dz = z - zz;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Rebuilds the runnable-job cache and explains every enabled job that is not runnable yet. */
    public void revalidateConfig() {
        List<String> problems = new ArrayList<>();
        List<AutoEnchantConfig.Enchant> runnable = new ArrayList<>();
        for (AutoEnchantConfig.Enchant job : jobs()) {
            String issue = jobIssue(job);
            if (issue == null) runnable.add(job);
            else if (job != null && job.enabled) problems.add(job.displayName() + ": " + issue);
        }
        runnableJobs = List.copyOf(runnable);
        enchantIterator.refresh(runnableJobs);
        configProblems = List.copyOf(problems);
        if (!configProblems.isEmpty()) {
            warn("Auto-enchant configuration has {} issue(s): {}", configProblems.size(), String.join("; ", configProblems));
        }
    }

    /** Why an enabled job cannot run yet, or null when it is complete. Disabled jobs report "disabled" but stay silent. */
    private String jobIssue(AutoEnchantConfig.Enchant job) {
        if (job == null) return "invalid entry";
        if (!job.enabled) return "disabled";
        String id = EnchantPresetCatalog.shortId(job.item == null ? "" : job.item);
        if (id.isBlank() || ItemRegistry.AIR.name().equals(id) || ItemRegistry.REGISTRY.get(id) == null)
            return "item is not an ItemRegistry short name";
        if (job.itemEnchantments == null || job.itemEnchantments.isEmpty()) {
            // Rename-only mode ("不附魔仅改名") has no enchantments to apply: the anvil pass exists purely to write
            // the name, so a job without a target set is complete as long as it names something.
            if (!PLUGIN_CONFIG.renameOnly) return "no target enchantments";
            if (job.getRenameName().isBlank()) return "rename-only mode but no rename name";
        }
        if (job.inputItemChest == null || BlockPos.ZERO.equals(job.inputItemChest)) return "no input item chest";
        if (job.outputChest == null || BlockPos.ZERO.equals(job.outputChest)) return "no output chest";
        return null;
    }

    public EnchantPresetCatalog catalog() { return catalog; }
    public Map<String, List<BlockPos>> stations() { return Map.copyOf(stations); }
    public List<Marker> markers() { return List.copyOf(markers); }
    /** Human-readable line per discovered anvil/grindstone: role, block name, coordinate, distance. */
    public List<String> stationDetails() { return List.copyOf(stationDetails); }
    /** Human-readable line per discovered marker: role, enchantItem with enchantments, frame/container coordinates. */
    public List<String> markerDetails() { return List.copyOf(markerDetails); }
    /** Human-readable line per configured job, refreshed by each scan. */
    public List<String> jobDetails() { return List.copyOf(jobDetails); }
    public String status() { return status; }
    void setWorkflowStatus(String value) { status = value; }
    public long lastScanAt() { return lastScanAt; }
    public int lastAnvilContainerId() { return lastAnvilContainerId; }
    public int lastAnvilCost() { return lastAnvilCost; }
    public long lastAnvilCostAt() { return lastAnvilCostAt; }
    public List<String> configProblems() { return configProblems; }
    /** Marker bindings that were recognized by role but could not be resolved to a container. */
    public List<String> markerProblems() { return markerProblems; }
    /**
     * Level cost the server reported for the currently open anvil, i.e. exactly the number the vanilla anvil screen
     * turns into "Too Expensive!" once it reaches 40. Property 0 is advisory for us only in the sense that the result
     * slot is checked too; the value itself is authoritative.
     */
    public java.util.OptionalInt currentAnvilCost() {
        int openId = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        long age = System.currentTimeMillis() - lastAnvilCostAt;
        // The cost only changes when the slots change, and the state machine forgets it before every new placement, so a
        // generous window here keeps a slow preview readable instead of looking like a missing cost.
        long ttl = Math.max(10_000L, AutoEnchantPlugin.PLUGIN_CONFIG.waitForInteractTimeoutTicks * 50L);
        if (openId <= 0 || openId != lastAnvilContainerId || lastAnvilCost < 0 || age > ttl) {
            return java.util.OptionalInt.empty();
        }
        return java.util.OptionalInt.of(lastAnvilCost);
    }

    /**
     * Forgets the cached anvil cost. Called right before new inputs are placed so a preview can never read the cost
     * of the previous merge and mistake it for the current one.
     */
    public void clearAnvilCost() {
        lastAnvilCost = -1;
        lastAnvilCostAt = 0;
    }

    /** Length of one kill window in milliseconds, from the config (40 ticks = 2 seconds by default). */
    private long killWindowMs() {
        return Math.max(0L, AutoEnchantPlugin.PLUGIN_CONFIG.killWindowTicks) * 50L;
    }

    /** Minimum gap between two window starts in milliseconds (60 ticks = 3 seconds by default). */
    private long killWindowGapMs() {
        return Math.max(1L, AutoEnchantPlugin.PLUGIN_CONFIG.killWindowIntervalTicks) * 50L;
    }

    /** One window as a short human-readable string, e.g. {@code 2.0s on / 3.0s gap}. */
    private String killWindowText() {
        return String.format(java.util.Locale.ROOT, "%.1fs on / %.1fs gap",
            killWindowMs() / 1000.0, killWindowGapMs() / 1000.0);
    }

    /**
     * True when the next short fight is due.
     *
     * <p>Only the KILL source fights, a configured window length of 0 disables the windows entirely, and a running XP
     * goal keeps the aura on continuously - that is a farm, not a window, so no window may cut into it.
     */
    private boolean killWindowDue() {
        if (AutoEnchantPlugin.PLUGIN_CONFIG.experienceSource != AutoEnchantConfig.ExperienceSource.KILL) return false;
        if (AutoEnchantPlugin.PLUGIN_CONFIG.killWindowTicks <= 0) return false;
        if (xpFarmingActive) return false;
        // Only at the exp spot: a window is a short interruption of the enchant work there, never a fight somewhere
        // else on the map (walking, a distant anvil) where the silverfish farm is not the point.
        if (!atExpSpot()) return false;
        return System.currentTimeMillis() - lastKillWindowAt >= killWindowGapMs();
    }

    /** True when no exp spot is configured, or the BOT stands within its interaction tolerance of it. */
    private boolean atExpSpot() {
        BlockPos spot = AutoEnchantPlugin.PLUGIN_CONFIG.ExpFarmSpot;
        if (spot == null || BlockPos.ZERO.equals(spot)) return true;
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) return false;
        double dx = player.getX() - (spot.x() + 0.5);
        double dy = player.getY() - spot.y();
        double dz = player.getZ() - (spot.z() + 0.5);
        return dx * dx + dz * dz <= 9.0 && Math.abs(dy) <= 4.0;
    }

    /**
     * Starts one kill window: KillAura on, the current step remembered, and the workflow parked until
     * {@link #tickKillWindow()} sees enough silverfish dead (or the window ran out).
     *
     * <p>This is the anti-cull mechanism. The silverfish farm deletes a mob that was left alive for about nine
     * seconds, so a long stretch of container work must be interrupted by a few swings instead of being run in one
     * go. Killing a couple of them every few seconds also keeps the orbs small and close: one big kill at the end of
     * a long pause drops them all at once and much of that XP is never picked up.
     *
     * @return false when there is nothing to kill around the BOT; the caller then simply continues with its own step,
     *     so an empty farm costs no time at all
     */
    private boolean beginKillWindow(State resume) {
        long nearby = countSilverfish();
        if (nearby <= 0) {
            // Nothing to clear: the window would only stand around with the aura on. The gap is still consumed, so an
            // empty farm does not retry the check on every single tick.
            lastKillWindowAt = System.currentTimeMillis();
            return false;
        }
        afterKillWindowState = resume;
        killWindowStartedAt = System.currentTimeMillis();
        lastKillWindowAt = killWindowStartedAt;
        killWindowStartMobs = nearby;
        switchKillAura(true);
        state = State.KILL_WINDOW;
        status = "Killing a few silverfish at the exp spot (" + killWindowText() + ") before the next step";
        trace("[trace] kill window opened: {} silverfish nearby, resuming at {}", nearby, resume);
        return true;
    }

    /** Closes the window once enough silverfish died or its time is up, then hands the workflow back. */
    private void tickKillWindow() {
        if (openContainerId() != 0) {
            // A window never runs behind an open screen: the server drops attacks while a GUI is up.
            closeContainer(State.KILL_WINDOW);
            return;
        }
        long killed = killWindowStartMobs - countSilverfish();
        long elapsed = System.currentTimeMillis() - killWindowStartedAt;
        int target = Math.max(1, AutoEnchantPlugin.PLUGIN_CONFIG.killWindowMinKills);
        if (killed < target && elapsed < killWindowMs()) return;
        switchKillAura(false);
        lastKillWindowAt = System.currentTimeMillis();
        state = afterKillWindowState;
        trace("[trace] kill window closed: {} silverfish killed in {}ms", killed, elapsed);
    }

    /** Silverfish alive around the BOT, i.e. what a kill window is measured against. */
    private long countSilverfish() {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) return 0;
        double radiusSq = KILL_WINDOW_RADIUS * KILL_WINDOW_RADIUS;
        long count = 0;
        for (Entity entity : CACHE.getEntityCache().getEntities().values()) {
            if (!(entity instanceof com.zenith.cache.data.entity.EntityLiving living)) continue;
            if (!living.isAlive() || living.getEntityType() != EntityType.SILVERFISH) continue;
            double dx = living.getX() - player.getX();
            double dy = living.getY() - player.getY();
            double dz = living.getZ() - player.getZ();
            if (dx * dx + dy * dy + dz * dz <= radiusSq) count++;
        }
        return count;
    }

    /**
     * Starts farming: walks to the configured exp spot, switches the BOT's own KillAura on and leaves it on until
     * {@link #monitorXpFarming()} sees the goal reached.
     *
     * <p>The spot is where the BOT already works from - anvil, pickup chest and every book chest are within reach -
     * so this is not a trip: the aura simply stays on while the workflow waits for the level. That is why there is no
     * burst timer any more; a burst only made sense when farming meant walking somewhere.
     *
     * @return false while the BOT is still walking to the spot, and also when farming cannot start at all; only the
     *     latter marks the XP wait as interrupted (see {@link #consumeXpInterruption()})
     */
    public synchronized boolean startFarming() {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) { xpInterrupted = true; return false; }
        // Only KILL fights; EXP and NONE wait for the level without touching the aura (see ensureXpForCost).
        if (AutoEnchantPlugin.PLUGIN_CONFIG.experienceSource != AutoEnchantConfig.ExperienceSource.KILL) return false;
        if (MODULE.get(KillAura.class) == null) { xpInterrupted = true; return false; }
        if (!player.isAlive() || player.getHealth() < MIN_HEALTH) {
            xpInterrupted = true;
            return false;
        }
        String dimension = World.getCurrentDimension().name();
        // The spot is a place to fight from, not a teleport: the farm only starts once the BOT stands there.
        if (!reachKillSpot()) return false;
        switchKillAura(true);
        xpFarmingActive = true;
        xpInterrupted = false;
        long now = System.currentTimeMillis();
        xpFarmStartedAt = now;
        xpFarmStartLevel = player.getLevel();
        xpLastX = player.getX();
        xpLastY = player.getY();
        xpLastZ = player.getZ();
        xpStartDimension = dimension;
        status = "Farming XP at the exp spot: level " + player.getLevel() + " / goal " + xpGoalLevel
            + " (anvil cost " + xpGoalCost + ")";
        info("[trace] XP farm started: level {} -> goal {} (anvil cost {})",
            player.getLevel(), xpGoalLevel, xpGoalCost);
        return true;
    }

    /**
     * Walks the BOT to the configured kill spot. Returns true when no spot is configured (fight where the BOT stands)
     * or the BOT is already close enough; false while walking, and a genuinely unreachable spot interrupts the farm.
     */
    private boolean reachKillSpot() {
        BlockPos spot = AutoEnchantPlugin.PLUGIN_CONFIG.ExpFarmSpot;
        if (spot == null || BlockPos.ZERO.equals(spot)) return true;
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) return false;
        double dx = player.getX() - (spot.x() + 0.5);
        double dy = player.getY() - spot.y();
        double dz = player.getZ() - (spot.z() + 0.5);
        if (dx * dx + dz * dz <= 9.0 && Math.abs(dy) <= 4.0) {
            killSpotPath = null;
            killSpotAttempts = 0;
            return true;
        }
        long now = System.currentTimeMillis();
        if (killSpotPath != null && killSpotPath.isCompleted()) {
            boolean accepted = killSpotPath.isAccepted();
            killSpotPath = null;
            if (!accepted) {
                xpInterrupted = true;
                warn("Auto-enchant could not path to the kill spot {},{},{}", spot.x(), spot.y(), spot.z());
                return false;
            }
            // Accepted but finished somewhere else: retry a couple of times, then give up instead of pacing forever.
            if (++killSpotAttempts >= MAX_KILL_SPOT_ATTEMPTS) {
                xpInterrupted = true;
                warn("Auto-enchant stopped {} blocks from the kill spot after {} attempts",
                    String.format(java.util.Locale.ROOT, "%.1f", Math.sqrt(dx * dx + dz * dz)), killSpotAttempts);
                return false;
            }
        }
        if (killSpotPath == null) {
            killSpotPath = BARITONE.pathTo(spot.x(), spot.y(), spot.z());
            killSpotPathAt = now;
            status = "Walking to kill spot " + spot;
            return false;
        }
        if (now - killSpotPathAt > KILL_SPOT_TIMEOUT_MS) {
            killSpotPath = null;
            xpInterrupted = true;
            warn("Auto-enchant did not reach the kill spot within {}s", KILL_SPOT_TIMEOUT_MS / 1000);
            return false;
        }
        return false;
    }

    /**
     * Stops the XP goal bookkeeping, including a farm that is still running. The callers switch the aura, since only
     * they know whether the level is enough; everything that identifies the farm is cleared here so the next one
     * starts from a clean slate.
     */
    private void stopXpFarming() {
        xpFarmingActive = false;
        killSpotPath = null;
        killSpotAttempts = 0;
    }

    /**
     * Writes the BOT's own KillAura setting. Two things drive it: the XP gate ("does the anvil cost more level than the
     * BOT has") and the kill windows that keep the silverfish farm from being culled while the BOT works. It is never
     * left on for its own sake - every window switches it off again.
     *
     * <p>The user's own value is remembered the first time the module touches the setting and handed back by
     * {@link #releaseKillAura()} when the module stops.
     */
    private synchronized void switchKillAura(boolean enabled) {
        if (CONFIG.client.extra.killAura.enabled == enabled) return;
        if (!killAuraOwned) {
            killAuraUserSetting = CONFIG.client.extra.killAura.enabled;
            killAuraOwned = true;
        }
        CONFIG.client.extra.killAura.enabled = enabled;
        KillAura killAura = MODULE.get(KillAura.class);
        if (killAura != null) killAura.syncEnabledFromConfig();
        info("[trace] KillAura switched {}: {}", enabled ? "on" : "off",
            enabled ? "killing silverfish at the exp spot" : "back to the enchant work");
    }

    /** Hands the user's own KillAura setting back when this module stops driving it. */
    private synchronized void releaseKillAura() {
        if (!killAuraOwned) return;
        killAuraOwned = false;
        if (CONFIG.client.extra.killAura.enabled == killAuraUserSetting) return;
        CONFIG.client.extra.killAura.enabled = killAuraUserSetting;
        KillAura killAura = MODULE.get(KillAura.class);
        if (killAura != null) killAura.syncEnabledFromConfig();
        info("[trace] KillAura restored to the user's setting: {}", killAuraUserSetting ? "on" : "off");
    }

    /**
     * Decides whether the BOT has enough levels for the operation in front of it. Nothing else enables KillAura: a
     * free or an affordable operation switches it off, and farming only starts for the level the plan asks for plus a
     * small reserve.
     *
     * <p>The cost normally comes from the plan ({@link #plannedCostOf(int)}), so the level is farmed <em>before</em>
     * the anvil is opened; the anvil's own "cost N levels" line is then only a verification, which is what the calls
     * from {@code verifyAnvilPreview} do.
     *
     * <p>In {@code KILL} mode the farm is continuous - the BOT stands at the exp spot the whole time - so this method
     * is a level gate, not a fight scheduler.
     *
     * @param requiredLevel the level cost, from the plan or from the open anvil window
     * @return true when the BOT may take the result now
     */
    public boolean ensureXpForCost(int requiredLevel) {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) { xpInterrupted = true; return false; }
        int cost = Math.max(0, requiredLevel);
        // A free operation is no reason to start fighting: the BOT could stand at the anvil and take the result.
        if (cost == 0) {
            finishXp("the operation is free");
            return true;
        }
        // Farm exactly the level this call needs - not twice it and not a reserve above it. The anvil will not take
        // the result before that level, and every level beyond it is time spent killing at the exp spot instead of
        // standing at the anvil. The callers budget over the rest of the piece (see plannedRemainingCostOf), so this
        // is still one trip per piece; the goal already carries the plan's observed cost gap (planCostBias), so the
        // anvil's own cost line is covered without a second farm, and what a cheaper merge leaves over stays on the
        // BOT for the merges of the same piece that follow. Whatever is left when the piece ends is spent by the next
        // piece's own budget check instead of being farmed twice for the same level.
        int goal = cost;
        // A different operation means a different goal: its own farm budget.
        if (xpGoalLevel != goal || xpGoalCost != cost) {
            xpFarmStartedAt = 0;
        }
        xpGoalLevel = goal;
        xpGoalCost = cost;
        boolean windowOpen = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0;
        if (player.getLevel() >= goal) {
            finishXp("level " + player.getLevel() + " covers the anvil cost " + cost);
            return true;
        }
        // Fight only with both hands free: while a window is open the state machine is about to close it, and a path
        // request submitted under an open GUI is rejected by the server.
        if (windowOpen) {
            status = "Anvil needs " + cost + " levels (level " + player.getLevel() + "); closing it before farming XP";
            return false;
        }
        var source = AutoEnchantPlugin.PLUGIN_CONFIG.experienceSource;
        if (source == AutoEnchantConfig.ExperienceSource.NONE) {
            xpInterrupted = true;
            warn("Auto-enchant needs level {} but experienceSource is NONE", goal);
            status = "Anvil needs level " + goal + " but experienceSource is NONE";
            return false;
        }
        if (source == AutoEnchantConfig.ExperienceSource.EXP) {
            // EXP is not a fight: the proxy's own XP feature has to deliver the level, so the BOT just waits here.
            // KillAura is never switched on in this mode (see startFarming).
            status = "Waiting for XP: level " + player.getLevel() + " -> " + goal + " (anvil cost " + cost + ")";
            return false;
        }
        // Whether the BOT can farm at all is decided inside startFarming; walking to the spot is not a failure.
        if (!xpFarmingActive) startFarming();
        if (xpFarmingActive) {
            status = "Farming XP for the anvil: level " + player.getLevel() + " -> " + goal
                + " (anvil cost " + cost + ")";
        }
        return false;
    }

    public boolean consumeXpInterruption() {
        boolean interrupted = xpInterrupted;
        xpInterrupted = false;
        return interrupted;
    }

    /**
     * Watches the running farm. The level reaching the goal ends it; a death, a bad dimension and a teleport end it
     * as a failure; two budgets keep a dead spot from parking the workflow: a hard ceiling for the whole goal, and a
     * short "no level gained at all" window that catches a spot without silverfish or an aura that never swings.
     */
    private void monitorXpFarming() {
        if (!xpFarmingActive) return;
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) { interruptXp("BOT player data unavailable"); return; }
        if (!player.isAlive()) { interruptXp("BOT died"); return; }
        if (player.getHealth() < MIN_HEALTH) { interruptXp("Health below " + (int) MIN_HEALTH); return; }
        if (xpGoalLevel > 0 && player.getLevel() >= xpGoalLevel) {
            finishXp("level " + player.getLevel() + " reaches the anvil cost goal " + xpGoalLevel);
            return;
        }
        String dimension = World.getCurrentDimension().name();
        if (!dimension.equals(xpStartDimension)) { interruptXp("BOT changed dimension"); return; }
        double dx = player.getX() - xpLastX, dy = player.getY() - xpLastY, dz = player.getZ() - xpLastZ;
        xpLastX = player.getX(); xpLastY = player.getY(); xpLastZ = player.getZ();
        if (dx * dx + dy * dy + dz * dz > 16 * 16) { interruptXp("BOT was teleported during XP farming"); return; }
        long now = System.currentTimeMillis();
        // Hard ceiling for the whole goal.
        if (xpFarmStartedAt > 0 && now - xpFarmStartedAt > KILLAURA_MAX_SECONDS * 1000L) {
            interruptXp("XP farming ran " + KILLAURA_MAX_SECONDS + "s and level " + player.getLevel()
                + " is still short of " + xpGoalLevel);
            return;
        }
        // Not a single level in this many seconds means the farm is empty or the aura is not landing hits: say so
        // instead of standing there for the full ceiling.
        if (xpFarmStartedAt > 0 && now - xpFarmStartedAt > NO_XP_PROGRESS_MS
            && player.getLevel() <= xpFarmStartLevel) {
            interruptXp("no level gained at the exp spot in " + (NO_XP_PROGRESS_MS / 1000) + "s (still level "
                + player.getLevel() + ")");
        }
    }

    /**
     * Ends the whole XP goal because the level is no longer needed, and clears the farm budget so the next operation
     * starts with a fresh one.
     */
    private void finishXp(String reason) {
        boolean wasFarming = xpFarmingActive;
        stopXpFarming();
        // Only touch the aura when this module turned it on; a user-owned aura stays the user's business.
        if (wasFarming || killAuraOwned) switchKillAura(false);
        xpGoalLevel = -1;
        xpGoalCost = -1;
        xpFarmStartedAt = 0;
        if (!wasFarming) return;
        // The farm itself was a fight with the aura on: count it as the periodic silverfish clear so the next kill
        // window waits a full gap. Without this, XP reaching its goal flips the aura off and the very next tick the
        // gap has already elapsed, so a second window opens and delays the anvil interaction the BOT was farming for.
        lastKillWindowAt = System.currentTimeMillis();
        var player = CACHE.getPlayerCache().getThePlayer();
        status = "XP ready: " + reason;
        info("[trace] XP goal finished: {}{}", reason,
            player == null ? "" : " (level " + player.getLevel() + ", " + player.getTotalExperience() + " XP)");
    }

    private synchronized void interruptXp(String reason) {
        boolean wasFarming = xpFarmingActive;
        xpInterrupted = true;
        stopXpFarming();
        switchKillAura(false);
        // A failed goal gets a fresh budget on the retry, so a stuck spot cannot lock the goal out forever.
        xpGoalLevel = -1;
        xpGoalCost = -1;
        xpFarmStartedAt = 0;
        status = "XP farming interrupted: " + reason;
        if (wasFarming) warn("Auto-enchant XP farming interrupted: {}", reason);
    }

    public void onTradeListChange() {
        // Commands and scan both funnel here, exactly like the trader: rebuild the runnable-job
        // cache and drop any in-flight transaction so the new configuration is used right away.
        revalidateConfig();
        reset();
    }

    public enum State {
        ENTRYPOINT,
        EVAL_RESTOCK,
        RESTOCK_INPUT_GO_TO_CHEST,
        RESTOCK_INPUT_WITHDRAW,
        RESTOCK_INPUT_AWAIT_WITHDRAW,
        EVAL_UNCHANT,
        RESTOCK_ANVIL_GO_TO_CHEST,
        RESTOCK_ANVIL_WITHDRAW,
        RESTOCK_ANVIL_AWAIT_WITHDRAW,
        CRAFT_ANVIL_BLOCKS,
        AWAIT_CRAFT_ANVIL_BLOCKS,
        RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST,
        RESTOCK_ENCHANTED_BOOK__AWAIT_WITHDRAW,
        ENCHANT_INTERACT_WITH_ANVIL,
        ENCHANT_AWAIT_INTERACT_WITH_ANVIL,
        ENCHANT_TRY_START,
        AWAIT_ENCHANT,
        STORE_GO_TO_CHEST,
        STORE_DEPOSIT,
        STORE_AWAIT_DEPOSIT,
        READY_NEXT_ENCHANT,
        /** One piece is done but its batch is not: leave the finished product in the bag and pick up the next piece. */
        BATCH_NEXT_PIECE,
        POST_ENCHANT,
        POST_ENCHANT_INPUT_GO_TO,
        POST_ENCHANT_INPUT_DEPOSIT,
        POST_ENCHANT_INPUT_AWAIT_DEPOSIT,
        POST_ENCHANT_OVERFLOW_GO_TO,
        POST_ENCHANT_OVERFLOW_DEPOSIT,
        POST_ENCHANT_OVERFLOW_AWAIT_DEPOSIT,
        NEXT_ENCHANT,
        AWAIT_RESTOCK,
        // Sub-steps the detailed anvil, grindstone, rename and close handling needs.
        RESTOCK_INPUT_TAKE,
        CLOSE_INPUT,
        GRINDSTONE_GO_TO_CHEST,
        GRINDSTONE_AWAIT_INTERACT,
        GRINDSTONE_INSERT,
        GRINDSTONE_AWAIT_RESULT,
        GRINDSTONE_TAKE,
        GRINDSTONE_AWAIT_TAKE,
        GRINDSTONE_CLOSE,
        NEXT_BOOK,
        RESTOCK_ENCHANTED_BOOK_WITHDRAW,
        RESTOCK_ENCHANTED_BOOK_CLOSE,
        RESTOCK_ANVIL_TAKE,
        CLOSE_SUPPLY,
        PLACE_RENAME,
        CLOSE_FOR_XP,
        WAIT_XP,
        KILL_WINDOW,
        TAKE_RESULT,
        VERIFY_RESULT,
        CLOSE_ANVIL,
        CHECK_ANVIL,
        STORE_AWAIT_INTERACT,
        POST_ENCHANT_INPUT_AWAIT_INTERACT,
        POST_ENCHANT_OVERFLOW_AWAIT_INTERACT,
        CLOSE_OUTPUT,
        WAIT_CLOSE,
        DROP_ITEM,
        BACKOFF
    }

    public static final class EquipmentType {
        /** Unenchanted-carried items that are not tools/armour by suffix but still take enchantments. */
        private static final java.util.Set<String> EXTRA_ITEMS = java.util.Set.of(
            "bow", "crossbow", "trident", "mace", "spear", "fishing_rod", "shears", "shield",
            "carrot_on_a_stick", "warped_fungus_on_a_stick", "brush", "elytra", "wolf_armor");
        private static final List<String> SLOT_SUFFIXES = List.of(
            "_helmet", "_chestplate", "_leggings", "_boots", "_sword", "_axe", "_pickaxe", "_shovel", "_hoe",
            "_spear", "_horse_armor");
        private static volatile List<String> cachedValues;

        private EquipmentType() { }

        /** Enchantable equipment short names, sorted; safe to pass to {@code enumStrings}. */
        public static String[] values() {
            return cached().toArray(String[]::new);
        }

        /** True when this registry short name is one of the enchantable equipment choices. */
        public static boolean isEquipment(String itemId) {
            if (itemId == null) return false;
            return cached().contains(EnchantPresetCatalog.shortId(itemId));
        }

        private static List<String> cached() {
            List<String> values = cachedValues;
            if (values != null) return values;
            synchronized (EquipmentType.class) {
                values = cachedValues;
                if (values == null) {
                    java.util.TreeSet<String> names = new java.util.TreeSet<>();
                    for (String name : ItemRegistry.REGISTRY.getKeyMap().keySet()) {
                        String id = EnchantPresetCatalog.shortId(name);
                        if (isEnchantable(id)) names.add(id);
                    }
                    values = List.copyOf(names);
                    cachedValues = values;
                }
            }
            return values;
        }

        private static boolean isEnchantable(String id) {
            if (EXTRA_ITEMS.contains(id)) return true;
            for (String suffix : SLOT_SUFFIXES) if (id.endsWith(suffix)) return true;
            return false;
        }
    }

    /**
     * Round-robin over the runnable jobs, mirroring the trader's {@code TradeIterator}. The pickup branch asks it whose
     * chest is next, a chest with nothing to give advances it, and a finished transaction advances it too, so one empty
     * or busy chest cannot starve the other jobs.
     */
    public static final class EnchantIterator implements Iterator<AutoEnchantConfig.Enchant> {
        private int index;
        private List<AutoEnchantConfig.Enchant> backingArray = List.of();

        @Override
        public boolean hasNext() {
            return !backingArray.isEmpty();
        }

        public int size() {
            return backingArray.size();
        }

        public AutoEnchantConfig.Enchant current() {
            return backingArray.isEmpty() ? null : backingArray.get(index);
        }

        @Override
        public AutoEnchantConfig.Enchant next() {
            if (!backingArray.isEmpty()) index = (index + 1) % backingArray.size();
            return current();
        }

        /** Anchors the rotation on a job, e.g. the one whose chest just delivered. */
        public void select(AutoEnchantConfig.Enchant job) {
            int at = backingArray.indexOf(job);
            if (at >= 0) index = at;
        }

        public void refresh(List<AutoEnchantConfig.Enchant> jobs) {
            backingArray = List.copyOf(jobs);
            if (index >= backingArray.size()) index = 0;
        }

        public void reset() {
            index = 0;
        }
    }

    private enum Origin { NONE, INPUT_CHEST, INVENTORY }

    private AutoEnchantConfig.Enchant target;
    private ItemStack itemSnapshot;
    private List<EnchantPresetCatalog.Operation> operations = List.of();
    private List<EnchantPresetCatalog.Enchant> books = List.of();
    private int operationIndex;
    private int bookIndex;

    /** One book type of a withdrawal campaign: how many copies to top the bag up to. */
    private record BookFetch(EnchantPresetCatalog.Enchant book, int want) { }

    /**
     * Book types the running campaign still has to visit. The campaign withdraws the whole plan's books in one pass -
     * one window per chest, N copies per type - so the merge phase never stops in the middle of a piece to reopen a
     * book chest.
     */
    private final List<BookFetch> fetchQueue = new ArrayList<>();
    private int fetchIndex;
    /** Carried copies of the current fetch entry when its window was opened, to measure what the clicks moved. */
    private long fetchBefore;
    /** Types whose chest was empty; they are not reopened in this transaction, the same chest is waited on instead. */
    private final Set<EnchantPresetCatalog.Enchant> fetchEmpty = new java.util.LinkedHashSet<>();
    private int activeContainerId;
    private int sourceSlot = -1;
    private int playerSlot = -1;
    private boolean windowSettlePending;
    private long windowSettledUntil;
    private State afterInventoryState;
    private State afterCloseState;
    /** Ticks the current restock/idle wait runs for; always started together with {@link #waitForRestockTimer}. */
    private long restockWaitTicks = 20L;
    /** True while a container-open path is walking; the future itself lives in its trip's own field. */
    private boolean openPathPending;
    /**
     * Open clicks of the current attempt that produced no window.
     *
     * <p>The server quietly drops a right click it cannot serve right now - typically the first click after another
     * window closed or right after a kill window - and the click itself is cheap. The first two misses therefore get a
     * quarter of the interaction budget before the click is re-sent, instead of a full second each: an anvil that
     * answered only on the third click used to cost three seconds.
     */
    private int openClickMisses;
    /** Block the misses above were counted at, so a different chest or anvil starts with a full budget again. */
    private BlockPos openMissPosition;
    /** The OPEN_* state a failed or stale window returns to, mirroring the trader's chest states. */
    private State openState = State.ENTRYPOINT;
    private BlockPos openingPosition;
    private String failureReason;
    private boolean grindRequired;
    private int waitingCost;
    private BlockPos anvilPosition;
    private int anvilRecoveryAttempts;
    /** When the placement request was issued, used only to bound how long the bot may take to walk up and click. */
    private long anvilPlacementAt;
    /** When the placement click was seen to be dispatched; the confirm window for the block runs from here. */
    private long anvilClickAt;
    /**
     * True while the anvil station is being rebuilt before a new transaction starts. The recovery itself is the same
     * path a mid-transaction anvil step uses, but it must end back in {@link State#ENTRYPOINT} instead of opening the
     * anvil for an item that has not been fetched yet.
     */
    private boolean stationRecoveryOnly;
    /** The path request of the idle branch walking to an unloaded anvil station, so it is issued once, not per tick. */
    private PathingRequestFuture stationPathFuture = PathingRequestFuture.rejected;
    private long stationPathAt;
    private Map<String, Integer> targetEnchants = Map.of();
    /** The wiki preset this target matches, or null when the target is a hand-written set. */
    private String activePreset;
    /** True while the enchantItem is only being renamed, i.e. no enchantment merge is left to perform. */
    private boolean renamePass;
    /** Set when an anvil step finds its inputs still unfetched, so closing it returns to the book phase. */
    private boolean backToBooks;
    /** Rename passes whose preview never showed the requested name; a cosmetic name is never worth blocking the item. */
    private int renameTries;
    /**
     * How many carried pieces already wore the target name when the rename pass put its input into the anvil.
     *
     * <p>A batch carries several physically identical pieces, so "some piece in the bag has that name" is no proof
     * that this pass renamed anything: an already named sibling satisfies it and the pass is closed as done while the
     * piece on the anvil goes back to the bag unnamed. Only a rise in that count proves the pass worked.
     */
    private int namedBeforeRename;
    /** Set when the server refuses to apply the name, so the item is unloaded as it is instead of retrying forever. */
    private boolean renameGivenUp;
    /** True when the rename packet went out with the inputs of the merge currently on the anvil. */
    private boolean renameSentThisMerge;
    /**
     * True while the open anvil's name field still holds a name written for the equipment.
     *
     * <p>An anvil keeps the typed name for its next operation: the book pair merged right after a named sword came out
     * named "钻石剑mx" and stopped stacking with its own copies. The field is therefore cleared as soon as a merge that
     * does not produce the equipment is placed in the same window.
     */
    private boolean anvilNameFieldDirty;
    /**
     * Largest gap seen between what the plan says a merge costs and what this server actually charges (capped).
     *
     * <p>The plan is the budget: the level is farmed by it before the anvil is opened, and the anvil's own cost line
     * only verifies it. A merge that turns out more expensive than the wiki's table predicts is learnt here so the
     * next anvil is stocked above its plan instead of running dry inside the window.
     */
    private int planCostBias;
    /** One full shulker box of equipment: 27 slots, one piece each. The throughput stat is reported per box. */
    private static final int SHULKER_BOX_PIECES = 27;
    /**
     * Throughput counters: how long one piece takes and how long a full shulker box takes.
     *
     * <p>Piece time is the wall clock between two completions, not the time spent inside one anvil window. A batch is
     * processed step by step, so its ten pieces share the same windows and only the completion rate is a meaningful
     * per-piece number; the box figure is the same measure over 27 pieces, i.e. exactly one full shulker box of
     * equipment.
     */
    private static final class ThroughputStats {
        long lastAt;
        long lastMs;
        long totalMs;
        long minMs;
        long maxMs;
        int pieces;
        long boxStartedAt;
        int boxPieces;
        int boxesDone;
        long boxTotalMs;
        long boxBestMs;
        long boxWorstMs;
        long batchStartedAt;
        int batchPieces;
        int batchesDone;
        int batchPiecesTotal;
        long batchTotalMs;
        long batchBestMs;
        long batchWorstMs;
    }

    private final ThroughputStats stats = new ThroughputStats();
    /**
     * Equipment merges whose preview never produced a result while the name field was filled in.
     *
     * <p>Re-asserting the name on a merge is the only way the name survives a merge on this server, but a server that
     * answers a same-name rename with a red X would leave the anvil with no output and the piece would be retried
     * forever. Two silent previews are therefore read as "this anvil will not take the name again", and the rest of
     * the piece runs without the packet - the end-of-piece rename pass still writes the name, just dearer.
     */
    private int renameMergeRefusals;
    /** Set after {@link #renameMergeRefusals} tripped: later merges of this piece leave the name field alone. */
    private boolean renameOnMergeDisabled;
    /** Guards the per-merge preview/inventory trace so a tick loop logs each step once, not every tick. */
    private boolean stepLogged;
    /** True once the finished enchantItem left the BOT inventory, i.e. the output step really completed. */
    private boolean outputStored;
    /**
     * True while the BOT is not enchanting but only freeing bag space: a finished product that is still sitting in
     * the inventory is carried to the output container. Without this branch a full bag has no way out - every fetch
     * and every book withdrawal bounces off it and the item is retried forever instead of being unloaded.
     */
    private boolean flushing;
    /**
     * True while surplus raw input pieces are being returned to the input chest to free bag space. A batch over-fill
     * (raw swords that were withdrawn before the finished ones counted towards the batch) leaves no finished product
     * for {@link #flushing} to unload; the raw pieces have no work invested, so the safe exit is to put them back.
     */
    private boolean returningExcessInput;
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
    /** Signature of the last dumped plan, so a repeated plan dump does not re-print the same tree every retry. */
    private String lastPlanSignature = "";
    private static final int MAX_ANVIL_RECOVERY = 3;
    /** How long the bot may take to walk up to the station and dispatch the placement click before it counts as stuck. */
    private static final long ANVIL_PLACEMENT_DISPATCH_MS = 5_000L;
    /**
     * How long the block may take to show up after the click went out (server round trip, chunk cache lag).
     *
     * <p>One short window for the first click and for every re-place alike: a placement click the server silently
     * drops (the usual case right after another window closed) never lands at all, so a longer wait only delays the
     * retry that fixes it. A block that really shows up late is still adopted by the next {@code CHECK_ANVIL} tick.
     */
    private static final long ANVIL_PLACEMENT_CONFIRM_MS = 800L;
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
    /**
     * The job whose batch is currently open. Non-null from the moment a batch is recorded (pieces fetched from the
     * chest, or unfinished pieces already in the bag) until every piece of it has been enchanted and unloaded. While
     * it is set {@code beginInput} only resumes pieces of this job: no new fetch and no job rotation until the batch
     * is finished to the last piece.
     */
    private AutoEnchantConfig.Enchant batchJob;
    /** Recorded size of the open batch (raw + half-done + finished); drives the whole-batch book campaign. */
    private int batchPieces;
    /** Whether the current piece came from a batch still waiting in the bag (then the job must not rotate yet). */
    private boolean itemFromRestockBatch;
    /**
     * The current unload deposits every finished product of the job at once instead of just this transaction's piece.
     * Set on the last piece of a batch; a normal single-piece unload stays identity-scoped.
     */
    private boolean batchUnload;
    /** Item id and carried-stack count captured before a restock withdrawal, used to size the batch. */
    private int restockItemId = -1;
    private int restockBefore;
    /** Pieces the restock click asked for, so a short delivery can name the number that never arrived. */
    private int restockWanted;
    /**
     * Containers whose block has actually disappeared (someone took the shulker box / barrel away), with the time
     * until the next check and the number of consecutive misses. Unlike an empty chest this cannot be fixed by
     * walking up to it again, so the branch is parked instead of re-clicking air dozens of times per second.
     */
    private final Map<BlockPos, Long> missingContainerUntil = new HashMap<>();
    private final Map<BlockPos, Integer> missingContainerMisses = new HashMap<>();
    private static final long MISSING_CONTAINER_MIN_MS = 2_000L;
    private static final long MISSING_CONTAINER_MAX_MS = 30_000L;
    private static final int MAX_ITEM_ATTEMPTS = 3;
    private static final long SKIP_COOLDOWN_MS = 300_000L;
    /** When the current enchantItem was picked up, for the Discord completion log. */
    private long transactionStartedAt;

    private void tick() {
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
            setWorkflowStatus("Waiting for the BOT to be alive and out of the queue");
            return;
        }
        // One inventory action is in flight at a time; the per-trip futures only name who submitted it, as in the
        // trader, so a stale request from one trip can never be mistaken for another trip's.
        RequestFuture action = pendingActionFuture();
        if (action != null) {
            if (!action.isCompleted()) {
                if (waitForActionTimer.tick(resultTimeoutTicks())) {
                    clearActionFuture();
                    fail("Inventory action timed out");
                }
                return;
            }
            if (!action.isAccepted()) {
                clearActionFuture();
                fail("Inventory action was rejected");
                return;
            }
            clearActionFuture();
            state = afterInventoryState;
            waitForActionTimer.reset();
            return;
        }
        if (openPathPending) {
            PathingRequestFuture path = openPathFuture();
            int openedId = openContainerId();
            if (openedId != 0 && openedId != activeContainerId) {
                activeContainerId = openedId;
                // A window really opened here, so the box is back (or was never gone): drop any backoff.
                clearMissingContainer(openingPosition);
                clearOpenPathFuture();
                waitForInteractTimer.reset();
                openClickMisses = 0;
            } else if (path != null && !path.isCompleted()) {
                // Keep walking; the future resolves once the click was actually executed.
                return;
            } else if (path == null || !path.isAccepted() || waitForInteractTimer.tick(openRetryTicks())) {
                // Distinguish "the pathfinder never got there" from "the click ran but no window appeared": the first
                // is a reachability problem, the second means the block is not a usable container right now.
                boolean noPath = path == null || !path.isAccepted();
                if (!noPath) noteOpenClickMiss();
                clearOpenPathFuture();
                // No counter and no retry bookkeeping: the open state is simply entered again.
                reopenContainer(noPath ? "no path to " + openingPosition : "no window after clicking " + openingPosition);
                return;
            } else return;
        }
        if (isWaitingForWindow()) {
            if (openContainerId() != activeContainerId || activeContainerId == 0) {
                if (waitForInteractTimer.tick(interactTimeoutTicks())) reopenContainer("no usable container window");
                return;
            }
            // The window is here, so whatever miss led up to it is settled: the next open starts on the short budget
            // again. Without this the counter only grew, and at a block that is opened over and over (the anvil) the
            // third open and every one after it waited the full interaction budget before clicking a second time.
            openClickMisses = 0;
            openMissPosition = null;
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
                case ENTRYPOINT, NEXT_ENCHANT -> {
                    // A due kill window runs before the next piece is fetched: a long run of container work must never
                    // leave the silverfish farm untouched for the nine seconds after which the server culls a mob.
                    if (killWindowDue() && openContainerId() == 0 && target == null && beginKillWindow(state)) {
                        return;
                    }
                    beginInput();
                }
                case EVAL_RESTOCK -> evalRestock();
                case READY_NEXT_ENCHANT -> completeTransaction();
                case BATCH_NEXT_PIECE -> batchNextPiece();
                case AWAIT_RESTOCK -> { if (waitForRestockTimer.tick(restockWaitTicks)) state = State.ENTRYPOINT; }
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
                case RESTOCK_ENCHANTED_BOOK__AWAIT_WITHDRAW -> verifyBookTransfer();
                case RESTOCK_ENCHANTED_BOOK_CLOSE -> closeContainer(State.NEXT_BOOK);
                case ENCHANT_AWAIT_INTERACT_WITH_ANVIL -> handleAnvilWindow();
                case ENCHANT_TRY_START -> placeAnvilInputs();
                case PLACE_RENAME -> placeRenameInput();
                case AWAIT_ENCHANT -> verifyAnvilPreview();
                case CLOSE_FOR_XP -> closeContainer(State.WAIT_XP);
                case WAIT_XP -> waitForExperience();
                case KILL_WINDOW -> tickKillWindow();
                case TAKE_RESULT -> takeAnvilResult();
                case VERIFY_RESULT -> verifyAnvilResult();
                case CLOSE_ANVIL -> closeContainer(failureReason != null ? State.POST_ENCHANT
                    : backToBooks ? State.NEXT_BOOK
                    : operationIndex >= operations.size()
                        ? (needsRename() ? State.NEXT_BOOK : State.BATCH_NEXT_PIECE)
                        // A merge whose books are still in a chest is fetched first: opening the anvil for it only
                        // produces the "input not found" round trip. Everything else re-checks the station, because
                        // the anvil can break between two merges.
                        : (bookIndex < books.size() && !inputsReady()) ? State.NEXT_BOOK : State.CHECK_ANVIL);
                case STORE_AWAIT_INTERACT, POST_ENCHANT_INPUT_AWAIT_INTERACT, POST_ENCHANT_OVERFLOW_AWAIT_INTERACT -> handleOutputWindow();
                case STORE_DEPOSIT, POST_ENCHANT_INPUT_DEPOSIT, POST_ENCHANT_OVERFLOW_DEPOSIT -> unloadItem();
                case STORE_AWAIT_DEPOSIT, POST_ENCHANT_INPUT_AWAIT_DEPOSIT, POST_ENCHANT_OVERFLOW_AWAIT_DEPOSIT -> verifyUnloaded();
                case CLOSE_OUTPUT -> closeOutput();
                case DROP_ITEM -> dropCurrentItem();
                case BACKOFF -> { if (waitForRestockTimer.tick(restockWaitTicks)) state = State.READY_NEXT_ENCHANT; }
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
        List<AutoEnchantConfig.Enchant> runnable = runnableJobs();
        if (runnable.isEmpty()) {
            setWorkflowStatus(configProblems().isEmpty()
                ? "No enabled job - light the lamp above a frame, or run 'autoEnchant add <id> <item> <inputChest> <outputChest>'"
                : "Waiting for a valid job configuration: " + configProblems().getFirst());
            return;
        }
        if (openContainerId() != 0) {
            // A command reset, another module or the user can leave a window open; the trader
            // funnels through awaitContainerClosed before every chest, so do the same here.
            setWorkflowStatus("Closing an open container before the next transaction");
            closeContainer(State.ENTRYPOINT);
            return;
        }
        // A target enchantItem already in the BOT inventory is an interrupted transaction, not a fresh fetch: it is resumed
        // as-is and never reset, so work the BOT already paid for is kept.
        // A recorded batch owns the workflow until its last piece is done: resume only its pieces, never start a
        // different job's fetch while its finished products are still waiting in the bag.
        int carried = findCarriedEquipment(batchJob);
        if (carried >= 0) { adoptCarried(carried); return; }
        if (batchJob != null) {
            // No unfinished piece of the batch is left. Send its finished products to the unload chest together;
            // if none are left either (dropped/failed piece), release the latch so the next batch can be fetched.
            if (!beginBatchUnload()) closeBatch();
            return;
        }
        // No batch record, but the bag may still hold finished pieces of a batch whose record died with the process.
        // Without adopting them the picker sees "nothing to resume", the quota counts those very pieces as a full
        // batch and the workflow loops on the pickup chest without ever withdrawing or unloading anything.
        if (adoptOrphanFinishedBatch(runnable)) return;
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
            if (flushExcessInput("no free slot left in the bag")) return;
            // Nothing unloadable is left in the bag. Opening the pickup chest would only bounce the withdraw click off
            // a full inventory, so the bag is reported and the branch runs again on the next tick.
            retryBranch("Bag is full: no free slot for the next enchantItem");
            return;
        }
        if (freeSlots < FREE_SLOTS_FOR_TRANSACTION && flushFinishedProduct("only " + freeSlots + " free slot(s) left")) return;
        if (stations().getOrDefault("anvil", List.of()).isEmpty()) {
            anvilPosition = firstAnvil();
            // A broken anvil leaves air where it stood, so the station list goes empty while the remembered position
            // still names the spot. Restoring it here - instead of waiting for a restock - is what walks the BOT to
            // the anvil supply chest and places a replacement; without it the workflow spins on an anvil that is gone.
            if (recoverMissingAnvil()) return;
            // A real anvil at the remembered position is enough for the next transaction, even while the last scan
            // (up to five seconds old) has not picked the replacement up yet.
            if (anvilPosition == null || !isAnvilBlock(anvilPosition)) {
                if (anvilPosition != null && !World.isChunkLoadedBlockPos(anvilPosition.x(), anvilPosition.z())) {
                    // The station is out of view: walking there loads the chunk, so the next check knows whether the
                    // anvil is really gone instead of rebuilding a block that is still standing. One path request at
                    // a time - re-issuing it every tick would restart Baritone constantly.
                    if (stationPathFuture.isCompleted()
                        || System.currentTimeMillis() - stationPathAt > KILL_SPOT_TIMEOUT_MS) {
                        stationPathFuture = BARITONE.pathTo(anvilPosition.x(), anvilPosition.y(), anvilPosition.z());
                        stationPathAt = System.currentTimeMillis();
                    }
                    setWorkflowStatus("Walking to the anvil station at " + anvilPosition);
                } else {
                    setWorkflowStatus("Waiting for a loaded anvil");
                }
                return;
            }
        }
        stationPathFuture = PathingRequestFuture.rejected;
        List<BlockPos> chests = inputChests(runnable);
        if (chests.isEmpty()) {
            setWorkflowStatus("Enabled jobs have no reachable input chest");
            return;
        }
        origin = Origin.INPUT_CHEST;
        // Rotate through the jobs: the iterator names the chest of the job whose turn it is.
        openingPosition = pickupChest(chests);
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
            containerPosition(job, "input").ifPresent(pos -> { if (!chests.contains(pos)) chests.add(pos); });
        }
        return chests;
    }

    /**
     * The pickup chest of the job whose turn it is. The iterator is advanced until a job with one of these chests is
     * found, so the rotation reaches every other job before it comes back to the same one.
     */
    private BlockPos pickupChest(List<BlockPos> chests) {
        for (int i = 0; i < enchantIterator.size(); i++) {
            AutoEnchantConfig.Enchant job = enchantIterator.current();
            if (job != null) {
                BlockPos pos = containerPosition(job, "input").orElse(null);
                if (pos != null && chests.contains(pos)) return pos;
            }
            enchantIterator.next();
        }
        // No job in the rotation names one of these chests (should not happen); fall back to the first one.
        return chests.getFirst();
    }

    /**
     * Finds the carried enchantItem that belongs to an enabled job, skipping items that just failed repeatedly.
     *
     * <p>Row order: the most advanced piece wins, so resuming the one closest to the target spends the fewest levels
     * and the barely-started ones wait their turn instead of being picked first forever. Step order: the least
     * advanced piece wins, which is what keeps a batch walking the merge tree in lockstep - the piece that already
     * received the current step steps aside for the ones that have not.
     */
    private int findCarriedEquipment() { return findCarriedEquipment(null); }

    /** Same ranking, optionally restricted to the recorded batch's job so no other job can steal its turn. */
    private int findCarriedEquipment(AutoEnchantConfig.Enchant onlyJob) {
        int bestSlot = -1;
        int bestScore = stepOrder() ? Integer.MAX_VALUE : -1;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            var job = runnableJobFor(stack);
            if (job.isEmpty()) continue;
            if (onlyJob != null && job.get() != onlyJob) continue;
            if (isCoolingDown(stack)) continue;
            // A finished product waiting for the batch unload is not work: it has the highest progress score, so
            // without this guard beginInput would pick it up again and replay a no-op transaction forever. A piece
            // whose enchantments are complete but whose NAME is not is still work - it needs the rename pass.
            if (isDeliverable(job.get(), stack)) continue;
            int score = progressScore(stack);
            if (stepOrder() ? score < bestScore : score > bestScore) { bestScore = score; bestSlot = slot; }
        }
        return bestSlot;
    }

    /** Rough "how far along the merge tree" rank: enchantments carried, renamed pieces one step ahead. */
    private int progressScore(ItemStack stack) {
        int enchants = catalog().enchantments(stack).size();
        boolean named = !catalog().customName(stack).isEmpty();
        return enchants * 2 + (named ? 1 : 0);
    }

    /** Resumes a carried enchantItem without any grindstone pass; only the missing books are applied. */
    private void adoptCarried(int slot) {
        ItemStack stack = playerItem(slot);
        var match = runnableJobFor(stack);
        if (match.isEmpty()) { state = State.ENTRYPOINT; return; }
        // A piece withdrawn together with the previous transaction is still a fresh chest item, so it gets the
        // same reset decision instead of being resumed as if the BOT had already worked on it.
        boolean fromRestock = consumeRestockBatch(stack);
        itemFromRestockBatch = fromRestock;
        origin = fromRestock ? Origin.INPUT_CHEST : Origin.INVENTORY;
        playerSlot = slot;
        sourceSlot = -1;
        itemSnapshot = stack.clone();
        target = match.get();
        beginTransaction();
        // Unfinished pieces already in the bag are part of the batch too: starting from one of them opens a batch
        // that includes every other carried piece of the same job, so they are all finished before a new fetch.
        openBatch(target);
        grindRequired = false;
        if (fromRestock && catalog().hasCurse(stack)) {
            failureReason = "Cursed enchantItem skipped";
            state = State.POST_ENCHANT;
            return;
        }
        preparePlan(stack);
        if (failureReason != null) { trace("plan failed: {}", failureReason); markAttempt(); state = State.POST_ENCHANT; return; }
        trace("resumed carried slot {}: {} (fromRestock={})", slot, describeItem(stack), fromRestock);
        // The full preset tree; the effective (resumed) plan is dumped by applyResumePlan once the item's own
        // enchantments and the carried intermediates have been taken into account.
        trace("preset {} tree: {} book(s), {} merge(s)", activePreset, books.size(), operations.size());
        if (fromRestock) {
            decideOnHandoff(stack);
            if (!grindRequired && failureReason == null && !operations.isEmpty()
                && !applyResumePlan(catalog().enchantments(stack))) {
                // Same rule as for a carried piece: a half-done item taken out of a supply chest is kept and retried,
                // not put back the moment one merge order does not line up.
                parkCarried(failureReason == null ? "no merge order fits the fetched set" : failureReason);
                return;
            }
            state = grindRequired ? State.EVAL_UNCHANT : State.NEXT_BOOK;
            return;
        }
        Map<String, Integer> current = catalog().enchantments(stack);
        trace("carried enchantments: {} | target: {}", describeEnchants(current), describeEnchants(targetEnchants));
        if (PLUGIN_CONFIG.renameOnly) {
            // The carried piece was fetched for its name, not for enchantments: go straight to the rename pass.
            operations = List.of();
            books = List.of();
            setWorkflowStatus("Carried enchantItem is only renamed");
            state = State.NEXT_BOOK;
            return;
        }
        if (current.isEmpty()) {
            setWorkflowStatus("Carried enchantItem is blank; enchanting without fetching a new one");
            state = State.NEXT_BOOK;
            return;
        }
        if (current.equals(targetEnchants)) {
            operations = List.of();
            books = List.of();
            setWorkflowStatus("Carried enchantItem already matches the plan; unloading");
            state = State.NEXT_BOOK;
            return;
        }
        if (!withinPlan(current)) {
            failureReason = "Carried enchantItem has enchantments outside the target";
            markAttempt();
            setWorkflowStatus("Carried enchantItem does not match the target; sending it to the output container");
            state = State.POST_ENCHANT;
            return;
        }
        if (!applyResumePlan(current)) {
            // A carried piece is work the BOT already paid for: unless it is genuinely too expensive to touch again,
            // it stays in the bag and cools down for a while instead of being dropped at the unload chest.
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
        // A container that is no longer a block (the shulker box was taken) cannot be opened by walking up to it:
        // Baritone would resolve "No block is at ..." in the same tick and reopenContainer would re-click forever.
        if (isContainerOpenState(state) && containerGone(openingPosition)) {
            reportMissingContainer(openingPosition, containerRole(state), "about to open " + openingPosition);
            return;
        }
        // One kill window before a window is opened. Book and anvil work keeps the BOT behind a container for far
        // longer than the silverfish farm tolerates, so the farm is cleared right before the pause, not after it.
        if (killWindowDue() && openContainerId() == 0 && beginKillWindow(state)) return;
        if (openContainerId() != 0) {
            // The trader funnels every chest interaction through awaitContainerClosed: never stack
            // a station screen on a window that is already open, close it and retry OPEN_*.
            setWorkflowStatus("Closing an open container before opening " + openingPosition);
            closeContainer(openState);
            return;
        }
        activeContainerId = 0;
        windowSettlePending = true;
        // As in the trader, the window wait restarts when the click actually ran, not when the path did.
        waitForInteractTimer.reset();
        PathingRequestFuture path = BARITONE.rightClickBlock(openingPosition.x(), openingPosition.y(), openingPosition.z());
        path.addExecutedListener(f -> waitForInteractTimer.reset());
        setOpenPathFuture(path);
        openPathPending = true;
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
        int skippedFinished = 0;
        for (int slot = 0; slot < top; slot++) {
            ItemStack stack = openContainer().getItemStack(slot);
            Optional<AutoEnchantConfig.Enchant> match = runnableJobFor(stack);
            if (match.isEmpty()) continue;
            // A finished piece is a deliverable, not input: withdrawing it would only carry it through the anvil
            // again, where every merge it needs is already applied. Such pieces reach an input chest by hand or from
            // an earlier unload that missed its output chest, so they are skipped instead of being taken as blanks.
            // A piece whose enchantments are complete but whose NAME is wrong is not skipped: taking it in is the
            // only way it gets the rename pass that the operator put it there for.
            if (isDeliverable(match.get(), stack)) {
                skippedFinished++;
                continue;
            }
            selected = slot; chosen = stack; chosenTarget = match.get();
            break;
        }
        if (skippedFinished > 0) {
            trace("pickup chest at {}: skipped {} finished piece(s) that already match their target",
                openingPosition, skippedFinished);
        }
        if (selected < 0) {
            // Nothing this chest can serve. The rotation moves on at once so the next chest (and the next job) gets its
            // turn; the chest itself is entered again when the rotation comes back to it.
            BlockPos chest = openingPosition;
            enchantIterator.next();
            retryBranch("Pickup chest at " + chest + " has nothing for an enabled job");
            return;
        }
        // Stay on the job that just delivered instead of re-probing the empty ones in front of it.
        enchantIterator.select(chosenTarget);
        // This piece came out of the chest itself: it is the one that advances the job rotation when it is finished.
        // The rest of its batch is adopted from the bag and must not rotate again (see completeTransaction).
        itemFromRestockBatch = false;
        sourceSlot = selected;
        origin = Origin.INPUT_CHEST;
        itemSnapshot = chosen.clone();
        target = chosenTarget;
        beginTransaction();
        setWorkflowStatus("Fetched " + describeItem(chosen) + " for job " + target.displayName());
        trace("fetched from slot {}: {} (job {}, penalty {})", selected, describeItem(chosen),
            target.displayName(), catalog().priorWorkPenalty(chosen));
        if (catalog().hasCurse(chosen)) {
            failureReason = "Cursed enchantItem skipped";
            state = State.RESTOCK_INPUT_TAKE;
            return;
        }
        preparePlan(chosen);
        if (failureReason != null) { trace("plan failed: {}", failureReason); state = State.RESTOCK_INPUT_TAKE; return; }
        trace("item enchantments on pickup: {}", describeEnchants(catalog().enchantments(chosen)));
        tracePlan();
        decideOnHandoff(chosen);
        trace("handoff: grindRequired={} current={}", grindRequired, describeEnchants(catalog().enchantments(chosen)));
        // An empty plan here means the enchantItem already matches the target; it is unloaded untouched (or renamed only).
        if (!grindRequired && failureReason == null && !operations.isEmpty()) {
            if (!applyResumePlan(catalog().enchantments(chosen))) markAttempt();
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
        if (PLUGIN_CONFIG.renameOnly) {
            // Rename-only mode: the item's enchantments are irrelevant, so an enchanted piece is never ground blank
            // for not matching a target set. The name is the whole job.
            setWorkflowStatus("Rename-only mode; skipping the enchant check");
            return;
        }
        Map<String, Integer> current = catalog().enchantments(item);
        if (current.isEmpty()) {
            setWorkflowStatus("Fetched a blank item; enchanting from scratch");
            return;
        }
        if (current.equals(targetEnchants)) {
            operations = List.of();
            books = List.of();
            // The enchantments are complete, but the name may still be wrong - that is exactly the case of a finished
            // piece put back into an input chest by hand. Nothing else is replayed; the empty plan sends the piece
            // straight to the rename-only pass.
            boolean rename = needsRename();
            setWorkflowStatus(rename
                ? "Fetched item is already enchanted; only its name needs the anvil"
                : "Fetched item already matches the target; unloading unchanged");
            trace("fetched item is already enchanted; {}", rename ? "a rename-only pass follows" : "nothing left to do");
            return;
        }
        if (!withinPlan(current)) {
            if (!AutoEnchantPlugin.PLUGIN_CONFIG.allowGrindstone) {
                setWorkflowStatus("Fetched item has extra enchantments but grindstone processing is disabled; continuing without a reset");
                return;
            }
            grindRequired = true;
            setWorkflowStatus("Fetched an enchanted item; resetting on the grindstone once");
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
            activePreset = null;
            if (PLUGIN_CONFIG.renameOnly) {
                // Rename-only mode: the item is fetched, named and unloaded without a single merge. An empty plan is
                // the plan, exactly as for an item whose enchantments already match the target.
                return;
            }
            activePreset = targetEnchants.isEmpty() ? null : catalog().matchPreset(target.item, targetEnchants);
            if (targetEnchants.isEmpty()) return;
            if (activePreset != null) {
                operations = catalog().operations(activePreset, fullItemId(target.item));
                books = catalog().requiredBooks(activePreset);
                if (operations.isEmpty()) failureReason = "Target has no enchant operations";
            } else {
                List<EnchantPresetCatalog.Enchant> wanted = customEnchantList();
                if (wanted.isEmpty()) { failureReason = "Target has no enchantments"; return; }
                var plan = CustomEnchantPlanner.plan(target.item, Map.of(), 0, wanted);
                if (plan == null) {
                    failureReason = "Target set cannot be applied within the 39-level anvil limit";
                    return;
                }
                operations = plan.operations();
                books = plan.books();
            }
        } catch (Exception e) {
            failureReason = "Could not build plan: " + e.getMessage();
        }
        clampPlanToStep();
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
        int penalty = itemSnapshot == null ? 0 : catalog().priorWorkPenalty(itemSnapshot);
        if (activePreset != null) {
            var plan = catalog().resumePlan(activePreset, current, availableEnchantSets());
            if (plan == null) {
                trace("resume plan: carried {} is not a node of preset {}; planning from the live set",
                    describeEnchants(current), activePreset);
                var fallback = CustomEnchantPlanner.plan(target.item, current, penalty, customEnchantList());
                if (fallback == null) {
                    failureReason = "Item does not align with the wiki merge tree and fits no order under 39 levels";
                    setWorkflowStatus("Item cannot be enchanted any further; sending it to the output container");
                    return false;
                }
                return adoptPlan(fallback, current, "general planner (carried set is no tree node)");
            }
            operations = plan.operations();
            books = plan.books();
            if (operations.isEmpty()) {
                setWorkflowStatus("Item already matches the plan; unloading");
                return true;
            }
            clampPlanToStep();
            setWorkflowStatus("Resuming on the wiki merge tree: keeping " + current.size()
                + " enchantment(s), " + books.size() + " book(s) and " + operations.size() + " merge(s) left");
            tracePlan();
            return true;
        }
        var plan = CustomEnchantPlanner.plan(target.item, current, penalty, customEnchantList());
        if (plan == null) {
            failureReason = "No custom merge order fits the 39-level limit from penalty " + penalty;
            setWorkflowStatus("Custom set has no ordering under the anvil limit; sending it to the output container");
            return false;
        }
        return adoptPlan(plan, current, "custom merge tree");
    }

    /** Applies a non-preset plan to the current transaction and reports it in the status line. */
    private boolean adoptPlan(CustomEnchantPlanner.Plan plan, Map<String, Integer> current, String kind) {
        operations = plan.operations();
        books = plan.books();
        if (operations.isEmpty()) {
            setWorkflowStatus("Item already matches the plan; unloading");
            return true;
        }
        clampPlanToStep();
        setWorkflowStatus("Resuming on the " + kind + ": keeping " + current.size()
            + " enchantment(s), " + books.size() + " book(s), " + operations.size() + " merge(s), about "
            + plan.predictedLevels() + " levels");
        tracePlan();
        return true;
    }

    /**
     * True when a batch is advanced one merge step at a time ({@link AutoEnchantConfig.BatchOrder#STEP}): every piece
     * of the batch receives the same step before the next step's books are fetched.
     */
    private boolean stepOrder() {
        return PLUGIN_CONFIG.batchOrder == AutoEnchantConfig.BatchOrder.STEP;
    }

    /**
     * Cuts the adopted plan down to the next merge step of the piece in hand.
     *
     * <p>Row order runs the whole plan before the next piece; step order runs only the operations up to the first one
     * that produces the equipment - a merged book pair is built first, then applied to the sword - and keeps the rest
     * for later passes. The remaining list stays 0-based as if the step were the whole plan, so the anvil loop, the
     * preview check and the "plan finished" transition need no special case. The books of the step are the
     * enchantment leaves of its result: a later step lists the earlier enchantments too, and {@code nextBook()} skips
     * those because the piece already carries them.
     */
    private void clampPlanToStep() {
        if (!stepOrder() || renamePass || operations.isEmpty()) return;
        String placeholder = targetPlaceholder();
        int end = -1;
        for (int i = 0; i < operations.size(); i++) {
            if (containsTarget(operations.get(i).result(), placeholder)) { end = i; break; }
        }
        if (end < 0) return;
        operations = List.copyOf(operations.subList(0, end + 1));
        List<EnchantPresetCatalog.Enchant> stepBooks = new ArrayList<>();
        expectedEnchants(operations.get(end).result(), placeholder).entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> stepBooks.add(new EnchantPresetCatalog.Enchant(entry.getKey(), entry.getValue())));
        books = List.copyOf(stepBooks);
        trace("batch step plan: {} merge(s) then back to the batch, {} book type(s) ({})", operations.size(),
            books.size(), books.stream().map(b -> b.id() + " " + b.level())
                .collect(java.util.stream.Collectors.joining(", ")));
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
            // was never reused and the state machine fetched the same books again and again.
            Map<String, Integer> stored = readEnchants(stack, true);
            if (!stored.isEmpty()) sets.add(stored);
        }
        return sets;
    }

    /**
     * Withdraws the current equipment piece plus more of the same equipment from the same chest in one inventory
     * request. The global {@code batchSize} is how many pieces make one batch, so the following pieces are processed
     * straight from the BOT inventory without reopening this chest, and the next job is not picked until they are all
     * done.
     */
    private void takeInput() {
        // Never withdraw more pieces than the bag can take: the extra stacks of a batch are dropped on the ground by
        // the server when the inventory fills up, which silently loses equipment.
        int free = freeInventorySlots();
        if (free < 1) {
            if (flushFinishedProduct("no free slot for the withdrawal")) return;
            if (flushExcessInput("no free slot for the withdrawal")) return;
            // No retry and no back-off: the window stays open and the same withdraw is tried again on the next tick,
            // once a finished product could be unloaded or the operator freed a slot.
            setWorkflowStatus("Bag is full: no free slot to take the withdrawal");
            return;
        }
        // Top the batch up to the configured size with what the bag ALREADY holds. This MUST be a physical count of
        // every carried sword of this job - raw, half-done, finished, cooling or cursed - because all of them occupy a
        // slot. countCarriedBatchPieces() skips cooling pieces; a cooling half-done sword was then neither resumed
        // (findCarriedEquipment skips it too) nor counted here, so every cycle withdrew a full batch again until the
        // bag was jammed with 17 swords and no book could fit.
        int batchSize = Math.max(1, PLUGIN_CONFIG.batchSize);
        int alreadyCarried = countCarriedById(configuredItemId());
        int wanted = Math.min(batchSize - alreadyCarried, free);
        // How much this chest can actually give: a batch that ends up short is either an empty pickup chest or pieces
        // the withdraw filter refused, and the two look identical in the batch line alone.
        int offered = 0;
        for (int slot = 0; slot < topSlots(); slot++) {
            ItemStack stack = openContainer().getItemStack(slot);
            if (stack == null || stack.getId() != configuredItemId()) continue;
            if (isDeliverable(target, stack)) continue;
            offered++;
        }
        trace("pickup batch: {} piece(s) already carried, withdrawing {} more (batch size {}, {} free slot(s), "
            + "chest offers {} takeable piece(s))", alreadyCarried, wanted, batchSize, free, offered);
        if (wanted <= 0) {
            // The bag already holds a whole batch (or every free slot is spoken for). Do not withdraw another piece:
            // drop the chest selection, unfreeze the carried pieces so beginInput() can adopt one, and go back to the
            // entry point. Without the unfreeze a bag of cooling half-done pieces would bounce open/close forever
            // while the picker keeps believing it has nothing to resume.
            int unfrozen = clearCooldownForCarriedJob(target);
            restockItemId = -1;
            restockBefore = 0;
            restockWanted = 0;
            trace("pickup skipped: bag already has {} of {} piece(s); unfroze {} carried piece(s), resuming from the bag",
                alreadyCarried, batchSize, unfrozen);
            closeContainer(State.ENTRYPOINT);
            return;
        }
        restockItemId = configuredItemId();
        restockBefore = countCarriedById(restockItemId);
        restockWanted = wanted;
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = new ArrayList<>(
            InventoryActionMacros.withdraw(openContainerId(),
                stack -> stack.getId() == configuredItemId() && !isDeliverable(target, stack),
                wanted));
        if (actions.isEmpty()) {
            restockItemId = -1;
            restockBefore = 0;
            restockWanted = 0;
            actions.add(new ShiftClick(openContainerId(), sourceSlot, ShiftClickItemAction.LEFT_CLICK));
        }
        setWorkflowStatus("Withdrawing " + actions.size() + " equipment piece(s) for job " + target.displayName());
        submit(actions, State.RESTOCK_INPUT_AWAIT_WITHDRAW);
    }

    private void verifyInputTransfer() {
        playerSlot = findPlayerItem(itemSnapshot);
        if (playerSlot < 0) {
            if (waitForActionTimer.tick(interactTimeoutTicks())) {
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
        // A short delivery is the usual reason a batch holds fewer pieces than the configured size: the chest ran out
        // or the filter refused the rest. Naming both numbers keeps it out of the "where did the pieces go" category.
        if (restockWanted > 0 && withdrawn != restockWanted) {
            trace("withdraw came up short: {} of {} piece(s) arrived (the chest ran out or the rest was refused)",
                withdrawn, restockWanted);
        }
        restockWanted = 0;
        int extra = withdrawn - 1; // the first piece is the current transaction
        String key = Integer.toString(restockItemId);
        if (extra > 0) restockBatch.put(key, extra); else restockBatch.remove(key);
        // Record the batch itself: every piece of this job now in the bag - the ones just withdrawn and any
        // unfinished ones already carried - belongs to it. Nothing new is fetched until all of them are done.
        openBatch(target);
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
            setWorkflowStatus("Reset skipped: only items taken from the equipment input chest are cleaned");
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
        // A quick move into an empty grindstone window lands in the first input slot, one packet instead of two.
        List<com.zenith.feature.inventory.actions.InventoryAction> actions =
            new ArrayList<>(moveIntoStation(playerSlot, 0, stationInputsEmpty()));
        submit(actions, State.GRINDSTONE_AWAIT_RESULT);
    }

    private void verifyGrindstonePreview() {
        if (openContainer().getItemStack(2) != Container.EMPTY_STACK) { state = State.GRINDSTONE_TAKE; return; }
        if (waitForActionTimer.tick(resultTimeoutTicks())) fail("Grindstone produced no output");
    }

    private void takeGrindstoneResult() {
        if (openContainer().getItemStack(2) == Container.EMPTY_STACK) { fail("Grindstone output disappeared"); return; }
        submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.GRINDSTONE_AWAIT_TAKE);
    }

    private void verifyGrindstoneResult() {
        playerSlot = findPlayerItemById(itemSnapshot.getId());
        if (playerSlot < 0) {
            if (waitForActionTimer.tick(resultTimeoutTicks())) fail("Grindstone output not received");
            return;
        }
        ItemStack cleaned = playerItem(playerSlot);
        if (cleaned != null && !catalog().enchantments(cleaned).isEmpty()) { fail("Grindstone output still has enchantments"); return; }
        itemSnapshot = cleaned.clone();
        state = State.GRINDSTONE_CLOSE;
    }

    /**
     * Re-enters the workflow after a branch found nothing it could work with: an empty chest, a container that is not
     * there, nothing to take.
     *
     * <p>There is no retry counter and no back-off. The in-flight transaction is dropped (a carried piece simply stays
     * in the bag and is adopted again), the window is closed by the next pickup branch, and the branch is entered
     * again on the next tick - the trader's behaviour. Where a chest is part of a rotation the cursor has already
     * moved on, so a dead chest costs one attempt, not the queue.
     *
     * @param what human readable reason, shown as the workflow status
     */
    private void retryBranch(String what) {
        setWorkflowStatus(what);
        clearTransaction();
    }

    /**
     * Keeps the current branch and enters it again: the status line names what is missing, an open window is closed and
     * the branch's open state is clicked again on the next tick. The in-flight transaction is kept, so the same piece,
     * the same chest and the same job are reused - no retry counter and no back-off.
     *
     * <p>This is the plain "the chest was empty or did not open, so walk up to it again" behaviour. It is used where a
     * container is part of the current transaction (a book chest, the anvil supply, an unload chest) and dropping the
     * transaction would only throw away progress the BOT already paid for.
     *
     * @param next state to enter again, i.e. the branch that just found nothing
     * @param what human readable reason, shown as the workflow status
     */
    private void reenter(State next, String what) {
        setWorkflowStatus(what);
        if (openContainerId() != 0) closeContainer(next); else state = next;
    }

    private void nextBook() {
        if (failureReason != null) { state = State.POST_ENCHANT; return; }
        if (bookIndex >= books.size()) {
            // Every merge is done. Only the name can still need the anvil, and it never replays a finished merge:
            // operationIndex keeps its progress because merges may run while books are still being restocked.
            if (operations.isEmpty() || operationIndex >= operations.size()) {
                if (needsRename()) { beginRenamePass(); return; }
                state = State.BATCH_NEXT_PIECE;
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
        // Everything the plan still needs from the chests is withdrawn in one campaign before any merge starts: one
        // window per type, copiesPerType books each, so a 4-piece batch costs 7 window openings instead of 28.
        if (bookFetchStep()) return;
        EnchantPresetCatalog.Enchant book = books.get(bookIndex);
        long requiredCopies = books.subList(0, bookIndex + 1).stream().filter(book::equals).count();
        trace("need book {}/{}: {} {} (carried {}, required {})", bookIndex + 1, books.size(),
            book.id(), book.level(), countBooks(book), requiredCopies);
        if (countBooks(book) >= requiredCopies) { bookIndex++; return; }
        // Already on the item: merging can run ahead of the restock phase, so a book whose enchantment the equipment
        // carries at the planned level is not fetched again.
        Integer onItem = itemSnapshot == null ? null
            : catalog().enchantments(itemSnapshot).get(book.id());
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
        // This chest was already found empty in this transaction: wait at it, exactly like the old single-book
        // branch did, instead of reopening it every tick in a campaign that can no longer make progress.
        if (fetchEmpty.contains(book)) {
            var emptyChest = bookContainerPosition(book.id(), book.level());
            if (emptyChest.isPresent()) openingPosition = emptyChest.get();
            reenter(State.RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST, "Book chest at " + openingPosition
                + " holds no " + book.id() + " " + book.level());
            return;
        }
        if (!hasFreeInventorySlot()) {
            // Opening the chest only to have the shift-click bounce off a full inventory spins forever. A finished
            // product carried by the bag is unloaded first; only when there is nothing to unload is the transaction
            // parked with the reason (the item stays untouched in the inventory). A single free slot is enough here:
            // the merge that follows moves both inputs into the anvil and puts only the result back.
            if (flushFinishedProduct("no room for " + book.id() + " " + book.level())) return;
            if (flushExcessInput("no room for " + book.id() + " " + book.level())) return;
            keepAndRetry("BOT inventory is full; no room for " + book.id() + " " + book.level());
            return;
        }
        var chest = bookContainerPosition(book.id(), book.level());
        if (chest.isEmpty()) {
            // A missing book marker is a supply problem, not a failed enchant: the piece keeps its progress in the bag
            // and the branch is entered again on the next tick.
            retryBranch("No book chest is bound for " + book.id() + " " + book.level());
            return;
        }
        openingPosition = chest.get();
        trace("book chest for {} {} is {}", book.id(), book.level(), openingPosition);
        state = State.RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST;
    }

    /**
     * One step of the book campaign: opens the chest of the next type still short, or returns false when the campaign
     * is over (and the merge phase can start).
     *
     * @return true when this step took over the workflow; the caller then simply returns
     */
    private boolean bookFetchStep() {
        if (fetchQueue.isEmpty() && !buildFetchQueue()) return false;
        if (fetchIndex >= fetchQueue.size()) {
            trace("book campaign finished: {} type(s) visited, {} book(s) carried",
                fetchQueue.size(), books.stream().filter(b -> countBooks(b) > 0).count());
            fetchQueue.clear();
            fetchIndex = 0;
            return false;
        }
        BookFetch next = fetchQueue.get(fetchIndex);
        if (fetchEmpty.contains(next.book())) { fetchIndex++; return true; }
        if (!hasFreeInventorySlot()) {
            if (flushFinishedProduct("no room for " + next.book().id() + " " + next.book().level())) return true;
            if (flushExcessInput("no room for " + next.book().id() + " " + next.book().level())) return true;
            keepAndRetry("BOT inventory is full; no room for " + next.book().id() + " " + next.book().level());
            return true;
        }
        var chest = bookContainerPosition(next.book().id(), next.book().level());
        if (chest.isEmpty()) {
            // A missing book marker is a supply problem, not a failed enchant: the type is skipped this round and the
            // merge phase reports what is still missing.
            trace("no book chest is bound for {} {}; skipping it in this campaign",
                next.book().id(), next.book().level());
            fetchIndex++;
            return true;
        }
        openingPosition = chest.get();
        trace("book campaign {}/{}: {} {} x{} from {}", fetchIndex + 1, fetchQueue.size(),
            next.book().id(), next.book().level(), next.want(), openingPosition);
        state = State.RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST;
        return true;
    }

    /**
     * Builds the campaign from the whole batch, not just the piece currently in hand. Every carried piece of this job
     * that still needs work is compared against the target set: a sword that already carries looting, unbreaking and
     * sharpness only asks for the enchants it is missing (e.g. mending), while a blank one asks for all of them. The
     * per-type total is then reduced by the books already in the bag - including merged intermediates - so a batch of
     * four blank pieces withdraws four of each book and a batch of half-done pieces withdraws far fewer.
     *
     * <p>In step order the demand is limited to the book types of the step the piece in hand is on, and the whole
     * campaign is capped by the free bag slots. Ten blank swords therefore withdraw ten looting books, apply them,
     * and only then come back for the next step's pair of types.
     *
     * @return true when at least one chest has something to fetch
     */
    private boolean buildFetchQueue() {
        fetchQueue.clear();
        fetchIndex = 0;
        if (target == null || targetEnchants.isEmpty()) return false;
        int batchSize = Math.max(1, PLUGIN_CONFIG.batchSize);
        // A recorded batch can be larger than the nominal fetch size when unfinished pieces were already in the bag.
        // Plan books for all of its pieces in one campaign instead of leaving the extras for a second campaign.
        int cap = batchJob != null ? Math.max(batchSize, Math.max(batchPieces, countCarriedBatchPieces())) : batchSize;

        // The pieces of this batch that still need enchanting. The one in hand is among them; other carried pieces
        // join it up to the batch size. Finished products and cooling-down pieces create no demand.
        List<Map<String, Integer>> pieces = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != configuredItemId()) continue;
            var job = runnableJobFor(stack);
            if (job.isEmpty() || job.get() != target) continue;
            if (isCoolingDown(stack)) continue;
            Map<String, Integer> current = normalizedEnchants(stack);
            if (current.equals(targetEnchants)) continue;
            pieces.add(current);
            if (pieces.size() >= cap) break;
        }
        if (pieces.isEmpty() && itemSnapshot != null) {
            Map<String, Integer> current = new HashMap<>();
            catalog().enchantments(itemSnapshot).forEach((id, lvl) -> current.put(EnchantPresetCatalog.shortId(id), lvl));
            if (!current.equals(targetEnchants)) pieces.add(current);
        }

        // Step order only ever needs the book types of the step it is on. Fetching the whole target set would buy
        // books for a step three charges ahead and fill the bag - the "inventory is full" loops of a big row batch.
        List<EnchantPresetCatalog.Enchant> campaign = new ArrayList<>();
        if (stepOrder() && !books.isEmpty()) {
            for (EnchantPresetCatalog.Enchant book : books) if (!campaign.contains(book)) campaign.add(book);
        } else {
            targetEnchants.forEach((id, level) -> campaign.add(new EnchantPresetCatalog.Enchant(id, level)));
        }

        Map<EnchantPresetCatalog.Enchant, Integer> want = new LinkedHashMap<>();
        for (EnchantPresetCatalog.Enchant book : campaign) {
            String id = book.id();
            int level = book.level();
            int lacking = 0;
            for (Map<String, Integer> current : pieces) {
                if (current.getOrDefault(id, 0) < level) lacking++;
            }
            if (lacking <= 0) continue;
            int deficit = lacking - carriedBookCopies(book);
            if (deficit > 0) want.put(book, deficit);
        }
        // Never plan more books than the bag can accept right now: the withdraw click would bounce off a full
        // inventory and the branch would park on "inventory is full". Types that do not fit are left for the next
        // campaign; the merge phase notices the missing book and comes back for it.
        int budget = Math.max(1, freeInventorySlots());
        for (var entry : want.entrySet()) {
            if (budget <= 0) break;
            if (fetchEmpty.contains(entry.getKey())) continue;
            int take = Math.min(entry.getValue(), budget);
            budget -= take;
            fetchQueue.add(new BookFetch(entry.getKey(), take));
        }
        if (!fetchQueue.isEmpty()) {
            trace("book campaign planned for {} piece(s): {} type(s), {} book(s) to withdraw",
                pieces.size(), fetchQueue.size(), fetchQueue.stream().mapToInt(BookFetch::want).sum());
        }
        return !fetchQueue.isEmpty();
    }

    /** enchantments() keys can carry a namespace; the planner and target sets use the short registry id. */
    private Map<String, Integer> normalizedEnchants(ItemStack stack) {
        Map<String, Integer> result = new HashMap<>();
        catalog().enchantments(stack).forEach((id, lvl) -> result.put(EnchantPresetCatalog.shortId(id), lvl));
        return result;
    }

    /**
     * Copies of one book already in the bag. Merged intermediates (e.g. sweeping + unbreaking in one book) carry
     * several enchantments and satisfy one copy of each, so counting only single-enchant books would re-fetch them.
     */
    private int carriedBookCopies(EnchantPresetCatalog.Enchant book) {
        String id = EnchantPresetCatalog.shortId(book.id());
        int count = 0;
        for (Map<String, Integer> set : availableEnchantSets()) {
            Integer lvl = set.get(id);
            if (lvl != null && lvl >= book.level()) count++;
        }
        return count;
    }

    private void handleBookWindow() {
        if (!isStorageWindow()) { fail("Book marker position did not open a storage container"); return; }
        BookFetch fetch = fetchIndex < fetchQueue.size() ? fetchQueue.get(fetchIndex) : null;
        EnchantPresetCatalog.Enchant required = fetch != null ? fetch.book() : books.get(bookIndex);
        int want = fetch != null ? fetch.want() : 1;
        List<Integer> slots = new ArrayList<>();
        for (int slot = 0; slot < topSlots() && slots.size() < want; slot++) {
            if (matchesBook(openContainer().getItemStack(slot), required)) slots.add(slot);
        }
        if (slots.isEmpty()) {
            // Empty book chest: this type leaves the campaign and the same chest is waited on afterwards. No retry
            // counter and no back-off.
            fetchEmpty.add(required);
            fetchIndex++;
            trace("book chest at {} holds no {} {}; campaign skips it", openingPosition, required.id(), required.level());
            state = State.RESTOCK_ENCHANTED_BOOK_CLOSE;
            return;
        }
        // Never take more than the bag can hold: the batch is bounded by free slots as well as by the type's deficit.
        int budget = Math.max(1, freeInventorySlots());
        List<Integer> take = slots.subList(0, Math.min(slots.size(), Math.min(want, budget)));
        fetchBefore = countBooks(required);
        sourceSlot = take.get(0);
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = new ArrayList<>();
        for (int slot : take) actions.add(new ShiftClick(openContainerId(), slot, ShiftClickItemAction.LEFT_CLICK));
        trace("book chest {}: taking x{} {} {} from slot(s) {}", openingPosition, take.size(),
            required.id(), required.level(), take);
        submitBurst(actions, State.RESTOCK_ENCHANTED_BOOK__AWAIT_WITHDRAW);
    }

    private void verifyBookTransfer() {
        EnchantPresetCatalog.Enchant book = fetchIndex < fetchQueue.size()
            ? fetchQueue.get(fetchIndex).book() : books.get(bookIndex);
        long carried = countBooks(book);
        if (carried > fetchBefore) {
            trace("book(s) taken: {} {} carried x{} (+{})", book.id(), book.level(), carried, carried - fetchBefore);
            fetchBefore = carried;
            fetchIndex++;
            state = State.RESTOCK_ENCHANTED_BOOK_CLOSE;
            return;
        }
        if (waitForActionTimer.tick(resultTimeoutTicks())) {
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
        if (operationIndex == 0 && enchantStartTime == unchantStartTime) enchantStartTime = System.nanoTime();
        activeOperation = operations.get(operationIndex);
        takeAttempts = 0;
        stepLogged = false;
        trace("anvil opened for merge[{}]: {} + {} => {}", operationIndex,
            describeNode(activeOperation.left()), describeNode(activeOperation.right()),
            describeNode(activeOperation.result()));
        state = State.ENCHANT_TRY_START;
    }

    private void placeAnvilInputs() {
        int windowId = openContainerId();
        if (windowId == 0 || windowId != activeContainerId) {
            // The window can close in the tick between "this step continues in the anvil that is still open" and the
            // placements that carry that decision out: an anvil that spent its last use changes block state and the
            // server closes the window with it. Placing anyway sent two clicks into a window that no longer existed,
            // left slot 2 empty, and was only noticed 1.5s later as "Anvil preview timed out" - a park, a back-off and
            // a kill window for what is really "open the next anvil". CHECK_ANVIL reopens it, or fetches a replacement
            // when the block is gone, and the piece keeps its progress either way.
            trace("merge[{}] anvil window closed before the inputs were placed (open={} expected={}); reopening",
                operationIndex, windowId, activeContainerId);
            waitForActionTimer.reset();
            state = State.CHECK_ANVIL;
            return;
        }
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
            left, describeItem(playerItem(left)), right, describeItem(playerItem(right)));
        boolean equipmentMerge = activeOperation != null
            && containsTarget(activeOperation.result(), targetPlaceholder());
        if (equipmentMerge) {
            // The piece's own name against the template name. A merge on this server rebuilds the result from its
            // inputs and drops a custom name that the packet does not write again, so the name field is filled in on
            // EVERY equipment merge - starting with the first one, where the piece is still fresh and a rename costs
            // one level instead of the whole prior-work penalty a rename pass on a finished piece would be charged.
            trace("merge[{}] equipment name before merge: '{}' (template '{}', re-assert={})", operationIndex,
                itemSnapshot == null ? "" : catalog().customName(itemSnapshot), desiredRename(), renamesOnMerge());
        }
        // A quick move is one packet instead of two (pick up + place) and is what a player does. The
        // server picks the destination itself: the first empty input slot, or one already holding the
        // same stack. That is the wanted slot for the base input while slot 0 is empty, and for a
        // second input that differs from the first. Two identical stacks would merge into slot 0 and
        // leave slot 1 empty, so that case keeps the two explicit clicks.
        ItemStack leftStack = playerItem(left);
        ItemStack rightStack = playerItem(right);
        boolean inputsEmpty = stationInputsEmpty();
        boolean quickRight = inputsEmpty && leftStack != null && rightStack != null
            && !sameStack(leftStack, rightStack);
        List<com.zenith.feature.inventory.actions.InventoryAction> actions = new ArrayList<>(moveIntoStation(left, 0, inputsEmpty));
        actions.addAll(moveIntoStation(right, 1, quickRight));
        // The name rides on every merge that touches the equipment, and on the first one above all: that is the
        // cheapest moment (a fresh piece pays one level) and it is the only way the name survives, because a merge
        // that is not told the name comes back unnamed - the piece goes into the anvil as "钻石剑mx" and the result
        // slot reports ''. Every other merge (a book pair) gets an explicitly EMPTY name instead, so a field left over
        // from the equipment merge can never paint the result.
        if (renamesOnMerge()) {
            trace("merge[{}] name field <- '{}' (piece '{}')", operationIndex, desiredRename(),
                itemSnapshot == null ? "" : catalog().customName(itemSnapshot));
            actions.add(new RenameItem(openContainerId(), desiredRename()));
            renameSentThisMerge = true;
            anvilNameFieldDirty = true;
        } else if (equipmentMerge) {
            // An equipment merge that must NOT be named (no template name, or the merge-rename was given up). The
            // field is left exactly as it is: an empty name field makes this anvil rebuild the result without the
            // piece's own name, which is the very thing the packet above exists to prevent.
            trace("merge[{}] equipment merge without a name packet (name field left untouched)", operationIndex);
            renameSentThisMerge = false;
        } else {
            // A book pair merged after a named sword inherits the name the field still holds, comes out named and
            // stops stacking with its own copies. The field is therefore cleared on EVERY merge that does not
            // produce the equipment - not only when a flag says an equipment merge came before it, because a plan
            // refresh in the middle of the window resets that flag while the server keeps the typed name.
            if (anvilNameFieldDirty) {
                trace("merge[{}] clearing the name field left over from the equipment merge", operationIndex);
            }
            actions.add(new RenameItem(openContainerId(), ""));
            renameSentThisMerge = false;
            anvilNameFieldDirty = false;
        }
        stepLogged = false;
        clearAnvilCost();
        submit(actions, State.AWAIT_ENCHANT);
        // The preview is a second server round-trip after the clicks, so it gets the result budget, not the
        // plain interaction budget: a slow preview is a retry, not an enchant failure.
        waitForActionTimer.reset();
    }

    /**
     * One station input (anvil or grindstone), moved with a single quick move when the server would put it in
     * {@code stationSlot} anyway, and with the explicit pick-up + place pair otherwise.
     */
    private List<com.zenith.feature.inventory.actions.InventoryAction> moveIntoStation(int playerSlot, int stationSlot, boolean quickMove) {
        int from = inventoryContainerSlot(playerSlot, topSlots());
        if (quickMove) {
            return List.of(new ShiftClick(openContainerId(), from, ShiftClickItemAction.LEFT_CLICK));
        }
        return List.of(
            new ClickItem(openContainerId(), from, ClickItemAction.LEFT_CLICK),
            new ClickItem(openContainerId(), stationSlot, ClickItemAction.LEFT_CLICK));
    }

    private boolean stationInputEmpty(int slot) {
        ItemStack stack = openContainer().getItemStack(slot);
        return stack == null || stack == Container.EMPTY_STACK;
    }

    /** Both anvil input slots are empty, i.e. a quick move lands in slot 0 and the next one in slot 1. */
    private boolean stationInputsEmpty() {
        return stationInputEmpty(0) && stationInputEmpty(1);
    }

    /** Vanilla "same item and components", i.e. the rule a quick move uses to decide whether stacks merge. */
    private boolean sameStack(ItemStack a, ItemStack b) {
        return a.getId() == b.getId() && a.getDataComponentsOrEmpty().equals(b.getDataComponentsOrEmpty());
    }

    /** An enchantItem that needs no merge but still needs its name: place it alone in the anvil and rename it there. */
    private void placeRenameInput() {
        String name = desiredRename();
        if (name == null) { state = State.CLOSE_ANVIL; return; }
        playerSlot = findCurrentEquipment();
        if (playerSlot < 0) { keepAndRetry("Cannot find the enchantItem to rename"); return; }
        namedBeforeRename = countNamedStacks(name);
        List<com.zenith.feature.inventory.actions.InventoryAction> actions =
            new ArrayList<>(moveIntoStation(playerSlot, 0, stationInputsEmpty()));
        actions.add(new RenameItem(openContainerId(), name));
        stepLogged = false;
        trace("rename pass placing slot {} {} (want '{}')", playerSlot, describeItem(playerItem(playerSlot)), name);
        clearAnvilCost();
        submit(actions, State.AWAIT_ENCHANT);
        waitForActionTimer.reset();
    }

    /** Opens the anvil for a rename-only pass, which is the only work left for an already finished enchantItem. */
    private void beginRenamePass() {
        renamePass = true;
        renameTries = 0;
        String name = desiredRename();
        setWorkflowStatus("No merge left; " + (name == null || name.isEmpty()
            ? "clearing the enchantItem's name" : "renaming the enchantItem to \"" + name + "\""));
        state = State.CHECK_ANVIL;
    }

    /**
     * True when the inputs of the merge on the anvil have to be submitted together with the name field.
     *
     * <p>The name is written on the first merge that touches the equipment - the cheapest moment, one level on a
     * fresh piece - and re-asserted on every later equipment merge, because a merge on this server returns the result
     * without the custom name unless the field is written again. Comparing the piece's own name with the template
     * name and skipping the packet when they match is what silently produced unnamed swords: the piece went into the
     * anvil named and the result slot came back blank.
     *
     * <p>Only a clear-name job (empty template name) is filtered here, since an empty field changes nothing on a piece
     * that has no name - the anvil answers that with a red X. After a couple of silent previews
     * ({@link #renameMergeRefusals}) the packet is dropped for the rest of the piece and the end-of-piece rename pass
     * finishes the name instead.
     */
    private boolean renamesOnMerge() {
        if (activeOperation == null || renameGivenUp || renameOnMergeDisabled) return false;
        if (!containsTarget(activeOperation.result(), targetPlaceholder())) return false;
        String name = desiredRename();
        if (name == null) return false;
        return !name.isEmpty() || needsRename();
    }

    /** Index of the first plan operation whose result is the equipment, i.e. where the name is written first. */
    private int firstEquipmentMerge() {
        String placeholder = targetPlaceholder();
        for (int i = 0; i < operations.size(); i++) {
            if (containsTarget(operations.get(i).result(), placeholder)) return i;
        }
        return -1;
    }

    /**
     * True when merge {@code index} is the one that actually writes the piece's name, i.e. the merge the anvil charges
     * the extra level for: the first equipment merge, and only while the piece is still unnamed.
     *
     * <p>The name packet itself is re-sent on every later equipment merge (this server rebuilds the result and drops a
     * custom name the field does not carry), but re-writing the name a piece already wears changes nothing and is free
     * - the anvil only charges the extra level for the merge that renames it. Adding the level to every equipment merge
     * made a 14-level step look like 15 and farmed one level too many for it.
     */
    private boolean renameAppliesTo(int index) {
        if (index < 0 || index >= operations.size()) return false;
        if (!containsTarget(operations.get(index).result(), targetPlaceholder())) return false;
        if (index != firstEquipmentMerge()) return false;
        String name = desiredRename();
        return name != null && !name.isEmpty() && needsRename();
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
     * True when this piece's own name differs from the template name, i.e. the name still has to be written.
     *
     * <p>This is the comparison the piece is judged by - the piece in hand against {@code target.renameName} - and it
     * is used to decide the rename pass at the END of a piece and to decide whether an empty template name is a
     * meaningful "clear it". It is deliberately no longer a gate on the merge packet: on this server a merge drops a
     * custom name that is not written again, so "the piece already wears the name" is exactly the state in which the
     * packet must still be sent - see {@link #renamesOnMerge()}.
     */
    private boolean needsRename() {
        String name = desiredRename();
        if (name == null || itemSnapshot == null || renameGivenUp) return false;
        return !name.equals(catalog().customName(itemSnapshot));
    }

    private void verifyAnvilPreview() {
        if (renamePass) { verifyRenamePreview(); return; }
        if (openContainerId() == 0) {
            // Nothing to preview without a window: the anvil's last use closes it. Waiting the full result budget for
            // a slot that no longer exists only delays the reopen, so hand it to CHECK_ANVIL at once.
            trace("merge[{}] preview: the anvil window is gone; reopening", operationIndex);
            waitForActionTimer.reset();
            state = State.CHECK_ANVIL;
            return;
        }
        ItemStack result = openContainer().getItemStack(2);
        OptionalIntCost price = currentCost();
        // Survival servers keep reporting the level cost but blank the result slot once the operation passes the
        // 39-level limit - the same property the vanilla screen turns into the red "Too Expensive!". That - and only
        // that - is a real "this item can no longer be enchanted" outcome.
        if (price.value >= TOO_EXPENSIVE_LEVEL) {
            trace("merge[{}] anvil reports 'Too Expensive': cost={} >= {} (planned={}, result={})", operationIndex,
                price.value, TOO_EXPENSIVE_LEVEL, activeOperation.expectedCost(), describeItem(result));
            failPermanently("Too expensive: merge[" + operationIndex + "] would cost " + price.value
                + " levels (anvil limit " + (TOO_EXPENSIVE_LEVEL - 1) + ")");
            return;
        }
        boolean empty = result == null || result == Container.EMPTY_STACK;
        if (empty || price.value < 0) {
            // The anvil is consumed by its last use, and the preview of that very merge never arrives because the
            // window belongs to a block that is gone. Watching the block during the wait turns this into the normal
            // "fetch a replacement" recovery within a tick instead of a full result timeout plus a parked piece.
            if (anvilPosition != null && !isAnvilBlock(anvilPosition)) {
                trace("merge[{}] preview: the anvil at {} is gone; fetching a replacement", operationIndex,
                    anvilPosition);
                keepAndRetry("Anvil is gone while merge[" + operationIndex + "] was waiting for its preview");
                return;
            }
            // No result yet: the window or the preview packet is still in flight, which is not a failure.
            if (waitForActionTimer.tick(resultTimeoutTicks())) {
                trace("merge[{}] preview never showed a result (result={} cost={})", operationIndex,
                    describeItem(result), price.value);
                // A same-name rename can be answered with a red X by an anvil that only accepts a real change. That
                // would leave the piece stuck forever, so the packet is dropped after two silent previews and the
                // name is finished by the rename pass instead of blocking the whole batch.
                if (renameSentThisMerge && ++renameMergeRefusals >= 2 && !renameOnMergeDisabled) {
                    renameOnMergeDisabled = true;
                    trace("merge[{}] anvil keeps refusing the name field; the rest of this piece merges without it "
                        + "and the rename pass writes the name", operationIndex);
                }
                keepAndRetry("Anvil preview timed out for merge[" + operationIndex + "]");
            }
            return;
        }
        String mismatch = productMismatch(result, activeOperation.result(), false);
        int plannedCost = activeOperation.expectedCost() + (renameAppliesTo(operationIndex) ? 1 : 0);
        if (!stepLogged) {
            stepLogged = true;
            // Penalty is the wiki's prior-work penalty (2^uses - 1): it is what pushes the last merges towards the
            // 39-level limit, so both the old and the resulting value are printed next to the cost.
            trace("merge[{}] preview: result={} cost={} planned={} penalty {} -> {} expected={} match={}", operationIndex,
                describeItem(result), price.value, plannedCost,
                itemSnapshot == null ? 0 : catalog().priorWorkPenalty(itemSnapshot),
                catalog().priorWorkPenalty(result),
                describeEnchants(expectedEnchants(activeOperation.result(), targetPlaceholder())),
                mismatch == null);
            if (mismatch != null) trace("merge[{}] result rejected: {}", operationIndex, mismatch);
            // The plan is what the level is farmed by before the anvil is opened, so an operation this server
            // charges more for than the wiki's table says would otherwise be discovered inside the window, where the
            // only way out is closing the anvil and fighting again. The measured gap is therefore remembered and
            // added to the pre-anvil budget of the following merges.
            if (price.value > plannedCost) {
                int gap = Math.min(3, price.value - plannedCost);
                if (gap > planCostBias) {
                    planCostBias = gap;
                    trace("merge[{}] charged {} instead of the planned {}; later anvils are stocked {} level(s) "
                        + "above their plan", operationIndex, price.value, plannedCost, planCostBias);
                }
            }
        }
        if (mismatch != null) {
            // The anvil shows something other than the planned merge: close it and let the item be re-planned from
            // its live state instead of spending the inputs.
            keepAndRetry("Anvil result did not match merge[" + operationIndex + "]");
            return;
        }
        // Farm for the rest of the piece, not for this one merge: a 3-level merge followed by an 11-level merge would
        // otherwise close the anvil for 3 levels, come back, and close it again for 11.
        int refill = refillBudget(price.value);
        waitingCost = refill;
        renameMergeRefusals = 0;
        if (!ensureXpForCost(refill)) { state = State.CLOSE_FOR_XP; return; }
        state = State.TAKE_RESULT;
    }

    /** A rename-only preview must show the new name; the enchantments are not part of this check. */
    private void verifyRenamePreview() {
        String name = desiredRename();
        if (openContainerId() == 0) {
            trace("rename preview: the anvil window is gone; reopening");
            waitForActionTimer.reset();
            state = State.CHECK_ANVIL;
            return;
        }
        ItemStack result = openContainer().getItemStack(2);
        OptionalIntCost price = currentCost();
        if (name == null) { keepAndRetry("Rename name disappeared before the anvil preview"); return; }
        // A name is cosmetic: a rename that grew past the level limit is skipped instead of costing the item.
        if (price.value >= TOO_EXPENSIVE_LEVEL) {
            trace("rename skipped: cost={} >= {}", price.value, TOO_EXPENSIVE_LEVEL);
            renamePass = false;
            setWorkflowStatus("Rename skipped: it would cost " + price.value + " levels");
            state = State.CLOSE_ANVIL;
            return;
        }
        boolean empty = result == null || result == Container.EMPTY_STACK;
        if (empty || price.value < 0) {
            if (anvilPosition != null && !isAnvilBlock(anvilPosition)) {
                trace("rename preview: the anvil at {} is gone; fetching a replacement", anvilPosition);
                keepAndRetry("Anvil is gone while the rename pass was waiting for its preview");
                return;
            }
            if (waitForActionTimer.tick(resultTimeoutTicks())) {
                // A red X (no output at all) means the anvil refused the rename: never let a label hold the item back.
                trace("rename preview produced no output (result={} cost={}); rename refused?", describeItem(result), price.value);
                if (++renameTries >= 2) {
                    renameGivenUp = true;
                    renamePass = false;
                    setWorkflowStatus("Rename skipped: the anvil refused to rename to \"" + name + "\"");
                    state = State.CLOSE_ANVIL;
                    return;
                }
                keepAndRetry("Anvil rename preview timed out");
            }
            return;
        }
        if (!name.equals(catalog().customName(result))) {
            trace("rename preview shows '{}' instead of '{}'", catalog().customName(result), name);
            // A name is cosmetic. If the server will not apply it after a couple of tries the item is unloaded as it
            // is, instead of being retried forever over a label.
            if (++renameTries >= 2) {
                renameGivenUp = true;
                renamePass = false;
                setWorkflowStatus("Rename skipped: the anvil did not apply \"" + name + "\"");
                state = State.CLOSE_ANVIL;
                return;
            }
            keepAndRetry("Anvil rename preview did not apply the new name");
            return;
        }
        if (!stepLogged) { stepLogged = true; trace("rename preview ok: '{}' cost={}", name, price.value); }
        int refill = refillBudget(price.value);
        waitingCost = refill;
        if (!ensureXpForCost(refill)) { state = State.CLOSE_FOR_XP; return; }
        state = State.TAKE_RESULT;
    }

    private void waitForExperience() {
        // Not enough levels yet is a waiting condition, not a failure: the item keeps its progress and is resumed
        // once XP farming can start again (or a later cycle tries again).
        if (consumeXpInterruption()) { keepAndRetry("XP farming is unavailable right now"); return; }
        // waitingCost is 0 when the gate was entered from the plan instead of from an anvil window; the rest of the
        // piece's plan is then the number to farm for, exactly as the anvil-window path budgets it.
        int cost = waitingCost > 0 ? waitingCost : refillBudget(plannedCostOf(operationIndex));
        if (ensureXpForCost(cost)) state = State.CHECK_ANVIL;
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
                || !name.equals(catalog().customName(result))) {
                keepAndRetry("Anvil rename output changed before pickup");
                return;
            }
            if (findEmptyPlayerSlot(topSlots()) < 0) { keepAndRetry("No empty BOT inventory slot for the anvil result"); return; }
            takeAttempts = 0;
            submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.VERIFY_RESULT);
            return;
        }
        if (!matchesProduct(result, activeOperation.result())) { keepAndRetry("Anvil output changed before pickup"); return; }
        if (findEmptyPlayerSlot(topSlots()) < 0) { keepAndRetry("No empty BOT inventory slot for the anvil result"); return; }
        takeAttempts = 0;
        submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.VERIFY_RESULT);
    }

    private void verifyAnvilResult() {
        if (renamePass) {
            // A rename only counts when the bag gained one more named piece: scanning for "any piece with that name"
            // let an identical sibling that was named by an earlier pass answer for this one, the pass was closed as
            // done and the piece still on the anvil went back into the bag without a name.
            if (countNamedStacks(desiredRename()) <= namedBeforeRename) {
                if (waitForActionTimer.tick(resultTimeoutTicks())) keepAndRetry("Renamed enchantItem was not received");
                return;
            }
            int renamed = findRenamedItem(desiredRename());
            if (renamed >= 0) {
                itemSnapshot = playerItem(renamed).clone();
                trace("rename done: slot {} {}", renamed, describeItem(itemSnapshot));
                renamePass = false;
                state = State.CLOSE_ANVIL;
                return;
            }
            if (waitForActionTimer.tick(resultTimeoutTicks())) keepAndRetry("Renamed enchantItem was not received");
            return;
        }
        int resultSlot = findProduct(activeOperation.result());
        if (resultSlot >= 0) {
            boolean equipment = containsTarget(activeOperation.result(), targetPlaceholder());
            if (equipment) itemSnapshot = playerItem(resultSlot).clone();
            trace("merge[{}] done: took slot {} {}", operationIndex, resultSlot, describeItem(playerItem(resultSlot)));
            if (equipment) {
                String actual = catalog().customName(itemSnapshot);
                trace("merge[{}] equipment name after merge: '{}' (template '{}')", operationIndex, actual,
                    desiredRename());
                // Visible proof that the name field was really written on this merge: the piece's own name is the
                // template name again. If it is not, the end-of-piece rename pass still writes it, at a higher price.
                if (desiredRename() != null && !desiredRename().isEmpty() && !desiredRename().equals(actual)) {
                    trace("merge[{}] result came back without the template name; the rename pass will write it",
                        operationIndex);
                }
            }
            operationIndex++;
            takeAttempts = 0;
            transientRetries = 0;
            // The pickup emptied both input slots server-side, so the next merge can usually be fed into the same
            // window. Closing the anvil there costs a close packet, a path request and a fresh interact per merge,
            // which was most of the time a merge took.
            if (continueInAnvilWindow()) return;
            // Step order: this piece's step is done, but a sibling piece of the batch still needs the same step. It
            // continues in this window instead of paying another anvil opening.
            if (continueBatchInAnvilWindow()) return;
            state = State.CLOSE_ANVIL;
            return;
        }
        if (!waitForActionTimer.tick(resultTimeoutTicks())) return;
        // The pickup click can be swallowed in flight while the server keeps the result in slot 2: re-click it a
        // couple of times before treating the merge as unfinished.
        ItemStack still = openContainer().getItemStack(2);
        if (still != null && still != Container.EMPTY_STACK && matchesProduct(still, activeOperation.result())
            && takeAttempts < MAX_TAKE_ATTEMPTS) {
            takeAttempts++;
            trace("merge[{}] result still in the anvil slot; re-clicking (attempt {})", operationIndex, takeAttempts);
            submit(new ShiftClick(openContainerId(), 2, ShiftClickItemAction.LEFT_CLICK), State.VERIFY_RESULT);
            return;
        }
        trace("merge[{}] result not in inventory; wanted {} => {}", operationIndex,
            describeNode(activeOperation.result()),
            describeEnchants(expectedEnchants(activeOperation.result(), targetPlaceholder())));
        traceInventory("anvil result missing");
        keepAndRetry("Anvil result was not received for merge[" + operationIndex + "]");
    }

    /**
     * True when the next merge can start in the anvil window that is still open, in which case the state machine is
     * pointed straight at {@code ENCHANT_TRY_START}.
     *
     * <p>Taking a result consumes both inputs, so the anvil is immediately ready for the next pair. Only used when
     * the window really belongs to this transaction, both input slots are empty (a leftover stack would sit where
     * the next input has to go, and a quick move would then pick the wrong slot) and the next merge's inputs are
     * already carried. Everything else takes the old close/reopen path, which also re-checks the anvil block.
     */
    private boolean continueInAnvilWindow() {
        if (renamePass) return false;
        if (operationIndex >= operations.size()) return false;
        int id = openContainerId();
        if (id == 0 || id != activeContainerId) return false;
        Container container = openContainer();
        if (container == null || container.getType() != ContainerType.ANVIL) return false;
        if (!stationInputEmpty(0) || !stationInputEmpty(1)) return false;
        if (!inputsReady()) return false;
        trace("merge[{}] starts in the still open anvil", operationIndex);
        state = State.ENCHANT_TRY_START;
        return true;
    }

    /**
     * Step order only: the piece in hand just finished its merge step, but the batch is not done. The anvil stays
     * open and the next carried piece of the batch starts its step in this same window, exactly like the next merge
     * of one piece does. Ten pieces therefore cost one anvil opening per step instead of ten.
     *
     * <p>When the next piece's step inputs are already in the bag the merge starts at once; otherwise the adoption
     * leaves the workflow in the normal book phase, which closes the window and comes back after the withdrawal.
     *
     * @return true when the workflow continues on the adopted piece and the caller must not close the anvil
     */
    private boolean continueBatchInAnvilWindow() {
        if (!stepOrder() || renamePass || batchJob == null) return false;
        int id = openContainerId();
        if (id == 0 || id != activeContainerId) return false;
        Container container = openContainer();
        if (container == null || container.getType() != ContainerType.ANVIL) return false;
        if (!stationInputEmpty(0) || !stationInputEmpty(1)) return false;
        int next = findNextStepPiece();
        if (next < 0) return false;
        // Hand the transaction over to the next piece without closing the window: clearTransaction() drops every
        // per-piece field, then adoptCarried() plans the piece exactly like beginInput would.
        clearTransaction();
        activeContainerId = id;
        adoptCarried(next);
        // The adopted piece owns the workflow now, whatever it decided: continue the step here, restock its books,
        // rename it, reset it or unload it. Falling back to CLOSE_ANVIL would overwrite that decision with a plain
        // close.
        if (failureReason == null && !renamePass && !grindRequired && !operations.isEmpty() && inputsReady()) {
            trace("batch step continues in the still open anvil with slot {}", next);
            state = State.ENCHANT_TRY_START;
        } else {
            // The adopted plan closes or reopens the window on its own (book restock, rename, relocate, unload).
            trace("batch step of slot {} is handed to its own plan ({} merge(s), {} book(s))", next,
                operations.size(), books.size());
        }
        return true;
    }

    /**
     * Least advanced unfinished piece of the open batch. The piece that just finished a step carries more than the
     * others, so it steps aside; when every piece is at the same point one of them simply starts the next step.
     */
    private int findNextStepPiece() {
        if (batchJob == null) return -1;
        Map<String, Integer> wanted = targetEnchantsOf(batchJob);
        int itemId = configuredItemId();
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != itemId) continue;
            if (runnableJobFor(stack).orElse(null) != batchJob) continue;
            if (isCoolingDown(stack) || catalog().hasCurse(stack)) continue;
            if (normalizedEnchants(stack).equals(wanted)) continue;
            int score = progressScore(stack);
            if (score < bestScore) { bestScore = score; best = slot; }
        }
        return best;
    }

    /**
     * Carried stacks of the job's item that already wear {@code name}. Used as the baseline of a rename pass: the pass
     * only succeeded when this count grows, because a batch carries several identical pieces.
     */
    private int countNamedStacks(String name) {
        if (name == null || name.isEmpty()) return 0;
        int itemId = configuredItemId();
        int count = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != itemId) continue;
            if (name.equals(catalog().customName(stack))) count++;
        }
        return count;
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
            if (!name.equals(catalog().customName(stack))) continue;
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
            slots.stream().map(slot -> slot + "=" + describeItem(playerItem(slot)))
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
     * <p>A regular single-piece unload is picked by identity. A batch unload (or a low-space flush) sweeps every
     * carried stack of the same job whose enchantment set already equals the target, so the whole batch leaves in one
     * window; this also covers pieces renamed by an earlier pass.
     */
    private List<Integer> depositTargets() {
        List<Integer> slots = new ArrayList<>();
        if (!flushing && !batchUnload && !returningExcessInput) {
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
        // A batch unload deposits every finished product of this job, not just this piece. A regular single-piece
        // unload stays identity-scoped so an unrelated piece in the same slot is never swept into the chest.
        if (!flushing && !batchUnload && !returningExcessInput) return sameStackIdentity(itemSnapshot, stack);
        // The same job, not merely the same item: two jobs can share an item type (and even a target set) while
        // pointing at different output chests, and a batch must never file one job's products into the other's chest.
        if (runnableJobFor(stack).orElse(null) != target) return false;
        // A return trip takes every RAW piece of the job; a flush/batch unload takes every piece that is DONE - the
        // target enchantments AND the template name. Depositing on the enchantments alone filed nine unnamed swords
        // into the output chest next to the one piece the rename pass had just named.
        if (returningExcessInput) return catalog().enchantments(stack).isEmpty();
        // A rename the anvil refused (cost past the limit) is given up on for this piece: it leaves unnamed rather
        // than holding the whole batch in the bag forever.
        if (renameGivenUp && itemSnapshot != null && sameStackIdentity(itemSnapshot, stack)) return true;
        return isDeliverable(target, stack);
    }

    private void verifyUnloaded() {
        List<Integer> remaining = remainingDepositSlots();
        if (remaining.isEmpty()) {
            outputStored = true;
            trace("deposit confirmed: {} stack(s) left the inventory", depositSlots.size());
            // A step-wise batch completes most of its pieces inside the batch loop, but the piece that closes the
            // batch never passes through that loop - it is the one that hands the workflow to this unload. Left to
            // the single completion log the batch reported "1 piece(s)" while the chest received ten. The deposit is
            // the honest count, so whatever the completion log did not count is added here.
            if (!flushing && !returningExcessInput && batchJob != null) {
                int uncounted = depositSlots.size() - stats.batchPieces;
                if (uncounted > 0) recordPiecesDone(uncounted);
            }
            state = State.CLOSE_OUTPUT;
            return;
        }
        if (!waitForActionTimer.tick(interactTimeoutTicks())) return;
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
        // transient retry on it. The window is closed and the unload branch is entered again on the next tick.
        reenter(State.POST_ENCHANT, "Unload container for job " + target.displayName() + " is full");
    }

    /** Target slots that still hold a depositable stack; slots are stable while the window stays open. */
    private List<Integer> remainingDepositSlots() {
        List<Integer> left = new ArrayList<>();
        for (int slot : depositSlots) if (isDepositTarget(playerItem(slot))) left.add(slot);
        return left;
    }

    /**
     * A flush could not store its piece. The piece stays in the bag and the unload branch is entered again on the next
     * tick - there is only one unload chest per job, so nothing is retried behind the operator's back and nothing is
     * dropped.
     */
    private void flushBlocked(String reason) {
        reenter(State.POST_ENCHANT, "Could not unload the finished product: " + reason);
    }

    private void dropCurrentItem() {
        if (openContainerId() != 0) { closeContainer(State.DROP_ITEM); return; }
        int slot = findCurrentEquipment();
        if (slot >= 0) submit(new DropItem(0, slot, org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction.DROP_SELECTED_STACK), State.BACKOFF);
        else state = State.BACKOFF;
        awaitRestockUntil(System.currentTimeMillis() + AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay * 50L);
    }

    private void beginOutput() {
        if (itemSnapshot == null) {
            awaitRestockUntil(System.currentTimeMillis()
                + Math.max(0, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay) * 50L);
            if (openContainerId() != 0) closeContainer(State.BACKOFF); else state = State.BACKOFF;
            return;
        }
        if (openContainerId() != 0) { closeContainer(State.POST_ENCHANT); return; }
        // A successful item goes to the unload chest. A failed (half-done) item goes to the same chest, or back to the
        // input chest when the job's post-enchant mode asks for it. "overflow" is only a role name now: it resolves to
        // the job's output chest, because finished and half-done pieces share one container.
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
        Optional<BlockPos> output = containerPosition(target, role);
        if (output.isEmpty() && role.equals("input")) {
            output = containerPosition(target, "output");
            if (output.isPresent()) goTo = State.POST_ENCHANT_OVERFLOW_GO_TO;
        }
        if (flushing) {
            // A flush exists to free a slot, so it uses the unload chest directly: there is no second container to fall
            // back to, and retrying the same chest would only bounce off it again.
            role = "output";
            goTo = State.STORE_GO_TO_CHEST;
            output = containerPosition(target, role);
        } else if (returningExcessInput) {
            // Surplus raw pieces go back where they came from, not to the finished-product chest.
            role = "input";
            goTo = State.POST_ENCHANT_INPUT_GO_TO;
            output = containerPosition(target, role);
        }
        if (output.isEmpty()) {
            // Nothing can take the piece: keep it in the BOT inventory (never drop it) and enter this branch again on
            // the next tick, so a container bound in the meantime is used without losing the piece.
            reenter(State.POST_ENCHANT, "No " + role + " container is bound for job " + target.displayName());
            return;
        }
        openingPosition = output.get();
        // "overflow" no longer names a second chest, so the trace says what the chest is (the unload chest) instead of
        // implying the piece goes somewhere else than a finished product.
        String roleLabel = "overflow".equals(role) ? "unload" : role;
        trace("unload via {} container {} (failure={}, item={})", roleLabel, openingPosition,
            failureReason == null ? "none" : failureReason, describeItem(itemSnapshot));
        state = goTo;
    }

    private void closeOutput() {
        closeContainer(State.BACKOFF);
        awaitRestockUntil(System.currentTimeMillis()
            + Math.max(0, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay) * 50L);
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
     * never be reported as a failed enchant and must never push the item out to the unload chest.
     */
    private void failPermanently(String reason) {
        State from = state;
        trace("giving up on the enchantItem in state {}: {}", from, reason);
        failureReason = reason;
        markAttempt();
        setWorkflowStatus("Skipping enchantItem: " + reason);
        state = State.POST_ENCHANT;
    }

    /**
     * Parks the current transaction without touching the enchantItem. The piece is left where it is (in the BOT
     * inventory), the window is closed and the next cycle adopts it again, so an interrupted merge or a chest that
     * would not open costs a delay instead of the item.
     */
    private void keepAndRetry(String reason) {
        State from = state;
        trace("retry in state {}: {}{}", from, reason, itemSnapshot == null ? "" : " | item=" + describeItem(itemSnapshot));
        retryReason = reason;
        // A vanished anvil block explains most anvil-step failures; recover the station instead of dropping the enchantItem.
        if (from != State.CHECK_ANVIL && from != State.CRAFT_ANVIL_BLOCKS && from != State.AWAIT_CRAFT_ANVIL_BLOCKS
            && anvilRecoveryAttempts < MAX_ANVIL_RECOVERY && anvilPosition != null && !isAnvilBlock(anvilPosition)) {
            anvilRecoveryAttempts++;
            retryReason = null;
            setWorkflowStatus("Anvil broke at " + anvilPosition + "; fetching a replacement ("
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
            setWorkflowStatus("Parked in inventory for " + (SKIP_COOLDOWN_MS / 1000) + "s after repeated "
                + "retries: " + reason);
        } else {
            setWorkflowStatus("Keeping the enchantItem and retrying: " + reason);
        }
        awaitRestockUntil(System.currentTimeMillis() + retryDelayMs());
        if (openContainerId() != 0) closeContainer(State.BACKOFF); else state = State.BACKOFF;
    }

    /**
     * Leaves a carried piece where it is instead of unloading it: the bag slot is kept, the piece drops out of the
     * rotation for {@code SKIP_COOLDOWN_MS}, and the next carried/chest item gets its turn in the meantime. This is
     * the "no half-finished piece is dumped into the unload chest while it can still be worked on" rule.
     */
    private void parkCarried(String reason) {
        if (itemSnapshot != null) {
            String key = identityKey(itemSnapshot);
            skipUntil.put(key, System.currentTimeMillis() + SKIP_COOLDOWN_MS);
            itemAttempts.remove(key);
        }
        transientRetries = 0;
        failureReason = null;
        setWorkflowStatus("Keeping the carried enchantItem in the bag for "
            + (SKIP_COOLDOWN_MS / 1000) + "s: " + reason);
        trace("carried enchantItem parked in the bag: {}{}", reason,
            itemSnapshot == null ? "" : " | item=" + describeItem(itemSnapshot));
        state = State.ENTRYPOINT;
    }

    /** Growing pause between transient retries, capped so a healthy item is not stalled for minutes. */
    private long retryDelayMs() {
        // The pause is there to let one bad packet settle, not to slow the batch down. It used to start at a full
        // second whatever restartDelay said, and grow to half a minute; at 15s per piece that was a whole piece's
        // worth of standing still after three retries. It now starts at restartDelay (one client use-cooldown by
        // default) and only the repeats grow, with a much lower ceiling so a stubborn step surfaces quickly.
        long base = Math.max(200L, AutoEnchantPlugin.PLUGIN_CONFIG.restartDelay * 50L);
        return Math.min(5_000L, base * Math.max(1, Math.min(transientRetries, 5)));
    }

    /** Counts consecutive failures for the current enchantItem; a repeated offender is parked for a while. */
    private void markAttempt() {
        if (itemSnapshot == null) return;
        String key = identityKey(itemSnapshot);
        int attempts = itemAttempts.merge(key, 1, Integer::sum);
        if (attempts >= MAX_ITEM_ATTEMPTS) {
            skipUntil.put(key, System.currentTimeMillis() + SKIP_COOLDOWN_MS);
            itemAttempts.remove(key);
            setWorkflowStatus("Item parked for " + (SKIP_COOLDOWN_MS / 1000) + "s after " + attempts + " failed attempts");
        }
    }

    private boolean isCoolingDown(ItemStack stack) {
        Long until = skipUntil.get(identityKey(stack));
        return until != null && System.currentTimeMillis() < until;
    }

    private String identityKey(ItemStack stack) {
        if (stack == null) return "none";
        return stack.getId() + "|" + catalog().enchantments(stack) + "|" + catalog().priorWorkPenalty(stack)
            + "|" + catalog().customName(stack);
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
        if (!waitForActionTimer.tick(interactTimeoutTicks())) return;
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
     * Re-enters the matching OPEN_* state after a click that produced no usable window, exactly the trader's plain
     * "enter the open state again": no attempt counter, no back-off, and no stockout bookkeeping.
     *
     * <p>The one guard on top of the trader's transition is the station itself: a working anvil degrades (chipped,
     * damaged) and finally breaks, and the position then holds plain air. Re-clicking it resolves in the same tick -
     * "No block is at ...", no path, no window - so a vanished station is recovered instead of re-clicked.
     */
    /** Container-owning OPEN states; the anvil and grindstone stations are deliberately not in this set. */
    private boolean isContainerOpenState(State from) {
        return switch (from) {
            case RESTOCK_INPUT_GO_TO_CHEST, RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST, RESTOCK_ANVIL_GO_TO_CHEST,
                 STORE_GO_TO_CHEST, POST_ENCHANT_INPUT_GO_TO, POST_ENCHANT_OVERFLOW_GO_TO -> true;
            default -> false;
        };
    }

    /** Human-readable role for a missing-container warning, taken from the state that wanted to open it. */
    private String containerRole(State from) {
        return switch (from) {
            case RESTOCK_INPUT_GO_TO_CHEST, POST_ENCHANT_INPUT_GO_TO -> "pickup";
            case RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST -> "enchantment book";
            case RESTOCK_ANVIL_GO_TO_CHEST -> "anvil supply";
            case STORE_GO_TO_CHEST, POST_ENCHANT_OVERFLOW_GO_TO -> "unload";
            default -> "storage";
        };
    }

    /**
     * True when the marker's chunk is loaded but no container block stands there. An unloaded chunk is "unknown" and
     * must be walked to first, exactly like a broken anvil in an unloaded chunk.
     */
    private boolean containerGone(BlockPos pos) {
        return pos != null && World.isChunkLoadedBlockPos(pos.x(), pos.z()) && !isContainerBlock(pos);
    }

    /**
     * Someone removed a container the workflow depends on (usually the shulker box under the unload frame). The
     * trader-style reopen has no attempt counter, so re-clicking air would spin at dozens of clicks per second for
     * hours. Park this branch for a short, doubling backoff instead, say what is missing, and keep the carried
     * piece in the bag: it is unloaded as soon as the box is put back.
     */
    private void reportMissingContainer(BlockPos pos, String role, String reason) {
        long now = System.currentTimeMillis();
        Long until = missingContainerUntil.get(pos);
        if (until != null && now < until) {
            // Already parked for this box: never extend the wait because a different branch (e.g. a flush for a full
            // bag) walked back here in the meantime.
            awaitRestockUntil(until);
            state = State.AWAIT_RESTOCK;
            return;
        }
        int misses = missingContainerMisses.merge(pos, 1, Integer::sum);
        long backoff = Math.min(MISSING_CONTAINER_MAX_MS,
            MISSING_CONTAINER_MIN_MS * (1L << Math.min(4, misses - 1)));
        missingContainerUntil.put(pos, now + backoff);
        String what = "The " + role + " container at " + pos + " is gone (" + blockNameAt(pos)
            + " is there now); checking again in " + (backoff / 1000) + "s";
        warn("Auto-enchant: {}{}", what, reason == null ? "" : " [" + reason + "]");
        clearTransaction();
        setWorkflowStatus(what);
        awaitRestockUntil(now + backoff);
        state = State.AWAIT_RESTOCK;
    }

    /** Forget a missing-container flag once a window actually opened at that position. */
    private void clearMissingContainer(BlockPos pos) {
        if (pos == null) return;
        missingContainerUntil.remove(pos);
        missingContainerMisses.remove(pos);
    }

    private void reopenContainer(String reason) {
        if (isContainerOpenState(openState) && openingPosition != null && containerGone(openingPosition)) {
            reportMissingContainer(openingPosition, containerRole(openState), reason);
            return;
        }
        if (stationMissing()) {
            trace("{} no longer holds an anvil ({}); restoring the station", openingPosition, reason);
            anvilRecoveryAttempts = 0;
            if (openContainerId() != 0) closeContainer(State.CHECK_ANVIL); else state = State.CHECK_ANVIL;
            return;
        }
        setWorkflowStatus("Reopening " + openingPosition + ": " + reason);
        if (openContainerId() != 0) closeContainer(openState); else state = openState;
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
     * Budget for an anvil result (or a picked-up output) to arrive, i.e. for one server round trip.
     *
     * <p>Kept short on purpose: an anvil that was used up produces no preview at all, and waiting seconds on it only
     * delays the replacement. A result that really is late is retried - {@code keepAndRetry} keeps the item and the
     * merge is attempted again - so a short budget costs one round trip, never an item.
     */
    private long awaitResultMs() {
        return Math.max(1_500L, awaitIntactMs());
    }

    /** Interaction budget in ticks; the trader's own timeout setting drives the timers below. */
    private long interactTimeoutTicks() {
        return Math.max(1L, AutoEnchantPlugin.PLUGIN_CONFIG.waitForInteractTimeoutTicks);
    }

    /**
     * Budget for one open click before it is re-sent: four ticks (one client use-cooldown), then the full interaction
     * budget for a block that keeps refusing.
     *
     * <p>Measured: at this server nearly every open misses once and the re-sent click opens the window in the same
     * second, whether the budget was 5, 10 or 20 ticks. So the first click is not slow, it is dropped - the BOT has
     * just walked up and its position has not reached the server yet. Waiting the full budget only adds dead time on
     * every chest and anvil (36 opens a batch); four ticks covers the position round trip, and a block that really
     * cannot be opened still falls back to the long budget so it is not hammered.
     */
    private long openRetryTicks() {
        return openClickMisses <= 2 ? 4L : interactTimeoutTicks();
    }

    /** Counts an open click that produced no window, starting over when the block being opened is a different one. */
    private void noteOpenClickMiss() {
        boolean sameSpot = openMissPosition != null && openingPosition != null
            && openMissPosition.x() == openingPosition.x() && openMissPosition.y() == openingPosition.y()
            && openMissPosition.z() == openingPosition.z();
        if (!sameSpot) {
            openClickMisses = 0;
            openMissPosition = openingPosition;
        }
        openClickMisses++;
        if (openClickMisses == 1) {
            // Distance is printed because a click that is out of reach is dropped silently by the server, and these
            // misses are frequent enough that the cause has to be visible in the log rather than guessed.
            var player = CACHE.getPlayerCache().getThePlayer();
            double dist = player == null ? -1
                : Math.sqrt(Math.pow(player.getX() - (openingPosition.x() + 0.5), 2)
                    + Math.pow(player.getY() - openingPosition.y(), 2)
                    + Math.pow(player.getZ() - (openingPosition.z() + 0.5), 2));
            trace("no window after clicking {} within {} tick(s); re-sending the click (distance {})", openingPosition,
                openRetryTicks(), String.format("%.2f", dist));
        }
    }

    /** Round-trip budget in ticks, rounded up from {@link #awaitResultMs()} so a wait never fires early. */
    private long resultTimeoutTicks() {
        return Math.max(1L, (awaitResultMs() + 49L) / 50L);
    }

    /**
     * Starts a restock/idle wait that has to last until an absolute time. The tick timer is seeded with the remaining
     * budget, so the wait is exact instead of polling the chest again every tick.
     */
    private void awaitRestockUntil(long untilMs) {
        long ms = Math.max(50L, untilMs - System.currentTimeMillis());
        restockWaitTicks = Math.max(1L, (ms + 49L) / 50L);
        waitForRestockTimer.reset();
    }

    /** The container-open path of the trip in flight; each chest family owns its own future, as in the trader. */
    private PathingRequestFuture openPathFuture() {
        return switch (state) {
            case RESTOCK_INPUT_WITHDRAW, RESTOCK_ENCHANTED_BOOK_WITHDRAW, RESTOCK_ANVIL_WITHDRAW -> restockPathingFuture;
            case GRINDSTONE_AWAIT_INTERACT -> interactWithGrindstone;
            case ENCHANT_AWAIT_INTERACT_WITH_ANVIL -> interactWithAnvil;
            case STORE_AWAIT_INTERACT -> storePathingFuture;
            case POST_ENCHANT_INPUT_AWAIT_INTERACT, POST_ENCHANT_OVERFLOW_AWAIT_INTERACT -> postEnchantPathingFuture;
            default -> null;
        };
    }

    /** Files the path just started under the trip that opened it; called while the OPEN_* state is still current. */
    private void setOpenPathFuture(PathingRequestFuture future) {
        switch (state) {
            case RESTOCK_INPUT_GO_TO_CHEST, RESTOCK_ENCHANTED_BOOK_GO_TO_CHEST, RESTOCK_ANVIL_GO_TO_CHEST ->
                restockPathingFuture = future;
            case GRINDSTONE_GO_TO_CHEST -> interactWithGrindstone = future;
            case ENCHANT_INTERACT_WITH_ANVIL -> interactWithAnvil = future;
            case STORE_GO_TO_CHEST -> storePathingFuture = future;
            case POST_ENCHANT_INPUT_GO_TO, POST_ENCHANT_OVERFLOW_GO_TO -> postEnchantPathingFuture = future;
            default -> { }
        }
    }

    private void clearOpenPathFuture() {
        openPathPending = false;
        switch (state) {
            case RESTOCK_INPUT_WITHDRAW, RESTOCK_ENCHANTED_BOOK_WITHDRAW, RESTOCK_ANVIL_WITHDRAW ->
                restockPathingFuture = PathingRequestFuture.rejected;
            case GRINDSTONE_AWAIT_INTERACT -> interactWithGrindstone = PathingRequestFuture.rejected;
            case ENCHANT_AWAIT_INTERACT_WITH_ANVIL -> interactWithAnvil = PathingRequestFuture.rejected;
            case STORE_AWAIT_INTERACT -> storePathingFuture = PathingRequestFuture.rejected;
            case POST_ENCHANT_INPUT_AWAIT_INTERACT, POST_ENCHANT_OVERFLOW_AWAIT_INTERACT ->
                postEnchantPathingFuture = PathingRequestFuture.rejected;
            default -> { }
        }
    }

    private void clearAllPathFutures() {
        openPathPending = false;
        restockPathingFuture = PathingRequestFuture.rejected;
        interactWithGrindstone = PathingRequestFuture.rejected;
        interactWithAnvil = PathingRequestFuture.rejected;
        storePathingFuture = PathingRequestFuture.rejected;
        postEnchantPathingFuture = PathingRequestFuture.rejected;
    }

    /** The inventory request in flight, whichever trip submitted it; one action runs at a time. */
    private RequestFuture pendingActionFuture() {
        RequestFuture[] futures = {restockWithdrawFuture, inventoryActionFuture, enchantActionFuture,
            shiftResultFuture, storeDepositFuture, postEnchantDepositFuture};
        for (RequestFuture future : futures) {
            if (future != null && future != RequestFuture.rejected) return future;
        }
        return null;
    }

    /** Files one submitted request under the trip that will await it, so a stale result cannot cross trips. */
    private void attachActionFuture(State after, RequestFuture future) {
        switch (after) {
            case RESTOCK_INPUT_AWAIT_WITHDRAW, RESTOCK_ENCHANTED_BOOK__AWAIT_WITHDRAW, RESTOCK_ANVIL_AWAIT_WITHDRAW ->
                restockWithdrawFuture = future;
            case GRINDSTONE_AWAIT_RESULT -> inventoryActionFuture = future;
            case GRINDSTONE_AWAIT_TAKE, VERIFY_RESULT -> shiftResultFuture = future;
            case AWAIT_ENCHANT -> enchantActionFuture = future;
            case STORE_AWAIT_DEPOSIT -> storeDepositFuture = future;
            case POST_ENCHANT_INPUT_AWAIT_DEPOSIT, POST_ENCHANT_OVERFLOW_AWAIT_DEPOSIT ->
                postEnchantDepositFuture = future;
            default -> inventoryActionFuture = future;
        }
    }

    private void clearActionFuture() {
        restockWithdrawFuture = RequestFuture.rejected;
        inventoryActionFuture = RequestFuture.rejected;
        enchantActionFuture = RequestFuture.rejected;
        shiftResultFuture = RequestFuture.rejected;
        storeDepositFuture = RequestFuture.rejected;
        postEnchantDepositFuture = RequestFuture.rejected;
    }

    /**
     * Guarantees an anvil exists at the remembered position before every anvil step. A broken anvil leaves air where
     * it stood, so the station is restored from the BOT inventory or from the bound anvil supply container.
     */
    private void checkAnvil() {
        if (anvilPosition == null) anvilPosition = firstAnvil();
        if (anvilPosition == null) {
            // No position at all: nothing can be placed and no chest holds the answer. The pick-up branch is entered
            // again on the next tick - no retry counter, no back-off; the status line names the missing station.
            setWorkflowStatus("No anvil station: no anvil is configured and none was found nearby");
            return;
        }
        if (isAnvilBlock(anvilPosition)) {
            anvilRecoveryAttempts = 0;
            if (stationRecoveryOnly) {
                // The station was rebuilt from the idle branch: no item is in flight, so the next step is the pickup
                // chest, not an anvil window for an item that was never fetched.
                stationRecoveryOnly = false;
                setWorkflowStatus("Anvil restored at " + anvilPosition + "; continuing with the next enchantItem");
                state = State.ENTRYPOINT;
                return;
            }
            openingPosition = anvilPosition;
            // The plan already knows what the next merge costs, so the level is farmed before the anvil is opened
            // instead of opening it, seeing too little, closing it again and only then farming. The anvil's own cost
            // line stays authoritative as a verification (see verifyAnvilPreview). The budget carries the gap this
            // server was seen charging above the plan, so the verification does not turn into a round trip.
            //
            // Budgeted over everything this piece still has to merge, not just the next merge: one farm trip that
            // covers the whole piece replaces the "merge, run dry, close the window, fight, reopen, merge again"
            // loop that a per-merge budget produced (a merge that costs 3 leaves 11 of 14 missing for the next one).
            // refillBudget is the same number the in-window gates use, so a piece that starts short is topped up once
            // no matter which gate noticed it.
            int budget = refillBudget(0);
            if (budget > 0) waitingCost = budget;
            if (budget > 0 && !ensureXpForCost(budget)) {
                state = State.WAIT_XP;
                return;
            }
            state = State.ENCHANT_INTERACT_WITH_ANVIL;
            return;
        }
        if (countStacks(ItemRegistry.ANVIL) > 0) {
            state = State.CRAFT_ANVIL_BLOCKS;
            return;
        }
        var supply = containerPosition("anvil_supply");
        if (supply.isEmpty()) {
            // Keep the enchantItem and stay on the station: no retry and no back-off, the same check runs on the next
            // tick so a supply chest bound in the meantime is used.
            setWorkflowStatus("Anvil is gone at " + anvilPosition + " and no anvil supply chest is bound");
            return;
        }
        openingPosition = supply.get();
        setWorkflowStatus("Anvil is gone at " + anvilPosition + "; taking one from the supply chest at " + openingPosition);
        trace("anvil missing at {}; withdrawing a replacement from {}", anvilPosition, openingPosition);
        state = State.RESTOCK_ANVIL_GO_TO_CHEST;
    }

    /**
     * Starts rebuilding a vanished anvil from the idle branch. The station list drops an anvil whose block is gone while
     * the configured position still remembers where it stood, so the block can be placed again from the bag or
     * withdrawn from the anvil supply chest.
     *
     * @return true when the recovery took over the workflow
     */
    private boolean recoverMissingAnvil() {
        anvilPosition = firstAnvil();
        if (anvilPosition == null) return false;
        // An unloaded chunk is "unknown", not "gone" (the same rule as stationMissing): the idle branch walks there
        // first, so a station that is merely out of view is never rebuilt.
        if (!World.isChunkLoadedBlockPos(anvilPosition.x(), anvilPosition.z())) return false;
        if (isAnvilBlock(anvilPosition)) return false;
        stationRecoveryOnly = true;
        setWorkflowStatus("Anvil is gone at " + anvilPosition + "; restoring the station");
        trace("anvil station missing at {}; restoring it before the next transaction", anvilPosition);
        if (openContainerId() != 0) closeContainer(State.CHECK_ANVIL); else state = State.CHECK_ANVIL;
        return true;
    }

    /** Where a replacement anvil would come from, for the status line of a stockout. */
    private String anvilSupplyLabel() {
        var supply = containerPosition("anvil_supply");
        return supply.map(Object::toString).orElse("not bound");
    }

    private void handleSupplyWindow() {
        if (!isStorageWindow()) { fail("Anvil supply position did not open a storage container"); return; }
        state = State.RESTOCK_ANVIL_TAKE;
    }

    private void takeSupplyAnvils() {
        if (countStacks(ItemRegistry.ANVIL) >= PLUGIN_CONFIG.anvilRestockStacks) { state = State.CLOSE_SUPPLY; return; }
        int slot = findAnvilInTop();
        if (slot < 0) {
            // Empty supply container: close the window and walk back up to the open branch. No retry counter and no
            // back-off - the same chest is entered again on the next tick.
            reenter(State.RESTOCK_ANVIL_GO_TO_CHEST,
                "Anvil supply at " + openingPosition + " holds no anvil");
            return;
        }
        submit(new ShiftClick(openContainerId(), slot, ShiftClickItemAction.LEFT_CLICK), State.RESTOCK_ANVIL_AWAIT_WITHDRAW);
    }

    private void verifySupplyTransfer() {
        if (countStacks(ItemRegistry.ANVIL) >= PLUGIN_CONFIG.anvilRestockStacks) { state = State.CLOSE_SUPPLY; return; }
        if (waitForActionTimer.tick(interactTimeoutTicks())) {
            // The anvil did not arrive: the window stays open and the click is sent again on the next tick.
            setWorkflowStatus("The anvil in the supply container at " + openingPosition + " was not transferred");
            state = State.RESTOCK_ANVIL_TAKE;
            return;
        }
        state = State.RESTOCK_ANVIL_TAKE;
    }

    private void placeAnvil() {
        if (anvilPosition == null) { state = State.CHECK_ANVIL; return; }
        if (countStacks(ItemRegistry.ANVIL) <= 0) {
            // Nothing to place: stay on the station check. No retry and no back-off - the check runs again on the next
            // tick and walks to the supply chest again if that is where the replacement has to come from.
            setWorkflowStatus("No anvil in the bag to place at " + anvilPosition
                + " (anvil supply " + anvilSupplyLabel() + ")");
            state = State.CHECK_ANVIL;
            return;
        }
        // The placement is verified by polling the block, not by a window opening, so the request keeps its own
        // field and is only used to shorten the wait when it fails outright.
        clearAllPathFutures();
        anvilBlockCraftFuture = BARITONE.placeBlock(anvilPosition.x(), anvilPosition.y(), anvilPosition.z(), ItemRegistry.ANVIL);
        waitForInteractTimer.reset();
        anvilPlacementAt = System.currentTimeMillis();
        anvilClickAt = 0;
        setWorkflowStatus("Placing an anvil at " + anvilPosition);
        state = State.AWAIT_CRAFT_ANVIL_BLOCKS;
    }

    /**
     * Waits for a placement that is already in flight. The block itself is the ground truth; the request future only
     * says whether the click has been dispatched yet.
     *
     * <p>Two clocks, because two different things can be slow. While the click is not out yet the bot may still be
     * walking or turning towards the station, and that time must not eat the confirmation budget - otherwise a long
     * approach makes the module call a failure and re-place a block that was about to land. Once the click is out the
     * block only appears after the server's block update arrives, which lags behind a burst of entity packets.
     */
    private void waitForPlacement() {
        if (isAnvilBlock(anvilPosition)) {
            anvilRecoveryAttempts = 0;
            anvilClickAt = 0;
            setWorkflowStatus("Anvil placed at " + anvilPosition);
            state = State.CHECK_ANVIL;
            return;
        }
        long now = System.currentTimeMillis();
        if (!anvilBlockCraftFuture.isCompleted()) {
            if (now - anvilPlacementAt >= ANVIL_PLACEMENT_DISPATCH_MS) anvilPlacementFailed();
            return;
        }
        if (anvilClickAt == 0) anvilClickAt = now;
        // A rejected future is no proof that the block is missing: the click can have gone out just before the process
        // lost control, so both outcomes get the same short confirm window and only the block read decides.
        if (System.currentTimeMillis() - anvilClickAt >= ANVIL_PLACEMENT_CONFIRM_MS) anvilPlacementFailed();
    }

    /**
     * A placement that did not land. Re-issue it in place while an anvil remains in the bag: a failed placement is a
     * station problem, not a failed enchant, so it must never park the transaction and send the piece back through
     * NEXT_ENCHANT (where the next kill window interrupts the very placement being retried). Once the spare anvils run
     * out, fall back to the idle branch's delayed retry, which walks to the supply chest if one is bound.
     */
    private void anvilPlacementFailed() {
        anvilBlockCraftFuture = RequestFuture.rejected;
        anvilClickAt = 0;
        if (countStacks(ItemRegistry.ANVIL) > 0 && anvilRecoveryAttempts < MAX_ANVIL_RECOVERY) {
            anvilRecoveryAttempts++;
            trace("anvil did not appear after placement; re-placing from the bag ({}/{})",
                anvilRecoveryAttempts, MAX_ANVIL_RECOVERY);
            setWorkflowStatus("Anvil at " + anvilPosition + " did not settle; re-placing ("
                + anvilRecoveryAttempts + "/" + MAX_ANVIL_RECOVERY + ")");
            state = State.CRAFT_ANVIL_BLOCKS;
            return;
        }
        stationRecoveryOnly = false;
        // A fresh budget on the next visit: a placement that keeps failing must stay retryable from the idle branch,
        // otherwise the exhausted counter would pin the workflow in AWAIT_RESTOCK forever.
        anvilRecoveryAttempts = 0;
        setWorkflowStatus("Could not place an anvil at " + anvilPosition + "; trying again after the restart delay");
        awaitRestockUntil(System.currentTimeMillis() + Math.max(500L, PLUGIN_CONFIG.restartDelay * 50L));
        state = State.AWAIT_RESTOCK;
    }

    /**
     * Frees a jammed bag by returning surplus RAW input pieces to the input chest they came from. Unlike a finished
     * product (which goes to the output chest via {@link #flushFinishedProduct}), a raw piece has no work invested,
     * so sending it back is safe and lets the next book fetch proceed. Only the excess beyond one batch is touched;
     * half-done and finished pieces are never taken by this route.
     *
     * @return true when a return trip was started and the caller must stop its own step
     */
    private boolean flushExcessInput(String reason) {
        if (target == null || returningExcessInput || flushing) return false;
        if (containerPosition(target, "input").isEmpty()) return false;
        List<Integer> raw = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != configuredItemId()) continue;
            var job = runnableJobFor(stack);
            if (job.isEmpty() || job.get() != target) continue;
            if (catalog().hasCurse(stack)) continue;
            if (catalog().enchantments(stack).isEmpty()) raw.add(slot);
        }
        // Keep one batch of raw pieces; only the surplus is safe to send back. With a healthy bag nothing is surplus
        // and this path is a no-op - the over-filled bag is the only thing that reaches it.
        if (raw.size() <= Math.max(1, PLUGIN_CONFIG.batchSize)) return false;
        int slot = raw.getFirst();
        ItemStack stack = playerItem(slot);
        playerSlot = slot;
        sourceSlot = -1;
        itemSnapshot = stack.clone();
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
        returningExcessInput = true;
        batchUnload = false;
        depositSlots = List.of();
        depositRetries = 0;
        waitForRestockTimer.reset();
        beginTransaction();
        trace("bag low on space ({}); returning {} surplus raw {} to the input chest to free slots",
            reason, raw.size(), describeItem(stack));
        setWorkflowStatus("Bag is over-filled with raw input; returning " + raw.size()
            + " surplus " + describeItem(stack) + " to the input chest");
        state = State.POST_ENCHANT;
        return true;
    }

    private BlockPos firstAnvil() {
        BlockPos configured = PLUGIN_CONFIG.anvil;
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

    /**
     * Pieces of the current job still waiting to be enchanted in the bag. They count towards the batch so the pickup
     * withdraws only the difference: a bag already holding two half-done swords tops up with two, not four more.
     * Finished products, cooling-down pieces and cursed pieces do not count.
     */
    private int countPendingPieces() {
        if (target == null) return 0;
        int count = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != configuredItemId()) continue;
            var job = runnableJobFor(stack);
            if (job.isEmpty() || job.get() != target) continue;
            if (isCoolingDown(stack) || catalog().hasCurse(stack)) continue;
            // A piece whose enchantments are complete but whose name is not is still pending: it needs the rename
            // pass before it may leave the bag, and counting it as done is what unloaded nine unnamed swords. A piece
            // whose rename the anvil refused past the limit is given up on instead - it must not hold the batch.
            if (isDeliverable(target, stack)) continue;
            if (renameGivenUp && itemSnapshot != null && sameStackIdentity(itemSnapshot, stack)) continue;
            count++;
        }
        return count;
    }

    /**
     * True when the piece in hand already carries its job's whole target set, i.e. its last merge step is done. In row
     * order that is the end of the piece; in step order an intermediate step leaves the piece unfinished in the bag.
     * The name counts too: a piece that still has to be renamed has not finished its work.
     */
    private boolean pieceFinished() {
        return itemSnapshot != null && !targetEnchants.isEmpty() && isDeliverable(target, itemSnapshot);
    }

    /**
     * Slots occupied by this job's batch: raw, half-done AND finished pieces. Restock sizes itself from this count,
     * not from {@link #countPendingPieces()}: a finished piece is still one of the batch's four slots until the whole
     * batch is unloaded together. Counting only pending pieces made every finished piece look like a free slot, so
     * one raw sword was withdrawn per piece enchanted and the bag filled with 26 raw swords before any batch unloaded.
     */
    private int countCarriedBatchPieces() {
        if (target == null) return 0;
        int count = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != configuredItemId()) continue;
            var job = runnableJobFor(stack);
            if (job.isEmpty() || job.get() != target) continue;
            if (isCoolingDown(stack) || catalog().hasCurse(stack)) continue;
            count++;
        }
        return count;
    }

    /**
     * Lifts the transient retry cooldown from every carried piece of {@code job}. Used only when a restock finds the
     * bag already full of this job's pieces: those cooling half-done swords must become workable again or the picker
     * would neither resume them (they are cooling) nor fetch new ones (the batch quota is already met), dead-locking
     * the bag. Enchanting can then resume from the most backward piece via {@link #findCarriedEquipment()}.
     */
    private int clearCooldownForCarriedJob(AutoEnchantConfig.Enchant job) {
        if (job == null) return 0;
        int cleared = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || stack.getId() != configuredItemId()) continue;
            var carried = runnableJobFor(stack);
            if (carried.isEmpty() || carried.get() != job) continue;
            String key = identityKey(stack);
            if (skipUntil.remove(key) != null) cleared++;
        }
        return cleared;
    }

    /**
     * Records the batch from the pieces of {@code job} currently in the bag. Call after {@code target} is set, since
     * the batch counter is scoped to that job.
     */
    private void openBatch(AutoEnchantConfig.Enchant job) {
        if (job == null || batchJob == job) return;
        batchJob = job;
        batchPieces = countCarriedBatchPieces();
        // The first piece of a session is measured from here, not from its own completion, and the batch clock starts
        // with the record: one piece per batch is what the per-batch stat reports.
        noteStatsWorkStarted();
        stats.batchStartedAt = System.currentTimeMillis();
        stats.batchPieces = 0;
        // The batch is what can be worked on; the raw item count can be higher when a piece is cooling down after a
        // failed attempt, cursed, or belongs to another job of the same item. Printing both makes a short batch
        // explainable instead of looking like a lost piece.
        int sameItem = countCarriedById(configuredItemId());
        trace("batch recorded: {} piece(s) of {} in the bag ({} carried of this item); no new input until they are "
                + "all enchanted and unloaded", batchPieces, job.displayName(), sameItem);
    }

    /**
     * Adopts finished products that sit in the bag without a batch record (process restart, plugin reload).
     *
     * <p>Batch membership lives in memory only, so after a restart the bag can hold a whole batch of finished pieces
     * with nothing pointing at them. The picker then found no piece to resume, while the batch quota was already met
     * by those very pieces - no withdrawal, no piece to enchant, no batch to unload - and the workflow bounced
     * between the pickup chest and the entry point forever. Recording them as a batch and unloading them in one trip
     * is what breaks that loop; the pieces stay untouched, they are only recognised as deliverables.
     *
     * @return true when an unload trip was started
     */
    private boolean adoptOrphanFinishedBatch(List<AutoEnchantConfig.Enchant> runnable) {
        if (batchJob != null) return false;
        for (AutoEnchantConfig.Enchant job : runnable) {
            Map<String, Integer> wanted = targetEnchantsOf(job);
            if (wanted.isEmpty()) continue;
            int itemId = itemIdOf(job);
            if (itemId < 0) continue;
            int finished = 0;
            for (int slot = 9; slot <= 44; slot++) {
                ItemStack stack = playerItem(slot);
                if (stack == null || stack.getId() != itemId) continue;
                if (runnableJobFor(stack).orElse(null) != job) continue;
                // Only pieces that need nothing - not even a rename - are adopted as an orphan batch. One with the
                // wrong name is left alone: findCarriedEquipment resumes it for the rename pass, which is what the
                // operator put it back in the chest for.
                if (!isDeliverable(job, stack)) continue;
                finished++;
            }
            if (finished == 0) continue;
            target = job;
            targetEnchants = wanted;
            openBatch(job);
            if (beginBatchUnload()) {
                trace("orphan batch adopted: {} finished piece(s) of {} were left in the bag without a batch record; "
                    + "unloading them before fetching new input", finished, job.displayName());
                return true;
            }
        }
        return false;
    }

    /** Releases the batch latch once every piece of it has left the inventory. */
    private void closeBatch() {
        if (batchJob == null) return;
        trace("batch released: {} piece(s) of {} finished and unloaded", batchPieces, batchJob.displayName());
        recordBatchDone();
        batchJob = null;
        batchPieces = 0;
    }

    /**
     * Closes the throughput record of one batch: its wall clock from the record to the last unloaded piece, the pieces
     * it really produced and the rate that follows from the two.
     *
     * <p>{@code batchPieces} is what the bag held when the batch was recorded, which is not the output: pieces can
     * fail, be parked or be dropped. The counter incremented on every completion is the honest number.
     */
    private void recordBatchDone() {
        if (stats.batchStartedAt == 0 || stats.batchPieces <= 0) {
            stats.batchStartedAt = 0;
            stats.batchPieces = 0;
            return;
        }
        long ms = Math.max(0L, System.currentTimeMillis() - stats.batchStartedAt);
        int pieces = stats.batchPieces;
        stats.batchesDone++;
        stats.batchPiecesTotal += pieces;
        stats.batchTotalMs += ms;
        stats.batchBestMs = stats.batchBestMs == 0 ? ms : Math.min(stats.batchBestMs, ms);
        stats.batchWorstMs = Math.max(stats.batchWorstMs, ms);
        info("stats: batch #{} done: {} piece(s) in {} ({} per piece)", stats.batchesDone, pieces,
            secs(ms), secs(ms / Math.max(1, pieces)));
        stats.batchStartedAt = 0;
        stats.batchPieces = 0;
    }

    /**
     * Safety net for a recorded batch with no unfinished piece left (the last piece failed permanently, was parked,
     * or was dropped): unload every finished product of the batch together instead of leaving the latch stuck.
     *
     * @return true when an unload trip was started
     */
    private boolean beginBatchUnload() {
        if (batchJob == null || openContainerId() != 0) return false;
        Map<String, Integer> wanted = targetEnchantsOf(batchJob);
        if (wanted.isEmpty()) return false;
        List<Integer> finished = new ArrayList<>();
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null || runnableJobFor(stack).orElse(null) != batchJob) continue;
            // Only deliverable pieces (target set + template name) leave the bag: one that still needs its rename
            // stays behind and is handled by the rename pass instead of being unloaded unnamed. A rename the anvil
            // refused past the level limit is the one exception - it goes out unnamed rather than blocking the batch.
            if (!isDeliverable(batchJob, stack)
                && !(renameGivenUp && itemSnapshot != null && sameStackIdentity(itemSnapshot, stack))) continue;
            finished.add(slot);
        }
        if (finished.isEmpty()) return false;
        target = batchJob;
        targetEnchants = wanted;
        playerSlot = finished.getFirst();
        itemSnapshot = playerItem(playerSlot).clone();
        sourceSlot = -1;
        origin = Origin.INVENTORY;
        operations = List.of();
        books = List.of();
        operationIndex = 0;
        bookIndex = 0;
        failureReason = null;
        grindRequired = false;
        renamePass = false;
        backToBooks = false;
        outputStored = false;
        flushing = false;
        returningExcessInput = false;
        batchUnload = true;
        depositSlots = List.of();
        depositRetries = 0;
        beginTransaction();
        trace("batch unload: depositing {} finished piece(s) of {} together", finished.size(), batchJob.displayName());
        state = State.POST_ENCHANT;
        return true;
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
     * A click rejected by the inventory queue is a busy queue, not a failed enchant. Calling {@link #fail(String)}
     * here used to recurse: fail -> keepAndRetry -> closeContainer -> submit -> fail ... until the stack overflowed,
     * with every frame trying to close the same window. Park the click for one retry delay instead.
     */
    private void parkBusyInventoryQueue() {
        clearActionFuture();
        trace("inventory queue is busy; parking the click instead of recursing");
        retryReason = "Inventory action queue is busy";
        awaitRestockUntil(System.currentTimeMillis() + retryDelayMs());
        // Deliberately no closeContainer here: that submits another click and would re-enter this same method.
        // BACKOFF -> READY_NEXT_ENCHANT -> beginInput closes any still-open window before the next real click.
        state = State.BACKOFF;
    }

    /**
     * Priority used for every chest/anvil click: the module's own, which sits above KillAura and Baritone (see
     * {@link AutoEnchantModule#getPriority()}). Computed per submit, so a live {@code modulePriority} change applies at
     * once and a click can never be replaced by a combat action in the same tick.
     */
    private int actionPriority() {
        return getPriority();
    }

    private void submit(List<com.zenith.feature.inventory.actions.InventoryAction> actions, State after) {
        RequestFuture future = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this)
            .priority(actionPriority())
            .actionDelayTicks(PLUGIN_CONFIG.actionDelay)
            .actions(actions)
            .build());
        afterInventoryState = after;
        attachActionFuture(after, future);
        waitForActionTimer.reset();
        if (future == RequestFuture.rejected) parkBusyInventoryQueue();
    }

    /**
     * Submits a single burst of clicks at one click per tick, the way the trader's deposit queue does.
     *
     * <p>The configured action delay exists so a human does not see machine-gun clicking; inside one window a run of
     * shift clicks is exactly what a player does, and paying 5 ticks per stack turns a full bag into seconds of
     * standing still. The budget grows with the queue, so a long trip is not mistaken for a failed one.
     */
    private void submitBurst(List<com.zenith.feature.inventory.actions.InventoryAction> actions, State after) {
        RequestFuture future = INVENTORY.submit(InventoryActionRequest.builder()
            .owner(this).priority(actionPriority()).actionDelayTicks(1).actions(actions).build());
        afterInventoryState = after;
        attachActionFuture(after, future);
        waitForActionTimer.reset();
        if (future == RequestFuture.rejected) parkBusyInventoryQueue();
    }

    /**
     * One piece of the batch is fully enchanted. The finished product stays in the bag while sibling pieces of the
     * same job still need work, so the whole batch can be unloaded in a single trip instead of one chest visit per
     * piece. Only the last piece of the batch opens the unload container.
     *
     * <p>In step order this point is reached after every step, not only after the last merge of a piece: the piece
     * stays in the bag with the progress it just paid for, and only a piece that really reached its target set is
     * logged as complete.
     */
    private void batchNextPiece() {
        if (openContainerId() != 0) { closeContainer(State.BATCH_NEXT_PIECE); return; }
        int pending = countPendingPieces();
        if (pending > 0) {
            // Not the last piece: keep the product in the bag, log this piece as done if it is, and let beginInput
            // adopt the next carried piece. The job cursor must not rotate here - the whole batch belongs to this job.
            boolean finished = !stepOrder() || pieceFinished();
            if (finished) logTransaction(true);
            retryReason = null;
            clearTransaction();
            itemAttempts.clear();
            transientRetries = 0;
            flushTrips = 0;
            if (finished) {
                trace("batch: finished piece kept in bag, {} piece(s) of this job still pending", pending);
                setWorkflowStatus("Batch: piece done, continuing with the next carried piece");
            } else {
                trace("batch step done: progress kept in the bag, {} piece(s) of this job still pending", pending);
                setWorkflowStatus("Batch: step done, continuing with the next carried piece");
            }
            state = State.NEXT_ENCHANT;
            return;
        }
        // Last piece of the batch: unload every finished product of this job together.
        batchUnload = true;
        trace("batch complete: unloading all finished products of {} together",
            target == null ? "this job" : target.displayName());
        state = State.POST_ENCHANT;
    }

    private void completeTransaction() {
        // Attempt counters survive a failed round so a repeatedly failing enchantItem eventually gets parked. A round
        // only counts as a success once the item really left the inventory: a parked retry is not a success, otherwise
        // the counter would be zeroed on every loop and the item would retry forever.
        boolean maintenance = flushing || returningExcessInput;
        boolean succeeded = outputStored && failureReason == null && !maintenance;
        // Log only real endings: a stored enchantItem or a failed one. A transient step failure keeps the
        // enchantItem in the inventory and is retried silently instead of spamming Discord. A flush or an excess-input
        // return is neither: it only freed a slot, so it gets a trace line instead of an "Enchant Completed" embed.
        if (retryReason != null) trace("transaction parked for a retry: {} (retries={})", retryReason, transientRetries);
        if (flushing) trace("flush finished: bag space freed, back to the enchant queue");
        if (returningExcessInput) trace("excess raw input returned: bag space freed, back to the enchant queue");
        trace("transaction timings: reset {}s, anvil {}s, carried inputs {} -> {}, finished outputs {}",
            secondsSince(unchantStartTime), secondsSince(enchantStartTime),
            preEnchantInputCount, target == null ? 0 : countCarriedById(configuredItemId()), preEnchantOutputCount);
        if ((outputStored || failureReason != null) && !maintenance) logTransaction(succeeded);
        // Rotate once when a whole batch has been unloaded (the last piece came from the bag, so the old
        // itemFromRestockBatch rule would wrongly skip it), otherwise keep the previous single-piece behaviour.
        boolean wasBatchUnload = batchUnload;
        // The recorded batch keeps its job until the closing unload; only that last trip rotates the cursor.
        boolean rotateJob = succeeded && (wasBatchUnload || (batchJob == null && !itemFromRestockBatch));
        retryReason = null;
        clearTransaction();
        if (outputStored && wasBatchUnload) closeBatch();
        if (succeeded) {
            itemAttempts.clear();
            transientRetries = 0;
            flushTrips = 0;
            if (rotateJob) {
                // Round-robin once per batch, not once per piece: four swords withdrawn together are all enchanted
                // before the rotation moves to the pickaxe. The extra pieces are adopted from the bag with
                // itemFromRestockBatch set, so only the chest-withdrawn piece advances the cursor.
                enchantIterator.next();
            }
        }
        long now = System.currentTimeMillis();
        skipUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        state = State.NEXT_ENCHANT;
        setWorkflowStatus("Ready for next input enchantItem");
    }

    /**
     * One completion entry per enchantItem, modeled on the trader's finished-trade log: the Discord embed carries the
     * job, the finished stack, the target set, the duration and what the transaction consumed, and the console gets the
     * same line as plain text.
     */
    private void logTransaction(boolean succeeded) {
        if (target == null || itemSnapshot == null) return;
        // Same trigger as the completion log: the piece really left the enchant queue, so throughput is measured here.
        // Flushes and excess-input returns never reach this call, they only moved an item and produced nothing.
        // A batch unload is counted at the deposit instead (see verifyUnloaded): it carries the whole batch, not the
        // one piece this transaction happens to hold.
        if (succeeded && !batchUnload) recordPieceDone();
        String duration = MathHelper.formatDuration(Duration.ofMillis(
            Math.max(0L, System.currentTimeMillis() - transactionStartedAt)));
        int levels = Math.max(0, preEnchantExpCount - carriedLevel());
        if (AutoEnchantPlugin.PLUGIN_CONFIG.logTradeStatusToDiscord) {
            var result = Embed.builder()
                .title(succeeded ? "Enchant Completed" : "Enchant Failed")
                .addField("Enchant ID", target.displayName())
                .addField("Item", describeItem(itemSnapshot))
                .addField("Target", targetEnchantmentsText())
                .addField("Duration", duration)
                .addField("Levels", levels + " spent");
            if (!succeeded && failureReason != null) result.addField("Reason", failureReason);
            discordNotification(result);
        } else if (succeeded) {
            info("Enchant completed: {} [{}] in {} ({} levels spent)", describeItem(itemSnapshot),
                targetEnchantmentsText(), duration, levels);
        } else {
            info("Enchant failed: {} [{}] in {} ({} levels spent): {}", describeItem(itemSnapshot),
                targetEnchantmentsText(), duration, levels, String.valueOf(failureReason));
        }
    }

    // ------------------------------------------------------------------ throughput statistics

    /**
     * Starts the clock for the first piece of a session.
     *
     * <p>Called when a batch is recorded, so the first piece is measured from the moment work started instead of
     * counting as zero seconds. Later batches leave the clock alone: pieces follow each other without a pause.
     */
    private void noteStatsWorkStarted() {
        if (stats.lastAt != 0) return;
        stats.lastAt = System.currentTimeMillis();
        stats.boxStartedAt = stats.lastAt;
    }

    /**
     * Counts one finished piece: the interval since the previous completion plus the rolling 27-piece shulker box.
     *
     * <p>The interval is the honest per-piece number for this workflow. A batch is enchanted step by step, so all ten
     * pieces share the same anvil windows and no single piece owns a wall-clock span of its own; the completion rate
     * is what a full box is actually measured against.
     */
    private void recordPieceDone() {
        long now = System.currentTimeMillis();
        if (stats.lastAt == 0) noteStatsWorkStarted();
        countPiece(Math.max(0L, now - stats.lastAt));
        stats.lastAt = now;
    }

    /**
     * Counts {@code n} pieces that finished together, splitting the interval between them.
     *
     * <p>Used by the batch unload of a step-wise run: the pieces were enchanted interleaved, so they reach the chest
     * at the same moment and none of them owns an interval of its own.
     */
    private void recordPiecesDone(int n) {
        if (n <= 0) return;
        long now = System.currentTimeMillis();
        if (stats.lastAt == 0) noteStatsWorkStarted();
        long pieceMs = Math.max(0L, now - stats.lastAt) / n;
        for (int i = 0; i < n; i++) countPiece(pieceMs);
        stats.lastAt = now;
    }

    /** One completion: the rolling per-piece number and the 27-piece shulker box it feeds. */
    private void countPiece(long pieceMs) {
        stats.pieces++;
        stats.boxPieces++;
        // A batch unloads once at its end, so the pieces counted here are exactly the batch's real output.
        if (batchJob != null) stats.batchPieces++;
        stats.lastMs = pieceMs;
        // The very first interval of a session covers fetching and planning, not one piece, so it is left out of the
        // averages while still counting towards the box.
        if (stats.pieces > 1) {
            stats.totalMs += pieceMs;
            stats.minMs = stats.minMs == 0 ? pieceMs : Math.min(stats.minMs, pieceMs);
            stats.maxMs = Math.max(stats.maxMs, pieceMs);
        }
        int measured = Math.max(0, stats.pieces - 1);
        trace("stats: piece #{} took {} (avg {}); box #{} {}/{} after {}",
            stats.pieces, secs(pieceMs), measured > 0 ? secs(stats.totalMs / measured) : "n/a",
            stats.boxesDone + 1, stats.boxPieces, SHULKER_BOX_PIECES, secs(System.currentTimeMillis() - stats.boxStartedAt));
        if (stats.boxPieces < SHULKER_BOX_PIECES) return;
        long boxMs = Math.max(0L, System.currentTimeMillis() - stats.boxStartedAt);
        stats.boxesDone++;
        stats.boxTotalMs += boxMs;
        stats.boxBestMs = stats.boxBestMs == 0 ? boxMs : Math.min(stats.boxBestMs, boxMs);
        stats.boxWorstMs = Math.max(stats.boxWorstMs, boxMs);
        info("stats: shulker box #{} done: {} piece(s) in {} ({} per piece)", stats.boxesDone,
            SHULKER_BOX_PIECES, secs(boxMs), secs(boxMs / SHULKER_BOX_PIECES));
        stats.boxPieces = 0;
        stats.boxStartedAt = System.currentTimeMillis();
    }

    /** Drops every counter, so the next piece starts a fresh measurement. */
    public void resetStats() {
        stats.lastAt = 0;
        stats.lastMs = 0;
        stats.totalMs = 0;
        stats.minMs = 0;
        stats.maxMs = 0;
        stats.pieces = 0;
        stats.boxStartedAt = 0;
        stats.boxPieces = 0;
        stats.boxesDone = 0;
        stats.boxTotalMs = 0;
        stats.boxBestMs = 0;
        stats.boxWorstMs = 0;
        stats.batchStartedAt = 0;
        stats.batchPieces = 0;
        stats.batchesDone = 0;
        stats.batchPiecesTotal = 0;
        stats.batchTotalMs = 0;
        stats.batchBestMs = 0;
        stats.batchWorstMs = 0;
    }

    /** Counter view for the {@code autoEnchant stats} command, in display order. */
    public Map<String, String> statsFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        int measured = Math.max(0, stats.pieces - 1);
        long avg = measured > 0 ? stats.totalMs / measured : 0;
        fields.put("Pieces done", Integer.toString(stats.pieces));
        fields.put("Per piece (avg)", avg > 0 ? secs(avg) : "n/a");
        fields.put("Per piece (fast/slow)",
            measured > 0 ? secs(stats.minMs) + " / " + secs(stats.maxMs) : "n/a");
        fields.put("Last piece", stats.pieces > 0 ? secs(stats.lastMs) : "n/a");
        fields.put("Throughput", avg > 0
            ? Math.round(3_600_000.0 / avg) + " pieces/hour (" + String.format(Locale.ROOT, "%.1f", 60_000.0 / avg)
                + " pieces/minute)"
            : "n/a");
        long boxElapsed = stats.boxPieces > 0 ? Math.max(0L, System.currentTimeMillis() - stats.boxStartedAt) : 0L;
        fields.put("Current box", stats.boxPieces + "/" + SHULKER_BOX_PIECES
            + (stats.boxPieces > 0 ? " pieces, " + secs(boxElapsed) + " so far" : " pieces"));
        fields.put("Boxes done", Integer.toString(stats.boxesDone));
        if (stats.boxesDone > 0) {
            long boxAvg = stats.boxTotalMs / stats.boxesDone;
            fields.put("Shulker box (avg)", secs(boxAvg) + " for " + SHULKER_BOX_PIECES + " pieces ("
                + secs(boxAvg / SHULKER_BOX_PIECES) + " per piece)");
            fields.put("Box (fast/slow)", secs(stats.boxBestMs) + " / " + secs(stats.boxWorstMs));
            fields.put("Boxes per hour", String.format(Locale.ROOT, "%.1f", 3_600_000.0 / boxAvg));
        }
        fields.put("Batches done", Integer.toString(stats.batchesDone));
        if (stats.batchesDone > 0) {
            long batchAvg = stats.batchTotalMs / stats.batchesDone;
            long batchPiecesAvg = stats.batchPiecesTotal / stats.batchesDone;
            fields.put("Batch (avg)", secs(batchAvg) + " for " + batchPiecesAvg + " piece(s) ("
                + secs(batchAvg / Math.max(1, batchPiecesAvg)) + " per piece)");
            fields.put("Batch (fast/slow)", secs(stats.batchBestMs) + " / " + secs(stats.batchWorstMs));
        }
        if (stats.batchPieces > 0) {
            fields.put("Batch in progress", stats.batchPieces + " piece(s) done, "
                + secs(Math.max(0L, System.currentTimeMillis() - stats.batchStartedAt)) + " so far");
        }
        return fields;
    }

    /** Millisecond duration as seconds with one decimal, e.g. {@code 12.4s}. */
    private static String secs(long ms) {
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** One-line throughput summary for the status panel. */
    public String statsSummary() {
        int measured = Math.max(0, stats.pieces - 1);
        if (measured <= 0) return "no finished piece yet";
        long avg = stats.totalMs / measured;
        long boxAvg = stats.boxesDone > 0 ? stats.boxTotalMs / stats.boxesDone : 0L;
        long batchAvg = stats.batchesDone > 0 ? stats.batchTotalMs / stats.batchesDone : 0L;
        return secs(avg) + "/piece (" + Math.round(3_600_000.0 / avg) + "/hour)"
            + (batchAvg > 0 ? ", batch " + secs(batchAvg) + " x"
                + (stats.batchPiecesTotal / stats.batchesDone) : "")
            + (boxAvg > 0 ? ", 27-piece box " + secs(boxAvg) : "")
            + (stats.boxPieces > 0 ? ", box in progress " + stats.boxPieces + "/" + SHULKER_BOX_PIECES : "");
    }

    /**
     * Claims the piece: the trader's per-trade start snapshot. The carried counts and the level are what the completion
     * log compares against, and the two start times split the reset (grindstone) phase from the anvil phase.
     */
    private void beginTransaction() {
        transactionStartedAt = System.currentTimeMillis();
        preEnchantInputCount = countCarriedById(configuredItemId());
        preEnchantOutputCount = countFinishedProducts(target);
        preEnchantExpCount = carriedLevel();
        unchantStartTime = System.nanoTime();
        enchantStartTime = unchantStartTime;
    }

    private int carriedLevel() {
        var player = CACHE.getPlayerCache().getThePlayer();
        return player == null ? 0 : player.getLevel();
    }

    /** Finished products of one job currently carried; the pre-enchant output snapshot of a flush trip. */
    private int countFinishedProducts(AutoEnchantConfig.Enchant job) {
        if (job == null) return 0;
        Map<String, Integer> wanted = targetEnchantsOf(job);
        if (wanted.isEmpty()) return 0;
        int count = 0;
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (isDeliverable(job, stack)) count++;
        }
        return count;
    }

    private static long secondsSince(long startNanos) {
        return Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000_000L);
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
    public void abort() {
        retryReason = null;
        clearTransaction();
        setWorkflowStatus("Ready for next input enchantItem");
    }

    /** Clears every per-transaction field; attempt counters and skip cooldowns survive on purpose. */
    private void clearTransaction() {
        // A kill window owns the aura; dropping the transaction in the middle of one has to switch it off again,
        // otherwise a dead or queued client leaves the BOT swinging at nothing.
        if (state == State.KILL_WINDOW) switchKillAura(false);
        state = State.ENTRYPOINT; target = null; itemSnapshot = null; operations = List.of(); books = List.of();
        operationIndex = bookIndex = 0; activeContainerId = 0; sourceSlot = playerSlot = -1;
        fetchQueue.clear(); fetchIndex = 0; fetchBefore = 0; fetchEmpty.clear();
        failureReason = null; grindRequired = false;
        clearAllPathFutures(); clearActionFuture();
        anvilBlockCraftFuture = RequestFuture.rejected;
        waitForInteractTimer.reset(); waitForActionTimer.reset(); waitForRestockTimer.reset();
        anvilPosition = null; anvilRecoveryAttempts = 0; targetEnchants = Map.of();
        anvilPlacementAt = 0; anvilClickAt = 0;
        stationRecoveryOnly = false;
        activePreset = null;
        renamePass = false;
        renameTries = 0;
        renameGivenUp = false;
        namedBeforeRename = 0;
        renameSentThisMerge = false;
        renameMergeRefusals = 0;
        renameOnMergeDisabled = false;
        anvilNameFieldDirty = false;
        backToBooks = false;
        stepLogged = false;
        origin = Origin.NONE;
        openingPosition = null;
        openState = State.ENTRYPOINT;
        openClickMisses = 0;
        openMissPosition = null;
        afterInventoryState = State.ENTRYPOINT; afterCloseState = State.ENTRYPOINT;
        waitingCost = 0;
        transactionStartedAt = 0;
        outputStored = false;
        flushing = false;
        returningExcessInput = false;
        batchUnload = false;
        depositSlots = List.of();
        depositRetries = 0;
        closeResent = false;
        itemFromRestockBatch = false;
        // batchJob / batchPieces are deliberately NOT cleared here: one batch spans several transactions and is
        // released only by closeBatch() after its finished products have left the inventory.
        // The transient-retry counter is NOT cleared here: it has to survive a parked round, otherwise a step that
        // fails on every attempt would spin forever instead of parking the item (completeTransaction() clears it on success).
        takeAttempts = 0;
        restockItemId = -1;
        restockBefore = 0;
        restockWanted = 0;
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
        return catalog().enchantments(a).equals(catalog().enchantments(b))
            && catalog().priorWorkPenalty(a) == catalog().priorWorkPenalty(b)
            && catalog().customName(a).equals(catalog().customName(b));
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
     * state machine re-fetches the same book after every interrupted merge and buries the inventory in duplicate books.
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
            warn("Auto-enchant unloaded {} times without freeing enough bag space ({} free); continuing",
                MAX_FLUSH_TRIPS, freeInventorySlots());
            return false;
        }
        ItemStack stack = playerItem(slot);
        var job = runnableJobFor(stack);
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
        flushTrips++;
        depositSlots = List.of();
        depositRetries = 0;
        // A flush is worth starting right away: it must not inherit the back-off of the step that ran out of space.
        waitForRestockTimer.reset();
        beginTransaction();
        trace("bag low on space ({}); unloading the finished slot {} {} to free a slot", reason, slot,
            describeItem(stack));
        setWorkflowStatus("BOT inventory is nearly full; unloading a finished " + describeItem(stack)
            + " first");
        state = State.POST_ENCHANT;
        return true;
    }

    /** Slot of a finished product of an enabled job: the stack already carries exactly its job's target set. */
    private int findFinishedProduct() {
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            var job = runnableJobFor(stack);
            if (job.isEmpty()) continue;
            // "Finished" means deliverable: the target set AND the template name. A piece still waiting for its
            // rename must not be unloaded by the bag-space flush.
            if (!isDeliverable(job.get(), stack)) continue;
            return slot;
        }
        return -1;
    }

    /**
     * True when this piece needs no work at all: it already carries the job's target enchantments AND the name the
     * job asks for.
     *
     * <p>A finished piece with the wrong name is deliberately <em>not</em> deliverable. Those pieces are put back
     * into an input chest by hand (an old batch that lost its name), and treating them as "done" is what produced an
     * endless pickup loop: nothing to resume, no quota left to fetch, and no batch to unload. They are taken in
     * instead, renamed through a rename-only anvil pass (their plan is empty, so no merge is replayed) and unloaded
     * with the batch.
     */
    private boolean isDeliverable(AutoEnchantConfig.Enchant job, ItemStack stack) {
        if (job == null || stack == null) return false;
        int itemId = itemIdOf(job);
        if (itemId < 0 || stack.getId() != itemId) return false;
        if (!PLUGIN_CONFIG.renameOnly) {
            Map<String, Integer> wanted = targetEnchantsOf(job);
            if (wanted.isEmpty()) return false;
            if (!catalog().enchantments(stack).equals(wanted)) return false;
        }
        String wantedName = job.renameName == null ? null : job.renameName.trim();
        // No name configured: the enchanted piece is the deliverable, exactly as before.
        if (wantedName == null) return true;
        String currentName = catalog().customName(stack);
        // An empty template name means "clear it", so any leftover name still needs the anvil.
        return wantedName.isEmpty() ? currentName.isEmpty() : wantedName.equals(currentName);
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
            String actualName = catalog().customName(stack);
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

    /** Registry id of a configured job's item, independent of {@code target}; -1 when the id is unknown. */
    private int itemIdOf(AutoEnchantConfig.Enchant job) {
        if (job == null || job.item == null) return -1;
        var data = ItemRegistry.REGISTRY.get(EnchantPresetCatalog.shortId(job.item));
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
        var price = currentAnvilCost();
        return new OptionalIntCost(price.orElse(-1));
    }
    private int findCurrentEquipment() { return findPlayerItem(itemSnapshot); }

    // ---------------------------------------------------------------------------------------------
    // Step tracing. Every enchanting step logs what was picked up, which enchantments the book carries,
    // what the anvil preview showed and what came out of it, so a wrong transaction can be read from the
    // proxy log alone instead of being guessed from the final "Enchant failed" line.
    // ---------------------------------------------------------------------------------------------

    private void trace(String msg, Object... args) {
        info("[trace] " + msg, args);
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

    /**
     * Level cost the plan expects for merge {@code index}, including the one extra level the anvil charges for a merge
     * that really writes the piece's name (see {@link #renameAppliesTo(int)}). This is the number a merge is budgeted
     * with before the anvil is opened; the window's own cost line only verifies it.
     */
    private int plannedCostOf(int index) {
        if (index < 0 || index >= operations.size()) return 0;
        int cost = operations.get(index).expectedCost();
        if (renameAppliesTo(index)) cost += 1;
        return Math.max(0, cost);
    }

    /** Total levels the whole plan is expected to spend, i.e. the XP budget of one piece. */
    private int plannedLevels() {
        int total = 0;
        for (int i = 0; i < operations.size(); i++) total += plannedCostOf(i);
        return total;
    }

    /**
     * Levels the plan still expects to spend from merge {@code index} to the end of the piece.
     *
     * <p>This is the number the level is farmed to before an anvil is opened: the merges of one piece run back to
     * back - often inside a single anvil window - so farming only the next merge's cost leaves the window short after
     * the first merge and forces it closed again for a second trip (a 14-level plan became 3 + 11 in two fights).
     */
    private int plannedRemainingCostOf(int index) {
        int total = 0;
        for (int i = Math.max(0, index); i < operations.size(); i++) total += plannedCostOf(i);
        return total;
    }

    /**
     * Level to hold before the rest of this piece can be merged, i.e. every merge still ahead plus the gap this server
     * has been seen charging above the plan.
     *
     * <p>Every gate that discovers the level is short farms this number, not the price of the one merge that tripped
     * it. A 14-level piece is a 3-level book merge followed by an 11-level equipment merge: gating on the failing merge
     * alone sent the BOT out for 3 levels, back to the anvil, and out again for 11 - two trips, two window closes and
     * two walks for the same 14 levels. The total is unchanged; only the number of round trips is.
     *
     * @param atLeast a level the caller already knows is needed (the anvil's own cost line), so an under-planned merge
     *                is still covered
     */
    private int refillBudget(int atLeast) {
        int planned = plannedRemainingCostOf(operationIndex);
        if (renamePass && itemSnapshot != null) {
            // A rename-only pass has no merge to budget by: the anvil charges one level plus the piece's whole
            // prior-work penalty (a finished sword pays ~16, not 1).
            planned = Math.max(planned, catalog().priorWorkPenalty(itemSnapshot) + 1);
        }
        if (planned > 0) planned += planCostBias;
        return Math.max(planned, atLeast);
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
        trace("plan budget: about {} levels for this piece (farmed up front, the anvil only verifies{}); levels are "
                + "spent cumulatively, so the column below is the level this piece needs in hand before merge[i]",
            plannedLevels(), planCostBias > 0 ? ", +" + planCostBias + " safety over the plan" : "");
        int cumulative = 0;
        for (int i = 0; i < operations.size(); i++) {
            var op = operations.get(i);
            // The planned cost is the wiki's number for this merge (enchantment value + both prior-work penalties),
            // so a plan that would run into the 39-level limit is visible before any XP is spent. Only the equipment
            // merge that really renames the piece costs one level more: the later merges re-send the name so the
            // server keeps it, but writing a name the piece already has is free.
            int planned = op.expectedCost() + (renameAppliesTo(i) ? 1 : 0);
            cumulative += planned;
            trace("  merge[{}] {} + {} => {} (~{} levels, {} cumulative{})", i, describeNode(op.left()),
                describeNode(op.right()), describeNode(op.result()), planned, cumulative,
                renameAppliesTo(i) ? ", name +1" : "");
        }
        for (int i = 0; i < books.size(); i++) {
            trace("  book[{}] {} {}", i, books.get(i).id(), books.get(i).level());
        }
        // The name is written on the FIRST merge that touches the equipment - a fresh piece pays one level there -
        // and re-sent on every later equipment merge (free) so each rebuilt result keeps its custom name.
        String name = desiredRename();
        if (name != null) {
            int renameAt = firstEquipmentMerge();
            trace("  name field '{}' is written on merge[{}]{} (+1 level there only) and re-sent on every later "
                    + "equipment merge (piece '{}', template '{}')",
                name.isEmpty() ? "(clear)" : name, renameAt,
                renameAt < 0 ? " - no equipment merge, a separate rename pass will run"
                    : " " + describeNode(operations.get(renameAt).result()),
                itemSnapshot == null ? "" : catalog().customName(itemSnapshot), target.renameName);
        }
    }

    /** Dumps every stack the BOT carries; used when an anvil result cannot be located. */
    private void traceInventory(String why) {
        trace("inventory dump ({}):", why);
        for (int slot = 9; slot <= 44; slot++) {
            ItemStack stack = playerItem(slot);
            if (stack == null) continue;
            trace("  slot {} {} enchants={} penalty={}", slot, describeItem(stack),
                describeEnchants(readEnchants(stack, stack.getId() == ItemRegistry.ENCHANTED_BOOK.id())),
                catalog().priorWorkPenalty(stack));
        }
    }

    private String targetPlaceholder() {
        if (activePreset != null) {
            var preset = catalog().get(activePreset);
            if (preset != null) return preset.targetPlaceholder();
        }
        return EnchantPresetCatalog.shortId(target.item);
    }

}

final class EnchantExpressionParser {
    public sealed interface Node permits Leaf, Merge { }
    public record Leaf(String name, int level) implements Node { }
    public record Merge(Node left, Node right, int expectedCost) implements Node { }

    private String source;
    private int cursor;

    public Node parse(String expression) {
        source = expression;
        cursor = 0;
        Node node = parseSequence('\0');
        skipWhitespace();
        if (cursor != source.length()) throw error("Unexpected trailing expression");
        return node;
    }

    private Node parseSequence(char end) {
        Node node = parseTerm();
        while (true) {
            skipWhitespace();
            if (cursor >= source.length() || (end != '\0' && source.charAt(cursor) == end)) break;
            if (source.charAt(cursor) != '+') throw error("Expected '+' or closing bracket");
            cursor++;
            Node right = parseTerm();
            node = new Merge(node, right, -1);
        }
        if (end != '\0') {
            skipWhitespace();
            if (cursor >= source.length() || source.charAt(cursor) != end) throw error("Missing closing bracket " + end);
            cursor++;
        }
        return node;
    }

    private Node parseTerm() {
        skipWhitespace();
        if (cursor >= source.length()) throw error("Expected enchantItem or enchantment token");
        char first = source.charAt(cursor);
        Node node;
        if (first == '(' || first == '[' || first == '{') {
            cursor++;
            char end = first == '(' ? ')' : first == '[' ? ']' : '}';
            node = parseSequence(end);
        } else {
            int start = cursor;
            while (cursor < source.length()) {
                char c = source.charAt(cursor);
                if (Character.isWhitespace(c) || c == '+' || c == ')' || c == ']' || c == '}' || c == '@') break;
                cursor++;
            }
            if (start == cursor) throw error("Expected token");
            String token = source.substring(start, cursor);
            int underscore = token.lastIndexOf('_');
            int level = 1;
            String name = token;
            if (underscore > 0 && underscore < token.length() - 1) {
                String suffix = token.substring(underscore + 1);
                if (suffix.chars().allMatch(Character::isDigit)) {
                    name = token.substring(0, underscore);
                    level = Integer.parseInt(suffix);
                }
            }
            node = new Leaf(name, level);
        }
        skipWhitespace();
        if (cursor < source.length() && source.charAt(cursor) == '@') {
            cursor++;
            skipWhitespace();
            int start = cursor;
            while (cursor < source.length() && Character.isDigit(source.charAt(cursor))) cursor++;
            if (start == cursor) throw error("Expected cost after '@'");
            int cost = Integer.parseInt(source.substring(start, cursor));
            if (!(node instanceof Merge merge)) throw error("Cost annotation must follow a combination");
            node = new Merge(merge.left(), merge.right(), cost);
        }
        return node;
    }

    private void skipWhitespace() {
        while (cursor < source.length() && Character.isWhitespace(source.charAt(cursor))) cursor++;
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + cursor + " in: " + source);
    }
}

final class CustomEnchantPlanner {
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
     * merge, so the state machine can execute the list front to back.
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
     * state machine's exact-set matching can locate it in the inventory.
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

final class EnchantPresetCatalog {
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
        var enchants = component.getEnchantments();
        enchants.forEach((id, level) -> {
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

    /** Registry short id; the one implementation lives in {@link AutoEnchantConfig#shortId(String)}. */
    public static String shortId(String id) {
        return AutoEnchantConfig.shortId(id);
    }
}

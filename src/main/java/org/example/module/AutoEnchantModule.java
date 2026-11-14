package org.example.module;

import com.github.rfresh2.EventConsumer;
import com.zenith.cache.data.entity.Entity;
import com.zenith.event.client.ClientBotTick;
import com.zenith.event.client.ClientDeathEvent;
import com.zenith.event.client.ClientDisconnectEvent;
import com.zenith.feature.pathfinder.Baritone;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.mc.block.BlockRegistry;
import com.zenith.feature.player.World;
import com.zenith.module.api.Module;
import com.zenith.module.impl.KillAura;
import com.zenith.network.codec.PacketHandlerCodec;
import com.zenith.network.codec.PacketHandlerStateCodec;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import org.example.AutoEnchantConfig;
import org.example.EnchantPresetCatalog;
import org.example.AutoEnchantPlugin;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetDataPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.rfresh2.EventConsumer.of;
import static com.zenith.Globals.*;
import static org.example.AutoEnchantPlugin.PLUGIN_CONFIG;

public class AutoEnchantModule extends Module {
    public record Marker(String role, BlockPos framePos, BlockPos containerPos, ItemStack displayedItem) { }

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
    private static final int WINDOW_SETTLE_DELAY_MS = 100;
    private static final int RESERVE_LEVELS = 2;
    private static final float MIN_HEALTH = 12;
    private static final int KILLAURA_MAX_SECONDS = 900;
    private static final long NO_XP_TIMEOUT_MS = 60_000L;

    private final EnchantPresetCatalog catalog = new EnchantPresetCatalog();
    {
        catalog.load();
    }
    private final EnchantWorkflowWorker workflowWorker = new EnchantWorkflowWorker(this);
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
    /** Enabled, complete jobs; refreshed by {@link #revalidateConfig()} instead of on every worker tick. */
    private volatile List<AutoEnchantConfig.Enchant> runnableJobs = List.of();
    private volatile List<String> markerProblems = List.of();
    private volatile List<String> markerDetails = List.of();
    private volatile List<String> jobDetails = List.of();
    private volatile List<String> stationDetails = List.of();
    private volatile String detailSignature = "";
    /** Last reported marker-problem text; the 5s scan must not repeat an unchanged warning forever. */
    private volatile String problemSignature = "";
    /**
     * True while this module has paused KillAura. The pause is module-instance only: CONFIG.client.extra.killAura
     * keeps the user's setting, so combat resumes by itself as soon as the anvil no longer needs the BOT still.
     */
    private volatile boolean killAuraSuspended;
    /** Consecutive ticks the worker has been idle; keeps combat off across the gap between two items. */
    private int combatIdleTicks;
    private volatile boolean xpFarmingActive;
    private volatile boolean xpInterrupted;
    /** Level the current anvil operation needs, taken from the cost the server shows in the anvil window. */
    private volatile int xpGoalLevel = -1;
    /** The anvil cost that produced {@link #xpGoalLevel}, kept for the status line. */
    private volatile int xpGoalCost = -1;
    private volatile int xpStartTotal;
    private volatile int lastObservedTotalXp;
    private volatile long xpStartAt;
    private volatile long lastXpProgressAt;
    private volatile double xpStartX, xpStartY, xpStartZ;
    private volatile double xpLastX, xpLastY, xpLastZ;
    private volatile String xpStartDimension;
    private volatile PathingRequestFuture killSpotPath;
    private volatile long killSpotPathAt;
    private int killSpotAttempts;
    private static final long KILL_SPOT_TIMEOUT_MS = 60_000L;
    private static final int MAX_KILL_SPOT_ATTEMPTS = 3;
    /** Ticks of confirmed idleness before combat is handed back; the worker is idle between items, not finished. */
    private static final int COMBAT_GRACE_TICKS = 20;

    @Override
    public boolean enabledSetting() {
        return PLUGIN_CONFIG.enabled;
    }

    public int getPriority() {
        KillAura killAura = MODULE.get(KillAura.class);
        int combat = killAura != null
            ? killAura.getPriority()
            : java.util.Objects.requireNonNullElse(CONFIG.client.extra.killAura.actionPriority, 8000);
        return Math.max(combat, Baritone.getPriority()) + 100;
    }

    @Override
    public List<EventConsumer<?>> registerEvents() {
        return List.of(
            of(ClientBotTick.class, event -> onBotTick()),
            of(ClientBotTick.Starting.class, e -> reset()),
            of(ClientBotTick.Stopped.class, e -> reset())
        );
    }

    @Override
    public void onEnable() {
        // Config is loaded before the first tick; build the runnable-job cache now so a restart
        // with an unchanged layout does not wait for a scan that reports no change.
        revalidateConfig();
    }

    @Override
    public void onDisable() {
        reset();
    }

    private void reset() {
        stopXpFarming();
        resumeKillAura("auto-enchant stopped");
        xpInterrupted = false;
        workflowWorker.abort();
    }

    @Override
    public PacketHandlerCodec registerClientPacketHandlerCodec() {
        return PacketHandlerCodec.clientBuilder()
            .setId("auto-enchant-anvil-preview")
            .setPriority(-5)
            .state(ProtocolState.GAME, PacketHandlerStateCodec.clientBuilder()
                .inbound(ClientboundContainerSetDataPacket.class, (packet, session) -> {
                    // Property 0 is the server-calculated anvil level cost. It is advisory until
                    // the result slot and current open container are also checked by the worker.
                    if (packet.getRawProperty() == 0) {
                        lastAnvilContainerId = packet.getContainerId();
                        lastAnvilCost = packet.getValue();
                        lastAnvilCostAt = System.currentTimeMillis();
                    }
                    return packet;
                })
                .build())
            .build();
    }

    private void onBotTick() {
        AutoEnchantConfig config = AutoEnchantPlugin.PLUGIN_CONFIG;
        if (config == null || !config.enabled) {
            // Turning the module off mid-farm must hand combat back: the pause is held by this module only.
            stopXpFarming();
            resumeKillAura("auto-enchant disabled");
            return;
        }
        monitorXpFarming();
        workflowWorker.tick();
        updateCombatLease();
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
     * name becomes the rename target. The mode (wiki preset or hand-written set) is decided by the worker at runtime.
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

    /** Jobs the worker may actually pick up: enabled and complete (item, target set, input and output chest). */
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

    /** Like {@link #jobFor} but only for jobs the worker can finish, so a draft is never started. */
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
        return name.equals("chest") || name.equals("trapped_chest") || name.equals("barrel")
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
    private boolean isAnvilItem(ItemStack shown) {
        int id = shown.getId();
        return id == com.zenith.mc.item.ItemRegistry.ANVIL.id()
            || id == com.zenith.mc.item.ItemRegistry.CHIPPED_ANVIL.id()
            || id == com.zenith.mc.item.ItemRegistry.DAMAGED_ANVIL.id();
    }

    private String markerRole(ItemStack shown) {
        // Role names match the container roles resolved by containerPosition(): the dust frame is the pickup chest and
        // an equipment frame is the unload chest. There is no separate semi-finished marker any more: finished and
        // half-done pieces both leave through the equipment frame's own container.
        if (shown.getId() == com.zenith.mc.item.ItemRegistry.REDSTONE.id()) return "input";
        if (isAnvilItem(shown)) return "anvil_supply";
        if (shown.getId() == com.zenith.mc.item.ItemRegistry.ENCHANTED_BOOK.id()) {
            var component = shown.getDataComponentsOrEmpty().get(DataComponentTypes.STORED_ENCHANTMENTS);
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

    /** Item name plus every enchantment it carries, e.g. {@code enchanted_book x1 [sharpness 5]}. */
    public String describeItem(ItemStack stack) {
        if (stack == null) return "none";
        var item = ItemRegistry.REGISTRY.get(stack.getId());
        String base = item == null ? "unknown_item_id=" + stack.getId() : shortItemId(item.name());
        StringBuilder out = new StringBuilder(base);
        if (stack.getAmount() > 1) out.append(" x").append(stack.getAmount());
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
        if (job.itemEnchantments == null || job.itemEnchantments.isEmpty()) return "no target enchantments";
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
        // The cost only changes when the slots change, and the worker forgets it before every new placement, so a
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

    /**
     * Starts XP farming: walks to the configured kill spot first, then enables KillAura. Never disables KillAura that
     * another module or the user had running.
     *
     * @return false while the BOT is still walking to the spot, and also when farming cannot start at all; only the
     *     latter marks the XP wait as interrupted (see {@link #consumeXpInterruption()})
     */
    public synchronized boolean startXpKillAura() {
        var config = AutoEnchantPlugin.PLUGIN_CONFIG;
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) { xpInterrupted = true; return false; }
        if (config.experienceSource == AutoEnchantConfig.ExperienceSource.NONE) {
            xpInterrupted = true;
            warn("Auto-enchant needs XP but experienceSource is NONE");
            return false;
        }
        KillAura killAura = MODULE.get(KillAura.class);
        if (config.experienceSource == AutoEnchantConfig.ExperienceSource.KILL && killAura == null) {
            xpInterrupted = true;
            return false;
        }
        if (!player.isAlive() || player.getHealth() < MIN_HEALTH) {
            xpInterrupted = true;
            return false;
        }
        String dimension = World.getCurrentDimension().name();
        // The grind spot is a place to fight from, not a teleport: the farm only starts once the BOT stands there.
        if (!reachKillSpot()) return false;
        // KILL mode fights for the exact level the anvil asked for. The aura is started at module-instance level and
        // the user's killaura setting is turned on only when there is real work to pay for (see switchKillAura).
        if (config.experienceSource == AutoEnchantConfig.ExperienceSource.KILL) {
            killAuraSuspended = false;
            // Fighting for the anvil means fighting, period: if the user's own setting is off, the farm switches it on
            // instead of silently refusing to grind. It is saved to the config so a restart keeps the same behaviour.
            if (!CONFIG.client.extra.killAura.enabled) switchKillAura(true);
            if (!killAura.isEnabled()) {
                killAura.enable();
                info("[trace] KillAura started for the anvil cost goal: level {} -> {} (cost {})",
                    player.getLevel(), xpGoalLevel, xpGoalCost);
            }
        }
        xpFarmingActive = true;
        xpInterrupted = false;
        xpStartTotal = player.getTotalExperience();
        lastObservedTotalXp = xpStartTotal;
        xpStartAt = lastXpProgressAt = System.currentTimeMillis();
        xpStartX = xpLastX = player.getX(); xpStartY = xpLastY = player.getY(); xpStartZ = xpLastZ = player.getZ();
        xpStartDimension = dimension;
        return true;
    }

    /**
     * Walks the BOT to the configured kill spot. Returns true when no spot is configured (fight where the BOT stands)
     * or the BOT is already close enough; false while walking, and a genuinely unreachable spot interrupts the farm.
     */
    private boolean reachKillSpot() {
        BlockPos spot = AutoEnchantPlugin.PLUGIN_CONFIG.killSpot;
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

    /** Stops the XP goal bookkeeping. Combat is a separate concern, handled by {@link #updateCombatLease()}. */
    private void stopXpFarming() {
        xpFarmingActive = false;
        killSpotPath = null;
        killSpotAttempts = 0;
    }

    /**
     * One authority for "may the BOT fight right now". It runs after the worker tick, so it never fights the state
     * machine: while a transaction is in flight the BOT must stand still and keep its rotation on the container, and
     * only the XP farm may take the aura back.
     *
     * <p>KillAura itself stays enabled in the config the whole time; this only pauses the module instance, so a
     * restart, a config reload or the user's own {@code killaura on/off} command always wins.
     */
    private void updateCombatLease() {
        var config = AutoEnchantPlugin.PLUGIN_CONFIG;
        boolean farmWantsCombat = xpFarmingActive && config.experienceSource == AutoEnchantConfig.ExperienceSource.KILL;
        if (farmWantsCombat) {
            combatIdleTicks = 0;
            resumeKillAura("farming XP for the anvil");
            return;
        }
        if (workflowWorker.busy()) {
            combatIdleTicks = 0;
            suspendKillAura("anvil work in progress");
            return;
        }
        // Idle for a tick or two between two items is not "done": handing combat back for that single tick makes the
        // BOT swing at a nearby mob and turn straight back to the anvil. Require a full second of quiet first.
        if (++combatIdleTicks < COMBAT_GRACE_TICKS) return;
        resumeKillAura("no enchant transaction in flight");
    }

    /**
     * Writes the user's KillAura setting. Only the XP farm calls this: when it needs to fight, a config with the aura
     * switched off is not a reason to give up, it is the setting that has to change.
     */
    private synchronized void switchKillAura(boolean enabled) {
        if (CONFIG.client.extra.killAura.enabled == enabled) return;
        CONFIG.client.extra.killAura.enabled = enabled;
        com.zenith.Globals.saveConfigAsync();
        info("[trace] KillAura switched {} in the config for XP farming", enabled ? "on" : "off");
        KillAura killAura = MODULE.get(KillAura.class);
        if (killAura == null) return;
        if (enabled) {
            killAuraSuspended = false;
            killAura.enable();
        } else {
            killAura.disable();
        }
    }

    /** Pauses combat for the duration of the anvil/chest work; the user's killaura setting is never touched. */
    private synchronized void suspendKillAura(String reason) {
        KillAura killAura = MODULE.get(KillAura.class);
        if (killAura == null) return;
        boolean firstPause = !killAuraSuspended;
        killAuraSuspended = true;
        // Re-check every tick: a config reload or a manual "killaura on" can enable the module behind our back, and
        // then one stray attack is enough to break the rotation the anvil interaction depends on.
        if (killAura.isEnabled()) {
            killAura.disable();
            if (firstPause) info("[trace] KillAura paused: {} (its config stays enabled)", reason);
        }
    }

    /** Hands combat back exactly as the user configured it, unless the config has KillAura switched off. */
    private synchronized void resumeKillAura(String reason) {
        if (!killAuraSuspended) return;
        killAuraSuspended = false;
        KillAura killAura = MODULE.get(KillAura.class);
        if (killAura == null) return;
        if (CONFIG.client.extra.killAura.enabled) {
            if (!killAura.isEnabled()) {
                killAura.enable();
                info("[trace] KillAura resumed: {}", reason);
            }
        } else if (killAura.isEnabled()) {
            // The farm can start an aura the user's config does not want; do not leave it running behind their back.
            killAura.disable();
            info("[trace] KillAura stopped again: its config is disabled");
        }
    }

    /**
     * Decides from the cost the anvil is showing whether the BOT has to go and farm XP. Nothing else enables KillAura:
     * a free or an affordable operation leaves the BOT alone, and farming only starts for the exact level the window
     * asks for (plus a small reserve so the next merge does not need a second trip).
     *
     * @param requiredLevel the level cost reported by the open anvil, i.e. the "cost N levels" line in the GUI
     * @return true when the BOT may take the result now
     */
    public boolean ensureXpForCost(int requiredLevel) {
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) { xpInterrupted = true; return false; }
        int cost = Math.max(0, requiredLevel);
        // A free operation is no reason to start fighting: the BOT could stand at the anvil and take the result.
        if (cost == 0) {
            xpGoalLevel = -1;
            xpGoalCost = 0;
            stopXpFarming();
            return true;
        }
        int goal = cost + RESERVE_LEVELS;
        if (player.getLevel() >= goal) {
            xpGoalLevel = -1;
            stopXpFarming();
            return true;
        }
        xpGoalLevel = goal;
        xpGoalCost = cost;
        // Fight only with both hands free: while the anvil window is open the worker is about to close it, and a path
        // request submitted under an open GUI is rejected by the server.
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() != 0) {
            status = "Anvil needs " + cost + " levels; closing it before farming XP";
            return false;
        }
        // Whether the BOT can farm at all is decided inside startXpKillAura; walking to the spot is not a failure.
        if (!xpFarmingActive) startXpKillAura();
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

    private void monitorXpFarming() {
        if (!xpFarmingActive) return;
        var config = AutoEnchantPlugin.PLUGIN_CONFIG;
        var player = CACHE.getPlayerCache().getThePlayer();
        if (player == null) { interruptXp("BOT player data unavailable"); return; }
        if (!player.isAlive()) { interruptXp("BOT died"); return; }
        if (player.getHealth() < MIN_HEALTH) { interruptXp("Health below " + (int) MIN_HEALTH); return; }
        // Stop the moment the anvil's cost is affordable: KillAura is only ever on to pay for a specific operation.
        if (xpGoalLevel > 0 && player.getLevel() >= xpGoalLevel) {
            stopXpFarming();
            status = "XP ready: level " + player.getLevel() + " reaches the anvil cost goal " + xpGoalLevel;
            info("[trace] xp goal reached: level {} >= {} (anvil cost {})", player.getLevel(), xpGoalLevel, xpGoalCost);
            xpGoalLevel = -1;
            return;
        }
        String dimension = World.getCurrentDimension().name();
        if (!dimension.equals(xpStartDimension)) { interruptXp("BOT changed dimension"); return; }
        double dx = player.getX() - xpLastX, dy = player.getY() - xpLastY, dz = player.getZ() - xpLastZ;
        xpLastX = player.getX(); xpLastY = player.getY(); xpLastZ = player.getZ();
        if (dx * dx + dy * dy + dz * dz > 16 * 16) { interruptXp("BOT was teleported during XP farming"); return; }
        long now = System.currentTimeMillis();
        if (now - xpStartAt > KILLAURA_MAX_SECONDS * 1000L) { interruptXp("XP farming timeout"); return; }
        if (player.getTotalExperience() > lastObservedTotalXp) {
            lastObservedTotalXp = player.getTotalExperience();
            lastXpProgressAt = now;
        } else if (now - lastXpProgressAt > NO_XP_TIMEOUT_MS) {
            interruptXp("No XP progress timeout");
        }
    }

    private synchronized void interruptXp(String reason) {
        if (!xpFarmingActive) return;
        xpInterrupted = true;
        stopXpFarming();
        status = "XP farming interrupted: " + reason;
        warn("Auto-enchant XP farming interrupted: {}", reason);
    }

    public void onTradeListChange() {
        // Commands and scan both funnel here, exactly like the trader: rebuild the runnable-job
        // cache and drop any in-flight transaction so the new configuration is used right away.
        revalidateConfig();
        reset();
    }

    /**
     * Workflow states, in the trader-plugin style: every chest interaction has its own
     * GO_TO / WITHDRAW / AWAIT_WITHDRAW triplet, and the anvil is entered with
     * ENCHANT_INTERACT_WITH_ANVIL and finished in AWAIT_ENCHANT. The sub-steps below keep the
     * detailed anvil, grindstone, rename and close handling that an enchanting run needs.
     */
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
        RESTOCK_ENCHANTED_BOOK_TAKE,
        RESTOCK_ENCHANTED_BOOK_CLOSE,
        RESTOCK_ANVIL_TAKE,
        CLOSE_SUPPLY,
        PLACE_RENAME,
        CLOSE_FOR_XP,
        WAIT_XP,
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
}

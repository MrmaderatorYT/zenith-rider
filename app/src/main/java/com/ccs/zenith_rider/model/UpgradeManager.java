package com.ccs.zenith_rider.model;

import com.ccs.zenith_rider.storage.SaveManager;

/**
 * Shop logic: permanent upgrades (5 levels each) and single-run modifiers (perks).
 * Also converts upgrade levels into concrete gameplay numbers.
 */
public final class UpgradeManager {

    public static final int UPG_MAGNET = 0;
    public static final int UPG_WINGS = 1;
    public static final int UPG_RESONATOR = 2;

    public static final int PERK_SHIELD = 0;
    public static final int PERK_DOUBLE = 1;
    public static final int PERK_LUNAR = 2;
    public static final int PERK_INSTANT = 3;
    public static final int PERK_TETHER = 4;

    public static final int UPG_HOVER = 3;
    public static final int UPG_WISP = 4;

    // ---- riders: each one changes pace, rotation and has a signature ability ----
    public static final int RIDER_COUNT = 6;
    public static final int[] RIDER_COST = {0, 600, 1500, 3000, 4500, 9000};
    public static final float[] RIDER_SPEED = {1f, 0.93f, 1.12f, 1.10f, 1f, 1.12f};
    public static final float[] RIDER_ACCEL = {1f, 1.1f, 0.55f, 2.0f, 1f, 2.0f};
    public static final float[] RIDER_ROTATION = {1f, 1.4f, 0.75f, 1.25f, 1f, 1.4f};
    public static final float[] RIDER_BOOST = {1f, 1f, 1f, 2.5f, 1f, 2.5f};
    public static final boolean[] RIDER_SMASH = {false, false, true, false, false, true};
    public static final boolean[] RIDER_DOUBLE_JUMP = {false, false, false, false, true, true};
    public static final float[] RIDER_SCALE = {1f, 0.92f, 1.16f, 1f, 0.95f, 1.05f};
    /** Scarf colour per rider; 0 means "use the time-of-day palette". */
    public static final int[] RIDER_SCARF = {0, 0xFFB388FF, 0xFFFF8A3D, 0xFF7CFFB2, 0xFF7FD4FF, 0xFFFFD86B};

    public static final int MAX_LEVEL = 5;
    public static final int MAX_SELECTED = 2;

    private static final int[] UPGRADE_COSTS = {120, 300, 600, 1000, 1600};
    private static final int[] PERK_COSTS = {90, 120, 70, 150, 80};

    private final SaveManager save;

    /** Perks ticked on the pre-run screen. */
    public final boolean[] selected = new boolean[SaveManager.PERK_COUNT];
    /** Perks that are in effect for the run in progress. */
    public final boolean[] active = new boolean[SaveManager.PERK_COUNT];

    public UpgradeManager(SaveManager save) {
        this.save = save;
    }

    public int level(int upgrade) {
        return save.upgradeLevels[upgrade];
    }

    /** Price of the next level, or -1 when the upgrade is maxed out. */
    public int upgradeCost(int upgrade) {
        int lvl = save.upgradeLevels[upgrade];
        return lvl >= MAX_LEVEL ? -1 : UPGRADE_COSTS[lvl];
    }

    public boolean buyUpgrade(int upgrade) {
        int cost = upgradeCost(upgrade);
        if (cost < 0 || save.orbs < cost) {
            return false;
        }
        save.orbs -= cost;
        save.upgradeLevels[upgrade]++;
        save.save();
        return true;
    }

    public int perkCost(int perk) {
        return PERK_COSTS[perk];
    }

    public int perkCount(int perk) {
        return save.perkCounts[perk];
    }

    public boolean buyPerk(int perk) {
        int cost = PERK_COSTS[perk];
        if (save.orbs < cost || save.perkCounts[perk] >= 99) {
            return false;
        }
        save.orbs -= cost;
        save.perkCounts[perk]++;
        save.save();
        return true;
    }

    public int selectedCount() {
        int n = 0;
        for (int i = 0; i < selected.length; i++) {
            if (selected[i]) {
                n++;
            }
        }
        return n;
    }

    /** Toggles a perk for the next run. Returns false when it cannot be selected. */
    public boolean toggleSelected(int perk) {
        if (selected[perk]) {
            selected[perk] = false;
            return true;
        }
        if (save.perkCounts[perk] <= 0 || selectedCount() >= MAX_SELECTED) {
            return false;
        }
        selected[perk] = true;
        return true;
    }

    public boolean ownsAnyPerk() {
        for (int i = 0; i < save.perkCounts.length; i++) {
            if (save.perkCounts[i] > 0) {
                return true;
            }
        }
        return false;
    }

    /** Spends the selected perks and marks them active for the new run. */
    public void consumeSelected() {
        for (int i = 0; i < selected.length; i++) {
            active[i] = false;
            if (selected[i] && save.perkCounts[i] > 0) {
                save.perkCounts[i]--;
                active[i] = true;
            }
            if (save.perkCounts[i] <= 0) {
                selected[i] = false;
            }
        }
    }

    public void clearActive() {
        for (int i = 0; i < active.length; i++) {
            active[i] = false;
        }
    }

    // ---- Zenith Flow unlock ----

    public static final int FLOW_COST = 7000;
    /** Combo multiplier at which the fully grown scarf ignites Zenith Flow. */
    public static final int FLOW_COMBO = 5;

    public boolean flowOwned() {
        return save.flowOwned;
    }

    public boolean buyFlow() {
        if (save.flowOwned || save.orbs < FLOW_COST) {
            return false;
        }
        save.orbs -= FLOW_COST;
        save.flowOwned = true;
        save.save();
        return true;
    }

    // ---- riders ----

    public int rider() {
        return save.rider;
    }

    public boolean riderOwned(int rider) {
        return (save.ridersOwned & (1 << rider)) != 0;
    }

    public boolean buyRider(int rider) {
        if (riderOwned(rider) || save.orbs < RIDER_COST[rider]) {
            return false;
        }
        save.orbs -= RIDER_COST[rider];
        save.ridersOwned |= 1 << rider;
        save.rider = rider;
        save.save();
        return true;
    }

    public void selectRider(int rider) {
        if (riderOwned(rider)) {
            save.rider = rider;
            save.save();
        }
    }

    // ---- gameplay values derived from upgrade levels ----

    /** Duration of the hover pickup in seconds. */
    public float hoverDuration() {
        return 5f + 1.5f * level(UPG_HOVER);
    }

    /** Number of wisps in a flock. */
    public int wispFlock() {
        return 4 + 2 * level(UPG_WISP);
    }


    /** Passive orb attraction radius in world units. */
    public float magnetRadius() {
        return 84f + 28f * level(UPG_MAGNET);
    }

    /** Duration of the magnet power-up in seconds. */
    public float magnetDuration() {
        return 6f + 2f * level(UPG_MAGNET);
    }

    /** Backflip angular speed in radians per second. */
    public float rotationSpeed() {
        // About 1.5 s per backflip at the start, in line with a calm, readable rotation.
        return 4.2f + 0.35f * level(UPG_WINGS);
    }

    /** Terminal sink speed while gliding, world units per second. */
    public float glideSink() {
        return 200f - 28f * level(UPG_WINGS);
    }

    /** Zenith Flow duration in seconds. */
    public float flowDuration() {
        return 7f + 1.6f * level(UPG_RESONATOR);
    }
}

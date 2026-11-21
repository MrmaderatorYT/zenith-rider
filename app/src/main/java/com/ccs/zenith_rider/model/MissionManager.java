package com.ccs.zenith_rider.model;

import com.ccs.zenith_rider.storage.SaveManager;

/**
 * Three simultaneously active mini-missions. Progress is stored in SaveManager arrays,
 * so reporting progress from the game loop is allocation-free.
 */
public final class MissionManager {

    public static final int FLIPS_RUN = 0;
    public static final int ORBS = 1;
    public static final int FLOW_DIST = 2;
    public static final int RAILS = 3;
    public static final int DIST_RUN = 4;
    public static final int CRYSTALS = 5;
    public static final int GLIDE_DIST = 6;
    public static final int SCORE_RUN = 7;
    public static final int COMBO = 8;
    public static final int GAPS = 9;
    public static final int TYPE_COUNT = 10;

    public static final int SLOTS = SaveManager.MISSION_SLOTS;
    public static final int MISSIONS_PER_RANK = 3;

    private static final int[] BASE_TARGET = {3, 5, 500, 2, 1000, 3, 200, 3000, 4, 5};
    /** Largest orb goal the table above can produce (tier 8). */
    private static final int MAX_ORB_TARGET = 30;
    /** Highest mission tier; reached at rank 9. */
    private static final int MAX_TIER = 8;
    /** Largest reward {@link #rewardFor} can produce; anything above it is on the old scale. */
    private static final int MAX_REWARD = 21;
    private static final boolean[] PER_RUN = {true, false, false, false, true, false, false, true, true, false};
    /** Order in which mission types are handed out; long enough to feel varied. */
    private static final int[] SEQUENCE = {0, 1, 3, 4, 9, 2, 5, 7, 6, 8, 1, 0, 4, 3, 9, 5, 7, 2, 8, 6};

    private final SaveManager save;

    private final boolean[] freshlyCompleted = new boolean[SLOTS];

    public MissionManager(SaveManager save) {
        this.save = save;
        for (int i = 0; i < SLOTS; i++) {
            if (save.missionType[i] < 0 || save.missionType[i] >= TYPE_COUNT || save.missionTarget[i] <= 0
                    || (!save.missionDone[i] && (needsFlow(save.missionType[i]) || staleOrbGoal(i)))) {
                assign(i);
            }
            // Rewards saved before orbs became scarce were about ten times larger.
            if (!save.missionDone[i] && save.missionReward[i] > MAX_REWARD) {
                save.missionReward[i] = rewardFor(Math.min(rank() - 1, MAX_TIER));
            }
        }
    }

    /**
     * Orbs paid for a mission: 5 at rank 1, rising by 2 per rank to 21. One mission is worth
     * roughly the orbs of one good run, so missions help but do not replace riding.
     */
    private static int rewardFor(int tier) {
        return 5 + 2 * tier;
    }

    public int rank() {
        return 1 + save.missionsCompleted / MISSIONS_PER_RANK;
    }

    public int missionsIntoRank() {
        return save.missionsCompleted % MISSIONS_PER_RANK;
    }

    public int type(int slot) {
        return save.missionType[slot];
    }

    public int target(int slot) {
        return save.missionTarget[slot];
    }

    public int progress(int slot) {
        return save.missionProgress[slot];
    }

    public int reward(int slot) {
        return save.missionReward[slot];
    }

    public boolean done(int slot) {
        return save.missionDone[slot];
    }

    private boolean typeInUse(int type, int exceptSlot) {
        for (int i = 0; i < SLOTS; i++) {
            if (i != exceptSlot && save.missionType[i] == type) {
                return true;
            }
        }
        return false;
    }

    /** Flow and glide goals cannot be met before Zenith Flow is unlocked. */
    private boolean needsFlow(int type) {
        return !save.flowOwned && (type == FLOW_DIST || type == GLIDE_DIST);
    }

    /** Orb goals saved before orbs became scarce are ten times too large to finish. */
    private boolean staleOrbGoal(int slot) {
        return save.missionType[slot] == ORBS && save.missionTarget[slot] > MAX_ORB_TARGET;
    }

    private void assign(int slot) {
        int type = SEQUENCE[save.missionCursor % SEQUENCE.length];
        int guard = 0;
        while ((typeInUse(type, slot) || needsFlow(type)) && guard < SEQUENCE.length) {
            save.missionCursor++;
            type = SEQUENCE[save.missionCursor % SEQUENCE.length];
            guard++;
        }
        save.missionCursor++;
        int tier = Math.min(rank() - 1, MAX_TIER);
        int target = Math.round(BASE_TARGET[type] * (1f + 0.6f * tier));
        if (type == COMBO) {
            target = Math.min(4 + tier, 10);
        }
        save.missionType[slot] = type;
        save.missionTarget[slot] = target;
        save.missionProgress[slot] = 0;
        save.missionReward[slot] = rewardFor(tier);
        save.missionDone[slot] = false;
    }

    /** Adds to cumulative missions of the given type. */
    public void add(int type, int amount) {
        if (amount <= 0) {
            return;
        }
        for (int i = 0; i < SLOTS; i++) {
            if (save.missionType[i] == type && !save.missionDone[i]) {
                save.missionProgress[i] += amount;
                check(i);
            }
        }
    }

    /** Raises "reach a value" missions of the given type to at least {@code value}. */
    public void reach(int type, int value) {
        for (int i = 0; i < SLOTS; i++) {
            if (save.missionType[i] == type && !save.missionDone[i] && value > save.missionProgress[i]) {
                save.missionProgress[i] = value;
                check(i);
            }
        }
    }

    private void check(int slot) {
        if (save.missionProgress[slot] >= save.missionTarget[slot]) {
            save.missionProgress[slot] = save.missionTarget[slot];
            save.missionDone[slot] = true;
            save.missionsCompleted++;
            save.orbs += save.missionReward[slot];
            freshlyCompleted[slot] = true;
        }
    }

    /** Returns a slot that was completed since the last poll, or -1. */
    public int pollCompleted() {
        for (int i = 0; i < SLOTS; i++) {
            if (freshlyCompleted[i]) {
                freshlyCompleted[i] = false;
                return i;
            }
        }
        return -1;
    }

    /** Resets the progress of single-run missions at the beginning of a run. */
    public void onRunStart() {
        for (int i = 0; i < SLOTS; i++) {
            if (!save.missionDone[i] && PER_RUN[save.missionType[i]]) {
                save.missionProgress[i] = 0;
            }
        }
    }

    public boolean allDone() {
        for (int i = 0; i < SLOTS; i++) {
            if (!save.missionDone[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Completed missions stay ticked from run to run, also after a crash. Only when the whole
     * set of three is done is it replaced by a new set (which is also when the rank goes up).
     */
    public void replaceCompleted() {
        if (!allDone()) {
            return;
        }
        for (int i = 0; i < SLOTS; i++) {
            assign(i);
        }
    }
}

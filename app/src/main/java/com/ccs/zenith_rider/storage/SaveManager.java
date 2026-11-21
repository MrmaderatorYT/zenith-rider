package com.ccs.zenith_rider.storage;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Persistent player profile backed by SharedPreferences.
 * All values live in public fields so the game loop can read them without any allocation;
 * {@link #save()} is only called on discrete events (purchase, run end, settings change).
 */
public final class SaveManager {

    public static final int UPGRADE_COUNT = 5;
    public static final int PERK_COUNT = 5;
    public static final int MISSION_SLOTS = 3;

    private static final String PREFS = "zenith_rider_save";

    private final SharedPreferences prefs;

    public float bestDistance;
    public int bestScore;
    public int orbs;
    public final int[] upgradeLevels = new int[UPGRADE_COUNT];
    public final int[] perkCounts = new int[PERK_COUNT];

    public final int[] missionType = new int[MISSION_SLOTS];
    public final int[] missionTarget = new int[MISSION_SLOTS];
    public final int[] missionProgress = new int[MISSION_SLOTS];
    public final int[] missionReward = new int[MISSION_SLOTS];
    public final boolean[] missionDone = new boolean[MISSION_SLOTS];
    public int missionsCompleted;
    public int missionCursor;

    /** 0 = English, 1 = Ukrainian, -1 = not chosen yet (follow the device locale). */
    public int language = -1;
    public boolean soundOn = true;
    /** Bit mask of tutorial hints that were already shown and completed. */
    public int tutorialMask;
    public int totalRuns;
    /** Selected rider and a bit mask of unlocked riders (bit 0 is always set). */
    public int rider;
    public int ridersOwned = 1;
    /** One-time purchase that unlocks Zenith Flow and the energy wing. */
    public boolean flowOwned;

    public SaveManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        load();
    }

    public void load() {
        bestDistance = prefs.getFloat("bestDistance", 0f);
        bestScore = prefs.getInt("bestScore", 0);
        orbs = prefs.getInt("orbs", 0);
        for (int i = 0; i < UPGRADE_COUNT; i++) {
            upgradeLevels[i] = clamp(prefs.getInt("upgrade" + i, 0), 0, 5);
        }
        for (int i = 0; i < PERK_COUNT; i++) {
            perkCounts[i] = Math.max(0, prefs.getInt("perk" + i, 0));
        }
        for (int i = 0; i < MISSION_SLOTS; i++) {
            missionType[i] = prefs.getInt("mType" + i, -1);
            missionTarget[i] = prefs.getInt("mTarget" + i, 0);
            missionProgress[i] = prefs.getInt("mProgress" + i, 0);
            missionReward[i] = prefs.getInt("mReward" + i, 0);
            missionDone[i] = prefs.getBoolean("mDone" + i, false);
        }
        missionsCompleted = prefs.getInt("missionsCompleted", 0);
        missionCursor = prefs.getInt("missionCursor", 0);
        language = prefs.getInt("language", -1);
        soundOn = prefs.getBoolean("soundOn", true);
        tutorialMask = prefs.getInt("tutorialMask", 0);
        totalRuns = prefs.getInt("totalRuns", 0);
        ridersOwned = prefs.getInt("ridersOwned", 1) | 1;
        flowOwned = prefs.getBoolean("flowOwned", false);
        rider = prefs.getInt("rider", 0);
        if (rider < 0 || rider > 30 || (ridersOwned & (1 << rider)) == 0) {
            rider = 0;
        }
    }

    public void save() {
        SharedPreferences.Editor e = prefs.edit();
        e.putFloat("bestDistance", bestDistance);
        e.putInt("bestScore", bestScore);
        e.putInt("orbs", orbs);
        for (int i = 0; i < UPGRADE_COUNT; i++) {
            e.putInt("upgrade" + i, upgradeLevels[i]);
        }
        for (int i = 0; i < PERK_COUNT; i++) {
            e.putInt("perk" + i, perkCounts[i]);
        }
        for (int i = 0; i < MISSION_SLOTS; i++) {
            e.putInt("mType" + i, missionType[i]);
            e.putInt("mTarget" + i, missionTarget[i]);
            e.putInt("mProgress" + i, missionProgress[i]);
            e.putInt("mReward" + i, missionReward[i]);
            e.putBoolean("mDone" + i, missionDone[i]);
        }
        e.putInt("missionsCompleted", missionsCompleted);
        e.putInt("missionCursor", missionCursor);
        e.putInt("language", language);
        e.putBoolean("soundOn", soundOn);
        e.putInt("tutorialMask", tutorialMask);
        e.putInt("totalRuns", totalRuns);
        e.putInt("ridersOwned", ridersOwned);
        e.putInt("rider", rider);
        e.putBoolean("flowOwned", flowOwned);
        e.apply();
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}

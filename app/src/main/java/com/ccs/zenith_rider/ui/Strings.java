package com.ccs.zenith_rider.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import com.ccs.zenith_rider.R;

import java.util.Locale;

/**
 * Bilingual string table. Both languages are loaded once at start-up from
 * res/values/strings.xml (English) and res/values-uk/strings.xml (Ukrainian),
 * so switching the language at runtime is a single integer assignment and
 * string look-ups in the render loop never allocate.
 */
public final class Strings {

    public static final int LANG_EN = 0;
    public static final int LANG_UK = 1;

    public static final int TITLE = 0;
    public static final int PLAY = 1;
    public static final int SHOP = 2;
    public static final int MISSIONS = 3;
    public static final int BACK = 4;
    public static final int START = 5;
    public static final int RESUME = 6;
    public static final int QUIT = 7;
    public static final int RETRY = 8;
    public static final int MENU = 9;
    public static final int PAUSED = 10;
    public static final int GAME_OVER = 11;
    public static final int DISTANCE = 12;
    public static final int SCORE = 13;
    public static final int BEST = 14;
    public static final int ORBS = 15;
    public static final int RANK = 16;
    public static final int NEW_RECORD = 17;
    public static final int SOUND = 18;
    public static final int ON = 19;
    public static final int OFF = 20;
    public static final int UPGRADES = 21;
    public static final int PERKS = 22;
    public static final int LEVEL = 23;
    public static final int MAX = 24;
    public static final int OWNED = 25;
    public static final int BUY = 26;
    public static final int CHOOSE_PERKS = 27;
    public static final int NO_ORBS = 28;
    public static final int MISSION_DONE = 29;
    public static final int REWARD = 30;
    public static final int FLOW = 31;
    public static final int METERS = 32;
    public static final int UPG_NAME = 33;   // +0..2
    public static final int UPG_DESC = 36;   // +0..2
    public static final int PERK_NAME = 39;  // +0..3
    public static final int PERK_DESC = 43;  // +0..3
    public static final int MISSION = 47;    // +0..9
    public static final int RANK_NAME = 57;  // +0..7
    public static final int TUT = 65;        // +0..5
    public static final int POP_FLIP = 71;
    public static final int POP_RAIL = 72;
    public static final int POP_SMASH = 73;
    public static final int POP_GAP = 74;
    public static final int POP_FLOW = 75;
    public static final int POP_SHIELD = 76;
    public static final int POP_MAGNET = 77;
    public static final int BEST_DIST = 78;
    public static final int BEST_SCORE = 79;
    public static final int COLLECTED = 80;
    public static final int SELECTED = 81;
    public static final int NONE_OWNED = 82;
    public static final int SELECT = 83;
    public static final int COMBO = 84;
    public static final int UPG_NAME_EXTRA = 85;  // +0..1 (upgrades 3..4)
    public static final int UPG_DESC_EXTRA = 87;  // +0..1
    public static final int PERK_NAME_EXTRA = 89; // perk 4
    public static final int PERK_DESC_EXTRA = 90;
    public static final int RIDERS = 91;
    public static final int RIDER_NAME = 92;      // +0..5
    public static final int RIDER_DESC = 98;      // +0..5
    public static final int POP_HOVER = 104;
    public static final int YOUR_SCORE = 105;
    public static final int DIST_TRAVELLED = 106;
    public static final int TRICK_SCORE = 107;
    public static final int BEST_COMBO = 108;
    public static final int CHASMS_CLEARED = 109;
    public static final int GOALS_DONE = 110;
    public static final int TOTAL = 111;
    public static final int HOME = 112;
    public static final int PLAY_AGAIN = 113;
    public static final int UPGRADE = 114;
    public static final int COMPLETE = 115;
    public static final int PURCHASE = 116;
    public static final int FLOW_UNLOCK_NAME = 117;
    public static final int FLOW_UNLOCK_DESC = 118;
    public static final int TUT_DASH = 119;       // hint 6 (hints 0..5 are TUT + i)
    public static final int COUNT = 120;

    public static final int MISSION_TYPES = 10;
    public static final int RANK_NAMES = 8;

    private static final int[] IDS = {
            R.string.title, R.string.play, R.string.shop, R.string.missions, R.string.back,
            R.string.start, R.string.resume, R.string.quit, R.string.retry, R.string.menu,
            R.string.paused, R.string.game_over, R.string.distance, R.string.score, R.string.best,
            R.string.orbs, R.string.rank, R.string.new_record, R.string.sound, R.string.on,
            R.string.off, R.string.upgrades, R.string.perks, R.string.level, R.string.max,
            R.string.owned, R.string.buy, R.string.choose_perks, R.string.no_orbs,
            R.string.mission_done, R.string.reward, R.string.flow, R.string.meters,
            R.string.upg_name_0, R.string.upg_name_1, R.string.upg_name_2,
            R.string.upg_desc_0, R.string.upg_desc_1, R.string.upg_desc_2,
            R.string.perk_name_0, R.string.perk_name_1, R.string.perk_name_2, R.string.perk_name_3,
            R.string.perk_desc_0, R.string.perk_desc_1, R.string.perk_desc_2, R.string.perk_desc_3,
            R.string.mission_0, R.string.mission_1, R.string.mission_2, R.string.mission_3,
            R.string.mission_4, R.string.mission_5, R.string.mission_6, R.string.mission_7,
            R.string.mission_8, R.string.mission_9,
            R.string.rank_0, R.string.rank_1, R.string.rank_2, R.string.rank_3,
            R.string.rank_4, R.string.rank_5, R.string.rank_6, R.string.rank_7,
            R.string.tut_0, R.string.tut_1, R.string.tut_2, R.string.tut_3, R.string.tut_4,
            R.string.tut_5,
            R.string.pop_flip, R.string.pop_rail, R.string.pop_smash, R.string.pop_gap,
            R.string.pop_flow, R.string.pop_shield, R.string.pop_magnet,
            R.string.best_dist, R.string.best_score, R.string.collected, R.string.selected,
            R.string.none_owned, R.string.select, R.string.combo,
            R.string.upg_name_3, R.string.upg_name_4, R.string.upg_desc_3, R.string.upg_desc_4,
            R.string.perk_name_4, R.string.perk_desc_4, R.string.riders,
            R.string.rider_name_0, R.string.rider_name_1, R.string.rider_name_2,
            R.string.rider_name_3, R.string.rider_name_4, R.string.rider_name_5,
            R.string.rider_desc_0, R.string.rider_desc_1, R.string.rider_desc_2,
            R.string.rider_desc_3, R.string.rider_desc_4, R.string.rider_desc_5,
            R.string.pop_hover,
            R.string.your_score, R.string.dist_travelled, R.string.trick_score, R.string.best_combo,
            R.string.chasms_cleared, R.string.goals_done, R.string.total, R.string.home,
            R.string.play_again, R.string.upgrade, R.string.complete, R.string.purchase,
            R.string.flow_unlock_name, R.string.flow_unlock_desc, R.string.tut_6
    };

    private final String[][] table = new String[2][COUNT];
    private final String[][] missionPrefix = new String[2][MISSION_TYPES];
    private final String[][] missionSuffix = new String[2][MISSION_TYPES];

    /** Current language index (LANG_EN or LANG_UK). */
    public int lang;

    public Strings(Context context) {
        loadLanguage(context, LANG_EN, "en");
        loadLanguage(context, LANG_UK, "uk");
    }

    private void loadLanguage(Context context, int index, String code) {
        Configuration cfg = new Configuration(context.getResources().getConfiguration());
        cfg.setLocale(new Locale(code));
        Resources res = context.createConfigurationContext(cfg).getResources();
        for (int i = 0; i < COUNT; i++) {
            table[index][i] = res.getString(IDS[i]);
        }
        for (int m = 0; m < MISSION_TYPES; m++) {
            String template = table[index][MISSION + m];
            int at = template.indexOf("%d");
            if (at < 0) {
                missionPrefix[index][m] = template;
                missionSuffix[index][m] = "";
            } else {
                missionPrefix[index][m] = template.substring(0, at);
                missionSuffix[index][m] = template.substring(at + 2);
            }
        }
    }

    public static int deviceLanguage() {
        return "uk".equals(Locale.getDefault().getLanguage()) ? LANG_UK : LANG_EN;
    }

    public String get(int id) {
        return table[lang][id];
    }

    public String upgradeName(int i) {
        return get(i < 3 ? UPG_NAME + i : UPG_NAME_EXTRA + i - 3);
    }

    public String upgradeDesc(int i) {
        return get(i < 3 ? UPG_DESC + i : UPG_DESC_EXTRA + i - 3);
    }

    public String perkName(int i) {
        return get(i < 4 ? PERK_NAME + i : PERK_NAME_EXTRA);
    }

    public String perkDesc(int i) {
        return get(i < 4 ? PERK_DESC + i : PERK_DESC_EXTRA);
    }

    /** Tutorial hint text. Hints 0..5 are the original family; later ones were appended. */
    public String hint(int i) {
        return get(i < 6 ? TUT + i : TUT_DASH + i - 6);
    }

    public String missionPrefix(int type) {
        return missionPrefix[lang][type];
    }

    public String missionSuffix(int type) {
        return missionSuffix[lang][type];
    }
}

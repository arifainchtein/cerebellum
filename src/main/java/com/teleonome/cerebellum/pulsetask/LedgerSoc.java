package com.teleonome.cerebellum.pulsetask;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Product-neutral battery SOC from the device's own battery ledger (Vital Signs version 3, added
 * 2026-10-12) and its Power Profile Dene. Runs alongside PulseTask's "Battery SOC Live %" so the
 * two can be compared before one replaces the other.
 *
 * Why: PulseTask multiplies the single battery-current reading in each data packet by the whole
 * time since the previous packet. A sleeping device measures that current while awake (~90 mA on
 * a Daffodil) and then sleeps for minutes drawing almost nothing, so every sleep is counted as if
 * the device had been awake. The ledger instead integrates charge in and out on the device itself,
 * every ~70 ms of awake time, and reports running totals; deep sleep is added here from
 * Slept Seconds x the profile's Sleep Current.
 *
 * Per new Vital Signs record (its "Vital Signs Received Time" changed):
 *   dCharge = dChargeIn x efficiency
 *           - dDischargeOut x (dAwake / dMeasured)      awake time the ledger missed (boot, before
 *                                                       startLedger) at the same average draw
 *           - dSlept x sleepCurrent
 *   chargeMah += dCharge, clamped to [0, capacity].
 * A changed Reset Count means the device's totals restarted: re-baseline, nothing is added.
 *
 * Anchors (voltage -> SOC from the chemistry's rest curve, scaled by Cells In Series), the same
 * tiers PulseTask uses, but rate-limited so the ledger - not the voltage table - carries the
 * charge between them (PulseTask re-anchors on every rest-state pulse, i.e. every packet of a
 * sleeping device at night, which would make the ledger irrelevant):
 *   - bootstrap on the first usable record,
 *   - at or above the reliable-zone voltage (curve is steep there): at most once an hour,
 *   - at rest in the flat zone (night/sleep, or a brief wake): at most every 6 hours.
 *
 * State is in memory, like every Cerebellum task: a Cerebellum restart re-bootstraps.
 */
public class LedgerSoc {

    // LiFePO4 per-cell rest curve (same table as PulseTask / SolarAnalysis)
    private static final double[][] LIFEPO4_CELL = {
        {3.65, 100}, {3.50, 99}, {3.45, 95}, {3.40, 90},
        {3.35, 80}, {3.30, 70}, {3.25, 60}, {3.20, 50},
        {3.15, 40}, {3.10, 30}, {3.05, 20}, {3.00, 10},
        {2.90, 5}, {2.80, 2}, {2.50, 0}
    };
    // Lead-acid (flooded/AGM) per-cell rest curve, ~25 C
    private static final double[][] LEAD_ACID_CELL = {
        {2.13, 100}, {2.10, 90}, {2.08, 80}, {2.05, 70}, {2.03, 60},
        {2.01, 50}, {1.98, 40}, {1.96, 30}, {1.93, 20}, {1.90, 10}, {1.75, 0}
    };
    // Li-ion (NMC) per-cell rest curve
    private static final double[][] LI_ION_CELL = {
        {4.20, 100}, {4.10, 90}, {4.00, 80}, {3.92, 70}, {3.85, 60}, {3.80, 50},
        {3.75, 40}, {3.70, 30}, {3.65, 20}, {3.55, 10}, {3.30, 0}
    };

    private static final double OCV_MAX_WAKE_SEC = 10.0;   // brief wake -> terminal ~ OCV
    private static final double MAX_AWAKE_SCALE = 1.5;     // cap on the missed-awake-time correction
    private static final long RELIABLE_ANCHOR_INTERVAL_SEC = 3600;
    private static final long REST_ANCHOR_INTERVAL_SEC = 6 * 3600;

    private final String deviceName;
    private final Logger logger = Logger.getLogger(getClass());

    private long lastReceived = -1;
    private long lastResetCount = -1;
    private double lastChargeIn, lastDischargeOut, lastSlept, lastAwake, lastMeasured;
    private double chargeMah = -1;          // -1 = not anchored yet
    private String lastAnchor = "";
    private long lastAnchorEpoch = 0;

    public LedgerSoc(String deviceName) {
        this.deviceName = deviceName;
    }

    /** Returns the DeneWords to publish (empty until the device sends version 3 + a Power Profile). */
    public JSONArray update(JSONObject telepathon) {
        JSONArray out = new JSONArray();
        JSONObject vs = dene(telepathon, "Vital Signs");
        JSONObject pp = dene(telepathon, "Power Profile");
        if (vs == null || pp == null) return out;
        if (num(vs, "Vital Signs Version", 0) < 3) return out;

        double capacity = num(pp, "Battery Capacity", 0);
        if (capacity <= 0) return out;
        String chemistry = str(pp, "Battery Chemistry", "LiFePO4");
        int cells = (int) Math.max(1, num(pp, "Cells In Series", 1));
        double efficiencyPct = num(pp, "Charge Efficiency", 0);
        double efficiency = (efficiencyPct > 0 ? efficiencyPct : defaultEfficiencyPct(chemistry)) / 100.0;
        double sleepUa = num(pp, "Sleep Current", 0);

        long received = (long) num(vs, "Vital Signs Received Time", 0);
        long resetCount = (long) num(vs, "Reset Count", -1);
        double chargeIn = num(vs, "Charge In Total", 0);
        double dischargeOut = num(vs, "Discharge Out Total", 0);
        double slept = num(vs, "Slept Seconds Total", 0);
        double awake = num(vs, "Awake Seconds Total", 0);
        double measured = num(vs, "Ledger Measured Seconds", 0);

        double voltage = num(dene(telepathon, "Purpose"), "Battery Voltage", 0);
        double operatingStatus = num(dene(telepathon, "Purpose"), "Operating Status", -1);
        double wakeTimeSec = num(dene(telepathon, "Purpose"), "Wake Time Sec", 999);

        // ── ledger step, once per new Vital Signs record ─────────────────────────
        if (received > 0 && received != lastReceived) {
            boolean restarted = lastResetCount < 0 || resetCount != lastResetCount
                    || chargeIn < lastChargeIn || dischargeOut < lastDischargeOut;
            if (!restarted && chargeMah >= 0) {
                double dIn = chargeIn - lastChargeIn;
                double dOut = dischargeOut - lastDischargeOut;
                double dSlept = Math.max(0, slept - lastSlept);
                double dAwake = Math.max(0, awake - lastAwake);
                double dMeasured = Math.max(0, measured - lastMeasured);
                double awakeScale = dMeasured > 0 ? Math.min(MAX_AWAKE_SCALE, Math.max(1.0, dAwake / dMeasured)) : 1.0;
                double sleepMah = dSlept * sleepUa / 1000.0 / 3600.0;
                double dCharge = dIn * efficiency - dOut * awakeScale - sleepMah;
                chargeMah = clamp(chargeMah + dCharge, 0, capacity);
                logger.debug("LedgerSoc[" + deviceName + "]: in=" + dIn + " out=" + dOut + " x" + round(awakeScale, 2)
                        + " sleep=" + round(sleepMah, 3) + " -> " + round(dCharge, 3) + " mAh, now " + round(chargeMah, 1));
            } else if (restarted && lastResetCount >= 0) {
                logger.info("LedgerSoc[" + deviceName + "]: totals restarted (reset count " + lastResetCount + " -> " + resetCount + "), re-baselined");
            }
            lastReceived = received;
            lastResetCount = resetCount;
            lastChargeIn = chargeIn;
            lastDischargeOut = dischargeOut;
            lastSlept = slept;
            lastAwake = awake;
            lastMeasured = measured;
        }

        // ── anchors (same tiers as PulseTask) ─────────────────────────────────────
        if (voltage > 0) {
            double[][] curve = curve(chemistry);
            double cellV = voltage / cells;
            double reliableCellV = reliableZoneCellVoltage(chemistry);
            double flatMinCellV = flatZoneMinCellVoltage(chemistry);
            boolean atRest = operatingStatus == 0.0 || wakeTimeSec <= OCV_MAX_WAKE_SEC;
            long nowSec = System.currentTimeMillis() / 1000;
            long sinceAnchor = nowSec - lastAnchorEpoch;
            String reason = null;
            if (chargeMah < 0) reason = "bootstrap";
            else if (cellV >= reliableCellV && sinceAnchor >= RELIABLE_ANCHOR_INTERVAL_SEC) reason = "reliable zone";
            else if (cellV >= flatMinCellV && cellV < reliableCellV && atRest && sinceAnchor >= REST_ANCHOR_INTERVAL_SEC) reason = "rest-state OCV";
            if (reason != null) {
                double before = chargeMah;
                chargeMah = interpolate(curve, cellV) / 100.0 * capacity;
                lastAnchorEpoch = nowSec;
                lastAnchor = reason;
                logger.info("LedgerSoc[" + deviceName + "]: anchor [" + reason + "] " + voltage + " V = "
                        + round(chargeMah / capacity * 100, 1) + "%"
                        + (before >= 0 ? " (ledger had " + round(before / capacity * 100, 1) + "%)" : ""));
            }
        }

        if (chargeMah < 0) return out;
        out.put(word("Battery SOC Ledger %", round(chargeMah / capacity * 100.0, 1)));
        out.put(word("Battery Ledger Charge mAh", round(chargeMah, 1)));
        JSONObject anchorWord = new JSONObject();
        anchorWord.put("Name", "Battery Ledger Last Anchor");
        anchorWord.put("Value", lastAnchorEpoch);
        anchorWord.put("Value Type", "long");
        anchorWord.put("Required", true);
        out.put(anchorWord);
        return out;
    }

    // ── chemistry ───────────────────────────────────────────────────────────────

    private static double[][] curve(String chemistry) {
        if ("Lead-Acid".equals(chemistry)) return LEAD_ACID_CELL;
        if ("Li-Ion".equals(chemistry)) return LI_ION_CELL;
        return LIFEPO4_CELL;
    }

    private static double reliableZoneCellVoltage(String chemistry) {
        if ("Lead-Acid".equals(chemistry)) return 2.12;
        if ("Li-Ion".equals(chemistry)) return 4.10;
        return 3.30;
    }

    private static double flatZoneMinCellVoltage(String chemistry) {
        if ("Lead-Acid".equals(chemistry)) return 1.90;
        if ("Li-Ion".equals(chemistry)) return 3.55;
        return 3.05;
    }

    private static double defaultEfficiencyPct(String chemistry) {
        if ("Lead-Acid".equals(chemistry)) return 85;
        return 98;   // lithium chemistries
    }

    private static double interpolate(double[][] table, double v) {
        if (v >= table[0][0]) return table[0][1];
        if (v <= table[table.length - 1][0]) return table[table.length - 1][1];
        for (int i = 0; i < table.length - 1; i++) {
            if (v <= table[i][0] && v >= table[i + 1][0]) {
                double ratio = (v - table[i + 1][0]) / (table[i][0] - table[i + 1][0]);
                return table[i + 1][1] + ratio * (table[i][1] - table[i + 1][1]);
            }
        }
        return 0;
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private static JSONObject dene(JSONObject chain, String name) {
        if (chain == null) return null;
        JSONArray denes = chain.optJSONArray("Denes");
        if (denes == null) return null;
        for (int i = 0; i < denes.length(); i++) {
            JSONObject d = denes.optJSONObject(i);
            if (d != null && name.equals(d.optString("Name"))) return d;
        }
        return null;
    }

    private static double num(JSONObject dene, String word, double dflt) {
        if (dene == null) return dflt;
        JSONArray words = dene.optJSONArray("DeneWords");
        if (words == null) return dflt;
        for (int i = 0; i < words.length(); i++) {
            JSONObject w = words.optJSONObject(i);
            if (w != null && word.equals(w.optString("Name"))) {
                try { return Double.parseDouble(String.valueOf(w.get("Value"))); } catch (Exception e) { return dflt; }
            }
        }
        return dflt;
    }

    private static String str(JSONObject dene, String word, String dflt) {
        if (dene == null) return dflt;
        JSONArray words = dene.optJSONArray("DeneWords");
        if (words == null) return dflt;
        for (int i = 0; i < words.length(); i++) {
            JSONObject w = words.optJSONObject(i);
            if (w != null && word.equals(w.optString("Name"))) return String.valueOf(w.opt("Value"));
        }
        return dflt;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static double round(double v, int digits) {
        double f = Math.pow(10, digits);
        return Math.round(v * f) / f;
    }

    private static JSONObject word(String name, double value) {
        JSONObject w = new JSONObject();
        w.put("Name", name);
        w.put("Value", value);
        w.put("Value Type", "double");
        w.put("Required", true);
        return w;
    }
}

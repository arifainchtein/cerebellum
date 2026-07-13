package com.teleonome.cerebellum.pulsetask;




import com.teleonome.cerebellum.Task;
import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs on every LoRa pulse ("Every Pulse" frequency).
 *
 * All calculations that need sub-hourly refresh live here. Each output word
 * has its own internal cadence — some update every pulse, others every N pulses.
 * Add new per-pulse calculations as private methods called from process().
 *
 * SOC approach — three anchor tiers:
 *
 *   1. Bootstrap (first pulse): anchor from voltage table immediately, even in
 *      the flat zone. The absolute value may be ±20% off but coulomb counting
 *      starts right away.
 *
 *   2. Reliable zone (voltage >= 3.30V): table is accurate regardless of load.
 *      Always update anchor — handles both charging and discharging in that zone.
 *
 *   3. Rest-state OCV (voltage 3.05–3.30V): terminal ≈ OCV when:
 *        - operatingStatus == 0 (night/sleep mode, no solar input), OR
 *        - wakeTimeSec <= OCV_MAX_WAKE_SEC (brief LoRa wake, minimal load effect).
 *      Used to recalibrate coulomb counter on cloudy days when voltage never
 *      rises above 3.30V. Updates anchor in both directions (allows drift correction).
 *
 * Coulomb counting resets to zero on every anchor update so accumulated drift
 * doesn't compound across multiple recalibrations.
 */
public class PulseTask implements Task {

    // ── LiFePO4 parameters (must match SolarAnalysis) ─────────────────────────
    private static final double BATTERY_CAPACITY_MAH    = 600.0;
    private static final double RELIABLE_ANCHOR_VOLTAGE = 3.30;
    private static final double OCV_ANCHOR_MIN_VOLTAGE  = 3.05; // below this the table is too flat
    private static final double OCV_MAX_WAKE_SEC        = 10.0; // brief wake → terminal ≈ OCV

    private final String teleonomeName;
    private final String deviceName;
    private final Logger logger;

    // ── SOC state ──────────────────────────────────────────────────────────────
    private double anchorSocPct        = -1;  // SOC % at last anchor; -1 = not yet set
    private double anchorMah           = -1;  // mAh at last anchor
    private double accumulatedDeltaMah =  0;  // net charge delta since anchor (+ = discharged)
    private long   lastPulseEpochSec   =  0;

    private int pulseCount = 0;

    public PulseTask(String teleonomeName, String deviceName) {
        this.teleonomeName = teleonomeName;
        this.deviceName = deviceName;
        this.logger = Logger.getLogger(getClass());
    }

    @Override public String getName()       { return "PulseTask"; }
    @Override public String getDeviceName() { return deviceName; }

    @Override
    public JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
        pulseCount++;

        double voltage         = getDeneWordDouble(telepathon, "Purpose", "Battery Voltage");
        double currentMa       = getDeneWordDouble(telepathon, "Purpose", "Battery Current");
        long   epochSec        = (long) getDeneWordDouble(telepathon, "Purpose", "Seconds Time");
        double operatingStatus = getDeneWordDouble(telepathon, "Purpose", "Operating Status");
        double wakeTimeSec     = getDeneWordDouble(telepathon, "Purpose", "Wake Time Sec");

        if (voltage <= 0 || epochSec <= 0) return new JSONArray();

        // ── Anchor management ──────────────────────────────────────────────────

        // Tier 1: bootstrap on the very first pulse.
        if (anchorSocPct < 0) {
            setAnchor(voltage, epochSec, "bootstrap");
        }
        // Tier 2: reliable zone — table is accurate here; always refresh.
        else if (voltage >= RELIABLE_ANCHOR_VOLTAGE) {
            setAnchor(voltage, epochSec, "reliable zone");
        }
        // Tier 3: flat zone but device is at rest — terminal voltage ≈ OCV.
        // Recalibrates coulomb counter on cloudy days and overnight.
        else if (voltage >= OCV_ANCHOR_MIN_VOLTAGE && isRestState(operatingStatus, wakeTimeSec)) {
            setAnchor(voltage, epochSec, "rest-state OCV");
        }

        // ── Incremental coulomb counting ───────────────────────────────────────

        if (lastPulseEpochSec > 0 && epochSec > lastPulseEpochSec) {
            double deltaHrs = (epochSec - lastPulseEpochSec) / 3600.0;
            accumulatedDeltaMah += currentMa * deltaHrs;
        }
        lastPulseEpochSec = epochSec;

        // ── Live SOC ───────────────────────────────────────────────────────────

        double liveMah = Math.max(0, anchorMah - accumulatedDeltaMah);
        double socPct  = Math.max(0, Math.min(100,
                Math.round(liveMah / BATTERY_CAPACITY_MAH * 1000.0) / 10.0));

        logger.debug("PulseTask[" + deviceName + "]: pulse=" + pulseCount
                + " V=" + voltage + " I=" + currentMa + "mA status=" + operatingStatus
                + " wake=" + wakeTimeSec + "s anchor=" + anchorSocPct
                + "% delta=" + Math.round(accumulatedDeltaMah * 10.0) / 10.0
                + "mAh SOC=" + socPct + "%");

        JSONArray words = new JSONArray();
        words.put(deneWord("Battery SOC Live %", socPct, "double"));
        return words;
    }

    // ── Anchor helpers ─────────────────────────────────────────────────────────

    private void setAnchor(double voltage, long epochSec, String reason) {
        anchorSocPct        = estimateSocPct(voltage);
        anchorMah           = anchorSocPct / 100.0 * BATTERY_CAPACITY_MAH;
        accumulatedDeltaMah = 0;
        lastPulseEpochSec   = epochSec;
        logger.info("PulseTask[" + deviceName + "]: anchor [" + reason + "] "
                + voltage + "V = " + anchorSocPct + "%");
    }

    /**
     * Returns true when terminal voltage is close enough to OCV to serve as an
     * anchor in the flat zone (3.05–3.30V):
     *  - Night/sleep mode: no solar input, stable low-current conditions.
     *  - Brief wake (wakeTimeSec <= OCV_MAX_WAKE_SEC): device just woke for LoRa;
     *    the short load pulse has minimal effect on battery voltage.
     */
    private boolean isRestState(double operatingStatus, double wakeTimeSec) {
        return operatingStatus == 0.0 || wakeTimeSec <= OCV_MAX_WAKE_SEC;
    }

    // ── Voltage → SOC (1S LiFePO4, matches SolarAnalysis table) ─────────────

    private double estimateSocPct(double voltage) {
        double[][] table = {
            {3.65, 100}, {3.50, 99}, {3.45, 95}, {3.40, 90},
            {3.35,  80}, {3.30, 70}, {3.25, 60}, {3.20, 50},
            {3.15,  40}, {3.10, 30}, {3.05, 20}, {3.00, 10},
            {2.90,   5}, {2.80,  2}, {2.50,  0}
        };
        if (voltage >= table[0][0])                return table[0][1];
        if (voltage <= table[table.length - 1][0]) return table[table.length - 1][1];
        for (int i = 0; i < table.length - 1; i++) {
            if (voltage <= table[i][0] && voltage >= table[i + 1][0]) {
                double ratio = (voltage - table[i + 1][0]) / (table[i][0] - table[i + 1][0]);
                return table[i + 1][1] + ratio * (table[i][1] - table[i + 1][1]);
            }
        }
        return 0;
    }

    // ── Telepathon navigation ─────────────────────────────────────────────────

    private double getDeneWordDouble(JSONObject deneChain, String deneName, String wordName) {
        try {
            JSONArray denes = deneChain.getJSONArray("Denes");
            for (int i = 0; i < denes.length(); i++) {
                JSONObject dene = denes.getJSONObject(i);
                if (deneName.equals(dene.getString("Name"))) {
                    JSONArray words = dene.getJSONArray("DeneWords");
                    for (int j = 0; j < words.length(); j++) {
                        JSONObject word = words.getJSONObject(j);
                        if (wordName.equals(word.getString("Name"))) {
                            return word.getDouble("Value");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return 0.0;
    }

    private JSONObject deneWord(String name, Object value, String valueType) {
        JSONObject w = new JSONObject();
        w.put("Name", name);
        w.put("Value", value);
        w.put("Value Type", valueType);
        w.put("Required", true);
        return w;
    }
}

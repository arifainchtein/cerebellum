package com.teleonome.cerebellum.graveyardshift;

import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import com.teleonome.cerebellum.Task;
import com.teleonome.framework.TeleonomeConstants;
import com.teleonome.framework.utils.Utils;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.*;

                                     


/**
 * Stateful task: accumulates readings across pulses, then at sunset produces
 * two complementary SOC estimates and a TX-schedule forecast to sunrise.
 *
 * SOC methods:
 *   - Voltage-based: piecewise linear LiFePO4 curve. Fast but unreliable in
 *     the flat 3.0–3.4V region where a small voltage span covers 10%–70% SOC.
 *   - Coulomb-based: integrates (batteryCurrent × activeTime) + (sleepCurrent
 *     × sleepTime) from the daily charge peak forward to sunset. More accurate
 *     in absolute terms; accuracy improves further once the firmware adds a
 *     wakeTimeSeconds field to each LoRa packet.
 *
 * WiFi burst records (Δt < MIN_LORA_INTERVAL_SECONDS) are excluded from the
 * coulomb integral because their Δt is meaningless for the calculation.
 */
public class GraveyardShift implements Task {

    private static final String TIMEZONE                     = "Australia/Melbourne";
    private static final double FALLBACK_DISCHARGE_V_PER_HOUR = 0.02; // 1S LiFePO4 typical night draw
    private static final int    MAX_HISTORY                  = 128;   // covers a full day at 15-min intervals
    private static final double BATTERY_CAPACITY_MAH         = 600.0; // firmware constant BATTERY_CAPACITY_MAH
    private static final double SLEEP_CURRENT_MA             = 0.3;   // deep-sleep draw (µA range → ~0.3 mA effective)
    private static final long   MIN_LORA_INTERVAL_SECONDS    = 60;    // shorter Δt = WiFi burst, excluded from integral
    // Voltage threshold above the flat LiFePO4 plateau — at or above this value
    // the voltage→SOC mapping is steep enough to serve as a reliable anchor.
    private static final double RELIABLE_ANCHOR_VOLTAGE      = 3.30;  // ≥70% SOC, leaves flat region
    // wakeTimeSec is stored as uint8_t in Daffodil firmware — it saturates at 255.
    // A value of 255 means "active phase was at least 255 seconds". Consecutive
    // records with wakeTimeSec=255 belong to the same long wake session (WiFi upload).
    private static final int    WAKE_TIME_SEC_SATURATED      = 255;

    private final Logger logger = Logger.getLogger(getClass());
    private final String deviceName;

    // Each entry: [timeSeconds, voltage (V), batteryCurrent (mA), sleepTime (s), wakeTimeSec]
    // wakeTimeSec = firmware "Wake Time Sec" DeneWord — exact active-phase duration.
    // 0 means the field was absent (old firmware); coulomb integral falls back to (Δt − sleepTime).
    private final List<double[]> history = new ArrayList<>();

    // Best coulomb anchor seen across recent days.
    // Persisted across invocations so a clear-sky noon calibrates cloudy-day evenings.
    // [timeSeconds, SOC%] — only updated when voltage breaks above RELIABLE_ANCHOR_VOLTAGE.
    private double[] bestAnchor = null;

    public GraveyardShift(String deviceName) {
        this.deviceName = deviceName;
    }

    @Override
    public String getName() { return "GraveyardShift"; }

    @Override
    public String getDeviceName() { return deviceName; }

    @Override
    public JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
    	logger.debug("line 74,telepathon=" + telepathon.toString(4) );
        double voltage        = getDeneWordDouble(telepathon, "Purpose", "Battery Voltage");
        double batteryCurrent = getDeneWordDouble(telepathon, "Purpose", "Battery Current");
        int    sleepSec       = (int) getDeneWordDouble(telepathon, "Purpose", "Sleep Time");
        double wakeTimeSec    = getDeneWordDouble(telepathon, "Purpose", "Wake Time Sec");
        double lat           = getDeneWordDouble(telepathon, "Configuration", "Latitude");
        double lon           = getDeneWordDouble(telepathon, "Configuration", "longitude");
        long   timeSeconds   = telepathon.optLong("Seconds Time", System.currentTimeMillis() / 1000);
        logger.debug("Starting to process GraveyardShift");
        if (voltage == 0 || lat == 0) {
            logger.debug("GraveyardShift[" + deviceName + "]: skipping — voltage=" + voltage + " lat=" + lat);
            return new JSONArray();
        }

        history.add(new double[]{timeSeconds, voltage, batteryCurrent, sleepSec, wakeTimeSec});
        if (history.size() > MAX_HISTORY) history.remove(0);
        logger.debug("GraveyardShift[" + deviceName + "]: recorded V=" + voltage
                + " I=" + batteryCurrent + "mA sleep=" + sleepSec + "s wake=" + wakeTimeSec
                + "s  history=" + history.size() + " records");

        // Update the reliable anchor whenever voltage breaks above the flat LiFePO4 plateau.
        // This persists across days so a clear-sky noon calibrates cloudy-day evenings.
        if (voltage >= RELIABLE_ANCHOR_VOLTAGE) {
            double anchorSoc = estimateSocPct(voltage);
            if (bestAnchor == null || anchorSoc > bestAnchor[1]) {
                bestAnchor = new double[]{timeSeconds, anchorSoc};
                logger.info("GraveyardShift[" + deviceName + "]: new reliable anchor "
                        + voltage + "V = " + anchorSoc + "% SOC");
            }
        }

        Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        Location loc = new Location(String.valueOf(lat), String.valueOf(lon));
        SunriseSunsetCalculator calc = new SunriseSunsetCalculator(loc, TIMEZONE);
        Calendar sunsetCal  = calc.getOfficialSunsetCalendarForDate(now);
        Calendar tomorrow   = (Calendar) now.clone();
        tomorrow.add(Calendar.DAY_OF_MONTH, 1);
        Calendar sunriseCal = calc.getOfficialSunriseCalendarForDate(tomorrow);

        long sunsetEpoch  = sunsetCal.getTimeInMillis()  / 1000;
        long sunriseEpoch = sunriseCal.getTimeInMillis() / 1000;

        logger.info("GraveyardShift[" + deviceName + "]: FIRING — history=" + history.size()
                + " cycles=" + wakeCycleRepresentatives().size()
                + " anchor=" + (bestAnchor != null ? bestAnchor[1] + "%" : "none (voltage fallback)"));

        // ── Voltage-based forecast ──────────────────────────────────────────
        double dischargeVPerHour = calcDischargeRate();
        if (sleepSec <= 30) sleepSec = 900;

        double[] anchor = findLatestBefore(sunsetEpoch);
        if (anchor == null) anchor = history.get(history.size() - 1);
        double hoursAnchorToSunset = (sunsetEpoch - anchor[0]) / 3600.0;
        double voltageAtSunset  = anchor[1] - dischargeVPerHour * hoursAnchorToSunset;
        double hoursOvernight   = (sunriseEpoch - sunsetEpoch) / 3600.0;
        double voltageAtSunrise = voltageAtSunset - dischargeVPerHour * hoursOvernight;
        double socVoltSunset    = estimateSocPct(voltageAtSunset);
        double socVoltSunrise   = estimateSocPct(voltageAtSunrise);

        logger.info("GraveyardShift[" + deviceName + "]: voltage forecast — dischargeRate="
                + String.format("%.4f", dischargeVPerHour) + "V/hr"
                + " voltAtSunset=" + String.format("%.3f", voltageAtSunset) + "V"
                + " socAtSunset=" + String.format("%.1f", socVoltSunset) + "%"
                + " voltAtSunrise=" + String.format("%.3f", voltageAtSunrise) + "V"
                + " socAtSunrise=" + String.format("%.1f", socVoltSunrise) + "%");

        // ── Coulomb-based SOC at sunset ─────────────────────────────────────
        double coulombSocAtSunset = computeCoulombSocAtSunset(sunsetEpoch);
        double coulombMahAtSunset = coulombSocAtSunset / 100.0 * BATTERY_CAPACITY_MAH;
        logger.info("GraveyardShift[" + deviceName + "]: coulomb forecast — soc="
                + coulombSocAtSunset + "% mAh=" + String.format("%.1f", coulombMahAtSunset)
                + " anchorReliable=" + (bestAnchor != null));

        // TX cycles
        int txCycles = 0;
        for (long t = sunsetEpoch; t <= sunriseEpoch; t += sleepSec) txCycles++;

        printForecast(sunsetCal, sunriseCal, sunsetEpoch, sunriseEpoch,
                voltageAtSunset, voltageAtSunrise, dischargeVPerHour, anchor, sleepSec,
                socVoltSunset, socVoltSunrise, coulombSocAtSunset, coulombMahAtSunset);

        JSONArray words = new JSONArray();
        words.put(deneWord("Graveyard Voltage At Sunset",     voltageAtSunset,    "Double"));
        words.put(deneWord("Graveyard Voltage At Sunrise",    voltageAtSunrise,   "Double"));
        words.put(deneWord("Graveyard SOC At Sunset",         socVoltSunset,      "Double"));
        words.put(deneWord("Graveyard SOC At Sunrise",        socVoltSunrise,     "Double"));
        words.put(deneWord("Graveyard Coulomb SOC At Sunset", coulombSocAtSunset, "Double"));
        words.put(deneWord("Graveyard Coulomb mAh At Sunset", coulombMahAtSunset, "Double"));
        words.put(deneWord("Graveyard Anchor Reliable",
                bestAnchor != null && bestAnchor[1] >= estimateSocPct(RELIABLE_ANCHOR_VOLTAGE) ? 1.0 : 0.0,
                "Integer"));
        words.put(deneWord("Graveyard Discharge Rate",        dischargeVPerHour,  "Double"));
        words.put(deneWord("Graveyard Night Duration",        hoursOvernight,     "Double"));
        words.put(deneWord("Graveyard TX Cycles",             (double) txCycles,  "Integer"));
        words.put(deneWordStr("Graveyard Schedule",
                buildScheduleJson(sunsetEpoch, sunriseEpoch, voltageAtSunset, dischargeVPerHour, sleepSec)));

        // Build the Annabelle LoRa command string. Cerebellum will carry this to the
        // cerebellumStatus alongside the Annabelle Action Pointer from the task Dene,
        // and updateCerebellumPurposeDene() will activate the actuator action generically.
        String command = buildAnnabelleCommand(deviceName, voltageAtSunset, socVoltSunset,
                coulombSocAtSunset, coulombMahAtSunset, dischargeVPerHour,
                hoursOvernight, txCycles, voltageAtSunrise, socVoltSunrise,
                bestAnchor != null ? 1 : 0);
        words.put(deneWordStr(TeleonomeConstants.DENEWORD_ANNABELLE_COMMAND, command));
        return words;
    }

    // ── Coulomb counting ──────────────────────────────────────────────────────

    /**
     * Integrates charge from the day's peak voltage (anchor) forward to sunset.
     *
     * Anchor SOC is estimated from voltage at the highest-voltage LoRa record —
     * the closest available proxy to full charge. From there, each cycle's
     * mAh draw is:
     *   activePhase  = batteryCurrent × (Δt − sleepTime) / 3600
     *   sleepPhase   = SLEEP_CURRENT_MA × sleepTime / 3600
     *
     * WiFi burst records (Δt < MIN_LORA_INTERVAL_SECONDS) are excluded because
     * their Δt is unreliable for the integral.
     *
     * Wake Time Sec from the firmware gives the exact active-phase duration.
     * When absent (old firmware, value = 0), falls back to (Δt − sleepTime).
     */
    private double computeCoulombSocAtSunset(long sunsetEpoch) {
        List<double[]> cycles = wakeCycleRepresentatives();
        if (cycles.size() < 2) return estimateSocPct(peakVoltage(history));

        // Prefer the best persistent anchor (a clear-sky noon above the flat LiFePO4
        // plateau) over today's peak, which may sit in the unreliable flat region on
        // cloudy days. Falls back to today's voltage peak if no reliable anchor exists.
        long   anchorTime;
        double anchorSoc;
        if (bestAnchor != null) {
            anchorTime = (long) bestAnchor[0];
            anchorSoc  = bestAnchor[1];
        } else {
            int anchorIdx = 0;
            double maxV = 0;
            for (int i = 0; i < cycles.size(); i++) {
                if (cycles.get(i)[1] > maxV) { maxV = cycles.get(i)[1]; anchorIdx = i; }
            }
            anchorTime = (long) cycles.get(anchorIdx)[0];
            anchorSoc  = estimateSocPct(maxV);
        }
        double runningMah = anchorSoc / 100.0 * BATTERY_CAPACITY_MAH;

        // Integrate one entry per wake cycle from anchor to sunset.
        // wakeT=255 means the active phase was ≥255s (uint8_t cap) — coulomb count
        // for that cycle is a minimum bound; actual draw was equal or higher.
        for (int i = 0; i < cycles.size(); i++) {
            double[] cycle = cycles.get(i);
            if (cycle[0] <= anchorTime) continue;
            if (cycle[0] > sunsetEpoch) break;

            double wakeT  = cycle[4]; // max wakeTimeSec (255 = saturated, ≥255s)
            double sleepT = cycle[3]; // configured sleep after this wake
            double mAh    = (cycle[2] * wakeT  / 3600.0)
                          + (SLEEP_CURRENT_MA * sleepT / 3600.0);
            runningMah -= mAh;
        }

        return Math.max(0, Math.min(100, Math.round((runningMah / BATTERY_CAPACITY_MAH) * 1000.0) / 10.0));
    }

    /**
     * Groups raw history records into wake cycles using wakeTimeSec monotonicity,
     * then returns one representative record per cycle.
     *
     * When wakeTimeSec is available (new firmware): records belong to the same
     * wake cycle while wakeTimeSec keeps increasing. A reset (curr <= prev) marks
     * a new cycle. Representative fields:
     *   [0] timeSeconds  — last record in cycle (latest timestamp)
     *   [1] voltage      — last record (most recent reading)
     *   [2] current      — averaged across cycle (smooths WiFi burst spikes)
     *   [3] sleepTime    — max across cycle (burst records have 0; normal LoRa has real value)
     *   [4] wakeTimeSec  — max across cycle (= total active duration)
     *
     * When wakeTimeSec is 0 throughout (old firmware): falls back to grouping by
     * Δt < MIN_LORA_INTERVAL_SECONDS, and sets representative wakeTimeSec to 0
     * so the caller can apply the (Δt − sleepTime) fallback.
     */
    private List<double[]> wakeCycleRepresentatives() {
        if (history.isEmpty()) return new ArrayList<>();

        boolean newFirmware = history.stream().anyMatch(r -> r[4] > 0);
        List<double[]> result = new ArrayList<>();
        int cycleStart = 0;

        for (int i = 1; i <= history.size(); i++) {
            boolean endOfCycle;
            if (i == history.size()) {
                endOfCycle = true;
            } else if (newFirmware) {
                // New cycle when wakeTimeSec drops (millis() reset after deep sleep)
                // OR when the time gap exceeds wakeTimeSec + 30s tolerance — meaning
                // the device must have slept between records even if wakeTimeSec didn't
                // drop (e.g. wake 1 ends at 25s, wake 2 first packet also shows ~30s).
                // Two 255-saturated records 17s apart → 17 ≤ 285 → same cycle ✓.
                double currWake = history.get(i)[4];
                double dt       = history.get(i)[0] - history.get(i - 1)[0];
                endOfCycle = currWake < history.get(i - 1)[4] || dt > currWake + 30;
            } else {
                endOfCycle = (history.get(i)[0] - history.get(i - 1)[0]) >= MIN_LORA_INTERVAL_SECONDS;
            }

            if (endOfCycle) {
                double[] last = history.get(i - 1);
                double sumCurrent = 0, maxWake = 0, maxSleep = 0;
                for (int j = cycleStart; j < i; j++) {
                    sumCurrent += history.get(j)[2];
                    maxWake    = Math.max(maxWake,  history.get(j)[4]);
                    maxSleep   = Math.max(maxSleep, history.get(j)[3]);
                }
                double[] rep = last.clone();
                rep[2] = sumCurrent / (i - cycleStart); // averaged current
                rep[3] = maxSleep;                       // real sleepTime (0 for pure bursts)
                rep[4] = maxWake;                        // total active duration
                result.add(rep);
                cycleStart = i;
            }
        }
        return result;
    }

    private double peakVoltage(List<double[]> records) {
        return records.stream().mapToDouble(r -> r[1]).max().orElse(0);
    }

    // ── Discharge rate (voltage-based) ────────────────────────────────────────

    private double calcDischargeRate() {
        List<double[]> loraRecords = wakeCycleRepresentatives();
        List<Double> rates = new ArrayList<>();
        for (int i = 1; i < loraRecords.size(); i++) {
            double dt   = (loraRecords.get(i)[0] - loraRecords.get(i - 1)[0]) / 3600.0;
            double drop = loraRecords.get(i - 1)[1] - loraRecords.get(i)[1];
            if (dt > 0 && drop > 0 && drop < 0.5) rates.add(drop / dt);
        }
        if (rates.isEmpty()) return FALLBACK_DISCHARGE_V_PER_HOUR;
        return rates.stream().mapToDouble(v -> v).average().orElse(FALLBACK_DISCHARGE_V_PER_HOUR);
    }

    private double[] findLatestBefore(long targetEpoch) {
        for (int i = history.size() - 1; i >= 0; i--) {
            if (history.get(i)[0] <= targetEpoch) return history.get(i);
        }
        return null;
    }

    // ── Voltage → SOC lookup (1S LiFePO4) ────────────────────────────────────

    /**
     * Piecewise linear approximation of the LiFePO4 discharge curve for a
     * single cell (2.50–3.65V). All Daffodil devices are 1S so raw pack
     * voltage equals cell voltage.
     */
    private double estimateSocPct(double voltage) {
        double[][] table = {
            {3.65, 100}, {3.50, 99}, {3.45, 95}, {3.40, 90},
            {3.35, 80},  {3.30, 70}, {3.25, 60}, {3.20, 50},
            {3.15, 40},  {3.10, 30}, {3.05, 20}, {3.00, 10},
            {2.90,  5},  {2.80,  2}, {2.50,   0}
        };
        if (voltage >= table[0][0])                return table[0][1];
        if (voltage <= table[table.length - 1][0]) return table[table.length - 1][1];
        for (int i = 0; i < table.length - 1; i++) {
            if (voltage <= table[i][0] && voltage >= table[i + 1][0]) {
                double ratio = (voltage - table[i + 1][0]) / (table[i][0] - table[i + 1][0]);
                return Math.round((table[i + 1][1] + ratio * (table[i][1] - table[i + 1][1])) * 10.0) / 10.0;
            }
        }
        return 0;
    }

    // ── Stdout forecast ───────────────────────────────────────────────────────

    private void printForecast(Calendar sunsetCal, Calendar sunriseCal,
                               long sunsetEpoch, long sunriseEpoch,
                               double voltageAtSunset, double voltageAtSunrise,
                               double dischargeVPerHour, double[] anchor, int sleepSec,
                               double socVoltSunset, double socVoltSunrise,
                               double coulombSocAtSunset, double coulombMahAtSunset) {
        SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm");
        timeFmt.setTimeZone(TimeZone.getTimeZone(TIMEZONE));
        SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd");
        dateFmt.setTimeZone(TimeZone.getTimeZone(TIMEZONE));

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.printf( "║  GRAVEYARD SHIFT  — %-41s║%n", deviceName);
        System.out.printf( "║  %s sunset  →  %s sunrise%20s║%n",
                dateFmt.format(sunsetCal.getTime()), dateFmt.format(sunriseCal.getTime()), "");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.printf("  Sunset:       %s        Voltage at sunset:  %.3fV%n",
                timeFmt.format(sunsetCal.getTime()), voltageAtSunset);
        System.out.printf("  Sunrise:      %s        Discharge rate:     %.4f V/hr%n",
                timeFmt.format(sunriseCal.getTime()), dischargeVPerHour);
        System.out.printf("  Sleep period: %d s       Anchor reading:     %s (%.3fV)%n",
                sleepSec, timeFmt.format(new Date((long) anchor[0] * 1000L)), anchor[1]);
        System.out.println();
        String anchorNote = (bestAnchor != null) ? String.format("anchor %.0f%%", bestAnchor[1]) : "no reliable anchor — voltage fallback";
        System.out.printf("  SOC at sunset  →  voltage-based: %.0f%%   coulomb-based: %.0f%%  (%.0f mAh)  [%s]%n",
                socVoltSunset, coulombSocAtSunset, coulombMahAtSunset, anchorNote);
        System.out.println();
        System.out.printf("  %-5s  %-6s  %-9s  %s%n", "TX#", "Time", "Voltage", "Notes");
        System.out.println("  ──────────────────────────────────────────────────");

        long   t    = sunsetEpoch;
        double v    = voltageAtSunset;
        int    txNum = 0;
        while (t <= sunriseEpoch) {
            String note = txNum == 0 ? "sunset"
                    : Math.abs(t - sunriseEpoch) <= sleepSec ? "→ sunrise" : "";
            Calendar txCal = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
            txCal.setTimeInMillis(t * 1000L);
            System.out.printf("  %-5d  %-6s  %7.3fV   %s%n",
                    txNum, timeFmt.format(txCal.getTime()), v, note);
            txNum++;
            t += sleepSec;
            v -= dischargeVPerHour * (sleepSec / 3600.0);
        }
        System.out.println("  ──────────────────────────────────────────────────");
        System.out.printf("  Night duration:        %.1f hrs%n", (sunriseEpoch - sunsetEpoch) / 3600.0);
        System.out.printf("  Total TX cycles:       %d%n", txNum);
        System.out.printf("  Projected drop:        %.3fV%n", voltageAtSunset - voltageAtSunrise);
        System.out.printf("  Projected sunrise V:   %.3fV  (voltage SOC: %.0f%%)%n",
                voltageAtSunrise, socVoltSunrise);
        System.out.println();
    }

    // ── Telepathon navigation ─────────────────────────────────────────────────

    private double getDeneWordDouble(JSONObject deneChain, String deneName, String wordName) {
        try {
        	   logger.debug("line 408,deneName=" + deneName + " wordName=" + wordName);
            JSONArray denes = deneChain.getJSONArray("Denes");
            logger.debug("line 410,denes=" + denes.length());
            for (int i = 0; i < denes.length(); i++) {
                JSONObject dene = denes.getJSONObject(i);
                logger.debug("line 413,dene=" + dene.getString("Name"));
                if (deneName.equals(dene.getString("Name"))) {
                    JSONArray words = dene.getJSONArray("DeneWords");
                    logger.debug("line 416,words=" + words.length());
                    for (int j = 0; j < words.length(); j++) {
                        JSONObject word = words.getJSONObject(j);
                        logger.debug("line 419 words=" + word.getString("Name"));
                        if (wordName.equals(word.getString("Name"))) {
                        	
                        	//double d=Double.parseDouble(word.getString("Value"));
                        	double d=word.getDouble("Value");
                        	logger.debug("line 421 returning=" + d);
                            return d;
                            
                        }
                    }
                }
            }
        } catch (Exception e) {
        	logger.warn(Utils.getStringException(e));
        }
        return 0.0;
    }

    private String buildScheduleJson(long sunsetEpoch, long sunriseEpoch,
                                     double voltageAtSunset, double dischargeVPerHour, int sleepSec) {
        JSONArray schedule = new JSONArray();
        long   t = sunsetEpoch;
        double v = voltageAtSunset;
        while (t <= sunriseEpoch) {
            JSONObject entry = new JSONObject();
            entry.put("millis",  t * 1000L);
            entry.put("voltage", Math.round(v * 1000.0) / 1000.0);
            schedule.put(entry);
            t += sleepSec;
            v -= dischargeVPerHour * (sleepSec / 3600.0);
        }
        return schedule.toString();
    }

    private String buildAnnabelleCommand(String deviceName,
            double voltageAtSunset, double socAtSunset,
            double coulombSoc, double coulombMah,
            double dischargeRate, double nightHours, int txCycles,
            double voltageAtSunrise, double socAtSunrise, int anchorReliable) {
        return String.format("%s#%s#%d#%d#%d#%d#%d#%d#%d#%d#%d#%d",
                TeleonomeConstants.COMMAND_SEND_GRAVEYARD_SHIFT,
                deviceName,
                (int)(voltageAtSunset  * 1000),
                (int) socAtSunset,
                (int) coulombSoc,
                (int) coulombMah,
                (int)(dischargeRate   * 1000),
                (int)(nightHours      * 6),
                txCycles,
                (int)(voltageAtSunrise * 1000),
                (int) socAtSunrise,
                anchorReliable);
    }

    private JSONObject deneWord(String name, double value, String type) {
        JSONObject w = new JSONObject();
        w.put("Name",  name);
        w.put("Value", String.valueOf(value));
        w.put("Type",  type);
        return w;
    }

    private JSONObject deneWordStr(String name, String value) {
        JSONObject w = new JSONObject();
        w.put("Name",  name);
        w.put("Value", value);
        w.put("Type",  "String");
        return w;
    }
}

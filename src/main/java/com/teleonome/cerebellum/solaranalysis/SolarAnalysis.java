package com.teleonome.cerebellum.solaranalysis;



import com.luckycatlabs.sunrisesunset.SunriseSunsetCalculator;
import com.luckycatlabs.sunrisesunset.dto.Location;
import com.teleonome.cerebellum.Task;
import com.teleonome.framework.utils.Utils;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.*;

/**
 * Stateful task that runs three times per day at hours [12, 15, "Sunset"] via
 * the "Hours In A Day" execution frequency.
 *
 * Each run mode accumulates data and produces different DeneWords:
 *
 *   12h  — Midday health check: coulomb SOC so far + projected SOC at sunset.
 *           Early warning if the afternoon trajectory looks bad.
 *
 *   15h  — Afternoon check: same projection with more data + updated lux trend.
 *           More urgent alert threshold than the noon run.
 *
 *   Sunset — Final analysis: day mode percentages (Active/Cloudy/Sleep),
 *             lux correlation, and definitive coulomb SOC at sunset.
 *             This SOC value is the canonical handoff to GraveyardShift.
 *
 * Weather forecast integration: the projected SOC at 12h/15h currently uses
 * measured average discharge rate. When weather forecast data (cloudiness % per
 * 3-hour slot from OpenWeatherMap) becomes available in the Teleonome pulse,
 * the TODO block in projectSocToSunset() can be replaced with a solar-harvest
 * estimate that accounts for expected cloud cover.
 */
public class SolarAnalysis implements Task {

    private static final String TIMEZONE                  = "Australia/Melbourne";
    private static final int    STATUS_ACTIVE             = 1;
    private static final int    STATUS_CLOUDY             = 4;
    private static final int    STATUS_SLEEP              = 0;
    private static final double BATTERY_CAPACITY_MAH      = 600.0;
    private static final double SLEEP_CURRENT_MA          = 0.3;
    private static final long   MIN_LORA_INTERVAL_SECONDS = 60;
    private static final double RELIABLE_ANCHOR_VOLTAGE   = 3.30;
    // wakeTimeSec is stored as uint8_t in Daffodil firmware — saturates at 255.
    // Two consecutive records with wakeTimeSec=255 belong to the same WiFi session.
    private static final int    WAKE_TIME_SEC_SATURATED   = 255;
    private static final double LOW_BATTERY_ALERT_PCT     = 20.0; // warn if projected SOC < this

    private final Logger logger = Logger.getLogger(getClass());
    private final String deviceName;

    // Each entry: [timeSeconds, voltage, batteryCurrent_mA, sleepTime_s, operatingStatus, lux, wakeTimeSec]
    // wakeTimeSec = firmware "Wake Time Sec" DeneWord — exact active-phase duration.
    // 0 means the field was absent (old firmware); coulomb integral falls back to (Δt − sleepTime).
    private final List<double[]> history = new ArrayList<>();
    private long todayMidnight = 0;
    private double[] bestAnchor = null; // [timeSeconds, SOC%] — persists across days

    public SolarAnalysis(String deviceName) {
        this.deviceName = deviceName;
    }

    @Override
    public String getName() { return "SolarAnalysis"; }

    @Override
    public String getDeviceName() { return deviceName; }

    @Override
    public JSONArray process(JSONObject telepathon) throws Exception {
        double voltage        = getDeneWordDouble(telepathon, "Purpose", "Battery Voltage");
        double batteryCurrent = getDeneWordDouble(telepathon, "Purpose", "Battery Current");
        int    sleepSec       = (int) getDeneWordDouble(telepathon, "Purpose", "Sleep Time");
        int    status         = (int) getDeneWordDouble(telepathon, "Purpose", "Operating Status");
        double lux            = getDeneWordDouble(telepathon, "Purpose", "Light Level");
        double wakeTimeSec    = getDeneWordDouble(telepathon, "Purpose", "Wake Time Sec");
        double lat            = getDeneWordDouble(telepathon, "Configuration", "Latitude");
        double lon            = getDeneWordDouble(telepathon, "Configuration", "longitude");
        long   timeSeconds    = telepathon.optLong("Seconds Time", System.currentTimeMillis() / 1000);
        logger.debug("Starting to process Solar Analysis");
        if (lat == 0) {
            logger.debug("SolarAnalysis[" + deviceName + "]: skipping — lat=0");
            return new JSONArray();
        }

        // Reset accumulation at midnight
        Calendar midnight = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long midnightEpoch = midnight.getTimeInMillis() / 1000;
        if (midnightEpoch > todayMidnight) {
            logger.info("line 98 SolarAnalysis[" + deviceName + "]: midnight reset — clearing " + history.size() + " records");
            history.clear();
            todayMidnight = midnightEpoch;
        }

        history.add(new double[]{timeSeconds, voltage, batteryCurrent, sleepSec, status, lux, wakeTimeSec});
        logger.debug("SolarAnalysis[" + deviceName + "]: recorded V=" + voltage
                + " I=" + batteryCurrent + "mA status=" + status
                + " lux=" + lux + " wake=" + wakeTimeSec + "s  history=" + history.size());

        // Update best anchor if voltage breaks above the flat LiFePO4 plateau
        if (voltage >= RELIABLE_ANCHOR_VOLTAGE) {
            double anchorSoc = estimateSocPct(voltage);
            if (bestAnchor == null || anchorSoc > bestAnchor[1]) {
                bestAnchor = new double[]{timeSeconds, anchorSoc};
                logger.info("ine 113 SolarAnalysis[" + deviceName + "]: new reliable anchor "
                        + voltage + "V = " + anchorSoc + "% SOC");
            }
        }

        // Compute sunrise/sunset
        Location loc = new Location(String.valueOf(lat), String.valueOf(lon));
        SunriseSunsetCalculator calc = new SunriseSunsetCalculator(loc, TIMEZONE);
        Calendar today = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        long sunriseEpoch = calc.getOfficialSunriseCalendarForDate(today).getTimeInMillis() / 1000;
        long sunsetEpoch  = calc.getOfficialSunsetCalendarForDate(today).getTimeInMillis()  / 1000;

        // Determine run mode from current hour (Cerebellum already gated by slot)
        int hour = today.get(Calendar.HOUR_OF_DAY);
        long nowEpoch = System.currentTimeMillis() / 1000;
        boolean nearSunset = true;//Math.abs(nowEpoch - sunsetEpoch) <= 1800;

        logger.debug("line 130 SolarAnalysis[" + deviceName + "]: hour=" + hour
                + " nearSunset=" + nearSunset + " cycles=" + wakeCycleRepresentatives().size());

        if (nearSunset) {
            logger.info("SolarAnalysis[" + deviceName + "]: FIRING sunset run");
            return runSunset(sunriseEpoch, sunsetEpoch);
        } else if (hour == 15) {
            logger.info("SolarAnalysis[" + deviceName + "]: FIRING 15h run");
            return runIntermediate("15h", sunsetEpoch);
        } else if (hour == 12) {
            logger.info("SolarAnalysis[" + deviceName + "]: FIRING 12h run");
            return runIntermediate("12h", sunsetEpoch);
        }
        return new JSONArray();
    }

    // ── Run modes ─────────────────────────────────────────────────────────────

    /**
     * Midday / 15h intermediate run.
     * Reports current coulomb SOC and projects it forward to sunset using
     * the measured average net current from today's LoRa records.
     * Flags a low-battery alert if the projection falls below the threshold.
     *
     * TODO: incorporate weather forecast cloudiness for the remaining afternoon
     * slots (available from OpenWeatherMap via WeatherForecastManager) to
     * estimate expected solar harvest and improve projection accuracy.
     */
    private JSONArray runIntermediate(String label, long sunsetEpoch) {
        List<double[]> cycles = wakeCycleRepresentatives();
        if (cycles.size() < 3) return new JSONArray();

        long nowEpoch = System.currentTimeMillis() / 1000;
        double currentSoc = computeCoulombSoc(nowEpoch);
        double currentMah = currentSoc / 100.0 * BATTERY_CAPACITY_MAH;

        // Average net current from cycle representatives (one per wake session, not per burst record)
        double avgCurrentMa = cycles.stream()
                .mapToDouble(r -> r[2])
                .filter(c -> c > 0)
                .average()
                .orElse(100.0);

        double hoursToSunset = Math.max(0, (sunsetEpoch - nowEpoch) / 3600.0);
        double projectedMah  = Math.max(0, currentMah - avgCurrentMa * hoursToSunset);
        double projectedSoc  = projectedMah / BATTERY_CAPACITY_MAH * 100.0;
        boolean alert        = projectedSoc < LOW_BATTERY_ALERT_PCT;
        logger.info("SolarAnalysis[" + deviceName + "] " + label
                + ": currentSOC=" + String.format("%.1f", currentSoc) + "%"
                + " currentMah=" + String.format("%.0f", currentMah)
                + " avgI=" + String.format("%.1f", avgCurrentMa) + "mA"
                + " hoursToSunset=" + String.format("%.1f", hoursToSunset)
                + " projectedSOC=" + String.format("%.1f", projectedSoc) + "%"
                + (alert ? "  ⚠ LOW BATTERY ALERT" : ""));

        JSONArray words = new JSONArray();
        words.put(deneWord("Solar " + label + " SOC",                    currentSoc,   "Double"));
        words.put(deneWord("Solar " + label + " mAh",                    currentMah,   "Double"));
        words.put(deneWord("Solar " + label + " Projected SOC At Sunset", projectedSoc, "Double"));
        words.put(deneWord("Solar " + label + " Avg Current mA",          avgCurrentMa, "Double"));
        words.put(deneWord("Solar " + label + " Low Battery Alert",       alert ? 1.0 : 0.0, "Integer"));
        return words;
    }

    /** Sunset run: definitive mode percentages, lux correlation, and coulomb SOC. */
    private JSONArray runSunset(long sunriseEpoch, long sunsetEpoch) {
        // Use cycle representatives for mode analysis (one entry per wake session)
        List<double[]> dayLora = new ArrayList<>();
        logger.debug("line 201");
        for (double[] r : wakeCycleRepresentatives()) {
            if (r[0] >= sunriseEpoch && r[0] <= sunsetEpoch) dayLora.add(r);
        }
        logger.debug("line 201");
        if (dayLora.size() < 2) return new JSONArray();
        dayLora.sort(Comparator.comparingDouble(r -> r[0]));
        logger.debug("line 204");
        // Operating mode time distribution
        Map<Integer, Long> secondsByStatus = new HashMap<>();
        long totalSeconds = 0;
        for (int i = 0; i < dayLora.size() - 1; i++) {
            long gap = (long)(dayLora.get(i + 1)[0] - dayLora.get(i)[0]);
            int  st  = (int) dayLora.get(i)[4];
            secondsByStatus.merge(st, gap, Long::sum);
            totalSeconds += gap;
        }
        double[] last = dayLora.get(dayLora.size() - 1);
        long finalGap = sunsetEpoch - (long) last[0];
        if (finalGap > 0) {
            secondsByStatus.merge((int) last[4], finalGap, Long::sum);
            totalSeconds += finalGap;
        }
        logger.debug("line 220");
        double activePct = pct(secondsByStatus.getOrDefault(STATUS_ACTIVE, 0L), totalSeconds);
        double cloudyPct = pct(secondsByStatus.getOrDefault(STATUS_CLOUDY, 0L), totalSeconds);
        double sleepPct  = pct(secondsByStatus.getOrDefault(STATUS_SLEEP,  0L), totalSeconds);
        double otherPct  = Math.max(0, 100.0 - activePct - cloudyPct - sleepPct);

        // Lux analysis
        List<double[]> luxPairs = new ArrayList<>();
        double activeLuxSum = 0, cloudyLuxSum = 0;
        int activeLuxCount = 0, cloudyLuxCount = 0;
        for (double[] r : dayLora) {
            if (r[5] <= 0) continue;
            int st = (int) r[4];
            luxPairs.add(new double[]{r[5], st == STATUS_ACTIVE ? 1.0 : 0.0});
            if (st == STATUS_ACTIVE) { activeLuxSum += r[5]; activeLuxCount++; }
            if (st == STATUS_CLOUDY) { cloudyLuxSum += r[5]; cloudyLuxCount++; }
        }
        double luxCorr     = pearson(luxPairs);
        double activeLuxAvg = activeLuxCount > 0 ? activeLuxSum / activeLuxCount : 0;
        double cloudyLuxAvg = cloudyLuxCount > 0 ? cloudyLuxSum / cloudyLuxCount : 0;
        logger.debug("line 241");
        // Coulomb SOC at sunset — canonical value for GraveyardShift handoff
        double socAtSunset  = computeCoulombSoc(sunsetEpoch);
        double mahAtSunset  = socAtSunset / 100.0 * BATTERY_CAPACITY_MAH;
        boolean anchorReliable = bestAnchor != null;

        logger.info("line 246 SolarAnalysis[" + deviceName + "] sunset:"
                + " active=" + activePct + "% cloudy=" + cloudyPct + "% sleep=" + sleepPct + "%"
                + " luxCorr=" + luxCorr
                + " activeLux=" + String.format("%.0f", activeLuxAvg)
                + " cloudyLux=" + String.format("%.0f", cloudyLuxAvg)
                + " socAtSunset=" + socAtSunset + "%"
                + " mAhAtSunset=" + String.format("%.0f", mahAtSunset)
                + " anchorReliable=" + anchorReliable
                + " daytimeRecords=" + dayLora.size());

        JSONArray words = new JSONArray();
        words.put(deneWord("Solar Active Pct",           activePct,      "Double"));
        words.put(deneWord("Solar Cloudy Pct",            cloudyPct,      "Double"));
        words.put(deneWord("Solar Sleep Pct",             sleepPct,       "Double"));
        words.put(deneWord("Solar Other Pct",             otherPct,       "Double"));
        words.put(deneWord("Solar Lux Correlation",       luxCorr,        "Double"));
        words.put(deneWord("Solar Active Avg Lux",        activeLuxAvg,   "Double"));
        words.put(deneWord("Solar Cloudy Avg Lux",        cloudyLuxAvg,   "Double"));
        words.put(deneWord("Solar Current Correlation",   0.0,            "Double")); // pending Wally PCB
        words.put(deneWord("Solar Reading Count",         (double) dayLora.size(), "Integer"));
        words.put(deneWord("Solar SOC At Sunset",         socAtSunset,    "Double"));
        words.put(deneWord("Solar mAh At Sunset",         mahAtSunset,    "Double"));
        words.put(deneWord("Solar Anchor Reliable",       anchorReliable ? 1.0 : 0.0, "Integer"));
        return words;
    }

    // ── Coulomb counting ──────────────────────────────────────────────────────

    private double computeCoulombSoc(long toEpoch) {
        List<double[]> cycles = wakeCycleRepresentatives();
        if (cycles.size() < 2) return estimateSocPct(peakVoltage());

        long   anchorTime;
        double anchorSoc;
        if (bestAnchor != null) {
            anchorTime = (long) bestAnchor[0];
            anchorSoc  = bestAnchor[1];
        } else {
            int idx = 0; double maxV = 0;
            for (int i = 0; i < cycles.size(); i++) {
                if (cycles.get(i)[1] > maxV) { maxV = cycles.get(i)[1]; idx = i; }
            }
            anchorTime = (long) cycles.get(idx)[0];
            anchorSoc  = estimateSocPct(maxV);
        }

        double runningMah = anchorSoc / 100.0 * BATTERY_CAPACITY_MAH;
        for (double[] cycle : cycles) {
            if (cycle[0] <= anchorTime) continue;
            if (cycle[0] > toEpoch) break;
            double wakeT  = cycle[6]; // max wakeTimeSec for this cycle
            double sleepT = cycle[3]; // max sleepTime for this cycle
            runningMah   -= (cycle[2] * wakeT / 3600.0) + (SLEEP_CURRENT_MA * sleepT / 3600.0);
        }
        return Math.max(0, Math.min(100, Math.round(runningMah / BATTERY_CAPACITY_MAH * 1000.0) / 10.0));
    }

    /**
     * Groups raw history records into wake cycles using wakeTimeSec monotonicity.
     * See GraveyardShift.wakeCycleRepresentatives() for the full contract.
     * History layout: [time, voltage, current, sleepTime, status, lux, wakeTimeSec]
     * wakeTimeSec is cumulative within a wake session (millis()/1000 on the device).
     * When it resets (curr <= prev), a new wake cycle has started.
     */
    private List<double[]> wakeCycleRepresentatives() {
    	logger.debug("line 312 history.size()=" + history.size());
        if (history.isEmpty()) return new ArrayList<>();

        boolean newFirmware = history.stream().anyMatch(r -> r[6] > 0);
        List<double[]> result = new ArrayList<>();
        int cycleStart = 0;
     
        for (int i = 1; i <= history.size(); i++) {
            boolean endOfCycle;
            if (i == history.size()) {
                endOfCycle = true;
            } else if (newFirmware) {
                // New cycle when wakeTimeSec drops (millis() reset after deep sleep)
                // OR when the time gap exceeds wakeTimeSec + 30s tolerance.
                // Two 255-saturated records 17s apart → 17 ≤ 285 → same cycle ✓.
                double currWake = history.get(i)[6];
                double dt       = history.get(i)[0] - history.get(i - 1)[0];
                endOfCycle = currWake < history.get(i - 1)[6] || dt > currWake + 30;
                logger.debug("line 330,cv= " + endOfCycle);
            } else {
                endOfCycle = (history.get(i)[0] - history.get(i - 1)[0]) >= MIN_LORA_INTERVAL_SECONDS;
            }
            logger.debug("line 334,endcuycle= " + endOfCycle);
            if (endOfCycle) {
                double[] last = history.get(i - 1);
                double sumCurrent = 0, maxWake = 0, maxSleep = 0;
                for (int j = cycleStart; j < i; j++) {
                    sumCurrent += history.get(j)[2];
                    maxWake    = Math.max(maxWake,  history.get(j)[6]);
                    maxSleep   = Math.max(maxSleep, history.get(j)[3]);
                }
                double[] rep = last.clone();
                rep[2] = sumCurrent / (i - cycleStart); // averaged current
                rep[3] = maxSleep;                       // real sleepTime (0 for pure WiFi bursts)
                rep[6] = maxWake;                        // total active duration
                result.add(rep);
                cycleStart = i;
            }
        }
        logger.debug("line 351,result= " + result);
        return result;
    }

    private double peakVoltage() {
        return history.stream().mapToDouble(r -> r[1]).max().orElse(0);
    }

    // ── Voltage → SOC (1S LiFePO4) ───────────────────────────────────────────

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

    // ── Statistics ────────────────────────────────────────────────────────────

    private double pct(long part, long total) {
        return total == 0 ? 0.0 : Math.round((part * 10000.0) / total) / 100.0;
    }

    private double pearson(List<double[]> pairs) {
        int n = pairs.size();
        if (n < 2) return 0.0;
        double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
        for (double[] p : pairs) {
            sumX += p[0]; sumY += p[1];
            sumXY += p[0] * p[1];
            sumX2 += p[0] * p[0]; sumY2 += p[1] * p[1];
        }
        double num   = n * sumXY - sumX * sumY;
        double denom = Math.sqrt((n * sumX2 - sumX * sumX) * (n * sumY2 - sumY * sumY));
        return denom == 0 ? 0.0 : Math.round((num / denom) * 10000.0) / 10000.0;
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

    private JSONObject deneWord(String name, double value, String type) {
        JSONObject w = new JSONObject();
        w.put("Name",  name);
        w.put("Value", String.valueOf(value));
        w.put("Type",  type);
        return w;
    }
}

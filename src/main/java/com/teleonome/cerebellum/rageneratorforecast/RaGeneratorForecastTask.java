package com.teleonome.cerebellum.rageneratorforecast;

import com.teleonome.cerebellum.Task;
import com.teleonome.framework.TeleonomeConstants;
import com.teleonome.framework.denome.DenomeUtils;
import com.teleonome.framework.denome.Identity;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.TimeZone;

import static com.teleonome.cerebellum.rageneratorforecast.RaNightModel.*;

/**
 * Purpose: decide whether Ra's generator needs to run overnight to keep the
 * 48V/660Ah lead-acid bank above its 45.6V floor by sunrise. Unlike the other
 * teleonomes on this framework (solar + battery only), Ra also has a generator
 * input that can charge overnight - this task exists to answer "does someone
 * need to go start it" before the bank actually gets there, not just report a
 * forecast number. Named/packaged for Ra specifically since the 45.6V floor and
 * the overnight-generator premise are this system's own spec, not a general
 * pattern other teleonomes share.
 *
 * Forecast target is sunset->sunrise, not a fixed clock time (6pm-6am) - summer
 * and winter nights are very different lengths at this latitude (-37, 142,
 * ~400m - the SunriseSunsetCalculator library doesn't model altitude, but at
 * 400m that's a ~2-3 minute effect, not worth a custom calculation for).
 *
 * Two independent layers, either of which can trigger the recommendation - not
 * one merged model - because a single estimator's blind spot shouldn't be able
 * to silently mask real risk on an aging bank. They're deliberately built on
 * different bases so they fail independently:
 *
 *   1. Voltage-trend layer: empirical V/hr slope from the last hour of actual
 *      readings, extrapolated to sunrise against the 45.6V floor. Fast/reactive
 *      - reflects whatever's happening right now (a load spike, cloud cover,
 *      the generator already running) - and also yields an estimated *crossing
 *      time*, not just a yes/no by sunrise.
 *   2. Historical-load layer: average net Amps (Charge - Load) over the same
 *      sunset->sunrise window on each of the last few nights, projected forward
 *      as Ah against the 660Ah capacity, anchored to the SP PRO's current State
 *      of Charge. Slower-moving and less reactive than Layer 1, but far more
 *      stable for a multi-hour-ahead projection than extrapolating a single
 *      noisy trailing hour all the way to sunrise.
 *
 * Both layers read from Postgres's remembereddenewords_YYYY_M_D tables (queried
 * via PostgresqlPersistenceManager.instance(), a JVM-wide singleton) rather than
 * an in-memory window, since Ra already remembers Purpose:Sensor Data:Now:
 * Battery Voltage/Charge/Load every pulse - real, restart-surviving history
 * already exists; querying it beats re-deriving a shorter, restart-fragile
 * version of the same thing in memory.
 *
 * Both layers naturally read as "no risk" while the panels are charging during
 * the day (rising voltage / positive net current), so this runs every pulse
 * without needing a day/night gate - the physics does that on its own.
 *
 * A third, fast-path check runs ahead of both layers: on Ra, Load reads ~0A
 * whenever the generator is running (it serves house load directly rather than
 * the battery supplying it), so that alone means no risk *right now* - checked
 * separately because the two layers above are lagging (hour-plus windows), so
 * they'd take a while to recognize the generator has already fixed things.
 */
public class RaGeneratorForecastTask implements Task {

    private static final double MIN_SAFE_VOLTAGE     = 45.6;
    private static final long   VOLTAGE_TREND_WINDOW_MILLIS = 60 * 60_000L; // trailing 1h
    private static final double LOAD_NEAR_ZERO_AMPS  = 0.5;  // sensor noise tolerance around 0A

    private static final String VOLTAGE_IDENTITY = "@Ra:Purpose:Sensor Data:Now:Battery Voltage";

    private final String deviceName;
    private final Logger logger;
    private final SimpleDateFormat timeFormatter;

    public RaGeneratorForecastTask(String teleonomeName, String deviceName) {
        this.deviceName = deviceName;
        this.logger = Logger.getLogger(getClass());
        this.timeFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        this.timeFormatter.setTimeZone(TimeZone.getTimeZone(TIMEZONE));
    }

    @Override public String getName()       { return "RaGeneratorForecastTask"; }
    @Override public String getDeviceName() { return deviceName; }

    @Override
    public JSONArray processSelf(JSONObject pulse, String matchedSlot) throws Exception {
        double voltage = getSelfDouble(pulse, "Battery Voltage");
        if (voltage <= 0) {
            logger.debug("RaGeneratorForecastTask: no Battery Voltage reading, skipping pulse");
            return new JSONArray();
        }
        double soc  = getSelfDouble(pulse, "State of Charge");
        double load = getSelfDouble(pulse, "Load");
        long nowMillis = pulse.optLong("Pulse Timestamp in Milliseconds", System.currentTimeMillis());

        // Fast path: on this system, Load reads ~0 whenever the generator is running,
        // because the generator serves house load directly rather than the battery
        // supplying it (confirmed by Ari - matches Ra's own dormant "Current Load
        // Condition" actuator, which already used Load==0 as this exact signal).
        // Both layers below are lagging windows, so they'd take a while to recognize
        // the generator has fixed things - this catches it immediately instead.
        if (load <= LOAD_NEAR_ZERO_AMPS) {
            logger.debug("RaGeneratorForecastTask: Load=" + load + "A (~0) - generator serving load directly, no risk");
            JSONArray words = new JSONArray();
            words.put(deneWord("Generator Needed Tonight", false, "boolean"));
            words.put(deneWord("Generator Forecast Trigger", "None (Generator Serving Load)", "String"));
            words.put(deneWord("Projected Sunrise Voltage", round2(voltage), "double"));
            words.put(deneWord("Projected Sunrise SoC %", round2(soc), "double"));
            return words;
        }

        long sunriseMillis = nextSunriseMillis(nowMillis);
        double hoursToSunrise = (sunriseMillis - nowMillis) / 3_600_000.0;

        PostgresqlPersistenceManager db = PostgresqlPersistenceManager.instance();

        // ── Layer 1: voltage slope, from remembered Battery Voltage history ────
        double voltageSlopePerHour = slope(
                db.getRemeberedDeneWordStart(VOLTAGE_IDENTITY, nowMillis - VOLTAGE_TREND_WINDOW_MILLIS, nowMillis));
        double projectedVoltage = voltage;
        boolean voltageLayerTriggered = false;
        String crossingTime = "N/A";
        if (voltageSlopePerHour < 0) {
            projectedVoltage = voltage + voltageSlopePerHour * hoursToSunrise;
            voltageLayerTriggered = projectedVoltage < MIN_SAFE_VOLTAGE;

            double hoursToFloor = (voltage - MIN_SAFE_VOLTAGE) / -voltageSlopePerHour;
            if (hoursToFloor >= 0 && hoursToFloor <= hoursToSunrise) {
                crossingTime = timeFormatter.format(nowMillis + (long) (hoursToFloor * 3_600_000.0));
            }
        }

        // ── Layer 2: average historical night load, sunset->sunrise, last few nights ─
        double avgNetAmps = averageHistoricalNightNetAmps(db, nowMillis);
        double projectedSoc = soc;
        boolean socLayerTriggered = false;
        if (avgNetAmps < 0) {
            double projectedAhDelta = avgNetAmps * hoursToSunrise; // negative = net discharge
            projectedSoc = soc + (projectedAhDelta / BATTERY_CAPACITY_AH) * 100.0;
            socLayerTriggered = projectedSoc <= 0;
        }

        boolean generatorNeeded = voltageLayerTriggered || socLayerTriggered;
        String trigger = voltageLayerTriggered && socLayerTriggered ? "Both"
                : voltageLayerTriggered ? "Voltage"
                : socLayerTriggered ? "SoC"
                : "None";

        logger.debug("RaGeneratorForecastTask: V=" + voltage + " voltageSlope=" + round2(voltageSlopePerHour)
                + "V/hr avgNetAmps=" + round2(avgNetAmps) + "A hoursToSunrise=" + round2(hoursToSunrise)
                + " projectedV=" + round2(projectedVoltage) + " projectedSoC=" + round2(projectedSoc)
                + " crossingTime=" + crossingTime + " trigger=" + trigger);

        JSONArray words = new JSONArray();
        words.put(deneWord("Generator Needed Tonight", generatorNeeded, "boolean"));
        words.put(deneWord("Generator Forecast Trigger", trigger, "String"));
        words.put(deneWord("Voltage Floor Crossing Time Estimate", crossingTime, "String"));
        words.put(deneWord("Projected Sunrise Voltage", round2(projectedVoltage), "double"));
        words.put(deneWord("Projected Sunrise SoC %", round2(projectedSoc), "double"));
        words.put(deneWord("Average Night Load Net Amps", round2(avgNetAmps), "double"));
        return words;
    }

    // ── Trend helpers ─────────────────────────────────────────────────────────

    /** Slope in units-per-hour between the oldest and newest row in a getRemeberedDeneWordStart() result. */
    private double slope(JSONArray history) {
        if (history.length() < 2) return 0;
        JSONObject first = history.getJSONObject(0);
        JSONObject last  = history.getJSONObject(history.length() - 1);
        double dtHours = (last.getLong("Pulse Timestamp in Milliseconds")
                - first.getLong("Pulse Timestamp in Milliseconds")) / 3_600_000.0;
        if (dtHours <= 0) return 0;
        return (last.getDouble("Value") - first.getDouble("Value")) / dtHours;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── Self-data navigation ───────────────────────────────────────────────────

    private double getSelfDouble(JSONObject pulse, String wordName) {
        try {
            String teleonomeName = pulse.getJSONObject("Denome").getString("Name");
            Identity identity = new Identity(teleonomeName, "Purpose", "Sensor Data", "Now", wordName);
            Object value = DenomeUtils.getDeneWordByIdentity(pulse, identity, TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE);
            if (value == null) return 0.0;
            if (value instanceof Number) return ((Number) value).doubleValue();
            return Double.parseDouble(value.toString());
        } catch (Exception e) {
            return 0.0;
        }
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

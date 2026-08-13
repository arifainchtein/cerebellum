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
 * 48V/660Ah lead-acid bank above its 46.4V floor by sunrise. Unlike the other
 * teleonomes on this framework (solar + battery only), Ra also has a generator
 * input that can charge overnight - this task exists to answer "does someone
 * need to go start it" before the bank actually gets there, not just report a
 * forecast number. Named/packaged for Ra specifically since the 46.4V floor and
 * the overnight-generator premise are this system's own spec, not a general
 * pattern other teleonomes share.
 *
 * Forecast target is sunset->sunrise, not a fixed clock time (6pm-6am) - summer
 * and winter nights are very different lengths at this latitude (-37, 142,
 * ~400m - the SunriseSunsetCalculator library doesn't model altitude, but at
 * 400m that's a ~2-3 minute effect, not worth a custom calculation for).
 *
 * IMPORTANT: "sunset->sunrise" is the framing for Layers 1 and 2 below (they
 * project *future* overnight risk), but the actual floor check is NOT confined
 * to nighttime - see the unconditional emergency check in processSelf(), added
 * 2026-08-12 after a real miss: on a weak-sun day the battery can sit right at
 * 46.4V at midday (still net-discharging, just more slowly than overnight)
 * without either layer below ever tripping, since Layer 1 only fires on a
 * *declining* trend and Layer 2 only ever looks at nighttime history projected
 * to the *next* sunrise. The emergency check catches "already at the floor
 * right now," any time of day, independent of both layers.
 *
 * Two independent layers, either of which can trigger the recommendation - not
 * one merged model - because a single estimator's blind spot shouldn't be able
 * to silently mask real risk on an aging bank. They're deliberately built on
 * different bases so they fail independently:
 *
 *   1. Voltage-trend layer: empirical V/hr slope from the last hour of actual
 *      readings, extrapolated to sunrise against the 46.4V floor. Fast/reactive
 *      - reflects whatever's happening right now (a load spike, cloud cover,
 *      the generator already running) - and also yields an estimated *crossing
 *      time*, not just a yes/no by sunrise.
 *   2. Historical-load layer: average net Amps (Charge - Load) over the same
 *      sunset->sunrise window on each of the last few nights, projected forward
 *      as Ah against the 660Ah capacity, anchored to the PLSeries regulator's
 *      current State of Charge (Now:State of Charge - from the Plasmatronics
 *      PL-series regulator's own shunt-based tracking, not the Selectronic SP
 *      PRO). Slower-moving and less reactive than Layer 1, but far more stable
 *      for a multi-hour-ahead projection than extrapolating a single noisy
 *      trailing hour all the way to sunrise.
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

    private static final double MIN_SAFE_VOLTAGE     = 46.4;
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

    /**
     * Output DeneWords, one row per pulse (published to Purpose:Cerebellum:Ra and
     * shown on the Human Interface "Ra Generator Forecast" panel):
     *
     * <table border="1">
     *   <tr><th>DeneWord</th><th>Type</th><th>Meaning</th></tr>
     *   <tr><td>Generator Needed Tonight</td><td>boolean</td>
     *       <td>The overall recommendation - true if either the voltage-trend layer
     *       or the historical-load layer projects trouble before sunrise, or if the
     *       fast path already caught the answer before either layer even runs. The
     *       one field to actually check.</td></tr>
     *   <tr><td>Generator Forecast Trigger</td><td>String</td>
     *       <td>Which layer(s) produced the recommendation: "None", "Voltage",
     *       "SoC", "Both", or "None (Generator Serving Load)" if the fast path
     *       caught that the generator is already running.</td></tr>
     *   <tr><td>Voltage Floor Crossing Time Estimate</td><td>String</td>
     *       <td>If the last hour's voltage trend is declining, the estimated local
     *       time it crosses the 46.4V floor, extrapolated forward. "N/A" if voltage
     *       isn't currently declining, or the floor won't be reached before sunrise.</td></tr>
     *   <tr><td>Projected Sunrise Voltage</td><td>double (V)</td>
     *       <td>The voltage-trend layer's straight-line extrapolation of current
     *       voltage to sunrise, based on the last hour's empirical V/hr slope.</td></tr>
     *   <tr><td>Projected Sunrise SoC %</td><td>double (%)</td>
     *       <td>The historical-load layer's projection: current State of Charge
     *       (from the PLSeries regulator, not the Selectronic SP PRO) adjusted by
     *       the average net Amps drawn over the same sunset-&gt;sunrise window on
     *       the last {@link RaNightModel#NIGHTS_TO_AVERAGE} nights, projected
     *       forward against the 660Ah capacity.</td></tr>
     *   <tr><td>Average Night Load Net Amps</td><td>double (A)</td>
     *       <td>The historical average net Amps (Charge - Load) driving the SoC
     *       projection above, shown for transparency so the assumption behind that
     *       number is visible, not just its result.</td></tr>
     * </table>
     */
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

        // Unconditional emergency check: is the battery already at or below the floor
        // RIGHT NOW, this pulse - independent of trend direction or time of day. Layers
        // 1 and 2 below only ever look at *projected future* risk (sunset->sunrise):
        // Layer 1 only extrapolates when the last-hour trend is declining, so a flat
        // trend sitting right on 46.4V (e.g. weak midday sun barely balancing load)
        // never trips it; Layer 2 only ever uses nighttime historical averages
        // projected to the *next* sunrise, so it's blind at midday entirely. Both
        // layers implicitly assume "the risk is tonight" - this catches the case
        // where the risk is already here, any time of day. Checked first, ahead of
        // the fast path, since a low-voltage fact right now matters regardless of why.
        boolean voltageAtOrBelowFloorNow = voltage <= MIN_SAFE_VOLTAGE;

        // Fast path: on this system, Load reads ~0 whenever the generator is running,
        // because the generator serves house load directly rather than the battery
        // supplying it (confirmed by Ari - matches Ra's own dormant "Current Load
        // Condition" actuator, which already used Load==0 as this exact signal).
        // Both layers below are lagging windows, so they'd take a while to recognize
        // the generator has fixed things - this catches it immediately instead. But if
        // voltage hasn't actually recovered above the floor yet, don't clear the alarm
        // just because something is currently drawing Load down to ~0 - Load~0 alone
        // doesn't prove the battery has recovered, only that it isn't being drawn down
        // *right now*.
        if (load <= LOAD_NEAR_ZERO_AMPS) {
            logger.debug("RaGeneratorForecastTask: Load=" + load + "A (~0) - generator/solar serving load directly"
                    + (voltageAtOrBelowFloorNow ? ", but voltage still at/below floor" : ", no risk"));
            JSONArray words = new JSONArray();
            words.put(deneWord("Generator Needed Tonight", voltageAtOrBelowFloorNow, "boolean"));
            words.put(deneWord("Generator Forecast Trigger",
                    voltageAtOrBelowFloorNow ? "Voltage (At Or Below Floor Now)" : "None (Generator Serving Load)",
                    "String"));
            words.put(deneWord("Voltage Floor Crossing Time Estimate", voltageAtOrBelowFloorNow ? "Now" : "N/A", "String"));
            words.put(deneWord("Projected Sunrise Voltage", round2(voltage), "double", "Volts"));
            words.put(deneWord("Projected Sunrise SoC %", round2(soc), "double", "%"));
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

        boolean generatorNeeded = voltageAtOrBelowFloorNow || voltageLayerTriggered || socLayerTriggered;
        String trigger = voltageAtOrBelowFloorNow ? "Voltage (At Or Below Floor Now)"
                : voltageLayerTriggered && socLayerTriggered ? "Both"
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
        words.put(deneWord("Projected Sunrise Voltage", round2(projectedVoltage), "double", "Volts"));
        words.put(deneWord("Projected Sunrise SoC %", round2(projectedSoc), "double", "%"));
        words.put(deneWord("Average Night Load Net Amps", round2(avgNetAmps), "double", "Amperes"));
        return words;
    }

    // ── Trend helpers ─────────────────────────────────────────────────────────

    /**
     * Least-squares slope in units-per-hour across every row in a
     * getRemeberedDeneWordStart() result - a regression over the whole window, not
     * just the oldest/newest two points. Battery Voltage on Ra is quantized to
     * ~0.4V steps by the sensor, so a naive two-point slope is extremely noisy:
     * which exact quantization step the window's two boundary samples happen to
     * land on varies almost randomly pulse to pulse, which was observed swinging
     * the projected crossing time by hours between consecutive one-minute pulses
     * with the voltage barely moving (2026-08-13). Fitting a line through all ~60
     * samples in the window averages that quantization noise out.
     */
    private double slope(JSONArray history) {
        int n = history.length();
        if (n < 2) return 0;

        long t0 = history.getJSONObject(0).getLong("Pulse Timestamp in Milliseconds");
        double sumT = 0, sumV = 0, sumTV = 0, sumTT = 0;
        for (int i = 0; i < n; i++) {
            JSONObject row = history.getJSONObject(i);
            double tHours = (row.getLong("Pulse Timestamp in Milliseconds") - t0) / 3_600_000.0;
            double v = row.getDouble("Value");
            sumT += tHours;
            sumV += v;
            sumTV += tHours * v;
            sumTT += tHours * tHours;
        }
        double denominator = n * sumTT - sumT * sumT;
        if (denominator == 0) return 0;
        return (n * sumTV - sumT * sumV) / denominator;
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

    private JSONObject deneWord(String name, Object value, String valueType, String units) {
        JSONObject w = deneWord(name, value, valueType);
        w.put("Units", units);
        return w;
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

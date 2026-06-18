package com.teleonome.cerebellum.chinampatask;

import com.teleonome.cerebellum.HippocampusQuery;
import com.teleonome.cerebellum.Task;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * Aquaponics monitoring for the Chinampa controller, registered under two
 * Cerebellum Task denes that share this same instance (same className,
 * same device — Cerebellum's getOrCreateTask caches by that key):
 *
 *   "Every Pulse"      -> live flow/height consistency check (matchedSlot="Pulse")
 *   "Hours In A Day"    -> pump cycle count for the hour just finished
 *                          (matchedSlot = hour string "0".."23"), all 24 hours listed
 *                          in Execution Time so it fires every hour.
 *
 * Physical layout: fish tank drains via gravity/solenoid into the grow beds;
 * grow beds auto-siphon (batch) into the sump; the pump moves water from the
 * sump back up into the fish tank.
 *
 * IMPORTANT: "Fish Tank Measured Height" / "Sump Trough Measured Height" are
 * sensor *distance-to-surface* readings (per chinampa.ino), not water level —
 * a SMALLER value means HIGHER water, a LARGER value means LOWER water. All
 * direction checks below are written in those (inverted) terms.
 */
public class ChinampaTask implements Task {

    private static final String TELEONOME_NAME = "ChinampaMonitor";
    private static final String DEVICE_NAME    = "Chinampa";
    private static final String TIMEZONE       = "Australia/Melbourne";

    // Leak/drift deadband when no flow is expected: 1cm floor + 0.5cm per
    // elapsed minute, to absorb sensor noise and longer-than-usual pulse gaps
    // (e.g. a missed pulse) without false-flagging.
    private static final double DEADBAND_FLOOR_CM        = 1.0;
    private static final double DEADBAND_CM_PER_MINUTE    = 0.5;

    private final Logger logger = Logger.getLogger(getClass());
    private final String deviceName;
    private HippocampusQuery queryHandler;

    // Previous-pulse state for the live consistency check.
    private Double lastFishTankHeight;
    private Double lastSumpTroughHeight;
    private Long   lastPulseEpochSec;

    public ChinampaTask(String deviceName) {
        this.deviceName = deviceName;
    }

    @Override public String getName()       { return "ChinampaTask"; }
    @Override public String getDeviceName() { return deviceName; }

    @Override public void setQueryHandler(HippocampusQuery handler) { this.queryHandler = handler; }

    @Override
    public JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
        if ("Pulse".equals(matchedSlot)) {
            return processPulse(telepathon);
        }
        return processHourlyPumpCount(matchedSlot);
    }

    // ── Every-pulse: live flow/height consistency ────────────────────────────

    private JSONArray processPulse(JSONObject telepathon) {
        double fishHeight  = getDeneWordDouble(telepathon, "Purpose", "Fish Tank Measured Height");
        double sumpHeight   = getDeneWordDouble(telepathon, "Purpose", "Sump Trough Measured Height");
        boolean pumpOn      = getDeneWordBoolean(telepathon, "Purpose", "Pump Relay Status");
        boolean solenoidOn  = getDeneWordBoolean(telepathon, "Purpose", "Fish Tank Outflow Solenoid Relay Status");
        long epochSec       = (long) getDeneWordDouble(telepathon, "Purpose", "Seconds Time");

        JSONArray words = new JSONArray();
        if (epochSec <= 0) return words;

        if (lastPulseEpochSec != null && epochSec > lastPulseEpochSec) {
            double elapsedMinutes = (epochSec - lastPulseEpochSec) / 60.0;
            double deadbandCm = Math.max(DEADBAND_FLOOR_CM, DEADBAND_CM_PER_MINUTE * elapsedMinutes);

            // Positive = water level rose (heights are inverted distance readings).
            double fishLevelRoseCm = lastFishTankHeight - fishHeight;
            double sumpLevelRoseCm = lastSumpTroughHeight - sumpHeight;

            String fishReason = checkFishTankConsistency(pumpOn, solenoidOn, fishLevelRoseCm, deadbandCm);
            String sumpReason = checkSumpConsistency(pumpOn, sumpLevelRoseCm, deadbandCm);

            words.put(deneWord("Fish Tank Flow Consistent", fishReason == null, "boolean"));
            if (fishReason != null) words.put(deneWord("Fish Tank Flow Alert Reason", fishReason, "String"));

            words.put(deneWord("Sump Trough Flow Consistent", sumpReason == null, "boolean"));
            if (sumpReason != null) words.put(deneWord("Sump Trough Flow Alert Reason", sumpReason, "String"));

            logger.debug("ChinampaTask[" + deviceName + "]: pump=" + pumpOn + " solenoid=" + solenoidOn
                    + " fishRose=" + fishLevelRoseCm + "cm sumpRose=" + sumpLevelRoseCm + "cm"
                    + " deadband=" + deadbandCm + "cm fishOK=" + (fishReason == null) + " sumpOK=" + (sumpReason == null));
        }

        lastFishTankHeight   = fishHeight;
        lastSumpTroughHeight = sumpHeight;
        lastPulseEpochSec    = epochSec;
        return words;
    }

    /** Returns null if consistent, else a short description of the mismatch. */
    private String checkFishTankConsistency(boolean pumpOn, boolean solenoidOn, double roseCm, double deadbandCm) {
        if (pumpOn && solenoidOn) return null; // net direction ambiguous, skip
        if (pumpOn) {
            // expect level rising (or steady) from the pump refill
            if (roseCm < -deadbandCm) return "pump on but fish tank level falling (" + round(roseCm) + "cm)";
            return null;
        }
        if (solenoidOn) {
            // expect level falling (or steady) as it drains to the grow beds
            if (roseCm > deadbandCm) return "outflow open but fish tank level rising (" + round(roseCm) + "cm)";
            return null;
        }
        // both off: nothing should be moving water in or out
        if (Math.abs(roseCm) > deadbandCm) return "no flow expected but fish tank level changed (" + round(roseCm) + "cm) — possible leak";
        return null;
    }

    /** Returns null if consistent, else a short description of the mismatch. */
    private String checkSumpConsistency(boolean pumpOn, double roseCm, double deadbandCm) {
        // The grow-bed auto-siphon dumps into the sump on its own batch schedule,
        // independent of the pump, so a sump RISE is always plausible. Only a
        // FALL is informative: with the pump off nothing should drain the sump,
        // and with the pump on the sump should be falling (or held by a
        // simultaneous siphon dump) — so an unexpected fall while off is the
        // clearest leak signal here.
        if (!pumpOn && roseCm < -deadbandCm) {
            return "pump off but sump level falling (" + round(roseCm) + "cm) — possible leak";
        }
        return null;
    }

    private double round(double v) { return Math.round(v * 10.0) / 10.0; }

    // ── Hourly: pump cycle count ──────────────────────────────────────────────

    private JSONArray processHourlyPumpCount(String hourSlot) {
        int targetHour;
        try {
            targetHour = Integer.parseInt(hourSlot);
        } catch (NumberFormatException e) {
            return new JSONArray();
        }

        Calendar windowEnd = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        windowEnd.set(Calendar.HOUR_OF_DAY, targetHour);
        windowEnd.set(Calendar.MINUTE, 0);
        windowEnd.set(Calendar.SECOND, 0);
        windowEnd.set(Calendar.MILLISECOND, 0);
        Calendar windowStart = (Calendar) windowEnd.clone();
        windowStart.add(Calendar.HOUR_OF_DAY, -1);

        long startEpochSec = windowStart.getTimeInMillis() / 1000;
        long endEpochSec   = windowEnd.getTimeInMillis() / 1000;

        String identity = "@" + TELEONOME_NAME + ":Telepathons:" + DEVICE_NAME + ":Purpose:Pump Relay Status";

        int cycles = -1;
        String source = "None";

        if (queryHandler != null) {
            try {
                JSONArray data = queryHandler.query(identity, 3600_000L);
                if (data != null && data.length() > 0) {
                    cycles = countRisingEdgesFromHippocampus(data, startEpochSec, endEpochSec);
                    source = "Hippocampus";
                }
            } catch (Exception e) {
                logger.warn("ChinampaTask[" + deviceName + "]: Hippocampus query failed: " + e.getMessage());
            }
        }

        if (cycles < 0) {
            try {
                JSONArray data = PostgresqlPersistenceManager.instance()
                        .getTelepathonDeneWordStart(DEVICE_NAME, "Purpose", "Pump Relay Status", startEpochSec, endEpochSec);
                if (data != null && data.length() > 0) {
                    cycles = countRisingEdgesFromDatabase(data);
                    source = "Database";
                }
            } catch (Exception e) {
                logger.warn("ChinampaTask[" + deviceName + "]: database fallback query failed: " + e.getMessage());
            }
        }

        if (cycles < 0) {
            cycles = 0;
        }

        logger.info("ChinampaTask[" + deviceName + "]: Pump Cycles for hour ending " + targetHour
                + ":00 = " + cycles + " (source=" + source + ")");

        JSONArray words = new JSONArray();
        words.put(deneWord("Pump Cycles Last Hour", cycles, "int"));
        words.put(deneWord("Pump Cycles Last Hour Source", source, "String"));
        return words;
    }

    /** Hippocampus entries are {timeSeconds, Value}, ascending order, Value may be Boolean/Number/String. */
    private int countRisingEdgesFromHippocampus(JSONArray data, long startEpochSec, long endEpochSec) {
        int cycles = 0;
        Boolean previous = null;
        for (int i = 0; i < data.length(); i++) {
            JSONObject point = data.getJSONObject(i);
            long t = point.optLong("timeSeconds", -1);
            if (t < startEpochSec || t >= endEpochSec) continue;
            boolean current = parseBoolean(point.opt("Value"));
            if (previous != null && !previous && current) cycles++;
            previous = current;
        }
        return cycles;
    }

    /** getTelepathonDeneWordStart returns rows ordered timeseconds DESC, Value already 1.0/0.0. */
    private int countRisingEdgesFromDatabase(JSONArray data) {
        int cycles = 0;
        Boolean previous = null;
        for (int i = data.length() - 1; i >= 0; i--) {
            JSONObject point = data.getJSONObject(i);
            boolean current = point.optDouble("Value", 0.0) >= 0.5;
            if (previous != null && !previous && current) cycles++;
            previous = current;
        }
        return cycles;
    }

    private boolean parseBoolean(Object value) {
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) return ((Number) value).doubleValue() >= 0.5;
        if (value != null) return "true".equalsIgnoreCase(value.toString());
        return false;
    }

    // ── Telepathon navigation ─────────────────────────────────────────────────

    private double getDeneWordDouble(JSONObject telepathon, String deneName, String wordName) {
        try {
            JSONArray denes = telepathon.getJSONArray("Denes");
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

    private boolean getDeneWordBoolean(JSONObject telepathon, String deneName, String wordName) {
        try {
            JSONArray denes = telepathon.getJSONArray("Denes");
            for (int i = 0; i < denes.length(); i++) {
                JSONObject dene = denes.getJSONObject(i);
                if (deneName.equals(dene.getString("Name"))) {
                    JSONArray words = dene.getJSONArray("DeneWords");
                    for (int j = 0; j < words.length(); j++) {
                        JSONObject word = words.getJSONObject(j);
                        if (wordName.equals(word.getString("Name"))) {
                            return "true".equalsIgnoreCase(word.getString("Value"));
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
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

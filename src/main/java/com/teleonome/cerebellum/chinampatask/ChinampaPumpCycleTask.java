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
 * Counts how many times the Chinampa pump turned on during the hour just
 * finished. Registered under a "Hours In A Day" Cerebellum Task dene with
 * all 24 hours listed in Execution Time, so matchedSlot arrives as an hour
 * string ("0".."23") once per hour, every hour.
 *
 * Stateless by design — each run asks Hippocampus's short-term memory for
 * "Pump Relay Status" over the last hour first (cheap, in-memory, already
 * tracks this DeneWord per the denome's Internal:Hippocampus:Data config),
 * falling back to a direct PostgreSQL query of telepathon_YYYY_M_D
 * (PostgresqlPersistenceManager.getTelepathonDeneWordStart already handles
 * day-partition spanning) if Hippocampus has nothing. This avoids relying on
 * per-pulse edge detection, which would miss any pump cycle that starts and
 * ends between two Cerebellum pulses, or relies on a single LoRa packet that
 * might be dropped.
 */
public class ChinampaPumpCycleTask implements Task {

    private static final String TELEONOME_NAME = "ChinampaMonitor";
    private static final String DEVICE_NAME    = "Chinampa";
    private static final String TIMEZONE       = "Australia/Melbourne";

    private final Logger logger = Logger.getLogger(getClass());
    private final String deviceName;
    private HippocampusQuery queryHandler;

    public ChinampaPumpCycleTask(String deviceName) {
        this.deviceName = deviceName;
    }

    @Override public String getName()       { return "ChinampaPumpCycleTask"; }
    @Override public String getDeviceName() { return deviceName; }

    @Override public void setQueryHandler(HippocampusQuery handler) { this.queryHandler = handler; }

    @Override
    public JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
        int targetHour;
        try {
            targetHour = Integer.parseInt(matchedSlot);
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
                logger.warn("ChinampaPumpCycleTask[" + deviceName + "]: Hippocampus query failed: " + e.getMessage());
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
                logger.warn("ChinampaPumpCycleTask[" + deviceName + "]: database fallback query failed: " + e.getMessage());
            }
        }

        if (cycles < 0) {
            cycles = 0;
        }

        logger.info("ChinampaPumpCycleTask[" + deviceName + "]: Pump Cycles for hour ending " + targetHour
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

    private JSONObject deneWord(String name, Object value, String valueType) {
        JSONObject w = new JSONObject();
        w.put("Name", name);
        w.put("Value", value);
        w.put("Value Type", valueType);
        w.put("Required", true);
        return w;
    }
}

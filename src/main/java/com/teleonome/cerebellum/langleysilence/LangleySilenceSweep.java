package com.teleonome.cerebellum.langleysilence;

import com.teleonome.framework.persistence.PostgresqlPersistenceManager;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TimeZone;

/**
 * Purpose: detect Langley units that have gone completely silent (no
 * telepathon at all), which a pulse-driven Task can never notice on its own.
 * Standalone sweep run on a wall-clock schedule (Pi crontab), independent of
 * any MQTT pulse.
 *
 * Complements {@code LangleyTopologyTask}, which localizes fence-wire faults by
 * comparing a node against its parent on every pulse but can only ever react to
 * a live pulse - a node that stops pulsing entirely never triggers a re-check,
 * and if the whole fleet goes dark (e.g. Annabelle's LoRa fails) there is no
 * other node's pulse left to piggyback a sweep off either. This class exists
 * to cover exactly that blind spot, by running on the wall clock instead.
 *
 * Uses PostgresqlPersistenceManager's existing telepathon-history helpers
 * (getDistinctTelepathonNames / getTelepathonDataStart), which already resolve
 * across whichever daily telepathon_YYYY_M_D tables a time window spans - no
 * separate "today, falling back to yesterday" table-name logic needed here.
 */
public class LangleySilenceSweep {

    private static final String TIMEZONE = "Australia/Melbourne";
    // Langley pulses roughly once a second (see Langley.ino); these are many
    // missed cycles, not sensor/transmission noise. Same tiers LangleyTopologyTask
    // uses for a parent's freshness check.
    private static final long WARNING_SILENCE_SEC  = 5 * 60;
    private static final long CRITICAL_SILENCE_SEC = 20 * 60;
    // How far back to look for the latest reading per device. Wide enough that a
    // device silent since well before this run still shows up (with a large,
    // clearly CRITICAL age) instead of silently disappearing from the report.
    private static final long LOOKBACK_SECONDS = 24 * 3600;

    private final Logger logger = Logger.getLogger(getClass());

    public static void main(String[] args) {
        new LangleySilenceSweep().run();
    }

    private void run() {
        PropertyConfigurator.configure("/home/pi/Teleonome/lib/Log4J.properties");
        PostgresqlPersistenceManager dbManager = PostgresqlPersistenceManager.instance();

        long nowEpochSec = System.currentTimeMillis() / 1000;
        long windowStart  = nowEpochSec - LOOKBACK_SECONDS;

        ArrayList<String> allDeviceNames = dbManager.getDistinctTelepathonNames(windowStart, nowEpochSec);
        logger.debug("LangleySilenceSweep: " + allDeviceNames.size() + " distinct telepathon names in window");

        Map<String, Long> latestSeenEpochSecByDevice = new LinkedHashMap<>();
        for (String deviceName : allDeviceNames) {
            JSONArray rows = dbManager.getTelepathonDataStart(deviceName, windowStart, nowEpochSec);
            if (rows.length() == 0) continue;
            // getTelepathonDataStart orders ascending, so the last row is the latest.
            JSONObject latestRow = rows.getJSONObject(rows.length() - 1);
            if (!isLangley(latestRow.optJSONObject("data"))) continue;
            latestSeenEpochSecByDevice.put(deviceName, latestRow.getLong("timeSeconds"));
        }

        printReport(latestSeenEpochSecByDevice, nowEpochSec);
    }

    private boolean isLangley(JSONObject data) {
        if (data == null) return false;
        try {
            JSONArray denes = data.getJSONArray("Denes");
            for (int i = 0; i < denes.length(); i++) {
                JSONObject dene = denes.getJSONObject(i);
                if (!"Configuration".equals(dene.optString("Name"))) continue;
                JSONArray words = dene.getJSONArray("DeneWords");
                for (int j = 0; j < words.length(); j++) {
                    JSONObject word = words.getJSONObject(j);
                    if ("Device Type Id".equals(word.optString("Name"))) {
                        return "Langley".equals(word.optString("Value"));
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private void printReport(Map<String, Long> latestSeenEpochSecByDevice, long nowEpochSec) {
        SimpleDateFormat timeFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        timeFmt.setTimeZone(TimeZone.getTimeZone(TIMEZONE));

        System.out.println();
        System.out.println("=== Langley Silence Sweep ===");
        if (latestSeenEpochSecByDevice.isEmpty()) {
            System.out.println("  No Langley devices found in the last " + (LOOKBACK_SECONDS / 3600) + "h.");
            return;
        }
        for (Map.Entry<String, Long> entry : latestSeenEpochSecByDevice.entrySet()) {
            long ageSec = nowEpochSec - entry.getValue();
            String status = ageSec >= CRITICAL_SILENCE_SEC ? "CRITICAL"
                    : ageSec >= WARNING_SILENCE_SEC ? "WARNING" : "OK";
            System.out.printf("  %-20s  last seen %s  (%ds ago)  [%s]%n",
                    entry.getKey(), timeFmt.format(new Date(entry.getValue() * 1000L)), ageSec, status);
        }
        System.out.println();
    }
}

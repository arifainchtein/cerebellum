package com.teleonome.cerebellum.hypothalamusmonitoring;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Purpose: detect the Teleonome heartbeat (the Status pulse Hypothalamus fires on
 * a regular schedule) going silent - i.e. watches for Hypothalamus itself having
 * stopped, not any individual device. This is an absence check, which a
 * pulse-driven Task (process()/processSelf()) structurally can never make - nothing
 * calls into this class if no pulse arrives at all. So, like
 * TelepathonRegistryTask's fleet-wide sweep() / LangleySilenceSweep, this is driven
 * by a dedicated wall-clock timer thread in Cerebellum.java
 * (HypothalamusMonitorThread / runHypothalamusMonitorSweep()) rather than the
 * per-pulse Task dispatch pipeline - see BACKGROUND.md's execution-model table.
 *
 * Pure logic, no DB/MQTT access of its own - sweep() is handed
 * Cerebellum.lastPulseMillis (already tracked on every absorbPulse() call, just
 * previously unused) and returns DeneWords for the caller to fold into the ongoing
 * status broadcast, plus a one-shot EMERGENCY_ALERT_WORD_NAME word Cerebellum.java
 * pulls out and hands to publishEmergency() - identical convention to
 * TelepathonRegistryTask.EMERGENCY_ALERT_WORD_NAME.
 *
 * Debounced to avoid flooding the Emergency Channel while the outage continues:
 * alertSent latches true the first sweep that crosses PULSE_TIMEOUT_MS, and stays
 * true (no further alerts) for as long as the pulse stays silent. It only clears
 * once a pulse is observed again (silenceMs back under the timeout on some later
 * sweep) - so a *subsequent* silent period gets its own fresh alert instead of
 * either spamming every sweep or going permanently silent after the first outage.
 */
public class HypothalamusMonitoringTask {

    // How long the heartbeat can go quiet before this is considered an outage
    // worth an Emergency Channel alert. Matches HYPOTHALAMUS_MONITOR_INTERVAL_MS
    // in Cerebellum.java (the sweep cadence) - kept as separate constants rather
    // than one shared value since there's no structural reason they must be equal,
    // even though today they are.
    static final long PULSE_TIMEOUT_MS = 3 * 60_000L;

    // DeneWord name Cerebellum.runHypothalamusMonitorSweep() watches for and pulls
    // out of sweep()'s returned words to hand to publishEmergency() - see class
    // javadoc and TelepathonRegistryTask.EMERGENCY_ALERT_WORD_NAME for the same
    // convention.
    public static final String EMERGENCY_ALERT_WORD_NAME = "Hypothalamus Monitoring Emergency Alert";

    private final String teleonomeName;
    private final Logger logger;

    // Latches once an alert has fired for the current outage; cleared the next
    // time a pulse is observed. See class javadoc.
    private boolean alertSent = false;

    public HypothalamusMonitoringTask(String teleonomeName) {
        this.teleonomeName = teleonomeName;
        this.logger = Logger.getLogger(getClass());
    }

    /**
     * @param nowMillis        current wall-clock time
     * @param lastPulseMillis  Cerebellum.lastPulseMillis - epoch millis of the last
     *                         Status pulse absorbPulse() saw, or 0 if none yet
     * @return DeneWords for the ongoing status broadcast, plus
     *         EMERGENCY_ALERT_WORD_NAME the one sweep the outage is first detected.
     *         Empty only when lastPulseMillis is 0 (no pulse observed yet this run -
     *         nothing to evaluate against).
     */
    public JSONArray sweep(long nowMillis, long lastPulseMillis) {
        JSONArray words = new JSONArray();
        if (lastPulseMillis == 0) {
            logger.debug("HypothalamusMonitoringTask: no pulse observed yet, skipping this sweep");
            return words;
        }

        long silenceMs = nowMillis - lastPulseMillis;
        boolean silent = silenceMs >= PULSE_TIMEOUT_MS;

        words.put(deneWord("Hypothalamus Monitoring Status", silent ? "CRITICAL" : "OK", "String"));
        words.put(deneWord("Hypothalamus Monitoring Seconds Since Last Pulse", silenceMs / 1000, "Long"));

        if (silent) {
            if (!alertSent) {
                alertSent = true;
                String message = "No pulse received from " + teleonomeName + " for "
                        + (silenceMs / 60_000) + " minutes - Hypothalamus may be down";
                words.put(deneWord(EMERGENCY_ALERT_WORD_NAME, message, "String"));
                logger.warn("HypothalamusMonitoringTask: " + message);
            }
        } else if (alertSent) {
            // Pulse resumed since the last emergency - re-arm so the *next* silent
            // period raises its own alert instead of staying suppressed forever.
            alertSent = false;
            logger.info("HypothalamusMonitoringTask: pulse resumed for " + teleonomeName + ", alert re-armed");
        }

        return words;
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

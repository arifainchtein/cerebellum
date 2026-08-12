package com.teleonome.cerebellum.coulombcounting;

import com.teleonome.cerebellum.Task;
import com.teleonome.framework.TeleonomeConstants;
import com.teleonome.framework.denome.DenomeUtils;
import com.teleonome.framework.denome.Identity;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Calendar;
import java.util.Map;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Purpose: independent coulomb counting for Ra's 48V/660Ah lead-acid bank, run
 * entirely inside the Teleonome rather than trusting the PLSeries regulator's own
 * reported totals - Now:Charge/Now:Load/Today:Charge/Today:Load/State of Charge
 * all come from a Plasmatronics PL-series regulator read over serial by an Arduino
 * (the "PLSeries MicroController"/"PLComm Sensor" in Internal:Components/Sensors),
 * using its own shunt resistor for current sensing. This is NOT the Selectronic
 * SP PRO inverter/charger itself - that's a separate device, captured separately
 * (Purpose:Sensor Data:Selectronic, via camera/OCR of its display). The PLSeries
 * regulator's own SoC/AmpHour counters are a separate, external calculation that
 * can drift over months without a full-charge resync, and the aging bank makes
 * that drift worth watching directly.
 *
 * Reads Ra's own Purpose:Sensor Data:Now:Charge/Load (amps, from the PLSeries
 * regulator's shunt, via its Arduino/PLComm serial link) every pulse ("Self" task
 * - see Task.processSelf) and integrates net Ah itself, then reports it alongside
 * the PLSeries regulator's own Today:Charge/Today:Load total so drift between the
 * two is directly visible.
 *
 * netAhToday is a live in-memory accumulator, but it doesn't need its own persisted
 * ledger to survive a Cerebellum restart: Now:Charge/Now:Load are already remembered
 * every pulse into remembereddenewords_YYYY_M_D (same mechanism
 * RaGeneratorForecastTask reads history from), so on the first pulse after startup
 * bootstrapFromHistory() re-integrates that already-persisted history since local
 * midnight instead of starting at zero. That means there's exactly one source of
 * truth for this - the raw per-pulse readings - rather than a second accumulator
 * table that could quietly drift from it.
 */
public class CoulombCountingTask implements Task {

    // If the gap since the last pulse exceeds this, don't integrate across it (a
    // Cerebellum restart or a long serial dropout would otherwise inject a bogus
    // multi-hour Ah swing) - just resume clean integration from here.
    private static final double MAX_GAP_HOURS = 1.0;

    private static final String TIMEZONE = "Australia/Melbourne";

    private final String teleonomeName;
    private final String deviceName;
    private final Logger logger;

    private double netAhToday      = 0;
    private long   lastPulseMillis = 0;
    private double lastNetAmps     = 0;
    private int    lastResetDayOfYear = -1;

    public CoulombCountingTask(String teleonomeName, String deviceName) {
        this.teleonomeName = teleonomeName;
        this.deviceName = deviceName;
        this.logger = Logger.getLogger(getClass());
    }

    @Override public String getName()       { return "CoulombCountingTask"; }
    @Override public String getDeviceName() { return deviceName; }

    /**
     * Output DeneWords, one row per pulse (published to Purpose:Cerebellum:Ra and
     * shown on the Human Interface "Coulomb Counting" panel):
     *
     * <table border="1">
     *   <tr><th>DeneWord</th><th>Type</th><th>Meaning</th></tr>
     *   <tr><td>Coulomb Net AmpHours Today</td><td>double (Ah)</td>
     *       <td>Cerebellum's own independently-integrated net Ah balance since local
     *       midnight - trapezoidal integration of (Charge - Load) from Ra's own
     *       Now:Charge/Now:Load readings each pulse. Positive = net charging,
     *       negative = net discharging.</td></tr>
     *   <tr><td>PLSeries Net AmpHours Today</td><td>double (Ah)</td>
     *       <td>The PLSeries regulator's own reported net Ah today
     *       (Today:Charge - Today:Load, its own internal tracking) - shown for
     *       direct side-by-side comparison against Cerebellum's independent figure.</td></tr>
     *   <tr><td>Coulomb Drift AmpHours</td><td>double (Ah)</td>
     *       <td>Coulomb Net AmpHours Today minus PLSeries Net AmpHours Today.
     *       The signal this task exists to catch - a growing drift over days means
     *       the PLSeries regulator's internal counter is diverging from reality,
     *       which coulomb counters do without periodic full-charge resync.</td></tr>
     *   <tr><td>PLSeries State Of Charge</td><td>double (%)</td>
     *       <td>Pass-through of the PLSeries regulator's own live State of Charge
     *       (Now:State of Charge), included for context alongside the drift figures
     *       without needing to cross-reference the Now panel.</td></tr>
     * </table>
     */
    @Override
    public JSONArray processSelf(JSONObject pulse, String matchedSlot) throws Exception {
        resetOrBootstrapForNewDay(pulse);

        double voltage = getSelfDouble(pulse, "Now", "Battery Voltage");
        if (voltage <= 0) {
            // No usable reading this pulse (serial link down/stale) - skip it rather
            // than integrating a false 0A, and don't advance lastPulseMillis so the
            // next good reading integrates across the whole gap at once.
            logger.debug("CoulombCountingTask[" + deviceName + "]: no Battery Voltage reading, skipping pulse");
            return new JSONArray();
        }

        double charge = getSelfDouble(pulse, "Now", "Charge");
        double load   = getSelfDouble(pulse, "Now", "Load");
        double netAmps = charge - load;

        long nowMillis = pulse.optLong("Pulse Timestamp in Milliseconds", System.currentTimeMillis());

        if (lastPulseMillis > 0 && nowMillis > lastPulseMillis) {
            double dtHours = (nowMillis - lastPulseMillis) / 3_600_000.0;
            if (dtHours <= MAX_GAP_HOURS) {
                // Trapezoidal: average of the current and previous net-current readings.
                netAhToday += (netAmps + lastNetAmps) / 2.0 * dtHours;
            } else {
                logger.warn("CoulombCountingTask[" + deviceName + "]: gap of " + dtHours
                        + "h since last pulse, not integrating across it");
            }
        }
        lastPulseMillis = nowMillis;
        lastNetAmps = netAmps;

        double plSeriesChargeToday = getSelfDouble(pulse, "Today", "Charge");
        double plSeriesLoadToday   = getSelfDouble(pulse, "Today", "Load");
        double plSeriesNetToday    = plSeriesChargeToday - plSeriesLoadToday;
        double driftAh             = netAhToday - plSeriesNetToday;
        double plSeriesSoc         = getSelfDouble(pulse, "Now", "State of Charge");

        logger.debug("CoulombCountingTask[" + deviceName + "]: V=" + voltage
                + " Charge=" + charge + "A Load=" + load + "A netAhToday="
                + round2(netAhToday) + " plSeriesNetToday=" + round2(plSeriesNetToday)
                + " driftAh=" + round2(driftAh));

        JSONArray words = new JSONArray();
        words.put(deneWord("Coulomb Net AmpHours Today", round2(netAhToday), "double", "Ah"));
        words.put(deneWord("PLSeries Net AmpHours Today", round2(plSeriesNetToday), "double", "Ah"));
        words.put(deneWord("Coulomb Drift AmpHours", round2(driftAh), "double", "Ah"));
        words.put(deneWord("PLSeries State Of Charge", plSeriesSoc, "double", "%"));
        return words;
    }

    private void resetOrBootstrapForNewDay(JSONObject pulse) {
        Calendar now = Calendar.getInstance(TimeZone.getTimeZone(TIMEZONE));
        int dayOfYear = now.get(Calendar.DAY_OF_YEAR);
        if (lastResetDayOfYear != dayOfYear) {
            if (lastResetDayOfYear == -1) {
                // First pulse this JVM has seen - not necessarily the start of the day
                // (Cerebellum may have restarted mid-day), so recover progress so far
                // from already-persisted history instead of starting at zero.
                bootstrapFromHistory(pulse, now);
            } else {
                logger.info("CoulombCountingTask[" + deviceName + "]: new day, resetting net Ah (was "
                        + round2(netAhToday) + ")");
                netAhToday = 0;
            }
            lastResetDayOfYear = dayOfYear;
        }
    }

    /**
     * Re-integrates net Ah since local midnight from remembereddenewords history,
     * so a Cerebellum restart mid-day resumes from the correct running total instead
     * of silently reading low (as if less had been drawn than actually was) for the
     * rest of that day.
     */
    private void bootstrapFromHistory(JSONObject pulse, Calendar now) {
        Calendar midnight = (Calendar) now.clone();
        midnight.set(Calendar.HOUR_OF_DAY, 0);
        midnight.set(Calendar.MINUTE, 0);
        midnight.set(Calendar.SECOND, 0);
        midnight.set(Calendar.MILLISECOND, 0);
        long midnightMillis = midnight.getTimeInMillis();
        long nowMillis = pulse.optLong("Pulse Timestamp in Milliseconds", System.currentTimeMillis());

        PostgresqlPersistenceManager db = PostgresqlPersistenceManager.instance();
        JSONArray chargeHistory = db.getRemeberedDeneWordStart(
                "@" + teleonomeName + ":Purpose:Sensor Data:Now:Charge", midnightMillis, nowMillis);
        JSONArray loadHistory = db.getRemeberedDeneWordStart(
                "@" + teleonomeName + ":Purpose:Sensor Data:Now:Load", midnightMillis, nowMillis);

        // Merge by timestamp rather than assuming the two arrays are index-aligned -
        // Charge and Load are written together per pulse in practice, but a merge by
        // key is correct even if a row is ever missing on one side.
        TreeMap<Long, Double> chargeByMillis = toMillisMap(chargeHistory);
        TreeMap<Long, Double> loadByMillis   = toMillisMap(loadHistory);

        TreeMap<Long, Double> netByMillis = new TreeMap<>();
        double lastCharge = 0, lastLoad = 0;
        TreeSet<Long> allMillis = new TreeSet<>();
        allMillis.addAll(chargeByMillis.keySet());
        allMillis.addAll(loadByMillis.keySet());
        for (Long millis : allMillis) {
            if (chargeByMillis.containsKey(millis)) lastCharge = chargeByMillis.get(millis);
            if (loadByMillis.containsKey(millis))   lastLoad   = loadByMillis.get(millis);
            netByMillis.put(millis, lastCharge - lastLoad);
        }

        double bootstrappedAh = 0;
        Long prevMillis = null;
        double prevNetAmps = 0;
        for (Map.Entry<Long, Double> entry : netByMillis.entrySet()) {
            if (prevMillis != null) {
                double dtHours = (entry.getKey() - prevMillis) / 3_600_000.0;
                if (dtHours > 0 && dtHours <= MAX_GAP_HOURS) {
                    bootstrappedAh += (entry.getValue() + prevNetAmps) / 2.0 * dtHours;
                }
            }
            prevMillis = entry.getKey();
            prevNetAmps = entry.getValue();
        }

        netAhToday = bootstrappedAh;
        if (prevMillis != null) {
            lastPulseMillis = prevMillis;
            lastNetAmps = prevNetAmps;
        }
        logger.info("CoulombCountingTask[" + deviceName + "]: bootstrapped netAhToday=" + round2(netAhToday)
                + " from " + netByMillis.size() + " historical samples since midnight");
    }

    private TreeMap<Long, Double> toMillisMap(JSONArray history) {
        TreeMap<Long, Double> map = new TreeMap<>();
        for (int i = 0; i < history.length(); i++) {
            JSONObject row = history.getJSONObject(i);
            map.put(row.getLong("Pulse Timestamp in Milliseconds"), row.getDouble("Value"));
        }
        return map;
    }

    private double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ── Self-data navigation ───────────────────────────────────────────────────

    private double getSelfDouble(JSONObject pulse, String dene, String wordName) {
        try {
            Identity identity = new Identity(teleonomeName, "Purpose", "Sensor Data", dene, wordName);
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

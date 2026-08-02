package com.teleonome.cerebellum.telepathonregistry;

import com.teleonome.cerebellum.Task;
import com.teleonome.framework.persistence.PostgresqlPersistenceManager;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Purpose: maintain a persistent, never-forgets identity registry per telepathon
 * (serial number → canonical name), flag corrupted/duplicate identities and
 * fleet-wide silence, and seed a companion hardware/energy profile per device
 * (see "Device profile" section below and telepathon_profile.sql).
 *
 * Reconciles telepathon identity against a persistent, Cerebellum-owned registry
 * (Postgres table "telepathon_registry" - schema in
 * src/main/resources/sql/telepathon_registry.sql, not yet applied to any live DB),
 * and evaluates every registered device's freshness against a per-device-type
 * silence threshold.
 *
 * Motivating bug: on ChinampaMonitor, the same physical Daffodil shows up as two
 * telepathons - "TopTank" and "TOPT" - because a corrupted serial line garbled the
 * device-name token while the Serial Number token survived intact.
 * AnnabelleReader.java already guards against this
 * (DenomeManager.getKnownNameForSerial()), but that guard only scans the *live*
 * denome, which self-prunes any telepathon idle more than an hour - so a device
 * that's been quiet for over an hour "forgets" its serial number and a subsequent
 * corrupted-name arrival is wrongly accepted as a brand-new device. This task closes
 * that gap with a registry that never forgets a serial number, persisted in
 * Postgres instead of the live (self-pruning) denome.
 *
 * Identity policy (ASSUMPTION - not yet confirmed): sticky / first-seen-wins. The
 * name already on record for a serial number is treated as the true canonical name;
 * any later arrival under a different name for the same serial is flagged as a
 * mismatch to correct, never used to overwrite the canonical name. This mirrors
 * AnnabelleReader's existing getKnownNameForSerial() guard. If the real tie-break
 * rule should be different, decideReconciliation() below is the one method to change.
 * The same first-seen-wins weakness applies here too: whichever corrupted variant
 * happens to arrive first becomes "canonical" - the registry has no way to know
 * "Chinampa" is the clean name and "WestLWES"/"ghSUMP" aren't, if WestLWES (the
 * ChinampaMonitor record with Short Name "t") happened to arrive first. Not solved
 * here - flagging as a known gap.
 *
 * Secondary "fingerprint" match (matchFingerprint()): Serial Number alone isn't
 * always enough. On ChinampaMonitor, "Chinampa" (serial 28FF997A6723248), "ghSUMP"
 * (serial 54656D70 - which decodes from hex to the literal ASCII text "Temp", not a
 * real serial at all) and "WestLWES" (serial 50676ABE9F4A41AE) are three corrupted
 * records for what looks like one physical site - identical Device Type Id,
 * identical Latitude/longitude, and identical (or suffix-matching, where the head
 * got garbled too) Group identifier. Since the serial itself differs on all three,
 * the primary serial lookup finds nothing for ghSUMP/WestLWES and they'd otherwise
 * register as brand-new devices. When the primary lookup misses, a second pass
 * checks Device Type Id + Group identifier (exact, or last-4-chars match when
 * combined with a Latitude/longitude match) against every other serial already on
 * record, and flags a hit as "Possible Duplicate" rather than "New" - a weaker,
 * non-authoritative signal (a shared Group identifier doesn't guarantee one
 * physical device the way a serial number does, so this never overwrites a
 * canonical name the way a serial match's "Correction Needed" does - it only
 * surfaces a suggestion for review).
 *
 * This class does NOT perform any denome or database rename itself - the
 * "Telepathon Reported Name" / "Telepathon Canonical Name" DeneWords it emits are
 * the correction request; a Hypothalamus-side mutation (in progress separately)
 * is what actually executes the rename atomically against both the live denome and
 * the historical telepathon tables, consistent with "only the Cerebellum writes the
 * registry, only the Hypothalamus writes the denome."
 *
 * Lateness thresholds are explicit per Device Type Id, not derived from each
 * device's own history - device types differ physically (e.g. Daffodil's 600mAh
 * battery means longer overnight sleep gaps than a larger-battery Valentino-based
 * device), so an empirically "learned" threshold would get fooled by a device's own
 * normal-but-long quiet periods. Real numbers for Daffodil / Gloria / Chinampa /
 * Valentino-based devices are still placeholders below pending confirmation - see
 * TODOs. Day/night-aware thresholds (Daffodil sleeps longer at night) are not yet
 * implemented.
 *
 * Wiring note: Cerebellum's dispatch loop (Cerebellum.java) matches a Task Dene's
 * "Telepathon Type" DeneWord by exact string equality - there is no wildcard for
 * "any device type" today. To have this task fire for every device type, wire one
 * Task Dene per Device Type Id (Daffodil, Langley, Gloria, Chinampa, ...), all
 * pointing at this same class name, in each host's live Teleonome.denome (same
 * "no version-controlled template" caveat as LangleyTopologyTask - see BACKGROUND.md).
 */
public class TelepathonRegistryTask implements Task {

    private static final String REGISTRY_TABLE = "telepathon_registry";
    private static final String PROFILE_TABLE  = "telepathon_profile";

    // DeneWord name Cerebellum.runRegistrySweep() watches for and pulls out of
    // sweep()'s returned words to hand to publishEmergency() - a one-shot event,
    // not part of the ongoing status broadcast (see sweep() and evaluateLateness()).
    // Task.process()/sweep() have no direct MQTT access (see Task.java), so this
    // DeneWord-name convention is how a Task flags an emergency for Cerebellum.java
    // to publish on its behalf, without changing the Task interface itself.
    public static final String EMERGENCY_ALERT_WORD_NAME = "Telepathon Registry Emergency Alert";

    // Hardcoded per-Device-Type-Id hardware defaults - the last-resort fallback
    // when FactoryHardwareProfileClient is disabled or has no record for this
    // serial number (see telepathon_profile.sql). Battery capacity for Daffodil
    // matches the BATTERY_CAPACITY_MAH constant already established in
    // GraveyardShift/PulseTask/SolarAnalysis - everything else here is a
    // placeholder pending confirmed numbers. Package-visible so
    // FactoryHardwareProfileClient can return the same shape.
    static class DeviceProfileDefault {
        final String pcbBoards;
        final String batteryType;
        final Double batteryCapacityMah;
        final Double panelWatts;
        DeviceProfileDefault(String pcbBoards, String batteryType, Double batteryCapacityMah, Double panelWatts) {
            this.pcbBoards = pcbBoards;
            this.batteryType = batteryType;
            this.batteryCapacityMah = batteryCapacityMah;
            this.panelWatts = panelWatts;
        }
    }
    private static final Map<String, DeviceProfileDefault> DEVICE_TYPE_DEFAULT_PROFILES = new HashMap<>();
    static {
        DEVICE_TYPE_DEFAULT_PROFILES.put("Daffodil", new DeviceProfileDefault(
                "Wally Build 17, Daffodil Build 8", "LiFePO4 1S", 600.0, null));
        DEVICE_TYPE_DEFAULT_PROFILES.put("Langley", new DeviceProfileDefault(
                "Valentino Build 2, Langley Build 2", "LiFePO4 1S", null, null));
    }

    // [WARNING_SEC, CRITICAL_SEC] per Device Type Id. Langley is the one entry
    // backed by real data (~1s pulse cadence, matches LangleySilenceSweep). Every
    // other entry is a placeholder pending real numbers - see class javadoc.
    private static final Map<String, long[]> SILENCE_THRESHOLDS_SEC = new HashMap<>();
    static {
        SILENCE_THRESHOLDS_SEC.put("Langley", new long[]{5 * 60, 20 * 60});
        // TODO: placeholder, derived from GraveyardShift's documented ~900s Daffodil
        // sleep cycle (2x/4x that) - not a real confirmed threshold, and doesn't yet
        // account for Daffodil's longer overnight gaps.
        SILENCE_THRESHOLDS_SEC.put("Daffodil", new long[]{30 * 60, 60 * 60});
    }
    // TODO: placeholder for any Device Type Id not listed above (Gloria, Chinampa,
    // Valentino-based devices, ...) - needs real per-type numbers.
    private static final long[] DEFAULT_THRESHOLDS_SEC = {30 * 60, 60 * 60};

    // Shared across every per-device instance (same rationale as
    // LangleyTopologyTask's static fleet map) - one HttpClient, one
    // lib/app.properties read, not one per device.
    private static final FactoryHardwareProfileClient FACTORY_CLIENT = new FactoryHardwareProfileClient();

    private final String teleonomeName;
    private final String deviceName;
    private final Logger logger;

    public TelepathonRegistryTask(String teleonomeName, String deviceName) {
        this.teleonomeName = teleonomeName;
        this.deviceName = deviceName;
        this.logger = Logger.getLogger(getClass());
    }

    @Override public String getName()       { return "TelepathonRegistryTask"; }
    @Override public String getDeviceName() { return deviceName; }

    @Override
    public JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
        JSONArray words = new JSONArray();

        String reportedName    = telepathon.optString("Name", "");
        String serialNumber    = telepathon.optString("Serial Number", "");
        String deviceType      = getDeneWordString(telepathon, "Configuration", "Device Type Id");
        String groupIdentifier = getDeneWordString(telepathon, "Configuration", "Group identifier");
        Double latitude        = getDeneWordDouble(telepathon, "Configuration", "Latitude");
        Double longitude       = getDeneWordDouble(telepathon, "Configuration", "longitude");
        String rawData         = telepathon.optString("Raw Data", "");
        long nowEpochSec       = System.currentTimeMillis() / 1000;

        if (reportedName.isEmpty() || serialNumber.isEmpty()) {
            logger.debug("TelepathonRegistryTask[" + deviceName
                    + "]: telepathon missing Name or Serial Number, skipping");
            return words;
        }

        Connection conn = PostgresqlPersistenceManager.instance().getConnection();
        try {
            ReconcileOutcome outcome = reconcile(conn, serialNumber, reportedName, deviceType,
                    groupIdentifier, latitude, longitude, rawData, nowEpochSec);

            words.put(deneWord("Telepathon Identity Status", outcome.status, "String"));
            words.put(deneWord("Telepathon Canonical Name", outcome.canonicalName, "String"));
            words.put(deneWord("Telepathon Serial Number", serialNumber, "String"));
            if (outcome.mismatch) {
                words.put(deneWord("Telepathon Reported Name", reportedName, "String"));
            }
            if (outcome.possibleDuplicateOf != null) {
                words.put(deneWord("Telepathon Possible Duplicate Of", outcome.possibleDuplicateOf, "String"));
            }
        } finally {
            PostgresqlPersistenceManager.instance().closeConnection(conn);
        }

        return words;
    }

    /**
     * Fleet-wide lateness sweep - deliberately NOT called from process(). A
     * per-pulse Task can only ever be triggered by a device that's still pulsing,
     * so it can never notice a device that's stopped entirely, and Cerebellum.java
     * only ever attaches a Task's returned words to whichever device triggered the
     * call - there's no clean way for process() to report on OTHER devices.
     * Called instead from a dedicated time-based thread in Cerebellum.java (same
     * split the denome mutation system already makes between time-based and
     * event-based mutations), independent of pulse arrival, and broadcast under
     * its own synthetic "Telepathon Registry" Dene rather than a real device's.
     *
     * Also emits one EMERGENCY_ALERT_WORD_NAME word per device whose lateness
     * status just transitioned into WARNING/CRITICAL (see evaluateLateness()) -
     * Cerebellum.runRegistrySweep() pulls these out and calls publishEmergency()
     * with them rather than folding them into the ongoing status broadcast, so a
     * device that stays late doesn't re-alert on every 5-minute sweep.
     */
    public JSONArray sweep(long nowEpochSec) throws SQLException {
        JSONArray words = new JSONArray();
        Connection conn = PostgresqlPersistenceManager.instance().getConnection();
        try {
            List<LateDevice> late = evaluateLateness(conn, nowEpochSec);
            String worst = "OK";
            for (LateDevice d : late) {
                if ("CRITICAL".equals(d.status)) { worst = "CRITICAL"; break; }
                if ("WARNING".equals(d.status)) worst = "WARNING";
            }
            words.put(deneWord("Telepathon Registry Status", worst, "String"));
            words.put(deneWord("Telepathon Registry Late Devices", lateDevicesToJson(late), "String"));
            for (LateDevice d : late) {
                if (!d.alertWorthy) continue;
                String message = "Telepathon " + d.canonicalName + " has gone missing (no data for "
                        + (d.ageSec / 60) + " minutes)";
                words.put(deneWord(EMERGENCY_ALERT_WORD_NAME, message, "String"));
            }
        } finally {
            PostgresqlPersistenceManager.instance().closeConnection(conn);
        }
        return words;
    }

    // ── Reconciliation ───────────────────────────────────────────────────────────

    private static class ReconcileOutcome {
        final String status;         // "New" | "OK" | "Correction Needed" | "Possible Duplicate"
        final String canonicalName;
        final boolean mismatch;
        final String possibleDuplicateOf;  // non-null only when status == "Possible Duplicate"
        ReconcileOutcome(String status, String canonicalName, boolean mismatch) {
            this(status, canonicalName, mismatch, null);
        }
        ReconcileOutcome(String status, String canonicalName, boolean mismatch, String possibleDuplicateOf) {
            this.status = status;
            this.canonicalName = canonicalName;
            this.mismatch = mismatch;
            this.possibleDuplicateOf = possibleDuplicateOf;
        }
    }

    // Pure decision, no DB access - kept separate from reconcile() so it can be
    // exercised directly by a throwaway test driver without a live connection.
    ReconcileOutcome decideReconciliation(String existingCanonicalNameOrNull, String reportedName) {
        if (existingCanonicalNameOrNull == null) {
            return new ReconcileOutcome("New", reportedName, false);
        }
        if (existingCanonicalNameOrNull.equals(reportedName)) {
            return new ReconcileOutcome("OK", existingCanonicalNameOrNull, false);
        }
        return new ReconcileOutcome("Correction Needed", existingCanonicalNameOrNull, true);
    }

    // ── Secondary fingerprint match ─────────────────────────────────────────────

    // Candidate loaded from the registry to fingerprint-match a serial-less-match
    // record against. Package-visible (not private) so a throwaway test driver can
    // build fixtures directly without touching the DB.
    static class RegistryFingerprint {
        final String canonicalName;
        final String deviceType;
        final String groupIdentifier;
        final Double latitude;
        final Double longitude;
        RegistryFingerprint(String canonicalName, String deviceType, String groupIdentifier,
                             Double latitude, Double longitude) {
            this.canonicalName = canonicalName;
            this.deviceType = deviceType;
            this.groupIdentifier = groupIdentifier;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    private static final int    GROUP_ID_SUFFIX_MIN_LEN = 4;
    private static final double LOCATION_EPSILON = 0.001;

    // Pure logic, no DB access - exercised directly against the real ChinampaMonitor
    // Chinampa/ghSUMP/WestLWES records that motivated this (see class javadoc).
    // Requires the same Device Type Id, then either an exact Group identifier match,
    // or a matching last-GROUP_ID_SUFFIX_MIN_LEN-chars tail combined with a
    // Latitude/longitude match (covers the "front of the group id got garbled too"
    // case, like WestLWES's, without leaning on location alone - multiple distinct
    // real units can plausibly share one site).
    String matchFingerprint(String deviceType, String groupIdentifier, Double latitude, Double longitude,
                             List<RegistryFingerprint> candidates) {
        if (deviceType == null) return null;
        for (RegistryFingerprint c : candidates) {
            if (!deviceType.equals(c.deviceType)) continue;
            boolean groupExact = groupIdentifier != null && groupIdentifier.equals(c.groupIdentifier);
            boolean groupSuffix = suffixMatches(groupIdentifier, c.groupIdentifier, GROUP_ID_SUFFIX_MIN_LEN);
            boolean locationMatches = latitude != null && longitude != null
                    && c.latitude != null && c.longitude != null
                    && Math.abs(latitude - c.latitude) < LOCATION_EPSILON
                    && Math.abs(longitude - c.longitude) < LOCATION_EPSILON;
            if (groupExact || (groupSuffix && locationMatches)) {
                return c.canonicalName;
            }
        }
        return null;
    }

    boolean suffixMatches(String a, String b, int minLen) {
        if (a == null || b == null || a.length() < minLen || b.length() < minLen) return false;
        return a.substring(a.length() - minLen).equals(b.substring(b.length() - minLen));
    }

    private List<RegistryFingerprint> loadFingerprintCandidates(Connection conn, String deviceType,
                                                                  String excludeSerialNumber) throws SQLException {
        List<RegistryFingerprint> candidates = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT canonical_name, device_type, group_identifier, latitude, longitude FROM " + REGISTRY_TABLE
                        + " WHERE teleonome_name = ? AND device_type = ? AND serial_number <> ?")) {
            select.setString(1, teleonomeName);
            select.setString(2, deviceType);
            select.setString(3, excludeSerialNumber);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    double lat = rs.getDouble(4);
                    Double latitude = rs.wasNull() ? null : lat;
                    double lon = rs.getDouble(5);
                    Double longitude = rs.wasNull() ? null : lon;
                    candidates.add(new RegistryFingerprint(rs.getString(1), rs.getString(2), rs.getString(3),
                            latitude, longitude));
                }
            }
        }
        return candidates;
    }

    private ReconcileOutcome reconcile(Connection conn, String serialNumber, String reportedName,
                                        String deviceType, String groupIdentifier, Double latitude, Double longitude,
                                        String rawData, long nowEpochSec) throws SQLException {
        String existingCanonicalName = null;
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT canonical_name FROM " + REGISTRY_TABLE
                        + " WHERE teleonome_name = ? AND serial_number = ?")) {
            select.setString(1, teleonomeName);
            select.setString(2, serialNumber);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) existingCanonicalName = rs.getString(1);
            }
        }

        ReconcileOutcome outcome = decideReconciliation(existingCanonicalName, reportedName);

        ensureProfileDefaults(conn, serialNumber, deviceType);

        if (existingCanonicalName == null) {
            String duplicateOf = matchFingerprint(deviceType, groupIdentifier, latitude, longitude,
                    loadFingerprintCandidates(conn, deviceType, serialNumber));

            try (PreparedStatement insert = conn.prepareStatement(
                    "INSERT INTO " + REGISTRY_TABLE + " (teleonome_name, serial_number, canonical_name, "
                            + "device_type, group_identifier, latitude, longitude, first_seen_epoch, "
                            + "last_seen_epoch, last_seen_reported_name) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                insert.setString(1, teleonomeName);
                insert.setString(2, serialNumber);
                insert.setString(3, reportedName);
                insert.setString(4, deviceType);
                insert.setString(5, groupIdentifier);
                if (latitude != null) insert.setDouble(6, latitude); else insert.setNull(6, java.sql.Types.DOUBLE);
                if (longitude != null) insert.setDouble(7, longitude); else insert.setNull(7, java.sql.Types.DOUBLE);
                insert.setLong(8, nowEpochSec);
                insert.setLong(9, nowEpochSec);
                insert.setString(10, reportedName);
                insert.executeUpdate();
            }

            if (duplicateOf != null) {
                outcome = new ReconcileOutcome("Possible Duplicate", reportedName, false, duplicateOf);
                logger.warn("TelepathonRegistryTask: possible duplicate - serial='" + serialNumber
                        + "' name='" + reportedName + "' fingerprint-matches existing device '" + duplicateOf
                        + "'. Raw Data: " + rawData);
            } else {
                logger.info("TelepathonRegistryTask: new telepathon registered - serial='" + serialNumber
                        + "' name='" + reportedName + "' type='" + deviceType + "'");
            }
        } else {
            try (PreparedStatement update = conn.prepareStatement(
                    "UPDATE " + REGISTRY_TABLE + " SET last_seen_epoch = ?, last_seen_reported_name = ?, "
                            + "device_type = ? WHERE teleonome_name = ? AND serial_number = ?")) {
                update.setLong(1, nowEpochSec);
                update.setString(2, reportedName);
                update.setString(3, deviceType);
                update.setString(4, teleonomeName);
                update.setString(5, serialNumber);
                update.executeUpdate();
            }
            if (outcome.mismatch) {
                logger.warn("TelepathonRegistryTask: identity mismatch - serial='" + serialNumber
                        + "' registered as '" + existingCanonicalName + "' but this pulse reports name='"
                        + reportedName + "'. Raw Data: " + rawData);
            }
        }
        return outcome;
    }

    // ── Device profile ───────────────────────────────────────────────────────────

    static class DeviceProfile {
        final String pcbBoards;
        final String batteryType;
        final Double batteryCapacityMah;
        final Double panelWatts;
        final String profileSource;   // "Default" | "Factory" | "Manual"
        final String energyRecommendation;
        final String energyRecommendationStatus;
        DeviceProfile(String pcbBoards, String batteryType, Double batteryCapacityMah, Double panelWatts,
                      String profileSource, String energyRecommendation, String energyRecommendationStatus) {
            this.pcbBoards = pcbBoards;
            this.batteryType = batteryType;
            this.batteryCapacityMah = batteryCapacityMah;
            this.panelWatts = panelWatts;
            this.profileSource = profileSource;
            this.energyRecommendation = energyRecommendation;
            this.energyRecommendationStatus = energyRecommendationStatus;
        }
    }

    // Seeds a profile row the first time a serial number is seen. Never
    // overwrites an existing row - once profile_source is 'Manual' (a real
    // injection form, not yet built, or a hand edit) this must not stomp it
    // back to a lesser source, and even a 'Factory'/'Default' row is left
    // alone once written (no periodic refresh - see FactoryHardwareProfileClient
    // javadoc for why re-querying Factory on every pulse isn't done here).
    //
    // Source preference: FactoryHardwareProfileClient (real per-product-definition
    // BOM, keyed by this exact serial number) first, falling back to the
    // hardcoded per-Device-Type-Id defaults only when the client is disabled or
    // has no record for this serial - see class javadoc on
    // FactoryHardwareProfileClient for the endpoint contract this depends on.
    private void ensureProfileDefaults(Connection conn, String serialNumber, String deviceType) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT 1 FROM " + PROFILE_TABLE + " WHERE teleonome_name = ? AND serial_number = ?")) {
            select.setString(1, teleonomeName);
            select.setString(2, serialNumber);
            try (ResultSet rs = select.executeQuery()) {
                if (rs.next()) return; // profile already exists - leave it alone regardless of source
            }
        }

        DeviceProfileDefault def = FACTORY_CLIENT.fetch(serialNumber);
        String profileSource = "Default";
        if (def != null) {
            profileSource = "Factory";
        } else {
            def = DEVICE_TYPE_DEFAULT_PROFILES.get(deviceType);
        }

        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO " + PROFILE_TABLE + " (teleonome_name, serial_number, device_type, "
                        + "pcb_boards, battery_type, battery_capacity_mah, panel_watts, profile_source) "
                        + "VALUES (?,?,?,?,?,?,?,?)")) {
            insert.setString(1, teleonomeName);
            insert.setString(2, serialNumber);
            insert.setString(3, deviceType);
            insert.setString(4, def != null ? def.pcbBoards : null);
            insert.setString(5, def != null ? def.batteryType : null);
            if (def != null && def.batteryCapacityMah != null) insert.setDouble(6, def.batteryCapacityMah);
            else insert.setNull(6, java.sql.Types.DOUBLE);
            if (def != null && def.panelWatts != null) insert.setDouble(7, def.panelWatts);
            else insert.setNull(7, java.sql.Types.DOUBLE);
            insert.setString(8, profileSource);
            insert.executeUpdate();
        }
        logger.info("TelepathonRegistryTask: seeded " + profileSource + " profile for serial='" + serialNumber
                + "' type='" + deviceType + "'" + (def == null ? " (no known defaults for this Device Type Id)" : ""));
    }

    // Not yet called from anywhere - a read hook for whoever builds the
    // recommendation logic / injection form / webapp view on top of this table.
    DeviceProfile getProfile(Connection conn, String serialNumber) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT pcb_boards, battery_type, battery_capacity_mah, panel_watts, profile_source, "
                        + "energy_recommendation, energy_recommendation_status FROM " + PROFILE_TABLE
                        + " WHERE teleonome_name = ? AND serial_number = ?")) {
            select.setString(1, teleonomeName);
            select.setString(2, serialNumber);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) return null;
                double capMah = rs.getDouble(3);
                Double batteryCapacityMah = rs.wasNull() ? null : capMah;
                double watts = rs.getDouble(4);
                Double panelWatts = rs.wasNull() ? null : watts;
                return new DeviceProfile(rs.getString(1), rs.getString(2), batteryCapacityMah, panelWatts,
                        rs.getString(5), rs.getString(6), rs.getString(7));
            }
        }
    }

    // ── Lateness ─────────────────────────────────────────────────────────────────

    private static class LateDevice {
        final String canonicalName;
        final String deviceType;
        final long ageSec;
        final String status;
        // True only when this sweep is what moved the device from its previously
        // recorded alert status into this one (see evaluateLateness()) - the
        // signal sweep() uses to decide which devices are worth an Emergency
        // Channel publish, as opposed to a device that's simply still late.
        final boolean alertWorthy;
        LateDevice(String canonicalName, String deviceType, long ageSec, String status, boolean alertWorthy) {
            this.canonicalName = canonicalName;
            this.deviceType = deviceType;
            this.ageSec = ageSec;
            this.status = status;
            this.alertWorthy = alertWorthy;
        }
    }

    long[] thresholdsFor(String deviceType) {
        long[] thresholds = SILENCE_THRESHOLDS_SEC.get(deviceType);
        return thresholds != null ? thresholds : DEFAULT_THRESHOLDS_SEC;
    }

    String statusFor(long ageSec, long[] thresholds) {
        if (ageSec >= thresholds[1]) return "CRITICAL";
        if (ageSec >= thresholds[0]) return "WARNING";
        return "OK";
    }

    // Also persists each device's computed status back to last_alert_status so
    // the *next* sweep can tell a fresh transition (worth an emergency alert)
    // apart from a device that's simply still late from before (not worth
    // re-alerting on every 5-minute sweep) - see LateDevice.alertWorthy and
    // telepathon_registry.sql's column comment.
    private List<LateDevice> evaluateLateness(Connection conn, long nowEpochSec) throws SQLException {
        List<LateDevice> late = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT serial_number, canonical_name, device_type, last_seen_epoch, last_alert_status "
                        + "FROM " + REGISTRY_TABLE + " WHERE teleonome_name = ?")) {
            select.setString(1, teleonomeName);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String serialNumber   = rs.getString(1);
                    String canonicalName  = rs.getString(2);
                    String deviceType     = rs.getString(3);
                    long lastSeenEpochSec = rs.getLong(4);
                    String previousStatus = rs.getString(5); // null = never swept, treat as "OK"
                    long ageSec = nowEpochSec - lastSeenEpochSec;
                    String status = statusFor(ageSec, thresholdsFor(deviceType));

                    boolean statusChanged = !status.equals(previousStatus == null ? "OK" : previousStatus);
                    if (statusChanged) {
                        updateAlertStatus(conn, serialNumber, status);
                    }
                    // Never alert on recovery (status == "OK") - only on entering or
                    // escalating within WARNING/CRITICAL.
                    boolean alertWorthy = statusChanged && !"OK".equals(status);

                    if (!"OK".equals(status)) {
                        late.add(new LateDevice(canonicalName, deviceType, ageSec, status, alertWorthy));
                    }
                }
            }
        }
        return late;
    }

    private void updateAlertStatus(Connection conn, String serialNumber, String status) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE " + REGISTRY_TABLE + " SET last_alert_status = ? WHERE teleonome_name = ? AND serial_number = ?")) {
            update.setString(1, status);
            update.setString(2, teleonomeName);
            update.setString(3, serialNumber);
            update.executeUpdate();
        }
    }

    private String lateDevicesToJson(List<LateDevice> late) {
        JSONArray arr = new JSONArray();
        for (LateDevice d : late) {
            JSONObject o = new JSONObject();
            o.put("Name", d.canonicalName);
            o.put("Device Type", d.deviceType);
            o.put("Seconds Since Last Seen", d.ageSec);
            o.put("Status", d.status);
            arr.put(o);
        }
        return arr.toString();
    }

    // ── Telepathon navigation ────────────────────────────────────────────────────

    private String getDeneWordString(JSONObject telepathon, String deneName, String wordName) {
        try {
            JSONArray denes = telepathon.getJSONArray("Denes");
            for (int i = 0; i < denes.length(); i++) {
                JSONObject dene = denes.getJSONObject(i);
                if (deneName.equals(dene.getString("Name"))) {
                    JSONArray words = dene.getJSONArray("DeneWords");
                    for (int j = 0; j < words.length(); j++) {
                        JSONObject word = words.getJSONObject(j);
                        if (wordName.equals(word.getString("Name"))) {
                            return word.getString("Value");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private Double getDeneWordDouble(JSONObject telepathon, String deneName, String wordName) {
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
        return null;
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

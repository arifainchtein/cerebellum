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
 * Identity policy (ASSUMPTION - not yet confirmed): sticky / first-seen-wins for
 * the serial->name ledger specifically (upsertLedger()). The name already on
 * record for a serial number is treated as the true canonical name; any later
 * arrival under a different name for the same serial is flagged as a mismatch to
 * correct, never used to overwrite the canonical name. This mirrors
 * AnnabelleReader's existing getKnownNameForSerial() guard. Note this sticky
 * policy applies only to that direction (same serial, new name) - the opposite
 * direction (same name, new serial) is deliberately NOT first-seen-wins; see the
 * official/temporary-list curation described further down, which exists
 * specifically to avoid that weakness.
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
 *
 * Curation (added after the "six FISH Daffodils" bug - ChinampaMonitor had one
 * real FISH/Daffodil and five bogus ones, all showing in the webapp's Registry
 * Status popup because sweep() used to list every row of telepathon_registry -
 * one row per distinct serial number EVER reported under that name, with no
 * de-duplication at all): a reported (name, device_type) identity is no longer
 * trusted as "official" - and therefore no longer shown to the webapp - just
 * because a pulse said so. It must be confirmed by one of two independent,
 * deliberately redundant paths, both implemented below:
 *
 *   1. reconcileFromHistory() - queries every historical telepathon_YYYY_M_D
 *      reading for this reported name (PostgresqlPersistenceManager.
 *      getTelepathonDataStart(), same helper LangleySilenceSweep uses), tallies
 *      Serial Number occurrences restricted to this device_type, and promotes
 *      the serial if it's a clear (>50%) majority. This is what self-heals an
 *      already-broken registry like the six-FISH case within one sweep, without
 *      waiting on new pulses.
 *   2. checkPromotion() - a live, forward-looking signal for genuinely new
 *      devices: an unconfirmed (name, device_type, serial) triple accumulates in
 *      the "temporary list" (telepathon_registry_pending) across pulses, and
 *      only gets promoted once it recurs "Promotion Threshold"-or-more times.
 *      This exists specifically to defeat the old first-seen-wins flaw - if the
 *      very first telepathon ever received for a name has a corrupted/wrong
 *      serial number, first-seen-wins would lock that in forever (the registry
 *      never forgets). Requiring recurrence means a one-off bad reading can't
 *      become "official" by itself.
 *
 * Pending rows older than "Days in Temporary List" days are purged
 * (purgeStalePending()) before every promotion check, so a single corrupted
 * reading can't sit accumulating count indefinitely. "Days in Temporary
 * List", "Promotion Threshold", and "Days Before Stale" are all DeneWords
 * under Internal:Cerebellum:Configuration in the live Teleonome.denome - see
 * CerebellumConfigClient for the exact shape and defaults (7 days / 3
 * occurrences / 2 days) used when that Dene is missing or unreachable. The
 * first two govern promoting a *new* identity into the official registry;
 * "Days Before Stale" is unrelated - it governs how long an *already-official*
 * device can go silent before evaluateDeviceStatuses() escalates it from
 * "Late" (WARNING) to "Stale" (CRITICAL) - see thresholdsFor().
 *
 * telepathon_registry (the original table) keeps its original job as a raw,
 * never-forgets ledger keyed by serial_number - upsertLedger() still uses it for
 * the original TopTank/TOPT rename-detection case (same serial, new reported
 * name). telepathon_registry_official is the new, curated, de-duplicated table -
 * at most one row per (canonical_name, device_type) - that sweep() now reads for
 * the webapp. See telepathon_registry.sql for both new tables' schema/comments.
 */
public class TelepathonRegistryTask implements Task {

    private static final String REGISTRY_TABLE = "telepathon_registry";
    private static final String OFFICIAL_TABLE = "telepathon_registry_official";
    private static final String PENDING_TABLE  = "telepathon_registry_pending";
    private static final String PROFILE_TABLE  = "telepathon_profile";

    // How far back reconcileFromHistory() scans telepathon_YYYY_M_D tables when
    // tallying Serial Number occurrences for a reported name - generous enough to
    // catch a majority even for a device that pulses rarely, without scanning the
    // database's entire history on every unresolved pulse.
    private static final long HISTORY_LOOKBACK_DAYS = 90;

    // Shared across every per-device instance (same rationale as FACTORY_CLIENT
    // below) - one HttpClient, cached per-teleonome, not one per device.
    private static final CerebellumConfigClient CONFIG_CLIENT = new CerebellumConfigClient();

    // DeneWord name Cerebellum.runRegistrySweep() watches for and pulls out of
    // sweep()'s returned words to hand to publishEmergency() - a one-shot event,
    // not part of the ongoing status broadcast (see sweep() and evaluateDeviceStatuses()).
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

    // [WARNING_SEC, ...] per Device Type Id - only element [0] (WARNING, i.e. "Late")
    // is used any more. Langley's WARNING value is backed by real data (~1s pulse
    // cadence, matches LangleySilenceSweep); every other entry is a placeholder pending
    // real numbers - see class javadoc. A second element is kept in each array for
    // history/documentation but ignored by thresholdsFor(): the old CRITICAL cutoff
    // this used to hold has been replaced by the denome-configurable "Days Before
    // Stale" (teleonome-wide, not per-device-type - see CerebellumConfigClient). That
    // change exists because an established device with days of continuous history
    // (e.g. Chinampa) was going "Stale" after the same ~1hr silence gap used to flag a
    // barely-promoted device - there was no way to give a proven device more benefit of
    // the doubt before this.
    private static final Map<String, long[]> SILENCE_THRESHOLDS_SEC = new HashMap<>();
    static {
        SILENCE_THRESHOLDS_SEC.put("Langley", new long[]{5 * 60, 20 * 60});
        // TODO: placeholder, derived from GraveyardShift's documented ~900s Daffodil
        // sleep cycle (2x/4x that) - not a real confirmed threshold, and doesn't yet
        // account for Daffodil's longer overnight gaps.
        SILENCE_THRESHOLDS_SEC.put("Daffodil", new long[]{30 * 60, 60 * 60});
    }
    // TODO: placeholder for any Device Type Id not listed above (Gloria, Chinampa,
    // Valentino-based devices, ...) - needs a real per-type WARNING number.
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
            CerebellumConfigClient.RegistryConfig config = CONFIG_CLIENT.get(teleonomeName);
            ReconcileOutcome outcome = reconcile(conn, serialNumber, reportedName, deviceType,
                    groupIdentifier, latitude, longitude, rawData, nowEpochSec, config);

            words.put(deneWord("Telepathon Identity Status", outcome.status, "String"));
            words.put(deneWord("Telepathon Canonical Name", outcome.canonicalName, "String"));
            words.put(deneWord("Telepathon Serial Number", serialNumber, "String"));
            if (!"OK".equals(outcome.status)) {
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
     * status just transitioned into WARNING/CRITICAL (see evaluateDeviceStatuses()) -
     * Cerebellum.runRegistrySweep() pulls these out and calls publishEmergency()
     * with them rather than folding them into the ongoing status broadcast, so a
     * device that stays late doesn't re-alert on every 5-minute sweep.
     */
    public JSONArray sweep(long nowEpochSec) throws SQLException {
        JSONArray words = new JSONArray();
        Connection conn = PostgresqlPersistenceManager.instance().getConnection();
        try {
            CerebellumConfigClient.RegistryConfig config = CONFIG_CLIENT.get(teleonomeName);
            // Run before evaluateDeviceStatuses() so a device that isn't currently
            // pulsing (and therefore never calls process()/reconcile() itself) still
            // gets resolved onto the official list within one sweep interval, not
            // only when it happens to pulse again - see class javadoc.
            purgeStalePending(conn, nowEpochSec, config.daysInTemporaryList);
            reconcileAllPendingGroups(conn, nowEpochSec, config.promotionThreshold);

            List<DeviceStatus> all = evaluateDeviceStatuses(conn, nowEpochSec, config.daysBeforeStale);
            words.put(deneWord("Telepathon Registry Pending Devices", pendingToJson(conn), "String"));
            List<DeviceStatus> late = new ArrayList<>();
            String worst = "OK";
            for (DeviceStatus d : all) {
                if (!"OK".equals(d.status)) late.add(d);
                if ("CRITICAL".equals(d.status)) worst = "CRITICAL";
                else if ("WARNING".equals(d.status) && !"CRITICAL".equals(worst)) worst = "WARNING";
            }
            words.put(deneWord("Telepathon Registry Status", worst, "String"));
            words.put(deneWord("Telepathon Registry Devices", devicesToJson(all), "String"));
            words.put(deneWord("Telepathon Registry Late Devices", devicesToJson(late), "String"));
            for (DeviceStatus d : late) {
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
        final String status;         // "OK" | "Correction Needed" | "Possible Duplicate" |
                                      // "Pending - New" | "Pending - Serial Mismatch"
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
                                        String rawData, long nowEpochSec,
                                        CerebellumConfigClient.RegistryConfig config) throws SQLException {
        // Ledger update - unchanged rename-detection behaviour, keyed by serial
        // number (the original TopTank/TOPT case: same physical device, corrupted
        // reported name). Independent of the name+type official/pending machinery
        // below, which exists for the opposite bug (same reported name, several
        // different serials).
        String ledgerCanonicalName = upsertLedger(conn, serialNumber, reportedName, deviceType,
                groupIdentifier, latitude, longitude, nowEpochSec);
        boolean ledgerMismatch = ledgerCanonicalName != null && !ledgerCanonicalName.equals(reportedName);
        if (ledgerMismatch) {
            logger.warn("TelepathonRegistryTask: identity mismatch - serial='" + serialNumber
                    + "' registered as '" + ledgerCanonicalName + "' but this pulse reports name='"
                    + reportedName + "'. Raw Data: " + rawData);
        }

        ensureProfileDefaults(conn, serialNumber, deviceType);

        String officialSerial = selectOfficialSerial(conn, reportedName, deviceType);
        if (serialNumber.equals(officialSerial)) {
            updateOfficialLastSeen(conn, reportedName, deviceType, nowEpochSec);
            return ledgerMismatch
                    ? new ReconcileOutcome("Correction Needed", ledgerCanonicalName, true)
                    : new ReconcileOutcome("OK", reportedName, false);
        }

        // officialSerial is either null (no confirmed device behind this name+type
        // yet) or disagrees with this pulse's serial - this pulse does not get to
        // become official just by showing up (see class javadoc). It joins the
        // temporary list instead, and every arrival re-runs both promotion checks.
        upsertPending(conn, reportedName, deviceType, serialNumber, groupIdentifier, latitude, longitude, nowEpochSec);
        purgeStalePending(conn, nowEpochSec, config.daysInTemporaryList);

        String promoted = checkPromotion(conn, reportedName, deviceType, nowEpochSec, config.promotionThreshold);
        if (promoted == null) {
            promoted = reconcileFromHistory(conn, reportedName, deviceType, nowEpochSec);
        }
        if (promoted != null) {
            return promoted.equals(serialNumber)
                    ? new ReconcileOutcome("OK", reportedName, false)
                    : new ReconcileOutcome("Correction Needed", reportedName, true);
        }

        String duplicateOf = matchFingerprint(deviceType, groupIdentifier, latitude, longitude,
                loadFingerprintCandidates(conn, deviceType, serialNumber));
        if (duplicateOf != null) {
            logger.warn("TelepathonRegistryTask: possible duplicate - serial='" + serialNumber
                    + "' name='" + reportedName + "' fingerprint-matches existing device '" + duplicateOf
                    + "'. Raw Data: " + rawData);
            return new ReconcileOutcome("Possible Duplicate", reportedName, false, duplicateOf);
        }

        logger.info("TelepathonRegistryTask: serial='" + serialNumber + "' name='" + reportedName
                + "' type='" + deviceType + "' awaiting official confirmation (temporary list)");
        return new ReconcileOutcome(officialSerial == null ? "Pending - New" : "Pending - Serial Mismatch",
                reportedName, false);
    }

    // Raw ledger upsert, keyed by serial number - returns the canonical name
    // already on record for this serial before this call (null if this serial has
    // never been seen), so the caller can detect a rename/corruption of the
    // reported name for an otherwise-known physical device.
    private String upsertLedger(Connection conn, String serialNumber, String reportedName, String deviceType,
                                 String groupIdentifier, Double latitude, Double longitude,
                                 long nowEpochSec) throws SQLException {
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

        if (existingCanonicalName == null) {
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
            logger.info("TelepathonRegistryTask: ledger - new serial seen - serial='" + serialNumber
                    + "' name='" + reportedName + "' type='" + deviceType + "'");
            return null;
        }

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
        return existingCanonicalName;
    }

    // ── Official / temporary-list curation ──────────────────────────────────────

    private String selectOfficialSerial(Connection conn, String reportedName, String deviceType) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT serial_number FROM " + OFFICIAL_TABLE
                        + " WHERE teleonome_name = ? AND canonical_name = ? AND device_type = ?")) {
            select.setString(1, teleonomeName);
            select.setString(2, reportedName);
            select.setString(3, deviceType);
            try (ResultSet rs = select.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private void updateOfficialLastSeen(Connection conn, String reportedName, String deviceType,
                                         long nowEpochSec) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE " + OFFICIAL_TABLE + " SET last_seen_epoch = ? "
                        + "WHERE teleonome_name = ? AND canonical_name = ? AND device_type = ?")) {
            update.setLong(1, nowEpochSec);
            update.setString(2, teleonomeName);
            update.setString(3, reportedName);
            update.setString(4, deviceType);
            update.executeUpdate();
        }
    }

    // Promotes a (name, device_type) identity to the official table with the
    // given serial number, overwriting whatever was there before (a previously
    // wrong official entry gets corrected the same way a brand-new one gets
    // created - see class javadoc: neither promotion path is a one-shot,
    // first-only decision). Clears every pending row for this identity, whether
    // or not it was the one promoted - the group is resolved either way.
    private void promoteToOfficial(Connection conn, String reportedName, String deviceType, String serialNumber,
                                    String source, long nowEpochSec) throws SQLException {
        try (PreparedStatement upsert = conn.prepareStatement(
                "INSERT INTO " + OFFICIAL_TABLE + " (teleonome_name, canonical_name, device_type, serial_number, "
                        + "source, decided_epoch, last_seen_epoch) VALUES (?,?,?,?,?,?,?) "
                        + "ON CONFLICT (teleonome_name, canonical_name, device_type) DO UPDATE SET "
                        + "serial_number = EXCLUDED.serial_number, source = EXCLUDED.source, "
                        + "decided_epoch = EXCLUDED.decided_epoch, last_seen_epoch = EXCLUDED.last_seen_epoch")) {
            upsert.setString(1, teleonomeName);
            upsert.setString(2, reportedName);
            upsert.setString(3, deviceType);
            upsert.setString(4, serialNumber);
            upsert.setString(5, source);
            upsert.setLong(6, nowEpochSec);
            upsert.setLong(7, nowEpochSec);
            upsert.executeUpdate();
        }
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM " + PENDING_TABLE + " WHERE teleonome_name = ? AND reported_name = ? AND device_type = ?")) {
            delete.setString(1, teleonomeName);
            delete.setString(2, reportedName);
            delete.setString(3, deviceType);
            delete.executeUpdate();
        }
        logger.info("TelepathonRegistryTask: promoted serial='" + serialNumber + "' to official for name='"
                + reportedName + "' type='" + deviceType + "' (source=" + source + ")");
    }

    private void upsertPending(Connection conn, String reportedName, String deviceType, String serialNumber,
                                String groupIdentifier, Double latitude, Double longitude,
                                long nowEpochSec) throws SQLException {
        try (PreparedStatement upsert = conn.prepareStatement(
                "INSERT INTO " + PENDING_TABLE + " (teleonome_name, reported_name, device_type, serial_number, "
                        + "group_identifier, latitude, longitude, first_seen_epoch, last_seen_epoch, occurrence_count) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,1) "
                        + "ON CONFLICT (teleonome_name, reported_name, device_type, serial_number) DO UPDATE SET "
                        + "last_seen_epoch = EXCLUDED.last_seen_epoch, "
                        + "occurrence_count = " + PENDING_TABLE + ".occurrence_count + 1")) {
            upsert.setString(1, teleonomeName);
            upsert.setString(2, reportedName);
            upsert.setString(3, deviceType);
            upsert.setString(4, serialNumber);
            upsert.setString(5, groupIdentifier);
            if (latitude != null) upsert.setDouble(6, latitude); else upsert.setNull(6, java.sql.Types.DOUBLE);
            if (longitude != null) upsert.setDouble(7, longitude); else upsert.setNull(7, java.sql.Types.DOUBLE);
            upsert.setLong(8, nowEpochSec);
            upsert.setLong(9, nowEpochSec);
            upsert.executeUpdate();
        }
    }

    private void purgeStalePending(Connection conn, long nowEpochSec, int daysInTemporaryList) throws SQLException {
        long cutoffEpoch = nowEpochSec - daysInTemporaryList * 86_400L;
        try (PreparedStatement delete = conn.prepareStatement(
                "DELETE FROM " + PENDING_TABLE + " WHERE teleonome_name = ? AND first_seen_epoch < ?")) {
            delete.setString(1, teleonomeName);
            delete.setLong(2, cutoffEpoch);
            int deleted = delete.executeUpdate();
            if (deleted > 0) {
                logger.info("TelepathonRegistryTask: purged " + deleted
                        + " stale temporary-list entries older than " + daysInTemporaryList + " days");
            }
        }
    }

    // Live, forward-looking promotion signal: the (reported_name, device_type)
    // group's most-recurring serial, promoted once it has been seen
    // promotionThreshold-or-more times. Returns the promoted serial, or null if
    // nothing in the group has reached the threshold yet.
    private String checkPromotion(Connection conn, String reportedName, String deviceType, long nowEpochSec,
                                   int promotionThreshold) throws SQLException {
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT serial_number, occurrence_count FROM " + PENDING_TABLE
                        + " WHERE teleonome_name = ? AND reported_name = ? AND device_type = ? "
                        + "ORDER BY occurrence_count DESC LIMIT 1")) {
            select.setString(1, teleonomeName);
            select.setString(2, reportedName);
            select.setString(3, deviceType);
            try (ResultSet rs = select.executeQuery()) {
                if (!rs.next()) return null;
                String serial = rs.getString(1);
                int count = rs.getInt(2);
                if (count < promotionThreshold) return null;
                promoteToOfficial(conn, reportedName, deviceType, serial, "Promotion Threshold", nowEpochSec);
                return serial;
            }
        }
    }

    // Batch, self-healing promotion signal: tallies every historical
    // telepathon_YYYY_M_D reading for this reported name (restricted to
    // deviceType) over HISTORY_LOOKBACK_DAYS, and promotes the serial number if
    // it's a clear (>50%) majority of readings, not merely a plurality - a
    // near-even split is left for checkPromotion()'s live signal to eventually
    // settle instead of force-deciding on a thin lead. This is what resolves an
    // already-broken registry (e.g. six historical FISH/Daffodil serials) as soon
    // as this code runs, without waiting for new pulses to accumulate.
    private String reconcileFromHistory(Connection conn, String reportedName, String deviceType,
                                         long nowEpochSec) throws SQLException {
        long windowStartEpoch = nowEpochSec - HISTORY_LOOKBACK_DAYS * 86_400L;
        JSONArray rows;
        try {
            rows = PostgresqlPersistenceManager.instance()
                    .getTelepathonDataStart(reportedName, windowStartEpoch, nowEpochSec);
        } catch (Exception e) {
            logger.debug("TelepathonRegistryTask: history lookup failed for name='" + reportedName + "': "
                    + e.getMessage());
            return null;
        }

        Map<String, Integer> occurrencesBySerial = new HashMap<>();
        int totalMatchingRows = 0;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject data = rows.getJSONObject(i).optJSONObject("data");
            if (data == null) continue;
            if (!deviceType.equals(getDeneWordString(data, "Configuration", "Device Type Id"))) continue;
            String serial = data.optString("Serial Number", "");
            if (serial.isEmpty()) continue;
            occurrencesBySerial.merge(serial, 1, Integer::sum);
            totalMatchingRows++;
        }
        if (occurrencesBySerial.isEmpty()) return null;

        String bestSerial = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : occurrencesBySerial.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestSerial = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        if (bestSerial == null || bestCount * 2 <= totalMatchingRows) return null;

        promoteToOfficial(conn, reportedName, deviceType, bestSerial, "History Majority", nowEpochSec);
        return bestSerial;
    }

    // Called from sweep() so devices that aren't currently pulsing still get
    // resolved onto the official list every 5-minute sweep interval, not only
    // when they next happen to pulse and go through reconcile() themselves.
    private void reconcileAllPendingGroups(Connection conn, long nowEpochSec, int promotionThreshold) throws SQLException {
        List<String[]> groups = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT DISTINCT reported_name, device_type FROM " + PENDING_TABLE + " WHERE teleonome_name = ?")) {
            select.setString(1, teleonomeName);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) groups.add(new String[]{rs.getString(1), rs.getString(2)});
            }
        }
        for (String[] group : groups) {
            String promoted = checkPromotion(conn, group[0], group[1], nowEpochSec, promotionThreshold);
            if (promoted == null) {
                reconcileFromHistory(conn, group[0], group[1], nowEpochSec);
            }
        }
    }

    private String pendingToJson(Connection conn) throws SQLException {
        JSONArray arr = new JSONArray();
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT reported_name, device_type, serial_number, occurrence_count, first_seen_epoch "
                        + "FROM " + PENDING_TABLE + " WHERE teleonome_name = ? "
                        + "ORDER BY reported_name, device_type, occurrence_count DESC")) {
            select.setString(1, teleonomeName);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    JSONObject o = new JSONObject();
                    o.put("Name", rs.getString(1));
                    o.put("Device Type", rs.getString(2));
                    o.put("Serial Number", rs.getString(3));
                    o.put("Occurrence Count", rs.getInt(4));
                    o.put("First Seen Epoch", rs.getLong(5));
                    arr.put(o);
                }
            }
        }
        return arr.toString();
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

    // Holds every registered device's current lateness status, not just late
    // ones - sweep() filters this down for "Telepathon Registry Late Devices"
    // and publishes the unfiltered form as "Telepathon Registry Devices" so a
    // reader can see the whole registry, not just problem devices.
    private static class DeviceStatus {
        final String serialNumber;
        final String canonicalName;
        final String deviceType;
        final long ageSec;
        final String status;
        // True only when this sweep is what moved the device from its previously
        // recorded alert status into this one (see evaluateDeviceStatuses()) -
        // the signal sweep() uses to decide which devices are worth an Emergency
        // Channel publish, as opposed to a device that's simply still late.
        final boolean alertWorthy;
        DeviceStatus(String serialNumber, String canonicalName, String deviceType, long ageSec,
                     String status, boolean alertWorthy) {
            this.serialNumber = serialNumber;
            this.canonicalName = canonicalName;
            this.deviceType = deviceType;
            this.ageSec = ageSec;
            this.status = status;
            this.alertWorthy = alertWorthy;
        }
    }

    // daysBeforeStale is teleonome-wide (Internal:Cerebellum:Configuration's "Days
    // Before Stale", read once per sweep - see CerebellumConfigClient), not
    // per-device-type - every device type on this teleonome gets the same grace period
    // before "Stale", while WARNING/"Late" stays per-device-type as before.
    long[] thresholdsFor(String deviceType, int daysBeforeStale) {
        long[] base = SILENCE_THRESHOLDS_SEC.get(deviceType);
        long warningSec = (base != null ? base : DEFAULT_THRESHOLDS_SEC)[0];
        return new long[]{ warningSec, daysBeforeStale * 86_400L };
    }

    String statusFor(long ageSec, long[] thresholds) {
        if (ageSec >= thresholds[1]) return "CRITICAL";
        if (ageSec >= thresholds[0]) return "WARNING";
        return "OK";
    }

    // Every registered device, regardless of status - also persists each
    // device's computed status back to last_alert_status so the *next* sweep
    // can tell a fresh transition (worth an emergency alert) apart from a
    // device that's simply still late from before (not worth re-alerting on
    // every 5-minute sweep) - see DeviceStatus.alertWorthy and
    // telepathon_registry.sql's column comment.
    private List<DeviceStatus> evaluateDeviceStatuses(Connection conn, long nowEpochSec,
                                                       int daysBeforeStale) throws SQLException {
        List<DeviceStatus> all = new ArrayList<>();
        try (PreparedStatement select = conn.prepareStatement(
                "SELECT serial_number, canonical_name, device_type, last_seen_epoch, last_alert_status "
                        + "FROM " + OFFICIAL_TABLE + " WHERE teleonome_name = ?")) {
            select.setString(1, teleonomeName);
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    String serialNumber   = rs.getString(1);
                    String canonicalName  = rs.getString(2);
                    String deviceType     = rs.getString(3);
                    long lastSeenEpochSec = rs.getLong(4);
                    String previousStatus = rs.getString(5); // null = never swept, treat as "OK"
                    long ageSec = nowEpochSec - lastSeenEpochSec;
                    String status = statusFor(ageSec, thresholdsFor(deviceType, daysBeforeStale));

                    boolean statusChanged = !status.equals(previousStatus == null ? "OK" : previousStatus);
                    if (statusChanged) {
                        updateAlertStatus(conn, canonicalName, deviceType, status);
                    }
                    // Never alert on recovery (status == "OK") - only on entering or
                    // escalating within WARNING/CRITICAL.
                    boolean alertWorthy = statusChanged && !"OK".equals(status);

                    all.add(new DeviceStatus(serialNumber, canonicalName, deviceType, ageSec, status, alertWorthy));
                }
            }
        }
        return all;
    }

    private void updateAlertStatus(Connection conn, String canonicalName, String deviceType,
                                    String status) throws SQLException {
        try (PreparedStatement update = conn.prepareStatement(
                "UPDATE " + OFFICIAL_TABLE + " SET last_alert_status = ? "
                        + "WHERE teleonome_name = ? AND canonical_name = ? AND device_type = ?")) {
            update.setString(1, status);
            update.setString(2, teleonomeName);
            update.setString(3, canonicalName);
            update.setString(4, deviceType);
            update.executeUpdate();
        }
    }

    private String devicesToJson(List<DeviceStatus> devices) {
        JSONArray arr = new JSONArray();
        for (DeviceStatus d : devices) {
            JSONObject o = new JSONObject();
            o.put("Name", d.canonicalName);
            o.put("Serial Number", d.serialNumber);
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

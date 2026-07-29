package com.teleonome.cerebellum.langleytopologytask;

import com.teleonome.cerebellum.Task;
import com.teleonome.cerebellum.health.CheckResult;
import com.teleonome.cerebellum.health.Status;
import com.teleonome.cerebellum.health.StatusEvaluator;
import com.teleonome.cerebellum.health.StatusEvaluator.Rule;

import org.apache.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Localizes electric-fence faults on a Langley network by walking one hop of the
 * (deviceshortname, parentShortname) tree on every pulse and comparing a node's
 * self-reported readings against its immediate parent's.
 *
 * Two distinct edge-level fault signals, both requiring a live parent snapshot:
 *
 *   - Fence-pulse edge check: this child's own "Seconds Since Last Pulse" is high
 *     while its parent's is normal. LoRa and the fence wire are independent paths
 *     (every Langley unit radios directly to Annabelle - parent/child is a purely
 *     electrical relationship), so a node that is alive and reporting over LoRa but
 *     has stopped seeing fence pulses has a break specifically between it and its
 *     parent.
 *   - Fence-voltage edge check: an abnormal relative drop in "Fence Voltage Avg"
 *     from parent to child localizes a short/high-resistance fault to that edge.
 *
 * Deliberately does NOT attempt to detect a node going silent (no telepathon at
 * all) - that is an absence, which a pulse-driven Task can never observe for
 * itself, and if the whole fleet goes dark there is no other node's pulse to
 * piggyback a sweep off either. See LangleySilenceSweep for that, run
 * independently on a wall-clock schedule instead of a pulse.
 *
 * The node registry below is static (shared across every per-device instance
 * Cerebellum creates via getOrCreateTask) because localizing a fault needs
 * visibility across the whole Langley fleet, not just this task instance's own
 * device.
 */
public class LangleyTopologyTask implements Task {

    // Same two-tier scale as BatteryHealthTask's Battery Voltage check.
    // Langley pulses arrive roughly once a second (see Langley.ino); these are many
    // missed pulses, not sensor noise.
    private static final double PULSE_GAP_WARNING_SEC  = 60;
    private static final double PULSE_GAP_CRITICAL_SEC = 300;

    // A parent reading older than this cannot be used as a fresh baseline for the
    // edge checks below - comparing against a stale parent snapshot would produce a
    // misleading edge-fault claim. Matches LangleySilenceSweep's WARNING tier.
    private static final long PARENT_STALE_SEC = 300;

    // Relative drop in Fence Voltage Avg, parent -> child, before it's treated as a
    // localized short/high-resistance fault rather than expected line loss.
    private static final double VOLTAGE_DROP_WARNING_RATIO  = 0.30;
    private static final double VOLTAGE_DROP_CRITICAL_RATIO = 0.50;

    private static final ConcurrentHashMap<String, NodeSnapshot> fleet = new ConcurrentHashMap<>();

    private final String teleonomeName;
    private final String deviceName;
    private final Logger logger;

    public LangleyTopologyTask(String teleonomeName, String deviceName) {
        this.teleonomeName = teleonomeName;
        this.deviceName = deviceName;
        this.logger = Logger.getLogger(getClass());
    }

    @Override public String getName()       { return "LangleyTopologyTask"; }
    @Override public String getDeviceName() { return deviceName; }

    @Override
    public JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
        JSONArray words = new JSONArray();

        String deviceshortname = telepathon.optString("Short Name", "");
        if (deviceshortname.isEmpty()) {
            logger.debug("LangleyTopologyTask[" + deviceName + "]: no Short Name on telepathon, skipping");
            return words;
        }

        NodeSnapshot self = new NodeSnapshot();
        self.deviceshortname       = deviceshortname;
        self.parentShortname       = getDeneWordString(telepathon, "Purpose", "Parent Short Name");
        self.branchLabel           = getDeneWordString(telepathon, "Purpose", "Branch Label");
        self.fenceVoltage          = getDeneWordDouble(telepathon, "Purpose", "Fence Voltage");
        self.fenceVoltageMin       = getDeneWordDouble(telepathon, "Purpose", "Fence Voltage Min");
        self.fenceVoltageMax       = getDeneWordDouble(telepathon, "Purpose", "Fence Voltage Max");
        self.fenceVoltageAvg       = getDeneWordDouble(telepathon, "Purpose", "Fence Voltage Avg");
        self.pulseCount            = getDeneWordDouble(telepathon, "Purpose", "Pulse Count");
        self.secondsSinceLastPulse = getDeneWordDouble(telepathon, "Purpose", "Seconds Since Last Pulse");
        self.batteryVoltage        = getDeneWordDouble(telepathon, "Purpose", "Battery Voltage");
        self.operatingStatus       = getDeneWordDouble(telepathon, "Purpose", "Operating Status");
        self.lastSeenEpochSec      = System.currentTimeMillis() / 1000;

        fleet.put(fleetKey(deviceshortname), self);

        if (self.parentShortname == null || self.parentShortname.isEmpty()) {
            logger.debug("LangleyTopologyTask[" + deviceName + "]: root/energizer node, no edge to check");
            return words;
        }

        NodeSnapshot parent = fleet.get(fleetKey(self.parentShortname));
        if (parent == null) {
            logger.debug("LangleyTopologyTask[" + deviceName + "]: parent '" + self.parentShortname + "' not seen yet");
            return words;
        }
        long parentAgeSec = self.lastSeenEpochSec - parent.lastSeenEpochSec;
        if (parentAgeSec > PARENT_STALE_SEC) {
            logger.debug("LangleyTopologyTask[" + deviceName + "]: parent '" + self.parentShortname
                    + "' stale (" + parentAgeSec + "s old), skipping edge checks");
            return words;
        }

        CheckResult pulseResult   = evaluateFencePulseEdge(self, parent);
        CheckResult voltageResult = evaluateFenceVoltageEdge(self, parent);
        Status worst = worst(pulseResult.getStatus(), voltageResult.getStatus());

        logger.info("LangleyTopologyTask[" + deviceName + "]: edge to parent '" + self.parentShortname
                + "' — " + pulseResult + " | " + voltageResult);

        words.put(deneWord("Langley Fence Edge Status", worst.name(), "String"));
        words.put(deneWord("Langley Fence Edge Message",
                combineMessages(pulseResult, voltageResult), "String"));
        return words;
    }

    // ── Edge checks ─────────────────────────────────────────────────────────────

    private CheckResult evaluateFencePulseEdge(NodeSnapshot child, NodeSnapshot parent) {
        boolean parentPulsingNormally = parent.secondsSinceLastPulse < PULSE_GAP_WARNING_SEC;
        StatusEvaluator<Double> evaluator = new StatusEvaluator<>("Fence Pulse Edge", Arrays.asList(
                new Rule<>(v -> parentPulsingNormally && v >= PULSE_GAP_CRITICAL_SEC,
                        Status.CRITICAL, "no fence pulse for " + Math.round(child.secondsSinceLastPulse)
                                + "s while parent '" + parent.deviceshortname + "' is pulsing normally"
                                + " — likely break between '" + parent.deviceshortname + "' and '" + child.deviceshortname + "'"),
                new Rule<>(v -> parentPulsingNormally && v >= PULSE_GAP_WARNING_SEC,
                        Status.WARNING, "fence pulse gap " + Math.round(child.secondsSinceLastPulse)
                                + "s while parent '" + parent.deviceshortname + "' is pulsing normally")
        ));
        return evaluator.evaluate(child.secondsSinceLastPulse);
    }

    private CheckResult evaluateFenceVoltageEdge(NodeSnapshot child, NodeSnapshot parent) {
        if (parent.fenceVoltageAvg <= 0) {
            return new CheckResult("Fence Voltage Edge", Status.OK, "no parent voltage baseline yet", child.fenceVoltageAvg);
        }
        double dropRatio = (parent.fenceVoltageAvg - child.fenceVoltageAvg) / parent.fenceVoltageAvg;
        StatusEvaluator<Double> evaluator = new StatusEvaluator<>("Fence Voltage Edge", Arrays.asList(
                new Rule<>(v -> v >= VOLTAGE_DROP_CRITICAL_RATIO,
                        Status.CRITICAL, String.format("%.0f%% voltage drop from parent '%s' (%.1fkV) to '%s' (%.1fkV)"
                                + " — likely short/high-resistance fault on this edge",
                                dropRatio * 100, parent.deviceshortname, parent.fenceVoltageAvg,
                                child.deviceshortname, child.fenceVoltageAvg)),
                new Rule<>(v -> v >= VOLTAGE_DROP_WARNING_RATIO,
                        Status.WARNING, String.format("%.0f%% voltage drop from parent '%s' (%.1fkV) to '%s' (%.1fkV)",
                                dropRatio * 100, parent.deviceshortname, parent.fenceVoltageAvg,
                                child.deviceshortname, child.fenceVoltageAvg))
        ));
        return evaluator.evaluate(dropRatio);
    }

    private Status worst(Status a, Status b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    private String combineMessages(CheckResult... results) {
        StringBuilder sb = new StringBuilder();
        for (CheckResult r : results) {
            if (r.getStatus() == Status.OK) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(r.getMessage());
        }
        return sb.length() > 0 ? sb.toString() : "OK";
    }

    private String fleetKey(String shortname) {
        return teleonomeName + ":" + shortname;
    }

    // ── Fleet snapshot ─────────────────────────────────────────────────────────

    private static class NodeSnapshot {
        String deviceshortname;
        String parentShortname;
        String branchLabel;
        double fenceVoltage;
        double fenceVoltageMin;
        double fenceVoltageMax;
        double fenceVoltageAvg;
        double pulseCount;
        double secondsSinceLastPulse;
        double batteryVoltage;
        double operatingStatus;
        // Wall clock at ingest, not the device's self-reported Seconds Time, so a
        // clock-skewed device can't mask its own staleness.
        long lastSeenEpochSec;
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

    private JSONObject deneWord(String name, Object value, String valueType) {
        JSONObject w = new JSONObject();
        w.put("Name", name);
        w.put("Value", value);
        w.put("Value Type", valueType);
        w.put("Required", true);
        return w;
    }
}

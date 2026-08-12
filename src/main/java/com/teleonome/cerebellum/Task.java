package com.teleonome.cerebellum;

import org.json.JSONArray;
import org.json.JSONObject;

public interface Task {

    String getName();

    String getDeviceName();

    /**
     * Processes the incoming telepathon data for this task's device.
     *
     * The telepathon is the device's DeneChain from the pulse's Telepathons nucleus,
     * containing Configuration, Sensors, and Purpose Denes with the current readings.
     *
     * Tasks are stateful and accumulate data across invocations. They return a
     * non-empty JSONArray of DeneWords only when they have results ready to publish
     * (e.g. at sunset for time-triggered tasks), and an empty JSONArray otherwise.
     */
    default JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception {
        return new JSONArray();
    }

    /**
     * Processes a "Self" task — one wired with Telepathon Type "Self", meaning it
     * reads the pulsing teleonome's own denome rather than an incoming Telepathon
     * (e.g. Ra's own Purpose:Sensor Data, or values pulled in from another teleonome
     * into Purpose:External Data). Cerebellum passes the full pulse JSONObject
     * (same shape as Teleonome.denome — top-level "Denome" key); implementations
     * resolve whatever they need via
     * {@code DenomeUtils.getDeneWordByIdentity(pulse, new Identity(teleonomeName,
     * nucleus, deneChain, dene, deneWord), TeleonomeConstants.DENEWORD_VALUE_ATTRIBUTE)}
     * — any path in the teleonome's own tree, not just one fixed DeneChain.
     *
     * Same stateful/return-empty-until-ready contract as process().
     */
    default JSONArray processSelf(JSONObject pulse, String matchedSlot) throws Exception {
        return new JSONArray();
    }

    /**
     * Cerebellum calls this once, right after construction, to hand the task a
     * way to query Hippocampus's short-term memory. Tasks that don't need
     * historical lookups can ignore it — default is a no-op.
     */
    default void setQueryHandler(HippocampusQuery handler) {}
}

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
    JSONArray process(JSONObject telepathon, String matchedSlot) throws Exception;

    /**
     * Cerebellum calls this once, right after construction, to hand the task a
     * way to query Hippocampus's short-term memory. Tasks that don't need
     * historical lookups can ignore it — default is a no-op.
     */
    default void setQueryHandler(HippocampusQuery handler) {}
}

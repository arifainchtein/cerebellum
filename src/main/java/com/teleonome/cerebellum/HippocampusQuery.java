package com.teleonome.cerebellum;

import org.json.JSONArray;

/**
 * Narrow callback handed to Tasks by Cerebellum, so a Task can ask for recent
 * history of a DeneWord without knowing anything about MQTT or Hippocampus.
 *
 * Returns the "Data" array from Hippocampus's response — entries shaped like
 * {timeSeconds, timeString, Value} — or an empty array if Hippocampus has
 * nothing for that identity (not tracked, not yet preloaded, or timed out).
 */
@FunctionalInterface
public interface HippocampusQuery {
    JSONArray query(String identity, long rangeMs) throws Exception;
}

package com.teleonome.cerebellum;

import org.eclipse.paho.client.mqttv3.MqttClient;

import java.sql.Connection;

public interface Task {

    String getName();

    void run(Connection conn, MqttClient mqtt) throws Exception;
}

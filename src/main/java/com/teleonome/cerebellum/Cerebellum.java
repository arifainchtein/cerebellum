package com.teleonome.cerebellum;

import org.apache.commons.io.FileUtils;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Properties;

public class Cerebellum {

    private static final String MQTT_BROKER      = "tcp://localhost:1883";
    private static final String CLIENT_ID        = "Cerebellum";
    private static final String STATUS_FILE_PATH = "/home/pi/Teleonome/CerebellumStatus.json";
    private static final long   PING_INTERVAL_MS = 30_000;

    private final int pid;

    public Cerebellum() {
        String processName = ManagementFactory.getRuntimeMXBean().getName();
        this.pid = Integer.parseInt(processName.split("@")[0]);
    }

    public static void main(String[] args) throws Exception {
        new Cerebellum().start();
    }

    private void start() throws Exception {
        try (Connection conn = connect();
             MqttClient  mqtt = new MqttClient(MQTT_BROKER, CLIENT_ID)) {

            mqtt.connect();

            new PingThread().start();

            // Tasks will be registered and scheduled here.
        }
    }

    private void writePing() {
        try {
            JSONObject status = new JSONObject();
            status.put("cerebellumPid", pid);
            status.put("Timestamp Milliseconds", System.currentTimeMillis());
            FileUtils.writeStringToFile(new File(STATUS_FILE_PATH), status.toString(4), "UTF-8");
        } catch (Exception e) {
            System.err.println("Failed to write CerebellumStatus.json: " + e.getMessage());
        }
    }

    static Connection connect() throws Exception {
        Properties prop = new Properties();
        try (FileInputStream in = new FileInputStream("lib/app.properties")) {
            prop.load(in);
        }
        int port   = Integer.parseInt(prop.getProperty("port"));
        String pwd = prop.getProperty("pwd");
        URI uri    = new URI("postgres://postgres:" + pwd + "@localhost:" + port + "/teleonome");
        String url = "jdbc:postgresql://" + uri.getHost() + ":" + uri.getPort() + uri.getPath();
        Properties cp = new Properties();
        cp.setProperty("user",     "postgres");
        cp.setProperty("password", pwd);
        return DriverManager.getConnection(url, cp);
    }

    private class PingThread extends Thread {

        PingThread() {
            setDaemon(true);
            setName("CerebellumPing");
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                writePing();
                try {
                    Thread.sleep(PING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}

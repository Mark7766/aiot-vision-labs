package com.sandy.aiot.vision.collector.tools;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.AbstractMap.SimpleEntry;

/**
 * Utility class for parsing OPC UA connection strings.
 */
public class OpcuaUriParser {

    /**
     * Extracts the IP address and port from an OPC UA connection string.
     *
     * @param connectionString The connection string containing the opc.tcp:// protocol, e.g., "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer"
     * @return A SimpleEntry object containing the IP address (hostname) and port. Returns null if parsing fails.
     */
    public static SimpleEntry<String, Integer> extractIpAndPort(String connectionString) {
        try {
            URI uri = new URI(connectionString);
            String host = uri.getHost();
            int port = uri.getPort();

            if (host != null && port != -1) {
                return new SimpleEntry<>(host, port);
            }
        } catch (URISyntaxException e) {
            System.err.println("Invalid URI syntax: " + e.getMessage());
        }
        return null;
    }

    public static void main(String[] args) {
        String opcuaUrl = "opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer";
        SimpleEntry<String, Integer> result = extractIpAndPort(opcuaUrl);

        if (result != null) {
            String ip = result.getKey();
            int port = result.getValue();
            System.out.println("Extracted IP address: " + ip);
            System.out.println("Extracted port: " + port);
        } else {
            System.out.println("Unable to extract IP address and port from the string.");
        }
    }
}
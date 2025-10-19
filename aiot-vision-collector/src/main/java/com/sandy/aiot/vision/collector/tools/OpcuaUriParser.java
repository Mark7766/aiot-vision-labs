package com.sandy.aiot.vision.collector.tools;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.AbstractMap.SimpleEntry;

public class OpcuaUriParser {

    /**
     * 从OPC UA连接字符串中提取IP地址和端口。
     *
     * @param connectionString 包含opc.tcp://协议的连接字符串，例如："opc.tcp://127.0.0.1:53530/OPCUA/SimulationServer"
     * @return 包含IP地址（主机名）和端口的SimpleEntry对象。如果解析失败，返回null。
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
            System.out.println("提取出的IP地址: " + ip);
            System.out.println("提取出的端口: " + port);
        } else {
            System.out.println("无法从字符串中提取IP地址和端口。");
        }
    }
}

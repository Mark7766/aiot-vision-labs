package com.sandy.aiot.vision.collector;

import org.apache.plc4x.java.api.PlcConnection;
import org.apache.plc4x.java.api.PlcDriverManager; // 修改导入
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * OPC UA 连接可用性测试 (默认跳过)。
 * 运行方式：
 * mvn -DrunOpcUaTest=true -Dtest=OpcUaConnectionTest test
 * 或在 IDE 的 Run Configuration 中添加 VM 参数: -DrunOpcUaTest=true
 */
public class OpcUaConnectionTest {

    // 使用 PLC4X 枚举形式: security-policy=NONE (而不是 None)
    private static final String CONNECTION_STRING = "opcua:tcp://10.235.229.10:53530?security-policy=NONE&message-security=NONE&discovery=true";

    @Test
    void testOpcUaConnection() throws Exception {
        // 只有显式开启时才真正执行，避免 CI 或离线环境失败
        Assumptions.assumeTrue(Boolean.getBoolean("runOpcUaTest"), "未启用 OPC UA 测试。使用 -DrunOpcUaTest=true 启用");

        long start = System.currentTimeMillis();
        try (PlcConnection connection = PlcDriverManager.getDefault().getConnectionManager().getConnection(CONNECTION_STRING)) { // 更新 API 调用
            Assertions.assertTrue(connection.isConnected(), "OPC UA 连接未建立");
            System.out.println("[OPC UA] 成功连接 -> " + CONNECTION_STRING + ", 耗时 " + (System.currentTimeMillis() - start) + " ms");
        }
    }
}

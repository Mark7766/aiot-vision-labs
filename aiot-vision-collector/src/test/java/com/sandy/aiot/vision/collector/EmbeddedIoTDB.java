package com.sandy.aiot.vision.collector;
import org.apache.iotdb.IoTDB;
import org.apache.iotdb.session.Session;
import org.apache.iotdb.session.pool.SessionPool;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.record.datapoint.DataPoint;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

import java.util.ArrayList;
import java.util.List;

public class EmbeddedIoTDB {
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 6667;

    private Session session;

    public void start() throws Exception {
        // 启动嵌入式服务器（默认配置data/system.schema.dir等）
        IoTDB.startup(); // 或指定配置：new IoTDB(new File("iotdb-engine.properties"))

        // 创建Session连接
        session = new Session(HOST, PORT, USERNAME, PASSWORD);
        session.open(false); // false: 不重连
    }

    public void stop() {
        if (session != null) {
            session.close();
        }
        IoTDB.shutdown();
    }

    public Session getSession() {
        return session;
    }

    // 示例：插入测试数据（non-aligned）
    public void insertSampleData() throws Exception {
        Session s = getSession();
        // meter1: 稀疏点
        TSRecord record1 = new TSRecord(1695900000000L, "root.meter1");
        record1.addTuple(DataPoint.getDataPoint(TSDataType.DOUBLE, "power", 10.0));
        s.insertRecord(record1);

        TSRecord record2 = new TSRecord(1695900900000L, "root.meter1"); // +15min
        record2.addTuple(DataPoint.getDataPoint(TSDataType.DOUBLE, "power", 15.0));
        s.insertRecord(record2);

        // meter2: 另一个稀疏点
        TSRecord record3 = new TSRecord(1695900450000L, "root.meter2");
        record3.addTuple(DataPoint.getDataPoint(TSDataType.DOUBLE, "power", 5.0));
        s.insertRecord(record3);

        s.close();
    }
}

package com.sandy.aiot.vision.collector.service;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.session.Session;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.utils.Binary;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class DataStorageServiceByIotdb implements DataStorageService {

    private Session session;

    // Hardcoded for demo; use @Value("${iotdb.host}", etc.) in production
    private static final String HOST = "10.235.229.11";
    private static final int PORT = 6667;
    private static final String USERNAME = "root";
    private static final String PASSWORD = "root";

    @PostConstruct
    public void init() {
        this.session = new Session(HOST, PORT, USERNAME, PASSWORD);
        try {
            session.open(false);
            log.info("IoTDB session connected successfully.");
        } catch (Exception e) {
            log.error("Failed to connect to IoTDB", e);
            throw new RuntimeException("IoTDB connection failed", e);
        }
    }

    @PreDestroy
    public void destroy() throws IoTDBConnectionException {
        if (session != null) {
            session.close();
            log.info("IoTDB session closed.");
        }
    }

    @Override
    public List<DataRecord> findLatest(Long deviceId) {
        List<DataRecord> records = new ArrayList<>();
        String sql = String.format("SELECT last * FROM root.%s.*", toDeviceId(deviceId));
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                // Assuming one value column per timeseries in LAST query
                Field field = record.getFields().get(0);
                Object value = convertFieldToValue(field);
                String timeseries = dataSet.getColumnNames().get(1); // Skip time column
                // Extract tagId from timeseries (e.g., root.123.tagX -> tagX)
                String tagIdStr = timeseries.substring(timeseries.lastIndexOf('.') + 1);
                Long tagId = Long.parseLong(tagIdStr);
                DataRecord dataRecord = DataRecord.builder()
                        .tagId(tagId)
                        .deviceId(deviceId)
                        .value(value)
                        .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))
                        .build();
                records.add(dataRecord);
            }
        } catch (Exception e) {
            log.error("Error querying latest data for device {}", deviceId, e);
        }
        return records;
    }


    @Override
    public Optional<DataRecord> findLatest(Long deviceId, Long tagId) {
        DataRecord dataRecord =null;
        String sql = String.format("SELECT last %s FROM root.%s", toMeasurement(tagId), toDeviceId(deviceId));
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                Field field = record.getFields().get(1);
                Object value = convertFieldToValue(field);
                dataRecord = DataRecord.builder()
                        .tagId(tagId)
                        .deviceId(deviceId)
                        .value(value)
                        .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))
                        .build();
            }
        } catch (Exception e) {
            log.error("Error querying top N for device {} tag {}", deviceId, tagId, e);
        }
        return dataRecord==null?Optional.empty():Optional.of(dataRecord);
    }

    @Override
    public List<DataRecord> findTopN(Long deviceId, Long tagId, int limit) {
        List<DataRecord> records = new ArrayList<>();
        String sql = String.format("SELECT %s FROM root.%s ORDER BY time DESC LIMIT %d", toMeasurement(tagId), toDeviceId(deviceId), limit);
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                Field field = record.getFields().get(0);
                Object value = convertFieldToValue(field);
                DataRecord dataRecord = DataRecord.builder()
                        .tagId(tagId)
                        .deviceId(deviceId)
                        .value(value)
                        .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))
                        .build();
                records.add(dataRecord);
            }
        } catch (Exception e) {
            log.error("Error querying top N for device {} tag {}", deviceId, tagId, e);
        }
        return records;
    }

    @Override
    public List<DataRecord> findTopN(Long deviceId, int limit) {
        List<DataRecord> records = new ArrayList<>();
        String sql = String.format("SELECT * FROM root.%s.* ORDER BY time DESC LIMIT %d", toDeviceId(deviceId), limit);
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                // For multi-column, but assuming single value per row in this query
                Field field = record.getFields().get(0);
                Object value = convertFieldToValue(field);
                String timeseries = dataSet.getColumnNames().get(1); // First non-time column
                String tagIdStr = timeseries.substring(timeseries.lastIndexOf('.') + 1);
                Long tagId = Long.parseLong(tagIdStr);
                DataRecord dataRecord = DataRecord.builder()
                        .tagId(tagId)
                        .deviceId(deviceId)
                        .value(value)
                        .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC))
                        .build();
                records.add(dataRecord);
            }
        } catch (Exception e) {
            log.error("Error querying top N for device {}", deviceId, e);
        }
        return records;
    }

    @Override
    public boolean save(List<DataRecord> dataRecords) {
        if (dataRecords.isEmpty()) {
            return true;
        }
        try {
            List<String> deviceIds = new ArrayList<>();
            List<Long> times = new ArrayList<>();
            List<List<String>> measurementsList=new ArrayList<>();
            List<List<org.apache.tsfile.enums.TSDataType>> typesList=new ArrayList<>();
            List<List<Object>> valuesList=new ArrayList<>();
            for (DataRecord record : dataRecords) {
                String deviceId=toDeviceId(record.getDeviceId());
                String deviceIdStr = "root." + deviceId;
                deviceIds.add(deviceIdStr);
                long timestamp = record.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli();
                times.add(timestamp);
                String measurement = toMeasurement(record.getTagId());
                List<String> measurements = new ArrayList<>();
                measurements.add(measurement);
                measurementsList.add(measurements);
                org.apache.tsfile.enums.TSDataType dataType =getTSDataType( record.getValue());
                List<org.apache.tsfile.enums.TSDataType> types = new ArrayList<>();
                types.add(dataType);
                typesList.add(types);
                List<Object> values = new ArrayList<>();
                values.add(record.getValue());
                valuesList.add(values);
            }
            session.insertRecords(deviceIds, times, measurementsList, typesList, valuesList);
            log.info("Saved {} records to IoTDB", dataRecords.size());
            return true;
        } catch (Exception e) {
            log.error("Error saving records to IoTDB", e);
            return false;
        }
    }

    private String toDeviceId(Long deviceId) {
        return "d"+deviceId;
    }

    private String toMeasurement(Long tagId) {
        return "m"+tagId;
    }

    private TSDataType getTSDataType(Object value) {
        if (value instanceof Boolean) {
            return TSDataType.BOOLEAN;
        } else if (value instanceof Integer) {
            return TSDataType.INT32;
        } else if (value instanceof Long) {
            return TSDataType.INT64;
        } else if (value instanceof Float) {
            return TSDataType.FLOAT;
        } else if (value instanceof Double) {
            return TSDataType.DOUBLE;
        } else if (value instanceof String) {
            return TSDataType.STRING;
        } else if (value instanceof ByteBuffer) {
            return TSDataType.BLOB;
        } else if (value instanceof Binary) {
            return TSDataType.TEXT;
        } else if (value instanceof Timestamp) {
            return TSDataType.TIMESTAMP;
        } else if (value instanceof Date) {
            return TSDataType.DATE;
        } else if (value instanceof List) {
            return TSDataType.VECTOR;
        } else {
            return TSDataType.UNKNOWN; // Fallback for unhandled types
        }
    }

    private Object convertFieldToValue(Field field) {
        TSDataType dataType = field.getDataType();
        if (dataType == TSDataType.BOOLEAN) {
            return field.getBoolV();
        } else if (dataType == TSDataType.INT32) {
            return field.getIntV();
        } else if (dataType == TSDataType.INT64) {
            return field.getLongV();
        } else if (dataType == TSDataType.FLOAT) {
            return field.getFloatV();
        } else if (dataType == TSDataType.DOUBLE) {
            return field.getDoubleV();
        } else if (dataType == TSDataType.TEXT) {
            return field.getBinaryV().getStringValue(Charset.defaultCharset());
        } else if (dataType == TSDataType.VECTOR) {
            return field.getBinaryV();
        } else if (dataType == TSDataType.UNKNOWN) {
            return null;
        } else if (dataType == TSDataType.TIMESTAMP) {
            return field.getLongV();
        } else if (dataType == TSDataType.DATE) {
            return field.getIntV();
        } else if (dataType == TSDataType.BLOB) {
            return field.getBinaryV();
        } else if (dataType == TSDataType.STRING) {
            return field.getBinaryV().getStringValue(Charset.defaultCharset());
        }
        return null; // Fallback for any unhandled type
    }
}
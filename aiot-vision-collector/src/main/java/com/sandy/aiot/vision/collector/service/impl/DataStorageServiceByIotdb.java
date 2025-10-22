package com.sandy.aiot.vision.collector.service.impl;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sun.jdi.ShortType;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.session.Session;
import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.utils.Binary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@Profile("!test")
@Slf4j
public class DataStorageServiceByIotdb implements DataStorageService {
    private Session session;

    @Value("${iotdb.host}")
    private String host;
    @Value("${iotdb.port}")
    private int port;
    @Value("${iotdb.username}")
    private String username;
    @Value("${iotdb.password}")
    private String password;
    @Value("${iotdb.rt.db}")
    private  String realtimeDBwithoutRoot;
    private  String realtimeDB;
    @Value("${iotdb.rt.ttl}")
    private  long realtimeTTL ;

    @PostConstruct
    public void init() {
        this.session = new Session(host, port, username, password);
        try {
            session.open(false);
            log.info("IoTDB session connected successfully. host={} port={}", host, port);
            setTTL();
        } catch (Exception e) {
            log.error("Failed to connect to IoTDB host={} port={}", host, port, e);
            throw new IllegalStateException("IoTDB connection failed", e);
        }
    }

    private void setTTL() {
        try {
            this.realtimeDB="root."+realtimeDBwithoutRoot;
            String sql = String.format("set ttl to %s %d", realtimeDB,realtimeTTL);
            session.executeNonQueryStatement(sql);
            log.info("TTL set to {} ms for database {}", realtimeTTL,realtimeDB);
        } catch (Exception e) {
            log.error("Error setting TTL for database {}", realtimeDB, e);
        }
    }

    @PreDestroy
    public void destroy() {
        if (session != null) {
            try {
                session.close();
                log.info("IoTDB session closed.");
            } catch (IoTDBConnectionException e) {
                log.warn("Error closing IoTDB session: {}", e.getMessage());
            }
        }
    }

    @Override
    public List<DataRecord> findLatest(Long deviceId) {
        List<DataRecord> records = new ArrayList<>();
        String sql = String.format("SELECT last * FROM " + realtimeDB + ".%s.*", toDeviceId(deviceId));
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                Field field = record.getFields().get(0);
                Object value = convertFieldToValue(field);
                String timeseries = dataSet.getColumnNames().get(1);
                String tagIdStr = timeseries.substring(timeseries.lastIndexOf('.') + 1).substring(1);
                Long tagId = Long.parseLong(tagIdStr);
                records.add(DataRecord.builder().tagId(tagId).deviceId(deviceId).value(value).timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)).build());
            }
        } catch (Exception e) {
            log.error("Error querying latest data for device {}", deviceId, e);
        }
        return records;
    }

    @Override
    public Optional<DataRecord> findLatest(Long deviceId, Long tagId) {
        DataRecord dataRecord = null;
        String sql = String.format("SELECT last %s FROM " + realtimeDB + ".%s", toMeasurement(tagId), toDeviceId(deviceId));
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                Field field = record.getFields().get(1);
                Object value = convertFieldToValue(field);
                dataRecord = DataRecord.builder().tagId(tagId).deviceId(deviceId).value(value).timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)).build();
            }
        } catch (Exception e) {
            log.error("Error querying latest for device {} tag {}", deviceId, tagId, e);
        }
        return Optional.ofNullable(dataRecord);
    }

    @Override
    public List<DataRecord> findTopN(Long deviceId, Long tagId, int limit) {
        List<DataRecord> records = new ArrayList<>();
        String sql = String.format("SELECT %s FROM " + realtimeDB + ".%s ORDER BY time DESC LIMIT %d", toMeasurement(tagId), toDeviceId(deviceId), limit);
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                Field field = record.getFields().get(0);
                Object value = convertFieldToValue(field);
                records.add(DataRecord.builder().tagId(tagId).deviceId(deviceId).value(value).timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)).build());
            }
        } catch (Exception e) {
            log.error("Error querying top N for device {} tag {}", deviceId, tagId, e);
        }
        return records;
    }

    @Override
    public List<DataRecord> findTopN(Long deviceId, int limit) {
        List<DataRecord> records = new ArrayList<>();
        String sql = String.format("SELECT * FROM " + realtimeDB + ".%s.* ORDER BY time DESC LIMIT %d", toDeviceId(deviceId), limit);
        try (var dataSet = session.executeQueryStatement(sql)) {
            while (dataSet.hasNext()) {
                RowRecord record = dataSet.next();
                long timestamp = record.getTimestamp();
                Field field = record.getFields().get(0);
                Object value = convertFieldToValue(field);
                String timeseries = dataSet.getColumnNames().get(1);
                String tagIdStr = timeseries.substring(timeseries.lastIndexOf('.') + 1).substring(1);
                Long tagId = Long.parseLong(tagIdStr);
                records.add(DataRecord.builder().tagId(tagId).deviceId(deviceId).value(value).timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp), ZoneOffset.UTC)).build());
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
            List<List<String>> measurementsList = new ArrayList<>();
            List<List<TSDataType>> typesList = new ArrayList<>();
            List<List<Object>> valuesList = new ArrayList<>();
            for (DataRecord record : dataRecords) {
                if (null == record.getValue()) {
                    log.warn("Skipping record with null value: deviceId={}, tagId={}, timestamp={}", record.getDeviceId(), record.getTagId(), record.getTimestamp());
                    continue;
                }
                String deviceIdStr = realtimeDB + "." + toDeviceId(record.getDeviceId());
                deviceIds.add(deviceIdStr);
                times.add(record.getTimestamp().toInstant(ZoneOffset.UTC).toEpochMilli());
                List<String> measurements = List.of(toMeasurement(record.getTagId()));
                measurementsList.add(measurements);
                List<TSDataType> types = List.of(getTSDataType(record.getValue()));
                typesList.add(types);
                List<Object> values = List.of(record.getValue());
                valuesList.add(values);
            }
            session.insertRecords(deviceIds, times, measurementsList, typesList, valuesList);
            log.debug("Saved {} records to IoTDB", dataRecords.size());
            return true;
        } catch (Exception e) {
            log.error("Error saving records to IoTDB", e);
            return false;
        }
    }

    private String toDeviceId(Long deviceId) {
        return "d" + deviceId;
    }

    private String toMeasurement(Long tagId) {
        return "m" + tagId;
    }

    private TSDataType getTSDataType(Object value) {
        if (value instanceof Boolean) return TSDataType.BOOLEAN;
        if (value instanceof Integer) return TSDataType.INT32;
        if (value instanceof Long) return TSDataType.INT64;
        if (value instanceof Float) return TSDataType.FLOAT;
        if (value instanceof Double) return TSDataType.DOUBLE;
        if (value instanceof String) return TSDataType.STRING;
        if (value instanceof ByteBuffer) return TSDataType.BLOB;
        if (value instanceof Binary) return TSDataType.TEXT;
        if (value instanceof Timestamp) return TSDataType.TIMESTAMP;
        if (value instanceof Date) return TSDataType.DATE;
        if (value instanceof List) return TSDataType.VECTOR;
        return TSDataType.UNKNOWN;
    }

    private Object convertFieldToValue(Field field) {
        TSDataType dataType = field.getDataType();
        return switch (dataType) {
            case BOOLEAN -> field.getBoolV();
            case INT32 -> field.getIntV();
            case INT64 -> field.getLongV();
            case FLOAT -> field.getFloatV();
            case DOUBLE -> field.getDoubleV();
            case TEXT, STRING -> field.getBinaryV().getStringValue(Charset.defaultCharset());
            case VECTOR, BLOB -> field.getBinaryV();
            case TIMESTAMP -> field.getLongV();
            case DATE -> field.getIntV();
            case UNKNOWN -> null;
        };
    }
}


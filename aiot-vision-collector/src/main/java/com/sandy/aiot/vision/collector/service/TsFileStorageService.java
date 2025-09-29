package com.sandy.aiot.vision.collector.service;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.apache.iotdb.tsfile.exception.write.WriteProcessException;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.fileSystem.FSFactoryProducer;
import org.apache.iotdb.tsfile.read.TsFileReader;
import org.apache.iotdb.tsfile.read.TsFileSequenceReader;
import org.apache.iotdb.tsfile.read.common.Path;
import org.apache.iotdb.tsfile.read.common.RowRecord;
import org.apache.iotdb.tsfile.read.expression.IExpression;
import org.apache.iotdb.tsfile.read.expression.QueryExpression;
import org.apache.iotdb.tsfile.read.expression.impl.BinaryExpression;
import org.apache.iotdb.tsfile.read.expression.impl.GlobalTimeExpression;
import org.apache.iotdb.tsfile.read.filter.factory.TimeFilterApi;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.iotdb.tsfile.write.TsFileWriter;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.record.datapoint.DataPoint;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简单的基于 TsFile 的存储服务：
 *  - 每台设备一个 devicePath: device_{deviceId}
 *  - 仅一个测点 snapshot(TEXT) 存储整条 JSON 快照
 *  - 查询时读取 snapshot measurement 并转换为 DataRecord 以复用现有解析逻辑
 *
 * 说明/限制：
 *  - 当前实现读取时全量扫描（再截取末 N 条），随着文件增大性能会下降，可后续优化为基于时间过滤或滚动文件。
 *  - TsFileWriter 与 TsFileReader 并发访问存在潜在风险；目前采用写入后立即"flush"（write() 内部会刷新 chunk），读取时重新打开独立 Reader。
 *  - 该实现不追加已有文件（若文件存在则复用，不删除），只能在一次进程生命周期内持续写入；如需跨重启追加需要更复杂逻辑（拆分多文件/嵌入 IoTDB Server）。
 */
@Service
@Slf4j
public class TsFileStorageService {

    @Value("${tsfile.path:data/records.tsfile}")
    private String tsfilePath;

    private TsFileWriter writer;

    // 已注册的 devicePath -> 是否注册 (key 使用 deviceId_tagId)
    private final Set<String> registeredDevices = ConcurrentHashMap.newKeySet();

    @PostConstruct
    public synchronized void init() {
        try {
            File f = FSFactoryProducer.getFSFactory().getFile(tsfilePath);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (f.exists()) {
                // 简化处理：删除旧文件，重新开始（后续可改为滚动文件或追加）
                log.info("删除旧 TsFile: {}", tsfilePath);
                Files.delete(f.toPath());
            }
            writer = new TsFileWriter(f);
            log.info("TsFileStorageService 初始化完成, path={}", tsfilePath);
        } catch (Exception e) {
            log.error("初始化 TsFileWriter 失败: {}", e.getMessage(), e);
        }
    }

    @PreDestroy
    public synchronized void shutdown() {
        if (writer != null) {
            try { writer.close(); } catch (IOException e) { log.warn("关闭 TsFileWriter 失败: {}", e.getMessage()); }
        }
    }


    /**
     * 获取指定设备最近一条记录（跨所有已注册 tag，按时间最大）。
     */
    public Optional<DataRecord> findLatest(Long deviceId) {
        List<DataRecord> list = findTopN(deviceId, 1);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 获取最近 LimitMinute 分钟内某个设备某个测点的数据，按时间倒序返回。
     * @param deviceId 设备ID
     * @param tagId 测点ID
     * @param LimitMinute 最近分钟数
     */
    public List<DataRecord> findTopN(Long deviceId, Long tagId, int LimitMinute) {
        if (deviceId == null || tagId == null || LimitMinute <= 0) return Collections.emptyList();
        File f = new File(tsfilePath);
        if (!f.exists()) return Collections.emptyList();

        long now = System.currentTimeMillis();
        long startTime = now - LimitMinute * 60L * 1000L;

        ArrayList<DataRecord> result = new ArrayList<>();
        try (TsFileSequenceReader reader = new TsFileSequenceReader(tsfilePath);
             TsFileReader tsFileReader = new TsFileReader(reader)) {

            Path path = new Path(String.valueOf(deviceId), String.valueOf(tagId), true);
            ArrayList<Path> paths = new ArrayList<>();
            paths.add(path);
            IExpression timeFilter = BinaryExpression.and(
                    new GlobalTimeExpression(TimeFilterApi.gtEq(startTime)),
                    new GlobalTimeExpression(TimeFilterApi.ltEq(now))
            );
            QueryExpression queryExpression = QueryExpression.create(paths, timeFilter);
            QueryDataSet dataSet = tsFileReader.query(queryExpression);
            while (dataSet.hasNext()) {
                RowRecord rr = dataSet.next();
                long ts = rr.getTimestamp();
                // 单测点，所以 fields 只有一个
                Object valueObj = rr.getFields().isEmpty() ? null : rr.getFields().get(0).getStringValue();
                DataRecord dr = DataRecord.builder()
                        .deviceId(deviceId)
                        .tagId(tagId)
                        .value(valueObj)
                        .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.ofHours(8)))
                        .build();
                result.add(dr);
            }
        } catch (Exception e) {
            log.warn("读取 TsFile 失败 (deviceId={}, tagId={}): {}", deviceId, tagId, e.getMessage());
        }
        // 查询结果默认按时间升序，需倒序
        result.sort(Comparator.comparing(DataRecord::getTimestamp).reversed());
        return result;
    }

    /**
     * 获取设备最近 topN 条记录（从所有已注册测点中选取最新时间的记录）。
     * 若测点数量 > topN，仅返回时间最近的 topN 条。
     */
    public List<DataRecord> findTopN(Long deviceId, int topN) {
        if (deviceId == null || topN <= 0) return Collections.emptyList();
        File f = new File(tsfilePath);
        if (!f.exists()) return Collections.emptyList();

        // 收集该设备下所有 tagId（通过注册集合）
        List<Long> tagIds = new ArrayList<>();
        String prefix = deviceId + "_";
        for (String key : registeredDevices) {
            if (key.startsWith(prefix)) {
                try {
                    String tagPart = key.substring(prefix.length());
                    tagIds.add(Long.parseLong(tagPart));
                } catch (NumberFormatException ignore) { }
            }
        }
        if (tagIds.isEmpty()) return Collections.emptyList();

        List<DataRecord> latestPerTag = new ArrayList<>();
        try (TsFileSequenceReader reader = new TsFileSequenceReader(tsfilePath);
             TsFileReader tsFileReader = new TsFileReader(reader)) {
            for (Long tagId : tagIds) {
                Path path = new Path(String.valueOf(deviceId), String.valueOf(tagId), true);
                ArrayList<Path> paths = new ArrayList<>();
                paths.add(path);
                QueryExpression queryExpression = QueryExpression.create(paths, null);
                QueryDataSet dataSet = tsFileReader.query(queryExpression);
                RowRecord last = null;
                while (dataSet.hasNext()) {
                    last = dataSet.next();
                }
                if (last != null) {
                    Object valueObj = last.getFields().isEmpty() ? null : last.getFields().get(0).getStringValue();
                    DataRecord dr = DataRecord.builder()
                            .deviceId(deviceId)
                            .tagId(tagId)
                            .value(valueObj)
                            .timestamp(LocalDateTime.ofInstant(Instant.ofEpochMilli(last.getTimestamp()), ZoneOffset.ofHours(8)))
                            .build();
                    latestPerTag.add(dr);
                }
            }
        } catch (Exception e) {
            log.warn("读取 TsFile latest 失败 (deviceId={}): {}", deviceId, e.getMessage());
        }
        // 合并并按时间倒序，截取 topN
        latestPerTag.sort(Comparator.comparing(DataRecord::getTimestamp).reversed());
        if (latestPerTag.size() > topN) {
            return new ArrayList<>(latestPerTag.subList(0, topN));
        }
        return latestPerTag;
    }

    public synchronized void save(List<DataRecord> dataRecords) throws WriteProcessException, IOException {
        // 用writer实现dataRecords的时序数据批量存储
        if (dataRecords == null || dataRecords.isEmpty()) return;
        for (DataRecord dr : dataRecords) {
            MeasurementSchema schema= new MeasurementSchema(dr.getTagId().toString(), TSDataType.FLOAT, TSEncoding.RLE);
            String regKey = tsDevicePath(dr.getDeviceId(), dr.getTagId());
            if(!registeredDevices.contains(regKey)){
                writer.registerTimeseries(new Path(String.valueOf(dr.getDeviceId())), schema);
                registeredDevices.add(regKey);
            }
            long dateTimeLong = dr.getTimestamp().toInstant(ZoneOffset.ofHours(8)).toEpochMilli(); // 东八区
            TSRecord tsRecord = new TSRecord(dateTimeLong, String.valueOf(dr.getDeviceId()));
            tsRecord.addTuple(
                    DataPoint.getDataPoint(
                            schema.getType(),
                            schema.getMeasurementId(),
                            String.valueOf(dr.getValue())));
            writer.write(tsRecord);
        }
    }

    // 构造内部使用的 devicePath
    private String tsDevicePath(Long deviceId,Long tagId) {
        return  String.valueOf(deviceId+"_"+tagId);
    }
}

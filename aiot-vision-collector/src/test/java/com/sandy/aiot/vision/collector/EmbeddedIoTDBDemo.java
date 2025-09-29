package com.sandy.aiot.vision.collector;

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
import org.apache.iotdb.tsfile.read.expression.impl.SingleSeriesExpression;
import org.apache.iotdb.tsfile.read.filter.factory.TimeFilterApi;
import org.apache.iotdb.tsfile.read.filter.factory.ValueFilterApi;
import org.apache.iotdb.tsfile.read.query.dataset.QueryDataSet;
import org.apache.iotdb.tsfile.write.TsFileWriter;
import org.apache.iotdb.tsfile.write.record.TSRecord;
import org.apache.iotdb.tsfile.write.record.datapoint.DataPoint;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;

import static com.sandy.aiot.vision.collector.Constant.*;


@Slf4j
public class EmbeddedIoTDBDemo {
    @Test
    public void testWrite() {
        try {
            String path = "test.tsfile";
            File f = FSFactoryProducer.getFSFactory().getFile(path);
            if (f.exists()) {
                Files.delete(f.toPath());
            }

            try (TsFileWriter tsFileWriter = new TsFileWriter(f)) {
                List<MeasurementSchema> schemas = new ArrayList<>();
                schemas.add(new MeasurementSchema(Constant.SENSOR_1, TSDataType.INT64, TSEncoding.RLE));
                schemas.add(new MeasurementSchema(Constant.SENSOR_2, TSDataType.INT64, TSEncoding.RLE));
                schemas.add(new MeasurementSchema(Constant.SENSOR_3, TSDataType.INT64, TSEncoding.RLE));
                schemas.add(new MeasurementSchema(Constant.SENSOR_4, TSDataType.TEXT, TSEncoding.PLAIN));
                schemas.add(new MeasurementSchema(Constant.SENSOR_5, TSDataType.TEXT, TSEncoding.PLAIN));
                schemas.add(new MeasurementSchema(Constant.SENSOR_6, TSDataType.TEXT, TSEncoding.PLAIN));
                schemas.add(new MeasurementSchema(Constant.SENSOR_7, TSDataType.TEXT, TSEncoding.PLAIN));
                // register timeseries
                tsFileWriter.registerTimeseries(new Path(DEVICE_1), schemas);
                long rowsize=60*10;
                long startTime = System.currentTimeMillis();
                write(tsFileWriter, DEVICE_1, schemas, rowsize, startTime, 0);
            }
        } catch (Exception e) {
            log.error("TsFileWriteWithTSRecord meet error", e);
        }
    }

    private static void write(
            TsFileWriter tsFileWriter,
            String deviceId,
            List<MeasurementSchema> schemas,
            long rowSize,
            long startTime,
            long startValue)
            throws IOException, WriteProcessException {
        long time = startTime;
        for (int i = 0; i < rowSize; i++) {
            // construct TsRecord
            TSRecord tsRecord = new TSRecord(time, deviceId);
            for (MeasurementSchema schema : schemas) {
                tsRecord.addTuple(
                        DataPoint.getDataPoint(
                                schema.getType(),
                                schema.getMeasurementId(),
                                startValue + ""));
                startValue++;
            }
            //time，要求增加1秒
            time += 1000;
            // write
            tsFileWriter.write(tsRecord);
        }
    }

    @Test
    public void getTimeAndValueFilter() throws IOException {
        // file path
        String path = "test.tsfile";
        try (TsFileSequenceReader reader = new TsFileSequenceReader(path);
             TsFileReader readTsFile = new TsFileReader(reader)) {
            ArrayList<Path> paths = new ArrayList<>();
            paths.add(new Path(DEVICE_1, SENSOR_1, true));
            paths.add(new Path(DEVICE_1, SENSOR_2, true));
            paths.add(new Path(DEVICE_1, SENSOR_3, true));
            paths.add(new Path(DEVICE_1, SENSOR_4, true));
            paths.add(new Path(DEVICE_1, SENSOR_5, true));
            paths.add(new Path(DEVICE_1, SENSOR_6, true));
            paths.add(new Path(DEVICE_1, SENSOR_7, true));
            LocalDateTime dateTime = LocalDateTime.of(2025, 9, 1, 0, 0, 0);
            long dateTimeLong = dateTime.toInstant(ZoneOffset.ofHours(8)).toEpochMilli(); // 东八区
            IExpression tFilter =
                    BinaryExpression.and(
                            new GlobalTimeExpression(TimeFilterApi.gtEq(dateTimeLong - 1000 * 6)),
                            new GlobalTimeExpression(TimeFilterApi.ltEq(dateTimeLong + 1000 * 5)));
            IExpression valueFilter =
                    new SingleSeriesExpression(
                            new Path(DEVICE_1, SENSOR_3, true), ValueFilterApi.gtEq(20L));
            IExpression finalFilter = BinaryExpression.and(tFilter, valueFilter);
            queryAndPrint(paths, readTsFile, finalFilter);
        } catch (Exception e) {
            log.error("TsFileWriteWithTSRecord meet error", e);
        }
    }
    @Test
    public void getByLimitFilter() throws IOException {

        String path = "test.tsfile";
        try (TsFileSequenceReader reader = new TsFileSequenceReader(path);
             TsFileReader readTsFile = new TsFileReader(reader)) {
            ArrayList<Path> paths = new ArrayList<>();
            paths.add(new Path(DEVICE_1, SENSOR_1, true));
            paths.add(new Path(DEVICE_1, SENSOR_2, true));
            paths.add(new Path(DEVICE_1, SENSOR_3, true));
            paths.add(new Path(DEVICE_1, SENSOR_4, true));
            paths.add(new Path(DEVICE_1, SENSOR_5, true));
            paths.add(new Path(DEVICE_1, SENSOR_6, true));
            paths.add(new Path(DEVICE_1, SENSOR_7, true));
            int limit = 60;
            long dateTimeLong = System.currentTimeMillis(); // 东八区
            IExpression timeFilter =
                    BinaryExpression.and(
                            new GlobalTimeExpression(TimeFilterApi.gtEq(dateTimeLong - 1000 * limit)),
                            new GlobalTimeExpression(TimeFilterApi.ltEq(dateTimeLong)));
            queryAndPrint(paths, readTsFile, timeFilter);
        } catch (Exception e) {
            log.error("TsFileWriteWithTSRecord meet error", e);
        }

    }
    @Test
    public void getByTimeFilter() throws IOException {

        String path = "test.tsfile";
        try (TsFileSequenceReader reader = new TsFileSequenceReader(path);
             TsFileReader readTsFile = new TsFileReader(reader)) {
            ArrayList<Path> paths = new ArrayList<>();
            paths.add(new Path(DEVICE_1, SENSOR_1, true));
            paths.add(new Path(DEVICE_1, SENSOR_2, true));
            paths.add(new Path(DEVICE_1, SENSOR_3, true));
            paths.add(new Path(DEVICE_1, SENSOR_4, true));
            paths.add(new Path(DEVICE_1, SENSOR_5, true));
            paths.add(new Path(DEVICE_1, SENSOR_6, true));
            paths.add(new Path(DEVICE_1, SENSOR_7, true));
            LocalDateTime dateTime = LocalDateTime.of(2025, 9, 1, 0, 0, 0);
            long dateTimeLong = dateTime.toInstant(ZoneOffset.ofHours(8)).toEpochMilli(); // 东八区
            IExpression timeFilter =
                    BinaryExpression.and(
                            new GlobalTimeExpression(TimeFilterApi.gtEq(dateTimeLong - 1000 * 6)),
                            new GlobalTimeExpression(TimeFilterApi.ltEq(dateTimeLong + 1000 * 2)));
            queryAndPrint(paths, readTsFile, timeFilter);
        } catch (Exception e) {
            log.error("TsFileWriteWithTSRecord meet error", e);
        }

    }
    @Test
    public void getValueFilter() throws IOException {
        // file path
        String path = "test.tsfile";
        try (TsFileSequenceReader reader = new TsFileSequenceReader(path);
             TsFileReader readTsFile = new TsFileReader(reader)) {
            ArrayList<Path> paths = new ArrayList<>();
            paths.add(new Path(DEVICE_1, SENSOR_1, true));
            paths.add(new Path(DEVICE_1, SENSOR_2, true));
            paths.add(new Path(DEVICE_1, SENSOR_3, true));
            paths.add(new Path(DEVICE_1, SENSOR_4, true));
            paths.add(new Path(DEVICE_1, SENSOR_5, true));
            paths.add(new Path(DEVICE_1, SENSOR_6, true));
            paths.add(new Path(DEVICE_1, SENSOR_7, true));
            // value filter : device_1.sensor_2 <= 20, should select 1 2 4 6 7
            IExpression valueFilter =
                    new SingleSeriesExpression(
                            new Path(DEVICE_1, SENSOR_2, true), ValueFilterApi.ltEq(11L));
            queryAndPrint(paths, readTsFile, valueFilter);
        } catch (Exception e) {
            log.error("TsFileWriteWithTSRecord meet error", e);
        }
    }
    @Test
    public void getAll() throws IOException {
        // file path
        String path = "test.tsfile";
        try (TsFileSequenceReader reader = new TsFileSequenceReader(path);
             TsFileReader readTsFile = new TsFileReader(reader)) {
            ArrayList<Path> paths = new ArrayList<>();
            paths.add(new Path(DEVICE_1, SENSOR_1, true));
            paths.add(new Path(DEVICE_1, SENSOR_2, true));
            paths.add(new Path(DEVICE_1, SENSOR_3, true));
            paths.add(new Path(DEVICE_1, SENSOR_4, true));
            paths.add(new Path(DEVICE_1, SENSOR_5, true));
            paths.add(new Path(DEVICE_1, SENSOR_6, true));
            paths.add(new Path(DEVICE_1, SENSOR_7, true));
            // no filter, should select 1 2 3 4 6 7 8
            queryAndPrint(paths, readTsFile, null);
        } catch (Exception e) {
            log.error("TsFileWriteWithTSRecord meet error", e);
        }
    }


    private static void queryAndPrint(
            ArrayList<Path> paths, TsFileReader readTsFile, IExpression statement) throws IOException {
       queryAndPrint(paths,readTsFile,statement, null);
    }
    private static void queryAndPrint(
            ArrayList<Path> paths, TsFileReader readTsFile, IExpression statement,Integer limit) throws IOException {
        QueryExpression queryExpression = QueryExpression.create(paths, statement);
        QueryDataSet queryDataSet = readTsFile.query(queryExpression);
        int index = 1;
        while (queryDataSet.hasNext()) {
            RowRecord d = queryDataSet.next();
            String next = d.toString();
            log.info("{}: {}",index,next);
            if(limit!=null && index==limit.intValue()){
                break;
            }
            index++;
        }
        log.info("----------------");
    }

}
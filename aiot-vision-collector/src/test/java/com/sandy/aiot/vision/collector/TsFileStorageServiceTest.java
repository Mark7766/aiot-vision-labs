package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.service.TsFileStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Basic unit test for TsFileStorageService write & read flows.
 */
public class TsFileStorageServiceTest {

    private final String testPath = "target/test-records.tsfile";

    @AfterEach
    void cleanup() {
        File f = new File(testPath);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    @Test
    void testWriteAndReadLatestAndTopN() throws Exception {
        TsFileStorageService svc = new TsFileStorageService();
        // inject path
        Field pathField = TsFileStorageService.class.getDeclaredField("tsfilePath");
        pathField.setAccessible(true);
        pathField.set(svc, testPath);
        // init (creates file)
        svc.init();

        LocalDateTime base = LocalDateTime.now().minusSeconds(10);
        for (int i = 0; i < 5; i++) {
            String json = "{\"deviceId\":1,\"device\":\"dev1\",\"data\":{\"v\":" + i + "},\"ts\":\"" + base.plusSeconds(i) + "\"}";
            svc.saveSnapshot(1L, json, base.plusSeconds(i));
        }

        // latest
        DataRecord latest = svc.findLatest(1L).orElseThrow();
        Assertions.assertNotNull(latest.getTimestamp(), "latest timestamp should exist");
        Assertions.assertTrue(latest.getValue().contains("\"v\":4"), "Latest should contain last value v=4");

        // top N (3)
        List<DataRecord> top3 = svc.findTopN(1L, 3);
        Assertions.assertEquals(3, top3.size(), "Top3 size");
        // ensure ordering is newest first
        Assertions.assertTrue(top3.get(0).getValue().contains("\"v\":4"));
        Assertions.assertTrue(top3.get(1).getValue().contains("\"v\":3"));
        Assertions.assertTrue(top3.get(2).getValue().contains("\"v\":2"));
    }
}


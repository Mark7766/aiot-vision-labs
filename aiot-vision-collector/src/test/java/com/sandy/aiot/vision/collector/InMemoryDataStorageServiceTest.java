package com.sandy.aiot.vision.collector;

import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
@Profile("test")
public class InMemoryDataStorageServiceTest implements DataStorageService {
    // deviceId -> (tagId -> list of records newest last)
    private final Map<Long, Map<Long, Deque<DataRecord>>> store = new ConcurrentHashMap<>();
    private static final int MAX_PER_TAG = 500;

    @Override
    public List<DataRecord> findLatest(Long deviceId) {
        Map<Long, Deque<DataRecord>> m = store.getOrDefault(deviceId, Collections.emptyMap());
        List<DataRecord> list = new ArrayList<>();
        for (Deque<DataRecord> q : m.values()) {
            DataRecord r = q.peekLast();
            if (r != null) list.add(r);
        }
        return list;
    }

    @Override
    public Optional<DataRecord> findLatest(Long deviceId, Long tagId) {
        Deque<DataRecord> q = store.getOrDefault(deviceId, Collections.emptyMap()).get(tagId);
        if (q == null) return Optional.empty();
        return Optional.ofNullable(q.peekLast());
    }

    @Override
    public List<DataRecord> findTopN(Long deviceId, Long tagId, int limit) {
        Deque<DataRecord> q = store.getOrDefault(deviceId, Collections.emptyMap()).get(tagId);
        if (q == null) return Collections.emptyList();
        return q.stream().sorted(Comparator.comparing(DataRecord::getTimestamp).reversed()).limit(limit).collect(Collectors.toList());
    }

    @Override
    public List<DataRecord> findTopN(Long deviceId, int limit) {
        return findLatest(deviceId); // simplified for tests
    }

    @Override
    public boolean save(List<DataRecord> dataRecords) {
        for (DataRecord r : dataRecords) {
            if (r.getTimestamp() == null) {
                r.setTimestamp(LocalDateTime.now());
            }
            store.computeIfAbsent(r.getDeviceId(), k -> new ConcurrentHashMap<>())
                    .computeIfAbsent(r.getTagId(), k -> new ArrayDeque<>())
                    .addLast(r);
            Deque<DataRecord> q = store.get(r.getDeviceId()).get(r.getTagId());
            while (q.size() > MAX_PER_TAG) q.removeFirst();
        }
        return true;
    }
}


package com.sandy.aiot.vision.collector.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DataRecordRepository;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.CollectorService;
import com.sandy.aiot.vision.collector.service.PredictService;
import com.sandy.aiot.vision.collector.vo.NamespaceVO;
import com.sandy.aiot.vision.collector.vo.TagValueVO;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelRsp;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/data")
@Slf4j
public class DataController {
    @Autowired
    private DataRecordRepository dataRecordRepository;
    @Autowired
    private CollectorService collectorService;
    @Autowired
    private DeviceRepository deviceRepository;
    @Autowired
    private TagRepository tagRepository;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PredictService predictService;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @GetMapping
    public String viewLatestPerDevice(Model model) {
        List<Device> devices = deviceRepository.findAll();
        List<DeviceSnapshotView> snapshots = new ArrayList<>();
        for (Device device : devices) {
            DeviceSnapshotView view = new DeviceSnapshotView();
            view.setDeviceId(device.getId());
            view.setDeviceName(device.getName());
            view.setProtocol(device.getProtocol());
            view.setConnectionString(device.getConnectionString());
            // tags config
            List<Tag> tags = device.getTags();
            Map<String, Tag> tagByName = tags == null ? Collections.emptyMap() : tags.stream().collect(Collectors.toMap(Tag::getName, t -> t));
            dataRecordRepository.findTop1ByTagIdOrderByTimestampDesc(device.getId()).ifPresent(rec -> {
                view.setTimestamp(rec.getTimestamp() == null ? null : TS_FMT.format(rec.getTimestamp()));
                Map<String, Object> parsed = parseRecordValue(rec);
                // parsed structure wrapper: {deviceId, device, data:{...}, ts:...}
                Map<String, Object> data = extractDataMap(parsed);
                List<TagValueView> tagValues = new ArrayList<>();
                if (data != null) {
                    for (Map.Entry<String, Object> e : data.entrySet()) {
                        String tagName = e.getKey();
                        Object value = e.getValue();
                        if (tagName.startsWith("_") || tagName.equals("mode")) continue; // skip meta
                        TagValueView tv = new TagValueView();
                        tv.setName(tagName);
                        Tag cfg = tagByName.get(tagName);
                        tv.setAddress(cfg == null ? "" : cfg.getAddress());
                        tv.setValue(value == null ? "" : String.valueOf(value));
                        tagValues.add(tv);
                    }
                }
                view.setTags(tagValues);
            });
            snapshots.add(view);
        }
        model.addAttribute("devices", snapshots);
        return "data"; // 使用新的 data.html 模板
    }

    // 手动触发采集
    @PostMapping("/collect")
    public String collectOnce() {
        collectorService.collectDataOnce();
        return "redirect:/data";
    }

    // 历史记录页面（单个 tag）
    @GetMapping("/history/{deviceId}/{tagName}")
    public String tagHistory(@PathVariable Long deviceId, @PathVariable String tagName, Model model) {
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            model.addAttribute("error", "设备不存在");
            return "tag-history";
        }
        Device device = deviceOpt.get();
        List<DataRecord> history = dataRecordRepository.findTop200ByTagIdOrderByTimestampDesc(deviceId);
        List<TagHistoryEntry> entries = new ArrayList<>();
        for (DataRecord rec : history) {
            Map<String, Object> parsed = parseRecordValue(rec);
            Map<String, Object> data = extractDataMap(parsed);
            if (data != null && data.containsKey(tagName)) {
                TagHistoryEntry e = new TagHistoryEntry();
                e.setTimestamp(rec.getTimestamp() == null ? null : TS_FMT.format(rec.getTimestamp()));
                Object val = data.get(tagName);
                e.setValue(val == null ? "" : String.valueOf(val));
                entries.add(e);
            }
        }
        // 按时间升序显示
        Collections.reverse(entries);
        model.addAttribute("device", device);
        model.addAttribute("tagName", tagName);
        model.addAttribute("entries", entries);
        return "tag-history";
    }

    // 提供最新值 JSON 接口用于前端 ajax 刷新
    @GetMapping(value = "/api/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<DeviceSnapshotView> apiLatest() {
        List<Device> devices = deviceRepository.findAll();
        List<DeviceSnapshotView> snapshots = new ArrayList<>();
        for (Device device : devices) {
            DeviceSnapshotView view = new DeviceSnapshotView();
            view.setDeviceId(device.getId());
            view.setDeviceName(device.getName());
            dataRecordRepository.findTop1ByTagIdOrderByTimestampDesc(device.getId()).ifPresent(rec -> {
                view.setTimestamp(rec.getTimestamp() == null ? null : TS_FMT.format(rec.getTimestamp()));
                Map<String, Object> parsed = parseRecordValue(rec);
                Map<String, Object> data = extractDataMap(parsed);
                List<TagValueView> tagValues = new ArrayList<>();
                if (data != null) {
                    for (Map.Entry<String, Object> e : data.entrySet()) {
                        String tagName = e.getKey();
                        if (tagName.startsWith("_") || tagName.equals("mode")) continue;
                        TagValueView tv = new TagValueView();
                        tv.setName(tagName);
                        tv.setValue(e.getValue() == null ? "" : String.valueOf(e.getValue()));
                        tagValues.add(tv);
                    }
                }
                view.setTags(tagValues);
            });
            snapshots.add(view);
        }
        return snapshots;
    }

    // 提供指定设备/标签的历史数据 JSON（最近200条，按时间升序）
    @GetMapping(value = "/api/history/{deviceId}/{tagName}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagHistoryEntry> apiTagHistory(@PathVariable Long deviceId, @PathVariable String tagName){
        List<DataRecord> history = dataRecordRepository.findTop200ByTagIdOrderByTimestampDesc(deviceId);
        List<TagHistoryEntry> list = new ArrayList<>();
        for (DataRecord rec : history) {
            Map<String,Object> parsed = parseRecordValue(rec);
            Map<String,Object> data = extractDataMap(parsed);
            if(data!=null && data.containsKey(tagName)){
                TagHistoryEntry e = new TagHistoryEntry();
                e.setTimestamp(rec.getTimestamp()==null?null:TS_FMT.format(rec.getTimestamp()));
                Object val = data.get(tagName);
                e.setValue(val==null?"":String.valueOf(val));
                list.add(e);
            }
        }
        Collections.reverse(list); // 升序
        return list;
    }

    // 新增：预测接口，返回历史与预测结果
    @GetMapping(value = "/api/predict/{deviceId}/{tagName}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TimeSeriesDataModelRsp apiPredict(@PathVariable Long deviceId, @PathVariable String tagName) {
        try {
            return predictService.predict(deviceId, tagName).toTimeSeriesDataModelRsp();
        } catch (Exception e) {
            log.error("预测失败 deviceId={} tagName={} err={}", deviceId, tagName, e.getMessage());
            return TimeSeriesDataModelRsp.empty();
        }
    }

    // 新增：获取设备的 Namespaces 列表
    @GetMapping(value = "/api/{deviceId}/namespaces", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<NamespaceVO> apiNamespaces(@PathVariable Long deviceId){
        Optional<Device> opt = deviceRepository.findById(deviceId);
        if(opt.isEmpty()) return Collections.emptyList();
        try {
            return collectorService.getNameSpaces(opt.get());
        } catch (Exception e){
            log.error("获取设备 namespaces 失败 deviceId={} err={}", deviceId, e.getMessage());
            return Collections.emptyList();
        }
    }

    // 新增：获取指定 namespace 下的 tags 列表
    @GetMapping(value = "/api/{deviceId}/namespaces/{nsIndex}/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagValueVO> apiNamespaceTags(@PathVariable Long deviceId, @PathVariable int nsIndex){
        Optional<Device> opt = deviceRepository.findById(deviceId);
        if(opt.isEmpty()) return Collections.emptyList();
        try {
            NamespaceVO ns = NamespaceVO.builder().index(nsIndex).uri(null).build();
            return collectorService.getTagsByDeviceAndNamespace(opt.get(), ns);
        } catch (Exception e){
            log.error("获取 namespace tags 失败 deviceId={} nsIndex={} err={}", deviceId, nsIndex, e.getMessage());
            return Collections.emptyList();
        }
    }

    // 解析 DataRecord.value JSON
    private Map<String, Object> parseRecordValue(DataRecord record) {
        if (record == null || record.getValue() == null) return Collections.emptyMap();
        try {
            return objectMapper.readValue(record.getValue(), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("解析数据失败 id={} err={}", record.getId(), e.getMessage());
            return Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractDataMap(Map<String, Object> parsed) {
        if (parsed == null) return null;
        Object data = parsed.get("data");
        if (data instanceof Map) {
            return (Map<String, Object>) data;
        }
        return null;
    }

    // 视图 DTO
    @Data
    public static class DeviceSnapshotView {
        private Long deviceId;
        private String deviceName;
        private String protocol;
        private String connectionString;
        private String timestamp;
        private List<TagValueView> tags = new ArrayList<>();
    }

    @Data
    public static class TagValueView {
        private String name;
        private String address;
        private String value;
    }

    @Data
    public static class TagHistoryEntry {
        private String timestamp;
        private String value;
    }
}

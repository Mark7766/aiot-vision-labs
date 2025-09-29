package com.sandy.aiot.vision.collector.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.CollectorService;
import com.sandy.aiot.vision.collector.service.PredictService;
import com.sandy.aiot.vision.collector.service.TsFileStorageService;
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

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/data")
@Slf4j
public class DataController {
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
    @Autowired
    private TsFileStorageService tsFileStorageService;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 获取所有设备的所有tag的最新值
     * @param model
     * @return
     */
    @GetMapping
    public String viewLatestPerDevice(Model model) {
        // 默认最近分钟范围（用于过滤最新数据，避免取到过旧的值）
        int minutesWindow = 5;
        List<DeviceSnapshotView> snapshots = buildLatestSnapshots(minutesWindow);
        model.addAttribute("devices", snapshots);
        return "data";
    }

    // 手动触发采集
    @PostMapping("/collect")
    public String collectOnce() {
        collectorService.collectDataOnce();
        return "redirect:/data";
    }

    // 历史记录页面（单个 tag）
    @GetMapping("/history/{deviceId}/{tagId}")
    public String tagHistory(@PathVariable Long deviceId,
                             @PathVariable Long tagId,
                             @RequestParam(value = "minutes", required = false, defaultValue = "3") int minutes,
                             Model model) {
        if (minutes <= 0) minutes = 3; // 合理兜底
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            model.addAttribute("error", "设备不存在");
            return "tag-history";
        }
        Device device = deviceOpt.get();
        // 获取测点
        Optional<Tag> tagOpt = tagRepository.findById(tagId);
        if (tagOpt.isEmpty() || tagOpt.get().getDevice() == null || !Objects.equals(tagOpt.get().getDevice().getId(), deviceId)) {
            model.addAttribute("error", "测点不存在或不属于该设备");
            return "tag-history";
        }
        Tag tag = tagOpt.get();
        // 直接按最近 minutes 分钟获取该测点数据（服务内已按时间倒序）
        List<DataRecord> history = tsFileStorageService.findTopN(deviceId, tagId, minutes);
        List<TagHistoryEntry> entries = new ArrayList<>();
        for (DataRecord rec : history) {
            TagHistoryEntry e = new TagHistoryEntry();
            e.setTimestamp(rec.getTimestamp() == null ? null : TS_FMT.format(rec.getTimestamp()));
            Object val = rec.getValue();
            e.setValue(val == null ? "" : String.valueOf(val));
            entries.add(e);
        }
        // 转为时间升序（页面脚本及模板期望升序）
        Collections.reverse(entries);
        model.addAttribute("device", device);
        model.addAttribute("tagName", tag.getName());
        model.addAttribute("tagId", tag.getId()); // 新增: 传递 tagId 给前端脚本使用
        model.addAttribute("entries", entries);
        model.addAttribute("minutes", minutes);
        return "tag-history";
    }

    // 提供最新值 JSON 接口用于前端 ajax 刷新
    @GetMapping(value = "/api/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<DeviceSnapshotView> apiLatest() {
        int minutesWindow = 5; // 与页面逻辑保持一致
        return buildLatestSnapshots(minutesWindow);
    }

    // 提供指定设备/标签的历史数据 JSON（最近200条，按时间升序）
    @GetMapping(value = "/api/history/{deviceId}/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagHistoryEntry> apiTagHistory(@PathVariable Long deviceId, @PathVariable Long tagId){
        List<DataRecord> history = tsFileStorageService.findTopN(deviceId, tagId,10);
        List<TagHistoryEntry> list = new ArrayList<>();
        for (DataRecord rec : history) {
            TagHistoryEntry e = new TagHistoryEntry();
            e.setTimestamp(rec.getTimestamp() == null ? null : TS_FMT.format(rec.getTimestamp()));
            Object val = rec.getValue();
            e.setValue(val == null ? "" : String.valueOf(val));
            list.add(e);
        }
        Collections.reverse(list); // 升序
        return list;
    }

    // 新增：预测接口，返回历史与预测结果
    @GetMapping(value = "/api/predict/{deviceId}/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TimeSeriesDataModelRsp apiPredict(@PathVariable Long deviceId, @PathVariable Long tagId) {
        try {
            return predictService.predict(deviceId, tagId).toTimeSeriesDataModelRsp();
        } catch (Exception e) {
            log.error("预测失败 deviceId={} tagId={} err={}", deviceId, tagId, e.getMessage());
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

    // 聚合方法：按设备/标签获取最近 minutesWindow 分钟内最新值
    private List<DeviceSnapshotView> buildLatestSnapshots(int minutesWindow){
        List<Device> devices = deviceRepository.findAllWithTags();
        if(devices.isEmpty()) return Collections.emptyList();
        List<DeviceSnapshotView> result = new ArrayList<>();
        for (Device d : devices) {
            DeviceSnapshotView view = new DeviceSnapshotView();
            view.setDeviceId(d.getId());
            view.setDeviceName(d.getName());
            view.setProtocol(d.getProtocol());
            view.setConnectionString(d.getConnectionString());
            LocalDateTime latestTs = null;
            List<Tag> tags = d.getTags();
            if(tags!=null){
                for (Tag t : tags) {
                    TagValueView tv = new TagValueView();
                    tv.setId(t.getId());
                    tv.setName(t.getName());
                    tv.setAddress(t.getAddress());
                    try {
                        List<DataRecord> recs = tsFileStorageService.findTopN(d.getId(), t.getId(), minutesWindow);
                        if(!recs.isEmpty()){
                            DataRecord latest = recs.get(0); // 已倒序
                            if(latest.getTimestamp()!=null && (latestTs==null || latest.getTimestamp().isAfter(latestTs))){
                                latestTs = latest.getTimestamp();
                            }
                            Object val = latest.getValue();
                            tv.setValue(val==null?"":String.valueOf(val));
                        } else {
                            tv.setValue("");
                        }
                    } catch (Exception e){
                        log.warn("获取最新值失败 deviceId={} tagId={} err={}", d.getId(), t.getId(), e.getMessage());
                        tv.setValue("");
                    }
                    view.getTags().add(tv);
                }
            }
            view.setTimestamp(latestTs==null?"":TS_FMT.format(latestTs));
            result.add(view);
        }
        // 可按设备ID排序，保证稳定
        result.sort(Comparator.comparing(DeviceSnapshotView::getDeviceId, Comparator.nullsLast(Long::compareTo)));
        return result;
    }

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
        private Long id;        // 新增: tag 主键 ID
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

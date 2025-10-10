package com.sandy.aiot.vision.collector.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandy.aiot.vision.collector.entity.DataRecord;
import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.repository.DeviceRepository;
import com.sandy.aiot.vision.collector.repository.TagRepository;
import com.sandy.aiot.vision.collector.service.CollectorService;
import com.sandy.aiot.vision.collector.service.DataStorageService;
import com.sandy.aiot.vision.collector.service.PredictService;
import com.sandy.aiot.vision.collector.vo.NamespaceVO;
import com.sandy.aiot.vision.collector.vo.TagValueVO;
import com.sandy.aiot.vision.collector.vo.TimeSeriesDataModelRsp;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Controller providing both HTML view endpoints and JSON APIs
 * for data visualization, prediction, and device/tag CRUD.
 */
@Controller
@RequestMapping("/data")
@Slf4j
@RequiredArgsConstructor
public class DataController {

    private final CollectorService collectorService;
    private final DeviceRepository deviceRepository;
    private final TagRepository tagRepository;
    private final ObjectMapper objectMapper; // kept for potential future serialization needs
    private final PredictService predictService;
    private final DataStorageService dataStorageService;

    @Value("${data.view.latest-minutes-window}")
    private int latestMinutesWindow;
    @Value("${data.api.history-limit}")
    private int apiHistoryLimit;
    @Value("${data.tag-history.default-minutes}")
    private int defaultTagHistoryMinutes;

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 进入实时数据页面（展示所有设备的最新状态）。
     */
    @GetMapping
    public String viewLatestPerDevice(Model model) {
        List<DeviceSnapshotView> snapshots = buildLatestSnapshots(latestMinutesWindow);
        model.addAttribute("devices", snapshots);
        return "data";
    }

    /** 触发一次即时采集（订阅模式下可作为测试）。 */
    @PostMapping("/collect")
    public String collectOnce() {
        collectorService.collectDataOnce();
        return "redirect:/data";
    }

    /**
     * 单个 tag 历史页面。
     */
    @GetMapping("/history/{deviceId}/{tagId}")
    public String tagHistory(@PathVariable Long deviceId,
                             @PathVariable Long tagId,
                             @RequestParam(value = "minutes", required = false) Integer minutes,
                             Model model) {
        int actualMinutes = (minutes == null || minutes <= 0) ? defaultTagHistoryMinutes : minutes;
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            model.addAttribute("error", "设备不存在");
            return "tag-history";
        }
        Optional<Tag> tagOpt = tagRepository.findById(tagId);
        if (tagOpt.isEmpty() || tagOpt.get().getDevice() == null || !Objects.equals(tagOpt.get().getDevice().getId(), deviceId)) {
            model.addAttribute("error", "测点不存在或不属于该设备");
            return "tag-history";
        }
        List<DataRecord> history = dataStorageService.findTopN(deviceId, tagId, actualMinutes);
        List<TagHistoryEntry> entries = new ArrayList<>(history.size());
        for (DataRecord rec : history) {
            TagHistoryEntry e = new TagHistoryEntry();
            e.setTimestamp(rec.getTimestamp() == null ? null : TS_FMT.format(rec.getTimestamp()));
            Object val = rec.getValue();
            e.setValue(val == null ? "" : String.valueOf(val));
            entries.add(e);
        }
        Collections.reverse(entries); // 前端期望升序
        model.addAttribute("device", deviceOpt.get());
        model.addAttribute("tagName", tagOpt.get().getName());
        model.addAttribute("tagId", tagId);
        model.addAttribute("entries", entries);
        model.addAttribute("minutes", actualMinutes);
        return "tag-history";
    }

    /** 最新设备/标签数据（供 Ajax 刷新）。 */
    @GetMapping(value = "/api/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<DeviceSnapshotView> apiLatest() {
        return buildLatestSnapshots(latestMinutesWindow);
    }

    /** 指定设备/标签历史数据（按时间升序, 限制条数可配置）。 */
    @GetMapping(value = "/api/history/{deviceId}/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagHistoryEntry> apiTagHistory(@PathVariable Long deviceId, @PathVariable Long tagId){
        int limit = apiHistoryLimit > 0 ? apiHistoryLimit : 200;
        List<DataRecord> history = dataStorageService.findTopN(deviceId, tagId, limit);
        List<TagHistoryEntry> list = new ArrayList<>(history.size());
        for (DataRecord rec : history) {
            TagHistoryEntry e = new TagHistoryEntry();
            e.setTimestamp(rec.getTimestamp() == null ? null : TS_FMT.format(rec.getTimestamp()));
            Object val = rec.getValue();
            e.setValue(val == null ? "" : String.valueOf(val));
            list.add(e);
        }
        Collections.reverse(list);
        return list;
    }

    /** 预测接口：返回历史与预测结果集合。 */
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

    /** 获取设备 namespaces 列表。 */
    @GetMapping(value = "/api/{deviceId}/namespaces", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<NamespaceVO> apiNamespaces(@PathVariable Long deviceId){
        return deviceRepository.findById(deviceId).map(d -> {
            try { return collectorService.getNameSpaces(d); } catch (Exception e){
                log.error("获取设备 namespaces 失败 deviceId={} err={}", deviceId, e.getMessage());
                return Collections.<NamespaceVO>emptyList();
            }
        }).orElse(Collections.emptyList());
    }

    /** 获取 namespace 下 tags 列表。 */
    @GetMapping(value = "/api/{deviceId}/namespaces/{nsIndex}/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagValueVO> apiNamespaceTags(@PathVariable Long deviceId, @PathVariable int nsIndex){
        return deviceRepository.findById(deviceId).map(d -> {
            try {
                NamespaceVO ns = NamespaceVO.builder().index(nsIndex).uri(null).build();
                return collectorService.getTagsByDeviceAndNamespace(d, ns);
            } catch (Exception e){
                log.error("获取 namespace tags 失败 deviceId={} nsIndex={} err={}", deviceId, nsIndex, e.getMessage());
                return Collections.<TagValueVO>emptyList();
            }
        }).orElse(Collections.emptyList());
    }

    /** 从 Namespace UI 快速添加标签。 */
    @PostMapping(value = "/api/{deviceId}/tags", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TagAddResp> apiQuickAddTag(@PathVariable Long deviceId, @RequestBody TagAddReq req){
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if(devOpt.isEmpty()){
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("设备不存在").build());
        }
        String address = Optional.ofNullable(req.getAddress()).map(String::trim).orElse("");
        if(address.isEmpty()){
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("地址不能为空").build());
        }
        if(tagRepository.findByDeviceIdAndAddress(deviceId, address).isPresent()){
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("已存在该地址的点位").build());
        }
        String name = Optional.ofNullable(req.getName()).map(String::trim).filter(s -> !s.isEmpty()).orElse(address);
        Tag tag = Tag.builder().name(name).address(address).device(devOpt.get()).build();
        try {
            tag = tagRepository.save(tag);
            return ResponseEntity.ok(TagAddResp.builder().success(true).message("添加成功").id(tag.getId()).name(tag.getName()).address(tag.getAddress()).build());
        } catch (Exception e){
            log.error("快速添加点位失败 deviceId={} address={} err={}", deviceId, address, e.getMessage());
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("保存失败:"+e.getMessage()).build());
        }
    }

    /** 通过 JSON 添加设备。 */
    @PostMapping(value = "/api/devices", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DeviceAddResp apiAddDevice(@RequestBody DeviceAddReq req){
        String name = Optional.ofNullable(req.getName()).orElse("").trim();
        String protocol = Optional.ofNullable(req.getProtocol()).map(String::trim).filter(s -> !s.isBlank()).orElse("opcua").toLowerCase();
        String conn = Optional.ofNullable(req.getConnectionString()).orElse("").trim();
        if(name.isEmpty()) return DeviceAddResp.fail("设备名称不能为空");
        if(conn.isEmpty()) return DeviceAddResp.fail("连接字符串不能为空");
        if(!protocol.equals("opcua")) return DeviceAddResp.fail("暂不支持协议:"+protocol);
        try {
            Device device = Device.builder().name(name).protocol(protocol).connectionString(conn).build();
            device = deviceRepository.save(device);
            DeviceAddResp resp = DeviceAddResp.ok();
            resp.setId(device.getId());
            resp.setName(device.getName());
            resp.setProtocol(device.getProtocol());
            resp.setConnectionString(device.getConnectionString());
            return resp;
        } catch (Exception e){
            log.error("新增设备失败 name={} conn={} err={}", name, conn, e.getMessage());
            return DeviceAddResp.fail("保存失败:"+e.getMessage());
        }
    }

    /** 标签列表。 */
    @GetMapping(value = "/api/{deviceId}/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagListItem> apiListTags(@PathVariable Long deviceId){
        return deviceRepository.findById(deviceId).map(d -> {
            List<Tag> tags = tagRepository.findByDeviceId(deviceId);
            List<TagListItem> list = new ArrayList<>(tags.size());
            for (Tag t : tags){
                TagListItem it = new TagListItem();
                it.setId(t.getId());
                it.setName(t.getName());
                it.setAddress(t.getAddress());
                list.add(it);
            }
            list.sort(Comparator.comparing(TagListItem::getId));
            return list;
        }).orElse(Collections.emptyList());
    }

    @Data
    public static class TagUpdateReq { private String name; private String address; }

    /** 更新标签。 */
    @PutMapping(value = "/api/{deviceId}/tags/{tagId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TagAddResp apiUpdateTag(@PathVariable Long deviceId, @PathVariable Long tagId, @RequestBody TagUpdateReq req){
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if(devOpt.isEmpty()) return TagAddResp.builder().success(false).message("设备不存在").build();
        Optional<Tag> tagOpt = tagRepository.findById(tagId);
        if(tagOpt.isEmpty() || tagOpt.get().getDevice()==null || !Objects.equals(tagOpt.get().getDevice().getId(), deviceId)){
            return TagAddResp.builder().success(false).message("点位不存在").build();
        }
        Tag tag = tagOpt.get();
        String newName = Optional.ofNullable(req.getName()).orElse("").trim();
        String newAddr = Optional.ofNullable(req.getAddress()).orElse("").trim();
        if(newAddr.isEmpty()) return TagAddResp.builder().success(false).message("地址不能为空").build();
        Optional<Tag> dup = tagRepository.findByDeviceIdAndAddress(deviceId, newAddr);
        if(dup.isPresent() && !Objects.equals(dup.get().getId(), tag.getId())){
            return TagAddResp.builder().success(false).message("同设备下地址已存在").build();
        }
        tag.setAddress(newAddr);
        if(!newName.isEmpty()) { tag.setName(newName); }
        try {
            tag = tagRepository.save(tag);
            return TagAddResp.builder().success(true).message("更新成功").id(tag.getId()).name(tag.getName()).address(tag.getAddress()).build();
        } catch (Exception e){
            log.error("更新点位失败 deviceId={} tagId={} err={}", deviceId, tagId, e.getMessage());
            return TagAddResp.builder().success(false).message("保存失败:"+e.getMessage()).build();
        }
    }

    /** 删除标签。 */
    @DeleteMapping(value = "/api/{deviceId}/tags/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public SimpleResp apiDeleteTag(@PathVariable Long deviceId, @PathVariable Long tagId){
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if(devOpt.isEmpty()) return SimpleResp.fail("设备不存在");
        Optional<Tag> tagOpt = tagRepository.findById(tagId);
        if(tagOpt.isEmpty() || tagOpt.get().getDevice()==null || !Objects.equals(tagOpt.get().getDevice().getId(), deviceId)){
            return SimpleResp.fail("点位不存在");
        }
        try {
            tagRepository.deleteById(tagId);
            return SimpleResp.ok();
        } catch (Exception e){
            log.error("删除点位失败 deviceId={} tagId={} err={}", deviceId, tagId, e.getMessage());
            return SimpleResp.fail("删除失败:"+e.getMessage());
        }
    }

    /** 删除设备（级联删除标签）。 */
    @DeleteMapping(value = "/api/devices/{deviceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public SimpleResp apiDeleteDevice(@PathVariable Long deviceId){
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if(devOpt.isEmpty()) return SimpleResp.fail("设备不存在");
        try {
            deviceRepository.delete(devOpt.get());
            return SimpleResp.ok();
        } catch (Exception e){
            log.error("删除设备失败 deviceId={} err={}", deviceId, e.getMessage());
            return SimpleResp.fail("删除失败:"+e.getMessage());
        }
    }

    // ---------------- View Model / DTO Classes ----------------

    @Data
    public static class TagListItem { private Long id; private String name; private String address; }

    @Data
    public static class SimpleResp { private boolean success; private String message; public static SimpleResp ok(){ SimpleResp r=new SimpleResp(); r.success=true; r.message="OK"; return r;} public static SimpleResp fail(String m){ SimpleResp r=new SimpleResp(); r.success=false; r.message=m; return r;} }

    private List<DeviceSnapshotView> buildLatestSnapshots(int minutesWindow){
        List<Device> devices = deviceRepository.findAllWithTags();
        if(devices.isEmpty()) return Collections.emptyList();
        List<DeviceSnapshotView> result = new ArrayList<>(devices.size());
        LocalDateTime now = LocalDateTime.now();
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
                    Optional<DataRecord> dataRecord = dataStorageService.findLatest(d.getId(), t.getId());
                    if(dataRecord.isPresent()){
                        tv.setValue(String.valueOf(dataRecord.get().getValue()));
                        LocalDateTime ts = dataRecord.get().getTimestamp();
                        if(ts!=null && (latestTs==null || ts.isAfter(latestTs))) { latestTs = ts; }
                    } else {
                        tv.setValue("");
                    }
                    view.getTags().add(tv);
                }
            }
            view.setTimestamp(latestTs==null?"":TS_FMT.format(latestTs));
            boolean ok = latestTs!=null && java.time.Duration.between(latestTs, now).toMinutes() < minutesWindow + 1;
            view.setConnectionOk(ok);
            result.add(view);
        }
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
        private Boolean connectionOk;
        private List<TagValueView> tags = new ArrayList<>();
    }

    @Data
    public static class TagValueView {
        private Long id;
        private String name;
        private String address;
        private String value;
    }

    @Data
    public static class TagHistoryEntry { private String timestamp; private String value; }

    @Data
    public static class TagAddReq { private String name; private String address; }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TagAddResp {
        private boolean success;
        private String message;
        private Long id;
        private String name;
        private String address;
    }

    @Data
    public static class DeviceAddReq { private String name; private String protocol; private String connectionString; }

    @Data
    public static class DeviceAddResp {
        private boolean success; private String message; private Long id; private String name; private String protocol; private String connectionString;
        public static DeviceAddResp ok(){ DeviceAddResp r = new DeviceAddResp(); r.success=true; r.message="OK"; return r; }
        public static DeviceAddResp fail(String msg){ DeviceAddResp r = new DeviceAddResp(); r.success=false; r.message=msg; return r; }
    }

    @PutMapping(value = "/api/devices/{deviceId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DeviceAddResp apiUpdateDevice(@PathVariable Long deviceId, @RequestBody DeviceAddReq req){
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if(devOpt.isEmpty()) return DeviceAddResp.fail("设备不存在");
        String name = Optional.ofNullable(req.getName()).orElse("").trim();
        String protocol = Optional.ofNullable(req.getProtocol()).map(String::trim).filter(s -> !s.isBlank()).orElse("opcua").toLowerCase();
        String conn = Optional.ofNullable(req.getConnectionString()).orElse("").trim();
        if(name.isEmpty()) return DeviceAddResp.fail("设备名称不能为空");
        if(conn.isEmpty()) return DeviceAddResp.fail("连接字符串不能为空");
        if(!protocol.equals("opcua")) return DeviceAddResp.fail("暂不支持协议:"+protocol);
        try {
            Device d = devOpt.get();
            d.setName(name);
            d.setProtocol(protocol);
            d.setConnectionString(conn);
            d = deviceRepository.save(d);
            DeviceAddResp resp = DeviceAddResp.ok();
            resp.setId(d.getId());
            resp.setName(d.getName());
            resp.setProtocol(d.getProtocol());
            resp.setConnectionString(d.getConnectionString());
            return resp;
        } catch (Exception e){
            log.error("更新设备失败 id={} err={}", deviceId, e.getMessage());
            return DeviceAddResp.fail("保存失败:"+e.getMessage());
        }
    }
}

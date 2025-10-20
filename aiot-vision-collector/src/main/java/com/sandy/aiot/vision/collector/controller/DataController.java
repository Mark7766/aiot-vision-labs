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
     * Displays the real-time data page (showing the latest status of all devices).
     */
    @GetMapping
    public String viewLatestPerDevice(Model model) {
        List<DeviceSnapshotView> snapshots = buildLatestSnapshots(latestMinutesWindow);
        model.addAttribute("devices", snapshots);
        return "data";
    }

    /**
     * Displays the history page for a single tag.
     */
    @GetMapping("/history/{deviceId}/{tagId}")
    public String tagHistory(@PathVariable Long deviceId,
                             @PathVariable Long tagId,
                             @RequestParam(value = "minutes", required = false) Integer minutes,
                             Model model) {
        int actualMinutes = (minutes == null || minutes <= 0) ? defaultTagHistoryMinutes : minutes;
        Optional<Device> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isEmpty()) {
            model.addAttribute("error", "Device does not exist");
            return "tag-history";
        }
        Optional<Tag> tagOpt = tagRepository.findById(tagId);
        if (tagOpt.isEmpty() || tagOpt.get().getDevice() == null || !Objects.equals(tagOpt.get().getDevice().getId(), deviceId)) {
            model.addAttribute("error", "Tag does not exist or does not belong to this device");
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
        Collections.reverse(entries); // Frontend expects ascending order
        model.addAttribute("device", deviceOpt.get());
        model.addAttribute("tagName", tagOpt.get().getName());
        model.addAttribute("tagId", tagId);
        model.addAttribute("entries", entries);
        model.addAttribute("minutes", actualMinutes);
        return "tag-history";
    }

    /**
     * Returns the latest device/tag data (for Ajax refresh).
     */
    @GetMapping(value = "/api/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<DeviceSnapshotView> apiLatest() {
        return buildLatestSnapshots(latestMinutesWindow);
    }

    /**
     * Returns historical data for a specific device/tag (in ascending time order, with configurable limit).
     */
    @GetMapping(value = "/api/history/{deviceId}/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagHistoryEntry> apiTagHistory(@PathVariable Long deviceId, @PathVariable Long tagId) {
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

    /**
     * Prediction endpoint: Returns a collection of historical and predicted results.
     */
    @GetMapping(value = "/api/predict/{deviceId}/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TimeSeriesDataModelRsp apiPredict(@PathVariable Long deviceId, @PathVariable Long tagId) {
        try {
            return predictService.predict(deviceId, tagId).toTimeSeriesDataModelRsp();
        } catch (Exception e) {
            log.error("Prediction failed for deviceId={} tagId={} error={}", deviceId, tagId, e.getMessage());
            return TimeSeriesDataModelRsp.empty();
        }
    }

    /**
     * Retrieves the list of namespaces for a device.
     */
    @GetMapping(value = "/api/{deviceId}/namespaces", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<NamespaceVO> apiNamespaces(@PathVariable Long deviceId) {
        return deviceRepository.findById(deviceId).map(d -> {
            try {
                return collectorService.getNameSpaces(d);
            } catch (Exception e) {
                log.error("Failed to retrieve namespaces for deviceId={} error={}", deviceId, e.getMessage());
                return Collections.<NamespaceVO>emptyList();
            }
        }).orElse(Collections.emptyList());
    }

    /**
     * Retrieves the list of tags under a namespace.
     */
    @GetMapping(value = "/api/{deviceId}/namespaces/{nsIndex}/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagValueVO> apiNamespaceTags(@PathVariable Long deviceId, @PathVariable int nsIndex) {
        return deviceRepository.findById(deviceId).map(d -> {
            try {
                NamespaceVO ns = NamespaceVO.builder().index(nsIndex).uri(null).build();
                return collectorService.getTagsByDeviceAndNamespace(d, ns);
            } catch (Exception e) {
                log.error("Failed to retrieve tags for namespace deviceId={} nsIndex={} error={}", deviceId, nsIndex, e.getMessage());
                return Collections.<TagValueVO>emptyList();
            }
        }).orElse(Collections.emptyList());
    }

    /**
     * Quickly adds a tag from the Namespace UI.
     */
    @PostMapping(value = "/api/{deviceId}/tags", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TagAddResp> apiQuickAddTag(@PathVariable Long deviceId, @RequestBody TagAddReq req) {
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if (devOpt.isEmpty()) {
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("Device does not exist").build());
        }
        String address = Optional.ofNullable(req.getAddress()).map(String::trim).orElse("");
        if (address.isEmpty()) {
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("Address cannot be empty").build());
        }
        if (tagRepository.findByDeviceIdAndAddress(deviceId, address).isPresent()) {
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("A tag with this address already exists").build());
        }
        String name = Optional.ofNullable(req.getName()).map(String::trim).filter(s -> !s.isEmpty()).orElse(address);
        Tag tag = Tag.builder().name(name).address(address).device(devOpt.get()).build();
        try {
            tag = tagRepository.save(tag);
            return ResponseEntity.ok(TagAddResp.builder()
                    .success(true)
                    .message("Tag added successfully")
                    .id(tag.getId())
                    .name(tag.getName())
                    .address(tag.getAddress())
                    .build());
        } catch (Exception e) {
            log.error("Failed to quickly add tag for deviceId={} address={} error={}", deviceId, address, e.getMessage());
            return ResponseEntity.ok(TagAddResp.builder().success(false).message("Save failed: " + e.getMessage()).build());
        }
    }

    /**
     * Adds a device via JSON.
     */
    @PostMapping(value = "/api/devices", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DeviceAddResp apiAddDevice(@RequestBody DeviceAddReq req) {
        String name = Optional.ofNullable(req.getName()).orElse("").trim();
        String protocol = Optional.ofNullable(req.getProtocol()).map(String::trim).filter(s -> !s.isBlank()).orElse("opcua").toLowerCase();
        String conn = Optional.ofNullable(req.getConnectionString()).orElse("").trim();
        if (name.isEmpty()) return DeviceAddResp.fail("Device name cannot be empty");
        if (conn.isEmpty()) return DeviceAddResp.fail("Connection string cannot be empty");
        if (!protocol.equals("opcua")) return DeviceAddResp.fail("Protocol not supported: " + protocol);
        try {
            Device device = Device.builder().name(name).protocol(protocol).connectionString(conn).build();
            device = deviceRepository.save(device);
            DeviceAddResp resp = DeviceAddResp.ok();
            resp.setId(device.getId());
            resp.setName(device.getName());
            resp.setProtocol(device.getProtocol());
            resp.setConnectionString(device.getConnectionString());
            return resp;
        } catch (Exception e) {
            log.error("Failed to add device name={} conn={} error={}", name, conn, e.getMessage());
            return DeviceAddResp.fail("Save failed: " + e.getMessage());
        }
    }

    /**
     * Lists tags for a device.
     */
    @GetMapping(value = "/api/{deviceId}/tags", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<TagListItem> apiListTags(@PathVariable Long deviceId) {
        return deviceRepository.findById(deviceId).map(d -> {
            List<Tag> tags = tagRepository.findByDeviceId(deviceId);
            List<TagListItem> list = new ArrayList<>(tags.size());
            for (Tag t : tags) {
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
    public static class TagUpdateReq {
        private String name;
        private String address;
    }

    /**
     * Updates a tag.
     */
    @PutMapping(value = "/api/{deviceId}/tags/{tagId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public TagAddResp apiUpdateTag(@PathVariable Long deviceId, @PathVariable Long tagId, @RequestBody TagUpdateReq req) {
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if (devOpt.isEmpty()) return TagAddResp.builder().success(false).message("Device does not exist").build();
        Optional<Tag> tagOpt = tagRepository.findById(tagId);
        if (tagOpt.isEmpty() || tagOpt.get().getDevice() == null || !Objects.equals(tagOpt.get().getDevice().getId(), deviceId)) {
            return TagAddResp.builder().success(false).message("Tag does not exist").build();
        }
        Tag tag = tagOpt.get();
        String newName = Optional.ofNullable(req.getName()).orElse("").trim();
        String newAddr = Optional.ofNullable(req.getAddress()).orElse("").trim();
        if (newAddr.isEmpty()) return TagAddResp.builder().success(false).message("Address cannot be empty").build();
        Optional<Tag> dup = tagRepository.findByDeviceIdAndAddress(deviceId, newAddr);
        if (dup.isPresent() && !Objects.equals(dup.get().getId(), tag.getId())) {
            return TagAddResp.builder().success(false).message("Address already exists for this device").build();
        }
        tag.setAddress(newAddr);
        if (!newName.isEmpty()) {
            tag.setName(newName);
        }
        try {
            tag = tagRepository.save(tag);
            return TagAddResp.builder()
                    .success(true)
                    .message("Tag updated successfully")
                    .id(tag.getId())
                    .name(tag.getName())
                    .address(tag.getAddress())
                    .build();
        } catch (Exception e) {
            log.error("Failed to update tag for deviceId={} tagId={} error={}", deviceId, tagId, e.getMessage());
            return TagAddResp.builder().success(false).message("Save failed: " + e.getMessage()).build();
        }
    }

    /**
     * Deletes a tag.
     */
    @DeleteMapping(value = "/api/{deviceId}/tags/{tagId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public SimpleResp apiDeleteTag(@PathVariable Long deviceId, @PathVariable Long tagId) {
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if (devOpt.isEmpty()) return SimpleResp.fail("Device does not exist");
        Optional<Tag> tagOpt = tagRepository.findById(tagId);
        if (tagOpt.isEmpty() || tagOpt.get().getDevice() == null || !Objects.equals(tagOpt.get().getDevice().getId(), deviceId)) {
            return SimpleResp.fail("Tag does not exist");
        }
        try {
            tagRepository.deleteById(tagId);
            return SimpleResp.ok();
        } catch (Exception e) {
            log.error("Failed to delete tag for deviceId={} tagId={} error={}", deviceId, tagId, e.getMessage());
            return SimpleResp.fail("Delete failed: " + e.getMessage());
        }
    }

    /**
     * Deletes a device (cascades to delete associated tags).
     */
    @DeleteMapping(value = "/api/devices/{deviceId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public SimpleResp apiDeleteDevice(@PathVariable Long deviceId) {
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if (devOpt.isEmpty()) return SimpleResp.fail("Device does not exist");
        try {
            deviceRepository.delete(devOpt.get());
            return SimpleResp.ok();
        } catch (Exception e) {
            log.error("Failed to delete device for deviceId={} error={}", deviceId, e.getMessage());
            return SimpleResp.fail("Delete failed: " + e.getMessage());
        }
    }

    // ---------------- View Model / DTO Classes ----------------

    @Data
    public static class TagListItem {
        private Long id;
        private String name;
        private String address;
    }

    @Data
    public static class SimpleResp {
        private boolean success;
        private String message;

        public static SimpleResp ok() {
            SimpleResp r = new SimpleResp();
            r.success = true;
            r.message = "OK";
            return r;
        }

        public static SimpleResp fail(String m) {
            SimpleResp r = new SimpleResp();
            r.success = false;
            r.message = m;
            return r;
        }
    }

    private List<DeviceSnapshotView> buildLatestSnapshots(int minutesWindow) {
        List<Device> devices = deviceRepository.findAllWithTags();
        if (devices.isEmpty()) return Collections.emptyList();
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
            if (tags != null) {
                for (Tag t : tags) {
                    TagValueView tv = new TagValueView();
                    tv.setId(t.getId());
                    tv.setName(t.getName());
                    tv.setAddress(t.getAddress());
                    Optional<DataRecord> dataRecord = dataStorageService.findLatest(d.getId(), t.getId());
                    if (dataRecord.isPresent()) {
                        tv.setValue(String.valueOf(dataRecord.get().getValue()));
                        LocalDateTime ts = dataRecord.get().getTimestamp();
                        if (ts != null && (latestTs == null || ts.isAfter(latestTs))) {
                            latestTs = ts;
                        }
                    } else {
                        tv.setValue("");
                    }
                    view.getTags().add(tv);
                }
            }
            view.setTimestamp(latestTs == null ? "" : TS_FMT.format(latestTs));
            boolean ok = collectorService.isConnectionOk(d);
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
    public static class TagHistoryEntry {
        private String timestamp;
        private String value;
    }

    @Data
    public static class TagAddReq {
        private String name;
        private String address;
    }

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
    public static class DeviceAddReq {
        private String name;
        private String protocol;
        private String connectionString;
    }

    @Data
    public static class DeviceAddResp {
        private boolean success;
        private String message;
        private Long id;
        private String name;
        private String protocol;
        private String connectionString;

        public static DeviceAddResp ok() {
            DeviceAddResp r = new DeviceAddResp();
            r.success = true;
            r.message = "OK";
            return r;
        }

        public static DeviceAddResp fail(String msg) {
            DeviceAddResp r = new DeviceAddResp();
            r.success = false;
            r.message = msg;
            return r;
        }
    }

    /**
     * Updates a device.
     */
    @PutMapping(value = "/api/devices/{deviceId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public DeviceAddResp apiUpdateDevice(@PathVariable Long deviceId, @RequestBody DeviceAddReq req) {
        Optional<Device> devOpt = deviceRepository.findById(deviceId);
        if (devOpt.isEmpty()) return DeviceAddResp.fail("Device does not exist");
        String name = Optional.ofNullable(req.getName()).orElse("").trim();
        String protocol = Optional.ofNullable(req.getProtocol()).map(String::trim).filter(s -> !s.isBlank()).orElse("opcua").toLowerCase();
        String conn = Optional.ofNullable(req.getConnectionString()).orElse("").trim();
        if (name.isEmpty()) return DeviceAddResp.fail("Device name cannot be empty");
        if (conn.isEmpty()) return DeviceAddResp.fail("Connection string cannot be empty");
        if (!protocol.equals("opcua")) return DeviceAddResp.fail("Protocol not supported: " + protocol);
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
        } catch (Exception e) {
            log.error("Failed to update device for id={} error={}", deviceId, e.getMessage());
            return DeviceAddResp.fail("Save failed: " + e.getMessage());
        }
    }
}
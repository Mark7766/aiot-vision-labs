package com.sandy.aiot.vision.collector.controller;

import com.sandy.aiot.vision.collector.entity.Device;
import com.sandy.aiot.vision.collector.entity.Tag;
import com.sandy.aiot.vision.collector.service.DataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequestMapping("/devices")
public class DeviceController {
    @Autowired
    private DataService dataService;

    @GetMapping
    public String listDevices(Model model) {
        model.addAttribute("devices", dataService.getAllDevices());
        // 添加用于页面直接创建设备的表单绑定对象
        model.addAttribute("device", new Device());
        return "devices";
    }

    @GetMapping("/add")
    public String addDeviceForm(Model model) {
        model.addAttribute("device", new Device());
        return "device-form"; // 复用或单独模板
    }

    @PostMapping
    public String saveDevice(@ModelAttribute Device device, Model model) {
        dataService.saveDevice(device);
        return "redirect:/devices";
    }

    @GetMapping("/edit/{id}")
    public String editDevice(@PathVariable Long id, Model model) {
        Device device = dataService.getDeviceById(id);
        model.addAttribute("device", device);
        return "device-form";
    }

    @PostMapping("/delete/{id}")
    public String deleteDevice(@PathVariable Long id) {
        dataService.deleteDevice(id);
        return "redirect:/devices";
    }

    @GetMapping("/{deviceId}/tags/add")
    public String addTagForm(@PathVariable Long deviceId, Model model) {
        Tag tag = new Tag();
        tag.setDevice(dataService.getDeviceById(deviceId));
        model.addAttribute("tag", tag);
        model.addAttribute("deviceId", deviceId);
        return "tag-form";
    }

    @PostMapping("/{deviceId}/tags")
    public String saveTag(@PathVariable Long deviceId, @ModelAttribute Tag tag) {
        tag.setDevice(dataService.getDeviceById(deviceId));
        dataService.saveTag(tag);
        return "redirect:/devices";
    }
}

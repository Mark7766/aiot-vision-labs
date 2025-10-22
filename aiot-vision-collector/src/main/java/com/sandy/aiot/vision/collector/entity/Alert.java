package com.sandy.aiot.vision.collector.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Alert entity representing a predictive / threshold warning for a tag.
 * MVP fields kept minimal; can be extended later (e.g. confidence interval, trend metrics).
 */
@Entity
@Table(name = "alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long deviceId;
    private Long tagId;

    /** Type classifier: THRESHOLD, DEVIATION (future: TREND, CONFIDENCE). */
    @Column(length = 32)
    private String type;

    /** Severity: LOW / MEDIUM / HIGH */
    @Column(length = 16)
    private String severity;

    /** Human readable message (i18n later). */
    @Column(length = 500)
    private String message;

    /** Threshold used to trigger (numeric). */
    private Double thresholdValue;
    /** Actual latest value (if available). */
    private Double actualValue;
    /** Max predicted value (if available). */
    private Double predictedPeakValue;
    /** Baseline predicted value (first prediction point) for deviation alerts. */
    private Double predictedBaseValue;
    /** Deviation percentage ( (actual - predictedBase)/predictedBase * 100 ). */
    private Double deviationPercent;

    /** Creation timestamp. */
    private LocalDateTime createdAt;

    /** Acknowledged status */
    private boolean acknowledged;
    private LocalDateTime acknowledgedAt;

    /** Ignored (dismissed without action) */
    private boolean ignored;
    private LocalDateTime ignoredAt;

    /** For duplicate suppression, store a signature hash (device+tag+type). */
    @Column(length = 120)
    private String signature;
}

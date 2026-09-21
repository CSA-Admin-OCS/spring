package com.open.spring.mvc.bathroom.bathroomML;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.open.spring.mvc.bathroom.Tinkle;

import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.api.Table;

/**
 * Aggregates data per person and generates labeled training data for ML.
 */
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class BathroomPreprocessNew {
    @Autowired
    private BathroomService bathroomService;

    @EventListener(ApplicationReadyEvent.class)
    public void process() {
        List<Tinkle> logs = bathroomService.getAllLogs();
        System.out.println("logs.size() = " + logs.size());

        Map<String, List<Double>> durationMap = new HashMap<>();
        Map<String, Integer> abnormalCountMap = new HashMap<>();

        for (Tinkle log : logs) {
            if (log.getTimeInOutPairs() == null) continue;
            String name = log.getPersonName();
            for (LocalDateTime[] pair : log.getTimeInOutPairs()) {
                long minutes = Duration.between(pair[0], pair[1]).toMinutes();
                durationMap.computeIfAbsent(name, k -> new ArrayList<>()).add((double) minutes);
                abnormalCountMap.put(name, abnormalCountMap.getOrDefault(name, 0) + (minutes > 15 ? 1 : 0));
            }
        }

        // Prepare columns for aggregate per-person data
        StringColumn nameCol = StringColumn.create("Name");
        DoubleColumn meanDurationCol = DoubleColumn.create("MeanDuration");
        DoubleColumn maxDurationCol = DoubleColumn.create("MaxDuration");
        DoubleColumn stdDevDurationCol = DoubleColumn.create("StdDevDuration");
        DoubleColumn totalVisitsCol = DoubleColumn.create("TotalVisits");
        DoubleColumn percentAbnormalCol = DoubleColumn.create("PercentAbnormal");
        BooleanColumn abnormalPersonCol = BooleanColumn.create("AbnormalPerson");

        for (Map.Entry<String, List<Double>> entry : durationMap.entrySet()) {
            String name = entry.getKey();
            List<Double> durations = entry.getValue();
            double mean = durations.stream().mapToDouble(d -> d).average().orElse(0);
            double max = durations.stream().mapToDouble(d -> d).max().orElse(0);
            double stdDev = Math.sqrt(durations.stream().mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0));
            int total = durations.size();
            int abnormalCount = abnormalCountMap.getOrDefault(name, 0);
            double percentAbnormal = (double) abnormalCount / total;

            nameCol.append(name);
            meanDurationCol.append(mean);
            maxDurationCol.append(max);
            stdDevDurationCol.append(stdDev);
            totalVisitsCol.append((double) total);
            percentAbnormalCol.append(percentAbnormal);
            abnormalPersonCol.append(percentAbnormal > 0.3); // Threshold
        }

        Table table = Table.create("Bathroom Summary", nameCol, meanDurationCol, maxDurationCol,
                stdDevDurationCol, totalVisitsCol, percentAbnormalCol, abnormalPersonCol);

        // Normalize numeric columns
        normalizeColumn(table, "MeanDuration");
        normalizeColumn(table, "MaxDuration");
        normalizeColumn(table, "StdDevDuration");
        normalizeColumn(table, "TotalVisits");
        normalizeColumn(table, "PercentAbnormal");

        // Save to CSV
        File outputFile = new File("bathroom_summary.csv");
        table.write().csv(outputFile);
    }

    private void normalizeColumn(Table table, String columnName) {
        DoubleColumn col = table.doubleColumn(columnName);
        double min = col.min();
        double max = col.max();
        for (int i = 0; i < col.size(); i++) {
            double norm = (col.getDouble(i) - min) / (max - min);
            col.set(i, norm);
        }
    }
}

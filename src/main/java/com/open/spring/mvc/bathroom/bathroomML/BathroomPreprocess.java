package com.open.spring.mvc.bathroom.bathroomML;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.io.File;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.open.spring.mvc.bathroom.Tinkle;

import tech.tablesaw.api.BooleanColumn;
import tech.tablesaw.api.DoubleColumn;
import tech.tablesaw.api.StringColumn;
import tech.tablesaw.columns.numbers.IntColumnType;
import tech.tablesaw.api.Table;

/*
 * Retrieves bathroom logs from DB and creates CSV table. Categorizes dataset based on columns and normalizes data
 */
@ConditionalOnProperty(name = "app.bootstrap.enabled", havingValue = "true", matchIfMissing = true)
@Component
public class BathroomPreprocess { 
    @Autowired
    private BathroomService bathroomService;

    @EventListener(ApplicationReadyEvent.class)
    public void process() { 
        List<Tinkle> logs = bathroomService.getAllLogs();
        System.out.println("logs.size() = " + logs.size());

        for (Tinkle log : logs) {
            System.out.println("Tinkle record found: " + log.getPersonName());
        }

        // Create columns
        StringColumn nameCol = StringColumn.create("Name");
        DoubleColumn durationCol = DoubleColumn.create("Duration"); // in minutes
        DoubleColumn durationByPeriodCol = DoubleColumn.create("Average Duration By Period"); // in minutes
        StringColumn dateCol = StringColumn.create("Date");
        BooleanColumn abnormalCol = BooleanColumn.create("Abnormal");

        for (Tinkle log : logs) {
            // System.out.println("Log for: " + log.getPersonName());
            if (log.getTimeInOutPairs() == null) {
                System.out.println("timeInOutPairs is null!");
                continue;
            }
            for (LocalDateTime[] pair : log.getTimeInOutPairs()) {
                LocalDateTime timeIn = pair[0];
                LocalDateTime timeOut = pair[1];

                long minutes = Duration.between(timeIn, timeOut).toMinutes();

                nameCol.append(log.getPersonName());
                durationCol.append((double) minutes);
                dateCol.append(timeIn.toLocalDate().toString());
                abnormalCol.append(minutes > 15);
            }
        }

        Table table = Table.create("Bathroom Logs", nameCol, durationCol, durationByPeriodCol, dateCol, abnormalCol);

    //     // Normalize duration (optional)
        normalizeColumn(table, "Duration");

    //     // Save to CSV
        File outputFile = new File("bathroom_cleaned.csv");
        table.write().csv(outputFile);
    //     System.out.println("Preprocessing done. File saved as bathroom_cleaned.csv!");
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


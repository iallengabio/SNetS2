package com.snets2.output;

import org.apache.poi.ss.usermodel.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ExcelExporterTest {

    @Test
    void testNaturalSortingOfDimensionsAndScenarios(@TempDir Path tempDir) throws Exception {
        SimulationResult result = new SimulationResult(1);
        Map<String, Object> scenario = Map.of("load", 10.0);

        // Add slots values in a completely unsorted/lexicographical order
        // Standard lexicographical order of these: 12, 14, 3, 33, 4, 9
        // Natural order should be: 3, 4, 9, 12, 14, 33
        result.addValue("SpectrumSizeStatistics", "Percentage per Slot Size", Map.of("slots", "12", "link", "all"), scenario, 0, 0.12);
        result.addValue("SpectrumSizeStatistics", "Percentage per Slot Size", Map.of("slots", "14", "link", "all"), scenario, 0, 0.14);
        result.addValue("SpectrumSizeStatistics", "Percentage per Slot Size", Map.of("slots", "3", "link", "all"), scenario, 0, 0.03);
        result.addValue("SpectrumSizeStatistics", "Percentage per Slot Size", Map.of("slots", "33", "link", "all"), scenario, 0, 0.33);
        result.addValue("SpectrumSizeStatistics", "Percentage per Slot Size", Map.of("slots", "4", "link", "all"), scenario, 0, 0.04);
        result.addValue("SpectrumSizeStatistics", "Percentage per Slot Size", Map.of("slots", "9", "link", "all"), scenario, 0, 0.09);

        // Also add overlaps to verify it's sorted naturally as well
        // Values: 10, 2, 0, 1
        // Natural order: 0, 1, 2, 10
        result.addValue("PhysicalLayerStatistics", "Average XT (dB) per overlaps", Map.of("src", "all", "dest", "all", "overlaps", "10"), scenario, 0, -10.0);
        result.addValue("PhysicalLayerStatistics", "Average XT (dB) per overlaps", Map.of("src", "all", "dest", "all", "overlaps", "2"), scenario, 0, -2.0);
        result.addValue("PhysicalLayerStatistics", "Average XT (dB) per overlaps", Map.of("src", "all", "dest", "all", "overlaps", "0"), scenario, 0, 0.0);
        result.addValue("PhysicalLayerStatistics", "Average XT (dB) per overlaps", Map.of("src", "all", "dest", "all", "overlaps", "1"), scenario, 0, -1.0);

        Path outputPath = tempDir.resolve("results.xlsx");
        ExcelExporter exporter = new ExcelExporter();
        exporter.export(result, outputPath);

        assertTrue(outputPath.toFile().exists());

        try (Workbook wb = WorkbookFactory.create(new FileInputStream(outputPath.toFile()))) {
            // 1. Verify slots sorting
            Sheet slotSheet = wb.getSheet("SpectrumSizeStatistics");
            assertNotNull(slotSheet);

            List<String> actualSlots = new ArrayList<>();
            for (int r = 1; r <= slotSheet.getLastRowNum(); r++) {
                Row row = slotSheet.getRow(r);
                if (row == null) continue;
                // Columns order: SubMetric (0), load (1), link (2), slots (3), rep0 (4)
                Cell slotsCell = row.getCell(3);
                actualSlots.add(slotsCell.getStringCellValue());
            }

            List<String> expectedSlots = List.of("3", "4", "9", "12", "14", "33");
            assertEquals(expectedSlots, actualSlots, "Slots should be sorted numerically");

            // 2. Verify overlaps sorting
            Sheet overlapSheet = wb.getSheet("PhysicalLayerStatistics");
            assertNotNull(overlapSheet);

            List<String> actualOverlaps = new ArrayList<>();
            for (int r = 1; r <= overlapSheet.getLastRowNum(); r++) {
                Row row = overlapSheet.getRow(r);
                if (row == null) continue;
                // Columns order: SubMetric (0), load (1), dest (2), overlaps (3), src (4), rep0 (5)
                Cell overlapsCell = row.getCell(3);
                actualOverlaps.add(overlapsCell.getStringCellValue());
            }

            List<String> expectedOverlaps = List.of("0", "1", "2", "10");
            assertEquals(expectedOverlaps, actualOverlaps, "Overlaps should be sorted numerically");
        }
    }
}

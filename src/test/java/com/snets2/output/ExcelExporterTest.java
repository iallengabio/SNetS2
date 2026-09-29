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

    @Test
    void testBlockingProbabilitySheetHasBitRateRowsAndUniformColumns(@TempDir Path tempDir) throws Exception {
        com.snets2.metrics.BitRateBlockingMetrics bp = new com.snets2.metrics.BitRateBlockingMetrics();
        // 100 Gbps: 4 requests, 1 blocked; 400 Gbps: 2 requests, 1 blocked
        for (int i = 0; i < 4; i++) bp.recordArrival("1", "2", 100.0);
        for (int i = 0; i < 2; i++) bp.recordArrival("2", "1", 400.0);
        bp.recordBlock("1", "2", 100.0, com.snets2.metrics.BlockingCause.FRAGMENTATION, 0);
        bp.recordBlock("2", "1", 400.0, com.snets2.metrics.BlockingCause.FRAGMENTATION, 1);

        SimulationResult result = new SimulationResult(1);
        bp.fillResults(result, Map.of("load", 10.0), 0, 2);

        // Every row of the sheet has the same dimension keys
        Set<String> expectedKeys = Set.of("src", "dest", "core", "bitrate");
        for (SimulationResult.MetricRow row : result.getData().get("BlockingProbability").values()) {
            assertEquals(expectedKeys, row.getDimensions().keySet(), row.getSubMetric());
        }

        Path outputPath = tempDir.resolve("results.xlsx");
        new ExcelExporter().export(result, outputPath);

        try (Workbook wb = WorkbookFactory.create(new FileInputStream(outputPath.toFile()))) {
            Sheet sheet = wb.getSheet("BlockingProbability");
            assertNotNull(sheet);

            // Columns: SubMetric (0), load (1), bitrate (2), core (3), dest (4), src (5), rep0 (6)
            List<String> header = new ArrayList<>();
            sheet.getRow(0).forEach(c -> header.add(c.getStringCellValue()));
            assertEquals(List.of("SubMetric", "load", "bitrate", "core", "dest", "src", "rep0"), header);

            Map<String, Double> perBitRate = new TreeMap<>();
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                for (int c = 2; c <= 5; c++) {
                    assertFalse(row.getCell(c).getStringCellValue().isEmpty(), "empty dimension in row " + r);
                }
                String subMetric = row.getCell(0).getStringCellValue();
                if (subMetric.equals("BP per bit rate")) {
                    assertEquals("all", row.getCell(3).getStringCellValue());
                    assertEquals("all", row.getCell(4).getStringCellValue());
                    assertEquals("all", row.getCell(5).getStringCellValue());
                    perBitRate.put(row.getCell(2).getStringCellValue(), row.getCell(6).getNumericCellValue());
                } else {
                    assertEquals("all", row.getCell(2).getStringCellValue(), subMetric);
                }
            }
            assertEquals(Map.of("100.0", 0.25, "400.0", 0.5), perBitRate);
        }
    }
}

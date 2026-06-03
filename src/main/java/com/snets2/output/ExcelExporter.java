package com.snets2.output;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Exports simulation results to a multi-tab Excel file.
 */
public class ExcelExporter {

    public void export(SimulationResult result, Path outputPath) throws IOException {
        try (Workbook workbook = new XSSFWorkbook()) {
            for (String sheetName : result.getData().keySet()) {
                Sheet sheet = workbook.createSheet(sheetName);
                Map<String, SimulationResult.MetricRow> rowsMap = result.getData().get(sheetName);
                
                if (rowsMap.isEmpty()) continue;

                // --- 1. Prepare Sorted List of Rows for organization ---
                List<SimulationResult.MetricRow> sortedRows = new ArrayList<>(rowsMap.values());
                // Sort by: SubMetric -> Scenario (Carga) -> Dimensions
                sortedRows.sort((r1, r2) -> {
                    int c = r1.getSubMetric().compareTo(r2.getSubMetric());
                    if (c != 0) return c;
                    c = r1.getScenario().toString().compareTo(r2.getScenario().toString());
                    if (c != 0) return c;
                    return r1.getDimensions().toString().compareTo(r2.getDimensions().toString());
                });

                // --- 2. Prepare Header ---
                Row headerRow = sheet.createRow(0);
                List<String> headerCols = new ArrayList<>();
                headerCols.add("SubMetric");

                SimulationResult.MetricRow sample = sortedRows.get(0);
                List<String> scenarioKeys = new ArrayList<>(sample.getScenario().keySet());
                Collections.sort(scenarioKeys);
                headerCols.addAll(scenarioKeys);

                List<String> dimensionKeys = new ArrayList<>(sample.getDimensions().keySet());
                Collections.sort(dimensionKeys);
                headerCols.addAll(dimensionKeys);

                for (int i = 0; i < result.getTotalReplications(); i++) {
                    headerCols.add("rep" + i);
                }

                for (int i = 0; i < headerCols.size(); i++) {
                    headerRow.createCell(i).setCellValue(headerCols.get(i));
                }

                // --- 3. Fill Data ---
                int rowIndex = 1;
                for (SimulationResult.MetricRow mRow : sortedRows) {
                    Row row = sheet.createRow(rowIndex++);
                    int colIndex = 0;
                    
                    row.createCell(colIndex++).setCellValue(mRow.getSubMetric());

                    for (String key : scenarioKeys) {
                        Object val = mRow.getScenario().get(key);
                        if (val instanceof Number) {
                            row.createCell(colIndex++).setCellValue(((Number) val).doubleValue());
                        } else {
                            row.createCell(colIndex++).setCellValue(String.valueOf(val));
                        }
                    }

                    for (String key : dimensionKeys) {
                        row.createCell(colIndex++).setCellValue(mRow.getDimensions().get(key));
                    }

                    for (int i = 0; i < result.getTotalReplications(); i++) {
                        Double val = mRow.getRepValues().get(i);
                        row.createCell(colIndex++).setCellValue(val != null ? val : 0.0);
                    }
                }
                
                // Auto-size columns for better readability
                for (int i = 0; i < headerCols.size(); i++) {
                    sheet.autoSizeColumn(i);
                }
            }

            try (FileOutputStream fileOut = new FileOutputStream(outputPath.toFile())) {
                workbook.write(fileOut);
            }
        }
    }
}

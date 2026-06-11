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
                    c = compareMaps(r1.getScenario(), r2.getScenario());
                    if (c != 0) return c;
                    return compareMaps(r1.getDimensions(), r2.getDimensions());
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

    private static int compareMaps(Map<String, ?> m1, Map<String, ?> m2) {
        if (m1 == m2) return 0;
        if (m1 == null) return -1;
        if (m2 == null) return 1;

        // Compare by keys first, to ensure consistent ordering
        Set<String> allKeys = new TreeSet<>(m1.keySet());
        allKeys.addAll(m2.keySet());
        
        for (String key : allKeys) {
            Object v1 = m1.get(key);
            Object v2 = m2.get(key);
            
            if (v1 == null && v2 == null) continue;
            if (v1 == null) return -1;
            if (v2 == null) return 1;
            
            int c = compareObjects(v1, v2);
            if (c != 0) return c;
        }
        return 0;
    }

    private static int compareObjects(Object o1, Object o2) {
        if (o1 instanceof Number && o2 instanceof Number) {
            return Double.compare(((Number) o1).doubleValue(), ((Number) o2).doubleValue());
        }
        return compareStringsNaturally(o1.toString(), o2.toString());
    }

    private static int compareStringsNaturally(String s1, String s2) {
        try {
            double d1 = Double.parseDouble(s1);
            double d2 = Double.parseDouble(s2);
            return Double.compare(d1, d2);
        } catch (NumberFormatException e) {
            // Proceed to natural alphanumeric chunk sorting
        }

        int i1 = 0, i2 = 0;
        int len1 = s1.length();
        int len2 = s2.length();
        
        while (i1 < len1 && i2 < len2) {
            char c1 = s1.charAt(i1);
            char c2 = s2.charAt(i2);
            
            if (Character.isDigit(c1) && Character.isDigit(c2)) {
                StringBuilder num1 = new StringBuilder();
                while (i1 < len1 && Character.isDigit(s1.charAt(i1))) {
                    num1.append(s1.charAt(i1));
                    i1++;
                }
                StringBuilder num2 = new StringBuilder();
                while (i2 < len2 && Character.isDigit(s2.charAt(i2))) {
                    num2.append(s2.charAt(i2));
                    i2++;
                }
                
                try {
                    double n1 = Double.parseDouble(num1.toString());
                    double n2 = Double.parseDouble(num2.toString());
                    int cmp = Double.compare(n1, n2);
                    if (cmp != 0) return cmp;
                } catch (NumberFormatException e) {
                    int cmp = num1.toString().compareTo(num2.toString());
                    if (cmp != 0) return cmp;
                }
            } else {
                int cmp = Character.compare(c1, c2);
                if (cmp != 0) return cmp;
                i1++;
                i2++;
            }
        }
        return Integer.compare(len1, len2);
    }
}

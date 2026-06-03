package com.snets2;

import java.io.File;

/**
 * Main entry point for SNetS2.
 * Initializes the Experimental Planner and triggers the simulation battery.
 */
public class MainRunner {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: java MainRunner <experiment_folder_path>");
            return;
        }

        String folderPath = args[0];
        File experimentFolder = new File(folderPath);

        if (!experimentFolder.exists() || !experimentFolder.isDirectory()) {
            System.err.println("Invalid experiment folder: " + experimentFolder.getAbsolutePath());
            return;
        }

        try {
            ExperimentalPlanner planner = new ExperimentalPlanner(experimentFolder);
            planner.run();

        } catch (Exception e) {
            System.err.println("CRITICAL ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

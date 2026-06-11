package com.snets2;

import java.io.File;

/**
 * Main entry point for SNetS2.
 * Initializes the Experimental Planner and triggers the simulation battery.
 */
public class MainRunner {

    public static void main(String[] args) {
        if (args.length == 0) {
            try {
                com.snets2.gui.MainGui.launch();
            } catch (Exception e) {
                System.err.println("Failed to launch GUI: " + e.getMessage());
                e.printStackTrace();
            }
            return;
        }

        if (args.length < 2) {
            System.err.println("Usage (CLI): java MainRunner <experiment_folder_path> <num_threads>");
            System.err.println("Or run without arguments to start the graphical user interface (GUI).");
            return;
        }

        String folderPath = args[0];
        File experimentFolder = new File(folderPath);

        if (!experimentFolder.exists() || !experimentFolder.isDirectory()) {
            System.err.println("Invalid experiment folder: " + experimentFolder.getAbsolutePath());
            return;
        }

        int numThreads;
        try {
            numThreads = Integer.parseInt(args[1]);
            if (numThreads < 1) {
                System.err.println("Number of threads must be at least 1.");
                return;
            }
        } catch (NumberFormatException e) {
            System.err.println("Invalid number of threads: " + args[1]);
            return;
        }

        try {
            ExperimentalPlanner planner = new ExperimentalPlanner(experimentFolder, numThreads);
            planner.run();

        } catch (Exception e) {
            System.err.println("CRITICAL ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }
}

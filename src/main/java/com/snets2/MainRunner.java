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

        if (args.length < 1) {
            System.err.println("Usage (CLI): java MainRunner <experiment_folder_path> [<num_threads>]");
            System.err.println("Or run without arguments to start the graphical user interface (GUI).");
            return;
        }

        String folderPath = args[0];
        File experimentFolder = new File(folderPath);

        if (!experimentFolder.exists() || !experimentFolder.isDirectory()) {
            System.err.println("Invalid experiment folder: " + experimentFolder.getAbsolutePath());
            return;
        }

        int numThreads = 1;
        if (args.length >= 2) {
            try {
                numThreads = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid number of threads: " + args[1]);
                return;
            }
        } else {
            try {
                File setupFile = new File(experimentFolder, "setup.json");
                if (setupFile.exists()) {
                    com.snets2.config.ExperimentSetup setup = com.snets2.config.ConfigLoader.load(setupFile);
                    if (setup.simulation() != null) {
                        numThreads = setup.simulation().threads();
                    }
                }
            } catch (Exception e) {
                System.err.println("Warning: could not read threads count from setup.json. Defaulting to 1.");
            }
        }
        
        if (numThreads < 1) {
            numThreads = 1;
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

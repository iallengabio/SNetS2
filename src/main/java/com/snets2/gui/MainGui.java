package com.snets2.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.snets2.ExperimentalPlanner;
import com.snets2.SimulationConstants;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;

/**
 * Premium Graphical User Interface for SNetS2.
 * Implements a modern dark dashboard layout with sidebar navigation,
 * real-time progress logging, and local configuration.
 */
public class MainGui {
    private JFrame frame;
    private CardLayout cardLayout;
    private JPanel contentCards;

    // Sidebar Buttons
    private SidebarButton homeBtn;
    private SidebarButton experimentsBtn;
    private SidebarButton settingsBtn;
    private SidebarButton aboutBtn;
    private final ArrayList<SidebarButton> sidebarButtons = new ArrayList<>();

    // Home Tab Components
    private JTextField pathField;
    private JButton browseButton;
    private JSpinner threadSpinner;
    private JButton startBtn;
    private JProgressBar progressBar;
    private JLabel progressPercentLabel;
    private JLabel statusLabel;
    private JTextPane logPane;

    // Experiments Tab Panel
    private JPanel experimentsListPanel;

    // Color Palette
    private final Color bgSidebar = new Color(15, 23, 42); // slate-900 (Sidebar)
    private final Color bgMain = new Color(9, 15, 29); // Sleek Navy Dark (Main panel background)
    private final Color bgCard = new Color(20, 27, 45); // Slate Dark Blue (Cards background)
    private final Color accentColor = new Color(79, 70, 229); // Indigo-600 (Primary buttons and active state)
    private final Color textMuted = new Color(148, 163, 184); // Gray-400 (Secondary labels)
    private final Color emeraldGreen = new Color(16, 185, 129); // Emerald-500 (Success indicators & progress)

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                FlatDarkLaf.setup();
                new MainGui().createAndShowGui();
            } catch (Exception e) {
                System.err.println("Error launching dashboard GUI: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void createAndShowGui() {
        frame = new JFrame("SNetS2 Simulator");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(950, 750);
        frame.setMinimumSize(new Dimension(850, 650));
        frame.setLocationRelativeTo(null);

        URL appIconUrl = MainGui.class.getResource("/SNetS2 logo.png");
        if (appIconUrl != null) {
            frame.setIconImage(new ImageIcon(appIconUrl).getImage());
        }

        // Main split container
        JPanel rootPanel = new JPanel(new BorderLayout());
        frame.setContentPane(rootPanel);

        // 1. Sidebar Panel (Left)
        JPanel sidebar = new JPanel(new BorderLayout());
        sidebar.setPreferredSize(new Dimension(200, 750));
        sidebar.setBackground(bgSidebar);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(30, 41, 59)));
        rootPanel.add(sidebar, BorderLayout.WEST);

        // Sidebar Header (Logo)
        JPanel sidebarHeader = new JPanel();
        sidebarHeader.setOpaque(false);
        sidebarHeader.setLayout(new BoxLayout(sidebarHeader, BoxLayout.Y_AXIS));
        sidebarHeader.setBorder(new EmptyBorder(25, 15, 25, 15));

        URL logoUrl = MainGui.class.getResource("/SNetS2 dark.png");
        JLabel sidebarLogoLabel;
        if (logoUrl != null) {
            ImageIcon orig = new ImageIcon(logoUrl);
            Image img = orig.getImage().getScaledInstance(120, -1, Image.SCALE_SMOOTH);
            sidebarLogoLabel = new JLabel(new ImageIcon(img));
        } else {
            sidebarLogoLabel = new JLabel("SNetS2");
            sidebarLogoLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
            sidebarLogoLabel.setForeground(Color.WHITE);
        }
        sidebarLogoLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        sidebarHeader.add(sidebarLogoLabel);

        sidebar.add(sidebarHeader, BorderLayout.NORTH);

        // Sidebar Navigation Buttons
        JPanel navPanel = new JPanel();
        navPanel.setOpaque(false);
        navPanel.setLayout(new BoxLayout(navPanel, BoxLayout.Y_AXIS));
        navPanel.setBorder(new EmptyBorder(10, 12, 10, 12));

        homeBtn = new SidebarButton("Home", new FlatSVGIcon("icons/home.svg", 16, 16));
        experimentsBtn = new SidebarButton("Experiments", new FlatSVGIcon("icons/experiments.svg", 16, 16));
        settingsBtn = new SidebarButton("Settings", new FlatSVGIcon("icons/settings.svg", 16, 16));
        aboutBtn = new SidebarButton("About", new FlatSVGIcon("icons/about.svg", 16, 16));

        sidebarButtons.add(homeBtn);
        sidebarButtons.add(experimentsBtn);
        sidebarButtons.add(settingsBtn);
        sidebarButtons.add(aboutBtn);

        for (SidebarButton btn : sidebarButtons) {
            navPanel.add(btn);
            navPanel.add(Box.createRigidArea(new Dimension(0, 8)));
        }

        homeBtn.setActive(true); // Default active tab
        sidebar.add(navPanel, BorderLayout.CENTER);

        // Sidebar Footer (Version and Dark Mode Indicator)
        JPanel sidebarFooter = new JPanel();
        sidebarFooter.setOpaque(false);
        sidebarFooter.setLayout(new BoxLayout(sidebarFooter, BoxLayout.Y_AXIS));
        sidebarFooter.setBorder(new EmptyBorder(20, 15, 20, 15));

        // JPanel togglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        // togglePanel.setOpaque(false);
        // JLabel darkLabel = new JLabel("🌙 Dark Mode");
        // darkLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        // darkLabel.setForeground(Color.WHITE);
        // JCheckBox darkToggle = new JCheckBox();
        // darkToggle.putClientProperty("JCheckBox.asSwitch", true);
        // darkToggle.setSelected(true);
        // darkToggle.setEnabled(false); // Default dark theme active
        // togglePanel.add(darkLabel);
        // togglePanel.add(darkToggle);

        // sidebarFooter.add(togglePanel);
        sidebarFooter.add(Box.createRigidArea(new Dimension(0, 15)));

        JLabel footerTitle = new JLabel("SNetS2 Simulator");
        footerTitle.setFont(new Font("SansSerif", Font.BOLD, 12));
        footerTitle.setForeground(Color.WHITE);

        JLabel footerVersion = new JLabel("v2.0.0");
        footerVersion.setFont(new Font("SansSerif", Font.PLAIN, 11));
        footerVersion.setForeground(textMuted);

        sidebarFooter.add(footerTitle);
        sidebarFooter.add(footerVersion);
        sidebar.add(sidebarFooter, BorderLayout.SOUTH);

        // 2. Main Content Card Panel (Right)
        cardLayout = new CardLayout();
        contentCards = new JPanel(cardLayout);
        contentCards.setBackground(bgMain);
        rootPanel.add(contentCards, BorderLayout.CENTER);

        // Create individual tabs
        JPanel homeTab = createHomeTab();
        JPanel experimentsTab = createExperimentsTab();
        JPanel settingsTab = createSettingsTab();
        JPanel aboutTab = createAboutTab();

        contentCards.add(homeTab, "Home");
        contentCards.add(experimentsTab, "Experiments");
        contentCards.add(settingsTab, "Settings");
        contentCards.add(aboutTab, "About");

        // Hook up Sidebar Navigation
        homeBtn.addActionListener(e -> switchTab(homeBtn, "Home"));
        experimentsBtn.addActionListener(e -> {
            loadExperimentsList();
            switchTab(experimentsBtn, "Experiments");
        });
        settingsBtn.addActionListener(e -> switchTab(settingsBtn, "Settings"));
        aboutBtn.addActionListener(e -> switchTab(aboutBtn, "About"));

        // Redirect standard streams to GUI Console
        redirectSystemStreams();

        frame.setVisible(true);
    }

    private void switchTab(SidebarButton activeBtn, String cardName) {
        for (SidebarButton btn : sidebarButtons) {
            btn.setActive(btn == activeBtn);
        }
        cardLayout.show(contentCards, cardName);
    }

    // ==========================================
    // TAB CREATION LOGIC
    // ==========================================

    private JPanel createHomeTab() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(bgMain);
        panel.setBorder(new EmptyBorder(25, 25, 25, 25));

        // Header Panel (Welcome text & Mini-logo)
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setOpaque(false);

        JPanel textPanel = new JPanel();
        textPanel.setOpaque(false);
        textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));

        JLabel titleLabel = new JLabel("Welcome to SNetS2 Simulator");
        titleLabel.setFont(new Font("SansSerif", Font.BOLD, 22));
        titleLabel.setForeground(Color.WHITE);

        JLabel subtitleLabel = new JLabel("Configure your experiment and start a simulation sweep.");
        subtitleLabel.setFont(new Font("SansSerif", Font.PLAIN, 13));
        subtitleLabel.setForeground(textMuted);

        textPanel.add(titleLabel);
        textPanel.add(Box.createRigidArea(new Dimension(0, 5)));
        textPanel.add(subtitleLabel);
        headerPanel.add(textPanel, BorderLayout.WEST);

        // Header Mini Logo (top right)
        URL logoUrl = MainGui.class.getResource("/SNetS2 dark.png");
        if (logoUrl != null) {
            ImageIcon originalIcon = new ImageIcon(logoUrl);
            Image scaledImage = originalIcon.getImage().getScaledInstance(90, -1, Image.SCALE_SMOOTH);
            JLabel miniLogo = new JLabel(new ImageIcon(scaledImage));
            headerPanel.add(miniLogo, BorderLayout.EAST);
        }
        panel.add(headerPanel, BorderLayout.NORTH);

        // Central Panel wrapping forms, progress, and logs
        JPanel centerGrid = new JPanel();
        centerGrid.setOpaque(false);
        centerGrid.setLayout(new BoxLayout(centerGrid, BoxLayout.Y_AXIS));
        panel.add(centerGrid, BorderLayout.CENTER);

        // A. Card 1: Simulation Configuration
        CardPanel configCard = new CardPanel();
        configCard.setLayout(new BorderLayout());
        configCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        JLabel cardTitle = new JLabel("Simulation Configuration");
        cardTitle.setIcon(createIcon("icons/settings.svg", 16, 16));
        cardTitle.setIconTextGap(8);
        cardTitle.setFont(new Font("SansSerif", Font.BOLD, 14));
        cardTitle.setForeground(Color.WHITE);
        configCard.add(cardTitle, BorderLayout.NORTH);

        // Form Fields Panel
        JPanel fieldsPanel = new JPanel(new GridBagLayout());
        fieldsPanel.setOpaque(false);
        fieldsPanel.setBorder(new EmptyBorder(15, 0, 15, 0));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(8, 0, 8, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: Experiment Folder
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        JLabel folderLabel = new JLabel("Experiment Folder");
        folderLabel.setIcon(createIcon("icons/folder.svg", 16, 16));
        folderLabel.setIconTextGap(8);
        folderLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        folderLabel.setForeground(Color.WHITE);
        fieldsPanel.add(folderLabel, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1.0;
        pathField = new JTextField();
        pathField.setBackground(bgSidebar);
        pathField.setForeground(Color.WHITE);
        pathField.setCaretColor(Color.WHITE);
        pathField.putClientProperty("JTextField.placeholderText", "Click browse or enter path...");
        fieldsPanel.add(pathField, gbc);

        gbc.gridx = 2;
        gbc.weightx = 0.0;
        browseButton = new JButton("Browse...");
        browseButton.setBackground(accentColor);
        browseButton.setForeground(Color.WHITE);
        browseButton.setFont(new Font("SansSerif", Font.BOLD, 12));
        browseButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        browseButton.addActionListener(e -> chooseFolder());
        fieldsPanel.add(browseButton, gbc);

        // Row 1: Threads Count
        gbc.gridx = 0;
        gbc.gridy = 1;
        JLabel cpuLabel = new JLabel("Threads Count");
        cpuLabel.setIcon(createIcon("icons/threads.svg", 16, 16));
        cpuLabel.setIconTextGap(8);
        cpuLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        cpuLabel.setForeground(Color.WHITE);
        fieldsPanel.add(cpuLabel, gbc);

        gbc.gridx = 1;
        gbc.gridwidth = 2;
        int activeCpus = Runtime.getRuntime().availableProcessors();
        threadSpinner = new JSpinner(new SpinnerNumberModel(Math.max(1, activeCpus - 1), 1, activeCpus * 2, 1));
        fieldsPanel.add(threadSpinner, gbc);
        configCard.add(fieldsPanel, BorderLayout.CENTER);

        // Start Sweep Action Button
        startBtn = new JButton("▶  Start Simulation Sweep");
        startBtn.setFont(new Font("SansSerif", Font.BOLD, 14));
        startBtn.setBackground(accentColor);
        startBtn.setForeground(Color.WHITE);
        startBtn.setPreferredSize(new Dimension(0, 42));
        startBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        startBtn.addActionListener(e -> startSweep());
        configCard.add(startBtn, BorderLayout.SOUTH);

        centerGrid.add(configCard);
        centerGrid.add(Box.createRigidArea(new Dimension(0, 15)));

        // B. Card 2: Progress Card
        CardPanel progressCard = new CardPanel();
        progressCard.setLayout(new BorderLayout(8, 8));
        progressCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel progHeader = new JPanel(new BorderLayout());
        progHeader.setOpaque(false);

        JLabel progTitle = new JLabel("Progress");
        progTitle.setIcon(createIcon("icons/progress.svg", 16, 16));
        progTitle.setIconTextGap(8);
        progTitle.setFont(new Font("SansSerif", Font.BOLD, 14));
        progTitle.setForeground(Color.WHITE);
        progHeader.add(progTitle, BorderLayout.WEST);

        progressPercentLabel = new JLabel("0.00%");
        progressPercentLabel.setFont(new Font("SansSerif", Font.BOLD, 14));
        progressPercentLabel.setForeground(emeraldGreen);
        progHeader.add(progressPercentLabel, BorderLayout.EAST);
        progressCard.add(progHeader, BorderLayout.NORTH);

        // Progress Bar
        progressBar = new JProgressBar(0, 100);
        progressBar.setForeground(emeraldGreen);
        progressBar.setBackground(new Color(30, 41, 59));
        progressBar.putClientProperty("JProgressBar.largeHeight", true);
        progressCard.add(progressBar, BorderLayout.CENTER);

        // Status Label
        statusLabel = new JLabel("Ready to start simulation sweep.");
        statusLabel.setFont(new Font("SansSerif", Font.ITALIC, 12));
        statusLabel.setForeground(textMuted);
        progressCard.add(statusLabel, BorderLayout.SOUTH);

        centerGrid.add(progressCard);
        centerGrid.add(Box.createRigidArea(new Dimension(0, 15)));

        // C. Card 3: Console Log Card
        CardPanel logCard = new CardPanel();
        logCard.setLayout(new BorderLayout());
        logCard.setBorder(new EmptyBorder(16, 16, 16, 16));

        JPanel logHeader = new JPanel(new BorderLayout());
        logHeader.setOpaque(false);

        JLabel logTitle = new JLabel("Console Log");
        logTitle.setIcon(createIcon("icons/console.svg", 16, 16));
        logTitle.setIconTextGap(8);
        logTitle.setFont(new Font("SansSerif", Font.BOLD, 14));
        logTitle.setForeground(Color.WHITE);
        logHeader.add(logTitle, BorderLayout.WEST);

        JButton clearBtn = new JButton("🗑  Clear Log");
        clearBtn.setBackground(new Color(30, 41, 59));
        clearBtn.setForeground(Color.WHITE);
        clearBtn.setFont(new Font("SansSerif", Font.BOLD, 11));
        clearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        clearBtn.addActionListener(e -> logPane.setText(""));
        logHeader.add(clearBtn, BorderLayout.EAST);
        logCard.add(logHeader, BorderLayout.NORTH);

        // Styled JTextPane log area
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(11, 16, 28)); // Slate console color
        logPane.setForeground(new Color(209, 213, 219));
        logPane.setFont(new Font("Monospaced", Font.PLAIN, 12));

        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logScroll.setBorder(BorderFactory.createLineBorder(new Color(30, 41, 59), 1));

        JPanel logScrollWrapper = new JPanel(new BorderLayout());
        logScrollWrapper.setOpaque(false);
        logScrollWrapper.setBorder(new EmptyBorder(12, 0, 0, 0));
        logScrollWrapper.add(logScroll, BorderLayout.CENTER);
        logCard.add(logScrollWrapper, BorderLayout.CENTER);

        centerGrid.add(logCard);

        return panel;
    }

    private JPanel createExperimentsTab() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(bgMain);
        panel.setBorder(new EmptyBorder(25, 25, 25, 25));

        JLabel title = new JLabel("Available Experiments");
        title.setIcon(createIcon("icons/experiments.svg", 18, 18));
        title.setIconTextGap(8);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        panel.add(title, BorderLayout.NORTH);

        // List container
        experimentsListPanel = new JPanel();
        experimentsListPanel.setOpaque(false);
        experimentsListPanel.setLayout(new BoxLayout(experimentsListPanel, BoxLayout.Y_AXIS));

        JScrollPane scroll = new JScrollPane(experimentsListPanel);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
    }

    private void loadExperimentsList() {
        experimentsListPanel.removeAll();

        File expDir = new File("experiments");
        if (expDir.exists() && expDir.isDirectory()) {
            File[] subdirs = expDir.listFiles(File::isDirectory);
            if (subdirs != null && subdirs.length > 0) {
                for (File subdir : subdirs) {
                    CardPanel card = new CardPanel();
                    card.setLayout(new BorderLayout());
                    card.setBorder(new EmptyBorder(12, 16, 12, 16));
                    card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 70));

                    JLabel folderLabel = new JLabel(subdir.getName());
                    folderLabel.setIcon(createIcon("icons/folder.svg", 16, 16));
                    folderLabel.setIconTextGap(8);
                    folderLabel.setFont(new Font("SansSerif", Font.BOLD, 13));
                    folderLabel.setForeground(Color.WHITE);
                    card.add(folderLabel, BorderLayout.WEST);

                    JButton loadBtn = new JButton("Load config");
                    loadBtn.setBackground(accentColor);
                    loadBtn.setForeground(Color.WHITE);
                    loadBtn.setFont(new Font("SansSerif", Font.BOLD, 12));
                    loadBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

                    loadBtn.addActionListener(e -> {
                        pathField.setText(subdir.getAbsolutePath());
                        switchTab(homeBtn, "Home");
                    });

                    card.add(loadBtn, BorderLayout.EAST);

                    experimentsListPanel.add(card);
                    experimentsListPanel.add(Box.createRigidArea(new Dimension(0, 10)));
                }
            } else {
                JLabel emptyLabel = new JLabel("No experiment directories found in 'experiments/'");
                emptyLabel.setForeground(textMuted);
                emptyLabel.setFont(new Font("SansSerif", Font.ITALIC, 13));
                experimentsListPanel.add(emptyLabel);
            }
        } else {
            JLabel errLabel = new JLabel("The 'experiments/' directory does not exist.");
            errLabel.setForeground(textMuted);
            errLabel.setFont(new Font("SansSerif", Font.ITALIC, 13));
            experimentsListPanel.add(errLabel);
        }

        experimentsListPanel.revalidate();
        experimentsListPanel.repaint();
    }

    private JPanel createSettingsTab() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(bgMain);
        panel.setBorder(new EmptyBorder(25, 25, 25, 25));

        JLabel title = new JLabel("Simulation Settings");
        title.setIcon(createIcon("icons/settings.svg", 18, 18));
        title.setIconTextGap(8);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        panel.add(title, BorderLayout.NORTH);

        CardPanel card = new CardPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(20, 20, 20, 20));

        JCheckBox debugBox = new JCheckBox("Enable Detailed Console Debug Logging (SimulationConstants.debugEnabled)");
        debugBox.setFont(new Font("SansSerif", Font.PLAIN, 13));
        debugBox.setForeground(Color.WHITE);
        debugBox.setSelected(SimulationConstants.debugEnabled);
        debugBox.addActionListener(e -> SimulationConstants.debugEnabled = debugBox.isSelected());

        JCheckBox validationBox = new JCheckBox(
                "Enable Strict State Validation (SimulationConstants.strictValidationEnabled)");
        validationBox.setFont(new Font("SansSerif", Font.PLAIN, 13));
        validationBox.setForeground(Color.WHITE);
        validationBox.setSelected(SimulationConstants.strictValidationEnabled);
        validationBox.addActionListener(e -> SimulationConstants.strictValidationEnabled = validationBox.isSelected());

        card.add(debugBox);
        card.add(Box.createRigidArea(new Dimension(0, 15)));
        card.add(validationBox);

        panel.add(card, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createAboutTab() {
        JPanel panel = new JPanel(new BorderLayout(15, 15));
        panel.setBackground(bgMain);
        panel.setBorder(new EmptyBorder(25, 25, 25, 25));

        JLabel title = new JLabel("About SNetS2");
        title.setIcon(createIcon("icons/about.svg", 18, 18));
        title.setIconTextGap(8);
        title.setFont(new Font("SansSerif", Font.BOLD, 20));
        title.setForeground(Color.WHITE);
        panel.add(title, BorderLayout.NORTH);

        CardPanel card = new CardPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(25, 25, 25, 25));

        URL logoUrl = MainGui.class.getResource("/SNetS2 logo.png");
        if (logoUrl != null) {
            ImageIcon orig = new ImageIcon(logoUrl);
            Image img = orig.getImage().getScaledInstance(200, -1, Image.SCALE_SMOOTH);
            JLabel logo = new JLabel(new ImageIcon(img));
            logo.setAlignmentX(Component.CENTER_ALIGNMENT);
            card.add(logo);
            card.add(Box.createRigidArea(new Dimension(0, 20)));
        }

        JLabel aboutTitle = new JLabel("SNetS2 Simulator");
        aboutTitle.setFont(new Font("SansSerif", Font.BOLD, 18));
        aboutTitle.setForeground(Color.WHITE);
        aboutTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(aboutTitle);

        card.add(Box.createRigidArea(new Dimension(0, 10)));

        JTextArea desc = new JTextArea(
                "Discrete Event Simulator (DES) for Multi-Core Elastic Optical Networks (MC-EON) " +
                        "supporting Spatial Division Multiplexing (SDM) modeling.\n\n" +
                        "Includes multithreaded replication sweeps, state recovery/checkpointing, " +
                        "and standard routing, core, and spectrum assignment (RMSCA) heuristics.");
        desc.setFont(new Font("SansSerif", Font.PLAIN, 13));
        desc.setForeground(textMuted);
        desc.setEditable(false);
        desc.setOpaque(false);
        desc.setLineWrap(true);
        desc.setWrapStyleWord(true);
        desc.setMaximumSize(new Dimension(500, 200));
        desc.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(desc);

        panel.add(card, BorderLayout.CENTER);

        return panel;
    }

    // ==========================================
    // ACTIONS & UTILITIES
    // ==========================================

    private void chooseFolder() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Select Experiment Directory");
        chooser.setCurrentDirectory(new File(System.getProperty("user.dir")));

        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            pathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private void startSweep() {
        String path = pathField.getText().trim();
        if (path.isEmpty()) {
            JOptionPane.showMessageDialog(frame, "Please specify an experiment directory first.", "Warning",
                    JOptionPane.WARNING_MESSAGE);
            return;
        }

        File folder = new File(path);
        if (!folder.exists() || !folder.isDirectory()) {
            JOptionPane.showMessageDialog(frame, "The selected path is not a valid directory.", "Error",
                    JOptionPane.ERROR_MESSAGE);
            return;
        }

        int threads = (int) threadSpinner.getValue();

        // Lock GUI Controls
        startBtn.setEnabled(false);
        browseButton.setEnabled(false);
        pathField.setEnabled(false);
        threadSpinner.setEnabled(false);
        logPane.setText("");
        progressBar.setValue(0);
        progressPercentLabel.setText("0.00%");
        statusLabel.setText("Initializing simulation planning sweep...");

        new Thread(() -> {
            try {
                ExperimentalPlanner planner = new ExperimentalPlanner(folder, threads);
                planner.setProgressListener((percent, completed, total) -> {
                    SwingUtilities.invokeLater(() -> {
                        progressBar.setValue((int) percent);
                        progressPercentLabel.setText(String.format("%.2f%%", percent));
                        statusLabel.setText(String.format("Running replication %d of %d...", completed, total));
                    });
                });

                planner.run();

                SwingUtilities.invokeLater(() -> {
                    progressBar.setValue(100);
                    progressPercentLabel.setText("100.00%");
                    statusLabel.setText("✔  Completed successfully! Results saved to results.xlsx.");
                    JOptionPane.showMessageDialog(frame,
                            "Simulation sweep completed successfully!\nResults saved to results.xlsx.", "Success",
                            JOptionPane.INFORMATION_MESSAGE);
                    unlockControls();
                });
            } catch (Throwable t) {
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("❌  Simulation execution failed.");
                    JOptionPane.showMessageDialog(frame, "Simulation failed: " + t.getMessage(), "Error",
                            JOptionPane.ERROR_MESSAGE);
                    unlockControls();
                });
                t.printStackTrace();
            }
        }).start();
    }

    private void unlockControls() {
        startBtn.setEnabled(true);
        browseButton.setEnabled(true);
        pathField.setEnabled(true);
        threadSpinner.setEnabled(true);
    }

    private void redirectSystemStreams() {
        OutputStream out = new LogConsoleStream(logPane);
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(out, true));
    }

    // ==========================================
    // CUSTOM COMPONENT SUBCLASSES
    // ==========================================

    /**
     * Rounded container card with borders conforming to dashboard aesthetics.
     */
    public static class CardPanel extends JPanel {
        private static final long serialVersionUID = 1L;
        private final int radius = 12;
        private final Color borderCol = new Color(30, 41, 59);

        public CardPanel() {
            setOpaque(false);
            setBackground(new Color(20, 27, 45)); // Card color
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // Draw background Rounded Rect
            g2.setColor(getBackground());
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);

            // Draw border
            g2.setColor(borderCol);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
        }
    }

    /**
     * Dashboard styled sidebar navigation button.
     */
    public static class SidebarButton extends JButton {
        private static final long serialVersionUID = 1L;
        private boolean active = false;
        private final Color activeBg = new Color(79, 70, 229); // Indigo background
        private final Color hoverBg = new Color(30, 41, 59);
        private final Color normalText = new Color(148, 163, 184);

        public SidebarButton(String text, Icon icon) {
            super(text, icon);
            setIconTextGap(12);
            if (icon instanceof FlatSVGIcon) {
                ((FlatSVGIcon) icon).setColorFilter(new FlatSVGIcon.ColorFilter(color -> getForeground()));
            }
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setForeground(normalText);
            setFont(new Font("SansSerif", Font.BOLD, 13));
            setHorizontalAlignment(SwingConstants.LEFT);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(12, 16, 12, 16));
            setMaximumSize(new Dimension(176, 40));
            setPreferredSize(new Dimension(176, 40));

            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent evt) {
                    if (!active) {
                        setBackground(hoverBg);
                        setOpaque(true);
                        setForeground(Color.WHITE);
                    }
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent evt) {
                    if (!active) {
                        setOpaque(false);
                        setForeground(normalText);
                    }
                }
            });
        }

        public void setActive(boolean active) {
            this.active = active;
            if (active) {
                setOpaque(true);
                setBackground(activeBg);
                setForeground(Color.WHITE);
            } else {
                setOpaque(false);
                setForeground(normalText);
            }
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (active || isOpaque()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /**
     * Intercepts console logs and formats them to the dashboard log view.
     * Prepends timestamps in emerald green and formats lines.
     */
    public static class LogConsoleStream extends OutputStream {
        private final JTextPane textPane;
        private final StringBuilder lineBuffer = new StringBuilder();
        private final Color timeColor = new Color(52, 211, 153);
        private final Color textColor = new Color(209, 213, 219);
        private final Color successColor = new Color(52, 211, 153);
        private final Color errorColor = new Color(239, 68, 68);

        public LogConsoleStream(JTextPane textPane) {
            this.textPane = textPane;
        }

        @Override
        public void write(int b) throws IOException {
            if (b == '\n') {
                flushLine();
            } else if (b != '\r') {
                lineBuffer.append((char) b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) {
                byte c = b[off + i];
                if (c == '\n') {
                    flushLine();
                } else if (c != '\r') {
                    lineBuffer.append((char) c);
                }
            }
        }

        private synchronized void flushLine() {
            String line = lineBuffer.toString();
            lineBuffer.setLength(0);

            String timeStr = DateTimeFormatter.ofPattern("HH:mm:ss").format(LocalTime.now());

            SwingUtilities.invokeLater(() -> {
                try {
                    StyledDocument doc = textPane.getStyledDocument();

                    // 1. Timestamp in green
                    SimpleAttributeSet timeAttrs = new SimpleAttributeSet();
                    StyleConstants.setForeground(timeAttrs, timeColor);
                    doc.insertString(doc.getLength(), "[" + timeStr + "]  ", timeAttrs);

                    // 2. Log body formatted by keyword status
                    SimpleAttributeSet textAttrs = new SimpleAttributeSet();
                    if (line.contains("completed successfully") || line.contains("SUCCESS") || line.startsWith("✔")) {
                        StyleConstants.setForeground(textAttrs, successColor);
                    } else if (line.contains("ERROR") || line.contains("critical") || line.contains("failed")
                            || line.startsWith("❌")) {
                        StyleConstants.setForeground(textAttrs, errorColor);
                    } else {
                        StyleConstants.setForeground(textAttrs, textColor);
                    }
                    doc.insertString(doc.getLength(), line + "\n", textAttrs);

                    // Limit log pane history size (e.g. 5000 lines max) to prevent memory leak
                    if (doc.getDefaultRootElement().getElementCount() > 5000) {
                        int endOffset = doc.getDefaultRootElement().getElement(200).getEndOffset();
                        doc.remove(0, endOffset);
                    }

                    // Auto scroll log to bottom
                    textPane.setCaretPosition(doc.getLength());
                } catch (Exception e) {
                    // Fail silently
                }
            });
        }
    }

    private FlatSVGIcon createIcon(String path, int width, int height) {
        FlatSVGIcon icon = new FlatSVGIcon(path, width, height);
        icon.setColorFilter(new FlatSVGIcon.ColorFilter(color -> Color.WHITE));
        return icon;
    }
}

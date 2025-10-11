package com.illumine.barb3tickfishing;

import com.tonic.model.ui.components.FancyButton;
import com.tonic.model.ui.components.FancyCard;
import com.tonic.model.ui.components.FancyDropdown;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;

import javax.inject.Inject;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.EnumMap;
import java.util.Map;

public class Barb3TickFishingSidePanel extends PluginPanel
{
    private static final int PANEL_PADDING = 10;

    private final Barb3TickFishingConfig config;

    private Barb3TickFishingPlugin plugin;

    private final FancyDropdown<ThreeTickFrequencyMode> frequencyDropdown;
    private final JTextField herbNameField;
    private final JCheckBox fallbackCheckbox;
    private final JCheckBox worldHopCheckbox;
    private final JSpinner hopIntervalSpinner;
    private final FancyButton startStopButton;

    private final JLabel runtimeLabel;
    private final Map<StatusField, JLabel> statusLabels = new EnumMap<>(StatusField.class);

    private final Timer runtimeTimer;
    private boolean running = false;
    private long startTimeMs = 0L;
    private boolean suppressFrequencyEvents = false;

    @Inject
    public Barb3TickFishingSidePanel(Barb3TickFishingConfig config)
    {
        this.config = config;

        setBackground(ColorScheme.DARK_GRAY_COLOR);
        setBorder(BorderFactory.createEmptyBorder(PANEL_PADDING, PANEL_PADDING, PANEL_PADDING, PANEL_PADDING));
        setLayout(new BorderLayout());

        JPanel content = new JPanel();
        content.setBackground(ColorScheme.DARK_GRAY_COLOR);
        content.setLayout(new GridBagLayout());

        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = 0;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 8, 0);

        FancyCard card = new FancyCard(
                "illu 3Tick Barb Fishing",
                "Vitalite port of illumine's Powbot barbarian fishing script."
        );
        content.add(card, c);
        c.gridy++;

        runtimeLabel = createRuntimeLabel();
        content.add(buildRuntimePanel(), c);
        c.gridy++;

        frequencyDropdown = new FancyDropdown<>("3T Frequency Mode", ThreeTickFrequencyMode.class);
        frequencyDropdown.setSelectedItem(config.frequencyMode());
        frequencyDropdown.addSelectionListener(e -> handleFrequencyChange());
        content.add(frequencyDropdown, c);
        c.gridy++;

        herbNameField = createHerbField();
        content.add(herbNameField, c);
        c.gridy++;

        fallbackCheckbox = new JCheckBox("Fallback to normal when supplies run out", config.fallbackToNormal());
        styleCheckBox(fallbackCheckbox);
        fallbackCheckbox.addActionListener(e -> handleFallbackToggle());
        content.add(fallbackCheckbox, c);
        c.gridy++;

        worldHopCheckbox = new JCheckBox("Allow world hopping", config.allowWorldHop());
        styleCheckBox(worldHopCheckbox);
        worldHopCheckbox.addActionListener(e -> handleWorldHopToggle());
        content.add(worldHopCheckbox, c);
        c.gridy++;

        hopIntervalSpinner = createHopIntervalSpinner();
        JPanel hopPanel = new JPanel(new BorderLayout());
        hopPanel.setOpaque(false);
        JLabel hopLabel = new JLabel("World hop interval (minutes)");
        hopLabel.setForeground(Color.WHITE);
        hopPanel.add(hopLabel, BorderLayout.NORTH);
        hopPanel.add(hopIntervalSpinner, BorderLayout.CENTER);
        content.add(hopPanel, c);
        c.gridy++;

        startStopButton = new FancyButton("Start");
        startStopButton.setAlignmentX(Component.CENTER_ALIGNMENT);
        startStopButton.addActionListener(e -> toggleRunningState());
        content.add(startStopButton, c);
        c.gridy++;

        JPanel statusPanel = buildStatusPanel();
        content.add(statusPanel, c);
        c.gridy++;

        c.weighty = 1;
        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        content.add(spacer, c);

        JScrollPane scrollPane = new JScrollPane(content);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.getViewport().setBackground(ColorScheme.DARK_GRAY_COLOR);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        add(scrollPane, BorderLayout.CENTER);

        runtimeTimer = new Timer(250, e -> updateRuntime());
        setWorldHopControlsState(worldHopCheckbox.isSelected());
        updateFrequencyVisibility(config.frequencyMode());
    }

    void attachPlugin(Barb3TickFishingPlugin plugin)
    {
        this.plugin = plugin;
    }

    public boolean isRunning()
    {
        return running;
    }

    public void setRunning(boolean running)
    {
        if (this.running == running)
        {
            return;
        }
        this.running = running;
        if (running)
        {
            startTimeMs = System.currentTimeMillis();
            runtimeTimer.start();
            startStopButton.setText("Stop");
        }
        else
        {
            runtimeTimer.stop();
            startStopButton.setText("Start");
        }
    }

    public void updateStatus(StatusSnapshot snapshot)
    {
        if (snapshot == null)
        {
            return;
        }
        SwingUtilities.invokeLater(() -> {
            setStatus(StatusField.MODE, snapshot.mode());
            setStatus(StatusField.FREQUENCY, snapshot.frequency());
            setStatus(StatusField.THREE_T_SHARE, snapshot.share3Tick());
            setStatus(StatusField.NEXT_SWITCH, snapshot.nextSwitch());
            setStatus(StatusField.WORLD, snapshot.world());
            setStatus(StatusField.NEXT_HOP, snapshot.nextHop());
            setStatus(StatusField.SUPPLIES, snapshot.supplies());
        });
    }

    public void shutdown()
    {
        runtimeTimer.stop();
    }

    String getHerbNameInput()
    {
        return herbNameField.getText().trim();
    }

    ThreeTickFrequencyMode getSelectedFrequencyMode()
    {
        return frequencyDropdown.getSelectedItem();
    }

    boolean isFallbackEnabled()
    {
        return fallbackCheckbox.isSelected();
    }

    boolean isWorldHopEnabled()
    {
        return worldHopCheckbox.isSelected();
    }

    int getWorldHopInterval()
    {
        return ((Number) hopIntervalSpinner.getValue()).intValue();
    }

    void setStatus(StatusField field, String value)
    {
        JLabel label = statusLabels.get(field);
        if (label != null)
        {
            label.setText(value == null || value.isBlank() ? "—" : value);
        }
    }

    private JLabel createRuntimeLabel()
    {
        JLabel label = new JLabel("00:00:00", JLabel.CENTER);
        label.setFont(new Font(Font.MONOSPACED, Font.BOLD, 24));
        label.setForeground(new Color(0, 255, 0));
        label.setAlignmentX(Component.CENTER_ALIGNMENT);
        return label;
    }

    private JPanel buildRuntimePanel()
    {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.setBorder(BorderFactory.createEmptyBorder(12, 8, 12, 8));
        panel.add(runtimeLabel, BorderLayout.CENTER);
        return panel;
    }

    private JTextField createHerbField()
    {
        JTextField field = new JTextField(config.herbName());
        field.setMaximumSize(new Dimension(PluginPanel.PANEL_WIDTH - 40, 28));
        field.addActionListener(e -> handleHerbChanged());
        field.addFocusListener(new java.awt.event.FocusAdapter()
        {
            @Override
            public void focusLost(java.awt.event.FocusEvent e)
            {
                handleHerbChanged();
            }
        });
        return field;
    }

    private JSpinner createHopIntervalSpinner()
    {
        int value = Math.max(1, config.worldHopIntervalMinutes());
        SpinnerNumberModel model = new SpinnerNumberModel(value, 1, 60, 1);
        JSpinner spinner = new JSpinner(model);
        spinner.addChangeListener(e -> handleHopIntervalChanged());
        return spinner;
    }

    private void handleFrequencyChange()
    {
        if (suppressFrequencyEvents)
        {
            return;
        }
        ThreeTickFrequencyMode selected = frequencyDropdown.getSelectedItem();
        if (selected == null)
        {
            selected = ThreeTickFrequencyMode.SOMETIMES;
        }
        config.setFrequencyMode(selected);
        updateFrequencyVisibility(selected);
        if (plugin != null)
        {
            plugin.onFrequencyModeChanged(selected);
        }
    }

    void setSelectedFrequencyMode(ThreeTickFrequencyMode mode)
    {
        suppressFrequencyEvents = true;
        try
        {
            frequencyDropdown.setSelectedItem(mode);
            updateFrequencyVisibility(mode);
        }
        finally
        {
            suppressFrequencyEvents = false;
        }
    }

    private void handleHerbChanged()
    {
        String herb = herbNameField.getText().trim();
        config.setHerbName(herb);
        if (plugin != null)
        {
            plugin.onHerbNameChanged(herb);
        }
    }

    private void handleFallbackToggle()
    {
        boolean enabled = fallbackCheckbox.isSelected();
        config.setFallbackToNormal(enabled);
        if (plugin != null)
        {
            plugin.onFallbackChanged(enabled);
        }
    }

    private void handleWorldHopToggle()
    {
        boolean enabled = worldHopCheckbox.isSelected();
        config.setAllowWorldHop(enabled);
        setWorldHopControlsState(enabled);
        if (plugin != null)
        {
            plugin.onWorldHopToggle(enabled);
        }
    }

    private void handleHopIntervalChanged()
    {
        int minutes = ((Number) hopIntervalSpinner.getValue()).intValue();
        config.setWorldHopIntervalMinutes(minutes);
        if (plugin != null)
        {
            plugin.onHopIntervalChanged(minutes);
        }
    }

    private void toggleRunningState()
    {
        if (plugin == null)
        {
            return;
        }
        if (!running)
        {
            plugin.onStartRequested();
        }
        else
        {
            plugin.onStopRequested();
        }
    }

    private void updateFrequencyVisibility(ThreeTickFrequencyMode mode)
    {
        boolean enable3TickOptions = mode != ThreeTickFrequencyMode.NEVER;
        herbNameField.setEnabled(enable3TickOptions);
        herbNameField.setForeground(enable3TickOptions ? Color.WHITE : Color.GRAY);
        fallbackCheckbox.setEnabled(enable3TickOptions);
    }

    private void setWorldHopControlsState(boolean enabled)
    {
        hopIntervalSpinner.setEnabled(enabled);
    }

    private void styleCheckBox(JCheckBox checkBox)
    {
        checkBox.setForeground(Color.WHITE);
        checkBox.setOpaque(false);
    }

    private void updateRuntime()
    {
        if (!running)
        {
            return;
        }
        long elapsed = System.currentTimeMillis() - startTimeMs;
        runtimeLabel.setText(formatDuration(elapsed));
    }

    private static String formatDuration(long millis)
    {
        long totalSeconds = Math.max(0L, millis / 1000L);
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    private JPanel buildStatusPanel()
    {
        JPanel panel = new JPanel();
        panel.setOpaque(false);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.DARK_GRAY), "Status"));

        for (StatusField field : StatusField.values())
        {
            panel.add(createStatusRow(field));
            panel.add(Box.createVerticalStrut(4));
        }
        return panel;
    }

    private JPanel createStatusRow(StatusField field)
    {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);

        JLabel label = new JLabel(field.displayName());
        label.setForeground(Color.LIGHT_GRAY);
        row.add(label, BorderLayout.WEST);

        JLabel value = new JLabel("—");
        value.setForeground(Color.WHITE);
        value.setHorizontalAlignment(JLabel.RIGHT);
        row.add(value, BorderLayout.EAST);

        statusLabels.put(field, value);
        return row;
    }

    public enum StatusField
    {
        MODE("Mode"),
        FREQUENCY("3T Frequency"),
        THREE_T_SHARE("3T Share"),
        NEXT_SWITCH("Next Switch"),
        WORLD("World"),
        NEXT_HOP("Next Hop"),
        SUPPLIES("Supplies");

        private final String displayName;

        StatusField(String displayName)
        {
            this.displayName = displayName;
        }

        public String displayName()
        {
            return displayName;
        }
    }

    public static class StatusSnapshot
    {
        private final String mode;
        private final String frequency;
        private final String share3Tick;
        private final String nextSwitch;
        private final String world;
        private final String nextHop;
        private final String supplies;

        public StatusSnapshot(String mode, String frequency, String share3Tick, String nextSwitch, String world, String nextHop, String supplies)
        {
            this.mode = mode;
            this.frequency = frequency;
            this.share3Tick = share3Tick;
            this.nextSwitch = nextSwitch;
            this.world = world;
            this.nextHop = nextHop;
            this.supplies = supplies;
        }

        public String mode()
        {
            return mode;
        }

        public String frequency()
        {
            return frequency;
        }

        public String share3Tick()
        {
            return share3Tick;
        }

        public String nextSwitch()
        {
            return nextSwitch;
        }

        public String world()
        {
            return world;
        }

        public String nextHop()
        {
            return nextHop;
        }

        public String supplies()
        {
            return supplies;
        }
    }
}

package com.illumine.barb3tickfishing;

import java.util.Objects;

class Barb3TickRuntimeConfig
{
    static final String DEFAULT_HERB_NAME = "Guam leaf";

    private ThreeTickFrequencyMode frequencyMode = ThreeTickFrequencyMode.SOMETIMES;
    private boolean switchingEnabled = frequencyMode.switchingEnabled();
    private String herbName = DEFAULT_HERB_NAME;
    private boolean switchToNormalOnSuppliesOut = true;

    ThreeTickFrequencyMode frequencyMode()
    {
        return frequencyMode;
    }

    boolean switchingEnabled()
    {
        return switchingEnabled;
    }

    String herbName()
    {
        return herbName;
    }

    boolean switchToNormalOnSuppliesOut()
    {
        return switchToNormalOnSuppliesOut;
    }

    void setFrequencyMode(ThreeTickFrequencyMode mode)
    {
        ThreeTickFrequencyMode resolved = (mode == null) ? ThreeTickFrequencyMode.SOMETIMES : mode;
        frequencyMode = resolved;
        switchingEnabled = resolved.switchingEnabled();
    }

    void setHerbName(String herb)
    {
        if (herb == null)
        {
            herbName = DEFAULT_HERB_NAME;
            return;
        }
        String trimmed = herb.trim();
        herbName = trimmed.isEmpty() ? DEFAULT_HERB_NAME : trimmed;
    }

    void setSwitchToNormalOnSuppliesOut(boolean enabled)
    {
        switchToNormalOnSuppliesOut = enabled;
    }

    void reset()
    {
        frequencyMode = ThreeTickFrequencyMode.SOMETIMES;
        switchingEnabled = frequencyMode.switchingEnabled();
        herbName = DEFAULT_HERB_NAME;
        switchToNormalOnSuppliesOut = true;
    }

    void applyFromConfig(Barb3TickFishingConfig persisted)
    {
        if (persisted == null)
        {
            return;
        }
        setFrequencyMode(persisted.frequencyMode());
        setHerbName(persisted.herbName());
        setSwitchToNormalOnSuppliesOut(persisted.fallbackToNormal());
    }

    @Override
    public String toString()
    {
        return "Barb3TickRuntimeConfig{" +
                "frequencyMode=" + frequencyMode +
                ", herbName='" + herbName + '\'' +
                ", switchToNormalOnSuppliesOut=" + switchToNormalOnSuppliesOut +
                '}';
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
        {
            return true;
        }
        if (!(o instanceof Barb3TickRuntimeConfig))
        {
            return false;
        }
        Barb3TickRuntimeConfig that = (Barb3TickRuntimeConfig) o;
        return switchToNormalOnSuppliesOut == that.switchToNormalOnSuppliesOut &&
                frequencyMode == that.frequencyMode &&
                Objects.equals(herbName, that.herbName);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(frequencyMode, herbName, switchToNormalOnSuppliesOut);
    }
}

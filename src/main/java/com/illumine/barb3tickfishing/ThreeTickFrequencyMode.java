package com.illumine.barb3tickfishing;

public enum ThreeTickFrequencyMode
{
    ALWAYS("Always 3Tick (VERY DANGEROUS!)"),
    MOSTLY("Mostly 3Tick"),
    SOMETIMES("Sometimes 3Tick"),
    NEVER("Never 3Tick"),
    RANDOM("Random");

    private final String label;

    ThreeTickFrequencyMode(String label)
    {
        this.label = label;
    }

    String label()
    {
        return label;
    }

    boolean startsInThreeTick()
    {
        return this != NEVER;
    }

    boolean switchingEnabled()
    {
        return this == MOSTLY || this == SOMETIMES || this == RANDOM;
    }

    String shortLabel()
    {
        switch (this)
        {
            case ALWAYS:
                return "Always";
            case MOSTLY:
                return "Mostly";
            case SOMETIMES:
                return "Sometimes";
            case NEVER:
                return "Never";
            case RANDOM:
            default:
                return "Random";
        }
    }

    static ThreeTickFrequencyMode fromOptionString(String value)
    {
        if (value != null)
        {
            for (ThreeTickFrequencyMode mode : values())
            {
                if (mode.label.equalsIgnoreCase(value.trim()))
                {
                    return mode;
                }
            }
        }
        return SOMETIMES;
    }

    @Override
    public String toString()
    {
        return label;
    }
}

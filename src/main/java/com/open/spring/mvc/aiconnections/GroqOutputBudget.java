package com.open.spring.mvc.aiconnections;

/** Budget the required grading JSON, not the size or type of the submitted work. */
public record GroqOutputBudget(int maxCompletionTokens, boolean compact) {
    private static final int CEILING = 800;

    public static GroqOutputBudget forHackCount(int hackCount) {
        if (hackCount < 0) throw new IllegalArgumentException("Hack count cannot be negative");
        long normal = 384L + 64L * hackCount;
        if (normal <= CEILING) return new GroqOutputBudget((int) normal, false);
        long compressed = 256L + 32L * hackCount;
        return new GroqOutputBudget(compressed <= CEILING ? (int) compressed : 0, true);
    }

    public boolean fits() { return maxCompletionTokens > 0; }
}

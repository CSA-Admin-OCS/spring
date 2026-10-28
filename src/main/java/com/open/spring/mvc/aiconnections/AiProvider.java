package com.open.spring.mvc.aiconnections;

public enum AiProvider {
    gemini, groq;

    public static AiProvider parse(String value) {
        try { return valueOf(value); }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new AiConnectionException(400, "AI_PROVIDER_INVALID", "Choose Gemini or Groq.");
        }
    }
}

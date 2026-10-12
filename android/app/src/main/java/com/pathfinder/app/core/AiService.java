package com.pathfinder.app.core;

public interface AiService {
    AiIntent interpret(String apiKey, String userText) throws AiException;
}

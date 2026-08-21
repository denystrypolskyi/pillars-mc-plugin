package org.example.pillars.gameevents;

public interface GameEvent {
    String getId();

    int getDurationSeconds();

    void start();

    void stop();
}
